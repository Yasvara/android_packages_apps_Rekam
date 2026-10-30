// SPDX-FileCopyrightText: The Yasvara Project
// SPDX-License-Identifier: Apache-2.0

//! Live writer: buffers one fragment per track, writes `moof`+`mdat` once per fragment, syncs.

use crate::bmff::BoxWriter;
use crate::entry::{sample_description, video_sample};
use crate::file::RandomAccess;
use crate::moov::{finalize_in_place, init_moov, Chunk, Sample, TrackIndex, PLACEHOLDER, SOUN, VIDE};
use crate::{Codec, Error, Result, TrackConfig};

/// When to fdatasync. Every fragment bounds the loss on power cut to one fragment (about
/// 20 ms per 1 s fragment at 20 Mbps, on the writer thread).
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SyncPolicy {
    /// After every fragment.
    EveryFragment,
    /// After every n-th fragment.
    Every(u32),
    /// Never (tests, scratch files).
    Never,
}

/// Writer tuning.
#[derive(Clone, Copy, Debug)]
pub struct WriterOptions {
    /// Fragment length; a fragment closes on the first sample at or past it.
    pub fragment_us: i64,
    /// Durability policy.
    pub sync: SyncPolicy,
}

impl Default for WriterOptions {
    fn default() -> Self {
        Self { fragment_us: 1_000_000, sync: SyncPolicy::EveryFragment }
    }
}

const SYNC_FLAGS: u32 = 0x0200_0000; // sample_depends_on = 2
const NON_SYNC_FLAGS: u32 = 0x0101_0000; // depends_on = 1, is_non_sync

struct Track {
    index: TrackIndex,
    codec: Codec,
    video: bool,
    timescale: u32,
    /// Newest sample, held back until the next one gives its duration.
    pending: Vec<u8>,
    pending_ticks: Option<u64>,
    pending_sync: bool,
    last_duration: u32,
    /// This fragment's samples and payload.
    frag: Vec<Sample>,
    frag_data: Vec<u8>,
    frag_start: u64,
    next_decode: u64,
}

/// Writes one hybrid MP4. Not thread-safe: one writer thread owns it (`rk-io`).
///
/// Steady state allocates nothing per sample: payload, pending and moof buffers keep their
/// capacity; only the per-recording index grows (12 bytes per sample, amortised).
pub struct HybridWriter<F: RandomAccess> {
    file: F,
    tracks: Vec<Track>,
    opts: WriterOptions,
    placeholder: u64,
    end: u64,
    sequence: u32,
    origin_us: Option<i64>,
    frag_start_us: Option<i64>,
    unsynced: u32,
    scratch: Vec<u8>,
    done: bool,
}

impl<F: RandomAccess> HybridWriter<F> {
    /// Writes `ftyp`, the placeholder and the init moov. Every csd must be known by now.
    pub fn create(mut file: F, configs: &[TrackConfig], opts: WriterOptions) -> Result<Self> {
        if configs.is_empty() {
            return Err(Error::Config("no tracks"));
        }
        let mut tracks = Vec::with_capacity(configs.len());
        for (i, c) in configs.iter().enumerate() {
            let video = c.codec.is_video();
            tracks.push(Track {
                index: TrackIndex {
                    track_id: i as u32 + 1,
                    handler: if video { VIDE } else { SOUN },
                    timescale: c.timescale,
                    width: c.width,
                    height: c.height,
                    enabled: c.enabled,
                    alternate_group: c.alternate_group,
                    stsd: sample_description(c)?,
                    ..TrackIndex::default()
                },
                codec: c.codec,
                video,
                timescale: c.timescale,
                pending: Vec::new(),
                pending_ticks: None,
                pending_sync: false,
                last_duration: 0,
                frag: Vec::new(),
                frag_data: Vec::new(),
                frag_start: 0,
                next_decode: 0,
            });
        }

        let mut head = Vec::new();
        let mut w = BoxWriter::new(&mut head);
        w.begin(b"ftyp");
        w.bytes(b"isom");
        w.u32(0x200);
        w.bytes(b"isomiso6mp41");
        w.end();
        let placeholder = head.len() as u64;
        head.extend_from_slice(&PLACEHOLDER);
        let indexes: Vec<TrackIndex> = tracks.iter().map(|t| t.index.clone()).collect();
        head.extend_from_slice(&init_moov(&indexes));

        file.set_len(0)?;
        file.write_at(0, &head)?;
        file.sync_data()?;
        Ok(Self {
            file,
            tracks,
            opts,
            placeholder,
            end: head.len() as u64,
            sequence: 0,
            origin_us: None,
            frag_start_us: None,
            unsynced: 0,
            scratch: Vec::new(),
            done: false,
        })
    }

    /// Queues one encoded sample. The file starts at the first video key frame (or the first
    /// sample of an audio-only file); earlier samples are dropped and `Ok(false)` is returned, as
    /// for a timestamp that does not move forward.
    pub fn write(&mut self, track: usize, data: &[u8], pts_us: i64, sync: bool) -> Result<bool> {
        if self.done {
            return Err(Error::Config("writer finished"));
        }
        let has_video = self.tracks.iter().any(|t| t.video);
        let t = self.tracks.get(track).ok_or(Error::Config("unknown track"))?;
        let origin = match self.origin_us {
            Some(o) => o,
            None if t.video && sync || !has_video => *self.origin_us.insert(pts_us),
            None => return Ok(false),
        };
        if pts_us < origin {
            return Ok(false);
        }
        let ticks = ((i128::from(pts_us - origin) * i128::from(t.timescale) + 500_000) / 1_000_000) as u64;
        if let Some(prev) = t.pending_ticks {
            if ticks <= prev {
                return Ok(false);
            }
            let duration = u32::try_from(ticks - prev).map_err(|_| Error::Config("gap too long"))?;
            self.commit(track, duration)?;
        }
        let t = &mut self.tracks[track];
        if t.video {
            video_sample(t.codec, data, &mut t.pending);
        } else {
            t.pending.clear();
            t.pending.extend_from_slice(data);
        }
        t.pending_ticks = Some(ticks);
        t.pending_sync = sync || !t.video;
        Ok(true)
    }

    /// Moves `track`'s pending sample into the current fragment, closing the fragment first if
    /// that sample starts past its length.
    fn commit(&mut self, track: usize, duration: u32) -> Result<()> {
        let origin = self.origin_us.unwrap_or(0);
        let ticks = self.tracks[track].pending_ticks.expect("commit without pending");
        let ts = self.tracks[track].timescale;
        let pts_us = origin + (i128::from(ticks) * 1_000_000 / i128::from(ts)) as i64;
        match self.frag_start_us {
            Some(start) if pts_us - start >= self.opts.fragment_us => {
                self.flush()?;
                self.frag_start_us = Some(pts_us);
            }
            None => self.frag_start_us = Some(pts_us),
            _ => {}
        }
        let t = &mut self.tracks[track];
        if t.index.samples.is_empty() && t.frag.is_empty() {
            t.index.start = ticks;
            t.next_decode = ticks;
        }
        if t.frag.is_empty() {
            t.frag_start = t.next_decode;
        }
        t.frag.push(Sample { size: t.pending.len() as u32, duration, sync: t.pending_sync });
        t.frag_data.extend_from_slice(&t.pending);
        t.next_decode += u64::from(duration);
        t.last_duration = duration;
        t.pending_ticks = None;
        Ok(())
    }

    /// Writes the current fragment (if any) as one `moof`+`mdat`.
    pub fn flush(&mut self) -> Result<()> {
        if self.tracks.iter().all(|t| t.frag.is_empty()) {
            return Ok(());
        }
        self.sequence += 1;
        let moof_at = self.end;
        let mut out = std::mem::take(&mut self.scratch);
        out.clear();
        let mut offsets = Vec::with_capacity(self.tracks.len());
        {
            let mut w = BoxWriter::new(&mut out);
            w.begin(b"moof");
            w.begin_full(b"mfhd", 0, 0);
            w.u32(self.sequence);
            w.end();
            for t in self.tracks.iter().filter(|t| !t.frag.is_empty()) {
                w.begin(b"traf");
                w.begin_full(b"tfhd", 0, 0x02_0000); // default-base-is-moof
                w.u32(t.index.track_id);
                w.end();
                w.begin_full(b"tfdt", 1, 0);
                w.u64(t.frag_start);
                w.end();
                w.begin_full(b"trun", 0, 0x0701); // data offset, duration, size, flags
                w.u32(t.frag.len() as u32);
                offsets.push(w.len());
                w.u32(0);
                for s in &t.frag {
                    w.u32(s.duration);
                    w.u32(s.size);
                    w.u32(if s.sync { SYNC_FLAGS } else { NON_SYNC_FLAGS });
                }
                w.end();
                w.end();
            }
            w.end();
        }
        let payload: usize = self.tracks.iter().map(|t| t.frag_data.len()).sum();
        let mdat_size = u32::try_from(8 + payload).map_err(|_| Error::Config("fragment over 4 GiB"))?;
        let head = out.len() + 8;
        let mut rel = head;
        let mut slot = offsets.into_iter();
        for t in self.tracks.iter_mut().filter(|t| !t.frag.is_empty()) {
            let at = slot.next().unwrap();
            out[at..at + 4].copy_from_slice(&(rel as u32).to_be_bytes());
            t.index.chunks.push(Chunk { offset: moof_at + rel as u64, samples: t.frag.len() as u32 });
            rel += t.frag_data.len();
        }
        out.extend_from_slice(&mdat_size.to_be_bytes());
        out.extend_from_slice(b"mdat");

        // moof + mdat header, then each payload straight from its buffer: no extra copy.
        let mut pos = moof_at;
        let mut result = self.file.write_at(pos, &out);
        pos += out.len() as u64;
        for t in self.tracks.iter_mut().filter(|t| !t.frag.is_empty()) {
            if result.is_ok() {
                result = self.file.write_at(pos, &t.frag_data);
            }
            pos += t.frag_data.len() as u64;
            t.index.samples.append(&mut t.frag);
            t.frag_data.clear();
        }
        self.scratch = out;
        result?;
        self.end = pos;
        self.unsynced += 1;
        let due = match self.opts.sync {
            SyncPolicy::EveryFragment => true,
            SyncPolicy::Every(n) => self.unsynced >= n.max(1),
            SyncPolicy::Never => false,
        };
        if due {
            self.file.sync_data()?;
            self.unsynced = 0;
        }
        Ok(())
    }

    /// Commits the held-back samples (each with its track's previous duration), writes the last
    /// fragment and finalizes the file. The writer is unusable afterwards.
    pub fn finish(mut self) -> Result<F> {
        for i in 0..self.tracks.len() {
            if self.tracks[i].pending_ticks.is_some() {
                let t = &self.tracks[i];
                let d = if t.last_duration > 0 { t.last_duration } else { t.timescale / 30 };
                self.commit(i, d.max(1))?;
            }
        }
        self.flush()?;
        self.done = true;
        let indexes: Vec<TrackIndex> = self.tracks.iter().map(|t| t.index.clone()).collect();
        finalize_in_place(&mut self.file, self.placeholder, self.end, &indexes)?;
        Ok(self.file)
    }

    /// Bytes on disk so far.
    pub fn bytes(&self) -> u64 {
        self.end
    }

    /// Media time written so far, microseconds.
    pub fn duration_us(&self) -> i64 {
        self.tracks
            .iter()
            .map(|t| (u128::from(t.next_decode) * 1_000_000 / u128::from(t.timescale.max(1))) as i64)
            .max()
            .unwrap_or(0)
    }
}

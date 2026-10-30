// SPDX-FileCopyrightText: The Yasvara Project
// SPDX-License-Identifier: Apache-2.0

//! Movie headers: the fragmented init `moov`, the full `moov`, and the in-place finalize.

use crate::bmff::{fourcc, BoxWriter};
use crate::file::RandomAccess;
use crate::Result;

pub const MOVIE_TIMESCALE: u32 = 1000;
/// `free` box that a finalize turns into the 16-byte header of the big `mdat`.
pub const PLACEHOLDER: [u8; 16] = *b"\0\0\0\x10freeRKMHYB01";
pub const VIDE: u32 = fourcc(b"vide");
pub const SOUN: u32 = fourcc(b"soun");

const MATRIX: [u32; 9] = [0x0001_0000, 0, 0, 0, 0x0001_0000, 0, 0, 0, 0x4000_0000];

/// One sample: 12 bytes, kept for the whole recording to build the final index.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Sample {
    /// Bytes.
    pub size: u32,
    /// Track timescale.
    pub duration: u32,
    /// Key frame (audio: always).
    pub sync: bool,
}

/// A contiguous run of one track's samples: one per track per fragment.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Chunk {
    /// File offset of the first sample.
    pub offset: u64,
    /// Samples in the run.
    pub samples: u32,
}

/// Everything the final moov needs for one track. The writer fills it live; recovery rebuilds it
/// from the init moov and the moofs.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct TrackIndex {
    /// 1-based track id.
    pub track_id: u32,
    /// `vide` or `soun`.
    pub handler: u32,
    /// Ticks per second.
    pub timescale: u32,
    /// Video width.
    pub width: u16,
    /// Video height.
    pub height: u16,
    /// Played by default.
    pub enabled: bool,
    /// Alternate group (0: none).
    pub alternate_group: i16,
    /// The complete `stsd` box.
    pub stsd: Vec<u8>,
    /// Decode time of the first sample, track timescale (later than zero when it started late).
    pub start: u64,
    /// Every sample, in decode order.
    pub samples: Vec<Sample>,
    /// Every run, in file order.
    pub chunks: Vec<Chunk>,
}

impl TrackIndex {
    /// Sum of sample durations, track timescale.
    pub fn duration(&self) -> u64 {
        self.samples.iter().map(|s| u64::from(s.duration)).sum()
    }
}

fn to_movie(t: u64, timescale: u32) -> u64 {
    (u128::from(t) * u128::from(MOVIE_TIMESCALE) / u128::from(timescale.max(1))) as u64
}

pub fn init_moov(tracks: &[TrackIndex]) -> Vec<u8> {
    moov(tracks, false)
}

pub fn full_moov(tracks: &[TrackIndex]) -> Vec<u8> {
    moov(tracks, true)
}

fn moov(tracks: &[TrackIndex], full: bool) -> Vec<u8> {
    let durations: Vec<u64> = tracks.iter().map(|t| if full { t.duration() } else { 0 }).collect();
    let movie: Vec<u64> = tracks.iter().zip(&durations).map(|(t, &d)| to_movie(t.start + d, t.timescale)).collect();
    let mut out = Vec::new();
    let mut w = BoxWriter::new(&mut out);
    w.begin(b"moov");

    w.begin_full(b"mvhd", 1, 0);
    w.u64(0);
    w.u64(0);
    w.u32(MOVIE_TIMESCALE);
    w.u64(movie.iter().copied().max().unwrap_or(0));
    w.u32(0x0001_0000);
    w.u16(0x0100);
    w.zeros(10);
    MATRIX.iter().for_each(|&m| w.u32(m));
    w.zeros(24);
    w.u32(tracks.iter().map(|t| t.track_id).max().unwrap_or(0) + 1);
    w.end();

    for ((t, &dur), &mdur) in tracks.iter().zip(&durations).zip(&movie) {
        trak(&mut w, t, dur, mdur, full);
    }

    if !full {
        w.begin(b"mvex");
        for t in tracks {
            w.begin_full(b"trex", 0, 0);
            w.u32(t.track_id);
            w.u32(1);
            w.zeros(12);
            w.end();
        }
        w.end();
    }
    w.end();
    out
}

fn trak(w: &mut BoxWriter, t: &TrackIndex, duration: u64, movie_duration: u64, full: bool) {
    let video = t.handler == VIDE;
    w.begin(b"trak");

    w.begin_full(b"tkhd", 1, if t.enabled { 7 } else { 6 });
    w.u64(0);
    w.u64(0);
    w.u32(t.track_id);
    w.u32(0);
    w.u64(movie_duration);
    w.zeros(8);
    w.i16(0); // layer
    w.i16(t.alternate_group);
    w.u16(if video { 0 } else { 0x0100 });
    w.u16(0);
    MATRIX.iter().for_each(|&m| w.u32(m));
    w.u32(u32::from(t.width) << 16);
    w.u32(u32::from(t.height) << 16);
    w.end();

    if t.start > 0 {
        // An empty edit for the time before the track's first sample, then the media.
        let gap = to_movie(t.start, t.timescale);
        w.begin(b"edts");
        w.begin_full(b"elst", 1, 0);
        w.u32(2);
        w.u64(gap);
        w.i64(-1);
        w.u32(0x0001_0000);
        w.u64(movie_duration.saturating_sub(gap));
        w.i64(0);
        w.u32(0x0001_0000);
        w.end();
        w.end();
    }

    w.begin(b"mdia");
    w.begin_full(b"mdhd", 1, 0);
    w.u64(0);
    w.u64(0);
    w.u32(t.timescale);
    w.u64(duration);
    w.u16(0x55c4); // "und"
    w.u16(0);
    w.end();

    w.begin_full(b"hdlr", 0, 0);
    w.u32(0);
    w.u32(t.handler);
    w.zeros(12);
    w.bytes(if video { b"Rekam Video\0" } else { b"Rekam Audio\0" });
    w.end();

    w.begin(b"minf");
    if video {
        w.begin_full(b"vmhd", 0, 1);
        w.zeros(8);
    } else {
        w.begin_full(b"smhd", 0, 0);
        w.zeros(4);
    }
    w.end();
    w.begin(b"dinf");
    w.begin_full(b"dref", 0, 0);
    w.u32(1);
    w.begin_full(b"url ", 0, 1);
    w.end();
    w.end();
    w.end();

    w.begin(b"stbl");
    w.bytes(&t.stsd);
    if full {
        sample_tables(w, t);
    } else {
        for kind in [b"stts", b"stsc", b"stco"] {
            w.begin_full(kind, 0, 0);
            w.u32(0);
            w.end();
        }
        w.begin_full(b"stsz", 0, 0);
        w.u32(0);
        w.u32(0);
        w.end();
    }
    w.end(); // stbl
    w.end(); // minf
    w.end(); // mdia
    w.end(); // trak
}

fn sample_tables(w: &mut BoxWriter, t: &TrackIndex) {
    // stts: run-length durations.
    let mut runs: Vec<(u32, u32)> = Vec::new();
    for s in &t.samples {
        match runs.last_mut() {
            Some((n, d)) if *d == s.duration => *n += 1,
            _ => runs.push((1, s.duration)),
        }
    }
    w.begin_full(b"stts", 0, 0);
    w.u32(runs.len() as u32);
    for (n, d) in runs {
        w.u32(n);
        w.u32(d);
    }
    w.end();

    if t.samples.iter().any(|s| !s.sync) {
        let sync: Vec<u32> = (1..).zip(&t.samples).filter(|(_, s)| s.sync).map(|(i, _)| i).collect();
        w.begin_full(b"stss", 0, 0);
        w.u32(sync.len() as u32);
        sync.iter().for_each(|&i| w.u32(i));
        w.end();
    }

    w.begin_full(b"stsz", 0, 0);
    w.u32(0);
    w.u32(t.samples.len() as u32);
    t.samples.iter().for_each(|s| w.u32(s.size));
    w.end();

    // stsc: chunks in a row with the same sample count share one entry.
    let mut stsc: Vec<(u32, u32)> = Vec::new();
    for (i, c) in (1..).zip(&t.chunks) {
        if stsc.last().map(|&(_, n)| n) != Some(c.samples) {
            stsc.push((i, c.samples));
        }
    }
    w.begin_full(b"stsc", 0, 0);
    w.u32(stsc.len() as u32);
    for (first, n) in stsc {
        w.u32(first);
        w.u32(n);
        w.u32(1);
    }
    w.end();

    if t.chunks.iter().any(|c| c.offset > u64::from(u32::MAX)) {
        w.begin_full(b"co64", 0, 0);
        w.u32(t.chunks.len() as u32);
        t.chunks.iter().for_each(|c| w.u64(c.offset));
    } else {
        w.begin_full(b"stco", 0, 0);
        w.u32(t.chunks.len() as u32);
        t.chunks.iter().for_each(|c| w.u32(c.offset as u32));
    }
    w.end();
}

/// Makes the fragmented file at `[0, end)` a plain MP4: full moov at `end`, sync, then the
/// placeholder at `placeholder` becomes a 64-bit `mdat` header spanning up to the moov, sync.
/// A crash between the two syncs leaves a trailing moov that recovery drops and rebuilds.
/// Tracks without samples are left out of the final moov.
pub fn finalize_in_place<F: RandomAccess>(file: &mut F, placeholder: u64, end: u64, tracks: &[TrackIndex]) -> Result<()> {
    let kept: Vec<TrackIndex> = tracks.iter().filter(|t| !t.samples.is_empty()).cloned().collect();
    let moov = full_moov(&kept);
    file.write_at(end, &moov)?;
    file.set_len(end + moov.len() as u64)?;
    file.sync_data()?;
    let mut header = [0u8; 16];
    header[..8].copy_from_slice(b"\0\0\0\x01mdat");
    header[8..].copy_from_slice(&(end - placeholder).to_be_bytes());
    file.write_at(placeholder, &header)?;
    file.sync_data()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn track(offsets: &[u64]) -> TrackIndex {
        TrackIndex {
            track_id: 1,
            handler: VIDE,
            timescale: 90_000,
            enabled: true,
            stsd: b"\0\0\0\x10stsd\0\0\0\0\0\0\0\0".to_vec(),
            samples: vec![Sample { size: 10, duration: 3000, sync: true }; offsets.len()],
            chunks: offsets.iter().map(|&offset| Chunk { offset, samples: 1 }).collect(),
            ..TrackIndex::default()
        }
    }

    fn has(b: &[u8], k: &[u8; 4]) -> bool {
        b.windows(4).any(|w| w == k)
    }

    #[test]
    fn offsets_past_4_gib_use_co64() {
        assert!(has(&full_moov(&[track(&[100, 200])]), b"stco"));
        let big = full_moov(&[track(&[100, 5 << 30])]);
        assert!(has(&big, b"co64") && !has(&big, b"stco"));
    }

    #[test]
    fn init_moov_announces_fragments_and_full_does_not() {
        let t = [track(&[100])];
        assert!(has(&init_moov(&t), b"mvex") && has(&init_moov(&t), b"trex"));
        assert!(!has(&full_moov(&t), b"mvex"));
    }

    #[test]
    fn late_start_gets_an_empty_edit() {
        let mut t = track(&[100]);
        assert!(!has(&full_moov(std::slice::from_ref(&t)), b"elst"));
        t.start = 45_000;
        let m = full_moov(&[t]);
        let at = m.windows(4).position(|w| w == b"elst").unwrap();
        // version 1, 2 entries, first: 500 ms of nothing
        assert_eq!(&m[at + 4..at + 12], &[1, 0, 0, 0, 0, 0, 0, 2]);
        assert_eq!(u64::from_be_bytes(m[at + 12..at + 20].try_into().unwrap()), 500);
        assert_eq!(i64::from_be_bytes(m[at + 20..at + 28].try_into().unwrap()), -1);
    }

    #[test]
    fn stsc_collapses_equal_chunks() {
        let mut t = track(&[0, 10, 20, 30]);
        t.chunks[3].samples = 2;
        t.samples.push(Sample { size: 10, duration: 3000, sync: false });
        let m = full_moov(&[t]);
        let at = m.windows(4).position(|w| w == b"stsc").unwrap();
        assert_eq!(u32::from_be_bytes(m[at + 8..at + 12].try_into().unwrap()), 2);
    }
}

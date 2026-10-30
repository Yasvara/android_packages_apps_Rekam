// SPDX-FileCopyrightText: The Yasvara Project
// SPDX-License-Identifier: Apache-2.0

//! Turns an interrupted recording back into a plain MP4.

use crate::bmff::Reader;
use crate::file::RandomAccess;
use crate::moov::{finalize_in_place, Chunk, Sample, TrackIndex, PLACEHOLDER, VIDE};
use crate::{Error, Result};

/// What [`recover`] did.
#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Recovered {
    /// Already a finalized file; untouched.
    Final,
    /// Truncated to the last complete fragment and finalized.
    Repaired {
        /// Complete fragments kept.
        fragments: u32,
        /// Samples kept, all tracks.
        samples: u64,
        /// Bytes cut after the last complete fragment.
        dropped_bytes: u64,
    },
    /// No complete fragment: nothing worth keeping.
    Empty,
}

const SYNC_BIT: u32 = 0x0001_0000; // sample_is_non_sync_sample

fn header<F: RandomAccess>(f: &mut F, at: u64, len: u64) -> Result<Option<([u8; 4], u64, u64)>> {
    if at + 8 > len {
        return Ok(None);
    }
    let mut h = [0u8; 16];
    f.read_at(at, &mut h[..8])?;
    let kind: [u8; 4] = h[4..8].try_into().unwrap();
    let (hlen, size) = match u32::from_be_bytes(h[..4].try_into().unwrap()) {
        1 => {
            if at + 16 > len {
                return Ok(None);
            }
            f.read_at(at + 8, &mut h[8..])?;
            (16, u64::from_be_bytes(h[8..].try_into().unwrap()))
        }
        0 => return Ok(None), // open-ended box: never written by us
        n => (8, u64::from(n)),
    };
    if size < hlen || at.checked_add(size).is_none_or(|e| e > len) {
        return Ok(None);
    }
    Ok(Some((kind, hlen, size)))
}

fn read<F: RandomAccess>(f: &mut F, at: u64, size: u64) -> Result<Vec<u8>> {
    let n = usize::try_from(size).map_err(|_| Error::Corrupt("box too large"))?;
    if n > 64 << 20 {
        return Err(Error::Corrupt("header box too large"));
    }
    let mut v = vec![0u8; n];
    f.read_at(at, &mut v)?;
    Ok(v)
}

/// Tracks of the init moov, with empty sample tables.
fn tracks(moov: &[u8]) -> Result<Vec<TrackIndex>> {
    let mut out = Vec::new();
    let mut r = Reader::new(moov);
    while r.left() > 0 {
        let (kind, trak) = r.child()?;
        if &kind != b"trak" {
            continue;
        }
        let mut tkhd = Reader::new(Reader::find(trak, b"tkhd")?);
        let version = tkhd.u8()?;
        let flags = tkhd.u24()?;
        tkhd.skip(if version == 1 { 16 } else { 8 })?;
        let track_id = tkhd.u32()?;
        tkhd.skip(4 + if version == 1 { 8 } else { 4 } + 8 + 2)?;
        let alternate_group = tkhd.u16()? as i16;
        tkhd.skip(2 + 2 + 36)?;
        let width = (tkhd.u32()? >> 16) as u16;
        let height = (tkhd.u32()? >> 16) as u16;

        let mdia = Reader::find(trak, b"mdia")?;
        let mut mdhd = Reader::new(Reader::find(mdia, b"mdhd")?);
        let v = mdhd.u8()?;
        mdhd.skip(3 + if v == 1 { 16 } else { 8 })?;
        let timescale = mdhd.u32()?;
        let mut hdlr = Reader::new(Reader::find(mdia, b"hdlr")?);
        hdlr.skip(8)?;
        let handler = hdlr.u32()?;
        let stbl = Reader::find(Reader::find(mdia, b"minf")?, b"stbl")?;
        let stsd_body = Reader::find(stbl, b"stsd")?;
        let mut stsd = Vec::with_capacity(stsd_body.len() + 8);
        stsd.extend_from_slice(&(stsd_body.len() as u32 + 8).to_be_bytes());
        stsd.extend_from_slice(b"stsd");
        stsd.extend_from_slice(stsd_body);
        if timescale == 0 {
            return Err(Error::Corrupt("timescale"));
        }
        out.push(TrackIndex {
            track_id,
            handler,
            timescale,
            width,
            height,
            enabled: flags & 1 != 0,
            alternate_group,
            stsd,
            ..TrackIndex::default()
        });
    }
    if out.is_empty() {
        return Err(Error::Corrupt("no tracks"));
    }
    Ok(out)
}

struct Run {
    track: usize,
    decode: u64,
    data_offset: u64,
    samples: Vec<Sample>,
}

/// Parses one moof; every run must lie inside the following mdat at `[mdat_at, mdat_end)`.
fn runs(moof: &[u8], moof_at: u64, mdat_body: (u64, u64), tracks: &[TrackIndex]) -> Result<Vec<Run>> {
    let mut out = Vec::new();
    let mut r = Reader::new(moof);
    while r.left() > 0 {
        let (kind, traf) = r.child()?;
        if &kind != b"traf" {
            continue;
        }
        let mut tfhd = Reader::new(Reader::find(traf, b"tfhd")?);
        tfhd.skip(4)?;
        let id = tfhd.u32()?;
        let track = tracks.iter().position(|t| t.track_id == id).ok_or(Error::Corrupt("unknown track"))?;
        let mut tfdt = Reader::new(Reader::find(traf, b"tfdt")?);
        let decode = if tfdt.u8()? == 1 {
            tfdt.skip(3)?;
            tfdt.u64()?
        } else {
            tfdt.skip(3)?;
            u64::from(tfdt.u32()?)
        };
        let mut trun = Reader::new(Reader::find(traf, b"trun")?);
        trun.skip(1)?;
        let flags = trun.u24()?;
        if flags & 0x0701 != 0x0701 {
            return Err(Error::Corrupt("trun layout"));
        }
        let count = trun.u32()? as usize;
        let data_offset = u64::from(trun.u32()?);
        if flags & 0x4 != 0 {
            trun.skip(4)?; // first_sample_flags
        }
        if trun.left() < count * 12 + if flags & 0x800 != 0 { count * 4 } else { 0 } {
            return Err(Error::Corrupt("trun entries"));
        }
        let mut samples = Vec::with_capacity(count);
        let mut bytes = 0u64;
        for _ in 0..count {
            let duration = trun.u32()?;
            let size = trun.u32()?;
            let sflags = trun.u32()?;
            if flags & 0x800 != 0 {
                trun.skip(4)?;
            }
            bytes += u64::from(size);
            samples.push(Sample { size, duration, sync: sflags & SYNC_BIT == 0 });
        }
        let start = moof_at + data_offset;
        if start < mdat_body.0 || start + bytes > mdat_body.1 {
            return Err(Error::Corrupt("run outside mdat"));
        }
        out.push(Run { track, decode, data_offset: start, samples });
    }
    Ok(out)
}

/// Recovers `file` in place. Safe to run on any of our files at any time, any number of times.
pub fn recover<F: RandomAccess>(file: &mut F) -> Result<Recovered> {
    let len = file.size()?;
    // The header (ftyp, placeholder, init moov) goes out before any sample: a file that stops
    // inside it never held one.
    if len < 8 {
        return Ok(Recovered::Empty);
    }
    let Some((kind, _, ftyp)) = header(file, 0, len)? else {
        let mut h = [0u8; 8];
        file.read_at(0, &mut h)?;
        return if &h[4..] == b"ftyp" { Ok(Recovered::Empty) } else { Err(Error::Corrupt("no ftyp")) };
    };
    if &kind != b"ftyp" {
        return Err(Error::Corrupt("no ftyp"));
    }
    let placeholder = ftyp;
    if placeholder + 16 > len {
        return Ok(Recovered::Empty);
    }
    let mut slot = [0u8; 16];
    file.read_at(placeholder, &mut slot)?;
    if &slot[4..8] == b"mdat" {
        if let Some((_, _, size)) = header(file, placeholder, len)? {
            if let Some((k, _, _)) = header(file, placeholder + size, len)? {
                if &k == b"moov" {
                    return Ok(Recovered::Final);
                }
            }
        }
        // Only a finalize torn inside its 16-byte header write leaves our marker behind.
        if slot[..8] != *b"\0\0\0\x01mdat" || slot[8..] != PLACEHOLDER[8..] {
            return Err(Error::Corrupt("finalized header without moov"));
        }
        file.write_at(placeholder, &PLACEHOLDER)?;
    } else if slot != PLACEHOLDER {
        return Err(Error::Corrupt("not a Rekam recording"));
    }

    let init_at = placeholder + 16;
    let Some((kind, hlen, size)) = header(file, init_at, len)? else { return Ok(Recovered::Empty) };
    if init_at + size > len {
        return Ok(Recovered::Empty);
    }
    if &kind != b"moov" {
        return Err(Error::Corrupt("no init moov"));
    }
    let mut tracks = tracks(&read(file, init_at + hlen, size - hlen)?)?;

    let mut at = init_at + size;
    let mut fragments = 0u32;
    while let Some((kind, hlen, size)) = header(file, at, len)? {
        if &kind != b"moof" {
            break; // e.g. the full moov of a finalize that never got its header written
        }
        let Some((mkind, mhlen, msize)) = header(file, at + size, len)? else { break };
        if &mkind != b"mdat" {
            break;
        }
        let mdat_at = at + size;
        let Ok(runs) = runs(&read(file, at + hlen, size - hlen)?, at, (mdat_at + mhlen, mdat_at + msize), &tracks) else {
            break;
        };
        for run in runs {
            let t = &mut tracks[run.track];
            if t.samples.is_empty() {
                t.start = run.decode;
            } else {
                // Contiguous unless something was lost in between: absorb a gap into the
                // previous sample so audio and video stay aligned.
                let expected = t.start + t.duration();
                if run.decode > expected {
                    let last = t.samples.last_mut().unwrap();
                    last.duration = last.duration.saturating_add(u32::try_from(run.decode - expected).unwrap_or(u32::MAX));
                }
            }
            t.chunks.push(Chunk { offset: run.data_offset, samples: run.samples.len() as u32 });
            t.samples.extend(run.samples);
        }
        fragments += 1;
        at = mdat_at + msize;
    }

    let samples: u64 = tracks.iter().map(|t| t.samples.len() as u64).sum();
    let has_video = tracks.iter().any(|t| t.handler == VIDE);
    if fragments == 0 || samples == 0 || has_video && tracks.iter().all(|t| t.handler != VIDE || t.samples.is_empty()) {
        return Ok(Recovered::Empty);
    }
    file.set_len(at)?;
    finalize_in_place(file, placeholder, at, &tracks)?;
    Ok(Recovered::Repaired { fragments, samples, dropped_bytes: len - at })
}


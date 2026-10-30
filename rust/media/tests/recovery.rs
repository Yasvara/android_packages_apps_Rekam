// SPDX-FileCopyrightText: The Yasvara Project
// SPDX-License-Identifier: Apache-2.0

//! Crash safety of the hybrid MP4: finalize output, truncation at every byte, power loss at every
//! write, interrupted finalize. Sample bytes are checked through an independent stbl walk.

use rekam_media::{recover, Codec, HybridWriter, MemFile, RandomAccess, Recovered, SyncPolicy, TrackConfig, WriterOptions};

// x264 1920x1080 High, and AAC-LC 48 kHz stereo.
const AVC_CSD: &[u8] = &[
    0, 0, 0, 1, 0x67, 0x64, 0, 0x28, 0xac, 0xd9, 0x40, 0x78, 0x02, 0x27, 0xe5, 0xc0, 0x44, 0, 0, 3, 0, 4, 0, 0, 3, 0,
    0xf0, 0x3c, 0x60, 0xc6, 0x58, 0, 0, 0, 1, 0x68, 0xeb, 0xe3, 0xcb, 0x22, 0xc0,
];
const AAC_ASC: &[u8] = &[0x11, 0x90];

fn video() -> TrackConfig {
    TrackConfig {
        codec: Codec::Avc,
        timescale: 90_000,
        width: 1920,
        height: 1080,
        sample_rate: 0,
        channels: 0,
        avg_bitrate: 0,
        csd: AVC_CSD.to_vec(),
        enabled: true,
        alternate_group: 0,
    }
}

fn audio() -> TrackConfig {
    TrackConfig {
        codec: Codec::Aac,
        timescale: 48_000,
        width: 0,
        height: 0,
        sample_rate: 48_000,
        channels: 2,
        avg_bitrate: 128_000,
        csd: AAC_ASC.to_vec(),
        enabled: true,
        alternate_group: 1,
    }
}

/// Payload that names its track and index, so a misplaced chunk offset can't go unnoticed.
fn payload(track: u8, i: u32, len: usize) -> Vec<u8> {
    let mut v = vec![track; len];
    v[1..5].copy_from_slice(&i.to_le_bytes());
    v
}

#[derive(Clone, Copy)]
struct Feed {
    track: usize,
    pts_us: i64,
    sync: bool,
    len: usize,
}

/// 30 fps video with a key frame every 15, interleaved with 1024-sample AAC frames.
fn feed(seconds: f64) -> Vec<Feed> {
    let mut f = Vec::new();
    let frames = (seconds * 30.0) as i64;
    for i in 0..frames {
        f.push(Feed { track: 0, pts_us: i * 33_333, sync: i % 15 == 0, len: if i % 15 == 0 { 400 } else { 120 + (i as usize * 7) % 90 } });
    }
    let aframes = (seconds * 48_000.0 / 1024.0) as i64;
    for i in 0..aframes {
        f.push(Feed { track: 1, pts_us: 5_000 + i * 1024 * 1_000_000 / 48_000, sync: true, len: 40 + (i as usize % 13) });
    }
    f.sort_by_key(|x| x.pts_us);
    f
}

fn record<F: RandomAccess>(file: F, feed: &[Feed], fragment_us: i64, finish: bool) -> rekam_media::Result<Option<F>> {
    let mut w = HybridWriter::create(file, &[video(), audio()], WriterOptions { fragment_us, sync: SyncPolicy::EveryFragment })?;
    let mut idx = [0u32; 2];
    for x in feed {
        let i = idx[x.track];
        if w.write(x.track, &payload(x.track as u8, i, x.len), x.pts_us, x.sync)? {
            idx[x.track] += 1;
        }
    }
    if finish {
        Ok(Some(w.finish()?))
    } else {
        Ok(None)
    }
}

// ---- independent reader -------------------------------------------------------------------

fn be32(b: &[u8], at: usize) -> u32 {
    u32::from_be_bytes(b[at..at + 4].try_into().unwrap())
}

/// Top-level boxes as (type, start, size).
fn top(b: &[u8]) -> Vec<([u8; 4], usize, usize)> {
    let mut out = Vec::new();
    let mut at = 0;
    while at + 8 <= b.len() {
        let mut size = be32(b, at) as usize;
        if size == 1 {
            size = u64::from_be_bytes(b[at + 8..at + 16].try_into().unwrap()) as usize;
        }
        assert!(size >= 8 && at + size <= b.len(), "bad top-level box at {at}");
        out.push((b[at + 4..at + 8].try_into().unwrap(), at, size));
        at += size;
    }
    assert_eq!(at, b.len(), "trailing bytes");
    out
}

/// Children of a box body.
fn kids(b: &[u8]) -> Vec<([u8; 4], &[u8])> {
    let mut out = Vec::new();
    let mut at = 0;
    while at + 8 <= b.len() {
        let size = be32(b, at) as usize;
        out.push((b[at + 4..at + 8].try_into().unwrap(), &b[at + 8..at + size]));
        at += size;
    }
    out
}

fn kid<'a>(b: &'a [u8], k: &[u8; 4]) -> Option<&'a [u8]> {
    kids(b).into_iter().find(|(t, _)| t == k).map(|(_, v)| v)
}

struct Track {
    id: u32,
    timescale: u32,
    samples: Vec<(usize, usize, u32, bool)>, // offset, size, duration, sync
    has_elst: bool,
}

/// A finalized file read the way a non-fragment-aware player does: only ftyp, mdat, moov.
fn read_plain(b: &[u8]) -> Vec<Track> {
    let boxes = top(b);
    let kinds: Vec<_> = boxes.iter().map(|x| x.0).collect();
    assert_eq!(kinds, [*b"ftyp", *b"mdat", *b"moov"], "plain layout");
    let (_, at, size) = boxes[2];
    let moov = &b[at + 8..at + size];
    assert!(kid(moov, b"mvex").is_none(), "final moov must not announce fragments");
    let mut out = Vec::new();
    for (k, trak) in kids(moov) {
        if &k != b"trak" {
            continue;
        }
        let tkhd = kid(trak, b"tkhd").unwrap();
        let id = be32(tkhd, 4 + 16);
        let mdia = kid(trak, b"mdia").unwrap();
        let timescale = be32(kid(mdia, b"mdhd").unwrap(), 4 + 16);
        let stbl = kid(kid(mdia, b"minf").unwrap(), b"stbl").unwrap();
        let stsz = kid(stbl, b"stsz").unwrap();
        let sizes: Vec<usize> = (0..be32(stsz, 8) as usize).map(|i| be32(stsz, 12 + 4 * i) as usize).collect();
        let stts = kid(stbl, b"stts").unwrap();
        let mut durations = Vec::new();
        for i in 0..be32(stts, 4) as usize {
            durations.extend(std::iter::repeat_n(be32(stts, 12 + 8 * i), be32(stts, 8 + 8 * i) as usize));
        }
        let sync: Vec<bool> = match kid(stbl, b"stss") {
            Some(stss) => {
                let s: Vec<u32> = (0..be32(stss, 4) as usize).map(|i| be32(stss, 8 + 4 * i)).collect();
                (1..=sizes.len() as u32).map(|n| s.contains(&n)).collect()
            }
            None => vec![true; sizes.len()],
        };
        let offsets: Vec<usize> = if let Some(stco) = kid(stbl, b"stco") {
            (0..be32(stco, 4) as usize).map(|i| be32(stco, 8 + 4 * i) as usize).collect()
        } else {
            let co = kid(stbl, b"co64").unwrap();
            (0..be32(co, 4) as usize).map(|i| u64::from_be_bytes(co[8 + 8 * i..16 + 8 * i].try_into().unwrap()) as usize).collect()
        };
        let stsc = kid(stbl, b"stsc").unwrap();
        let entries: Vec<(usize, usize)> = (0..be32(stsc, 4) as usize).map(|i| (be32(stsc, 8 + 12 * i) as usize, be32(stsc, 12 + 12 * i) as usize)).collect();
        assert_eq!(sizes.len(), durations.len());
        let mut samples = Vec::new();
        let mut n = 0;
        for (c, &off) in offsets.iter().enumerate() {
            let per = entries.iter().rev().find(|e| e.0 <= c + 1).unwrap().1;
            let mut o = off;
            for _ in 0..per {
                samples.push((o, sizes[n], durations[n], sync[n]));
                o += sizes[n];
                n += 1;
            }
        }
        assert_eq!(n, sizes.len(), "stsc covers every sample");
        out.push(Track { id, timescale, samples, has_elst: kid(trak, b"edts").is_some() });
    }
    out
}

/// Every sample's bytes are the payload written for (track, index).
fn check_samples(b: &[u8], tracks: &[Track]) {
    for t in tracks {
        let track = (t.id - 1) as u8;
        for (i, &(off, size, _, _)) in t.samples.iter().enumerate() {
            let s = &b[off..off + size];
            assert_eq!(s[0], track, "track {track} sample {i} at {off}: wrong track");
            assert_eq!(u32::from_le_bytes(s[1..5].try_into().unwrap()), i as u32, "track {track} sample {i}: wrong index");
            assert!(s[5..].iter().all(|&x| x == track));
        }
    }
}

fn sample_count(tracks: &[Track]) -> usize {
    tracks.iter().map(|t| t.samples.len()).sum()
}

// ---- tests --------------------------------------------------------------------------------

#[test]
fn finish_writes_a_plain_mp4() {
    let f = record(MemFile::new(), &feed(3.0), 1_000_000, true).unwrap().unwrap();
    let tracks = read_plain(&f.data);
    assert_eq!(tracks.len(), 2);
    assert_eq!(tracks[0].samples.len(), 90);
    assert_eq!(tracks[0].timescale, 90_000);
    assert!(tracks[0].samples[0].3 && !tracks[0].samples[1].3 && tracks[0].samples[15].3);
    assert!(tracks[0].samples.iter().all(|s| (2999..=3001).contains(&s.2)), "33.3 ms in 90 kHz");
    assert!(tracks[1].samples.iter().all(|s| (1023..=1025).contains(&s.2)));
    assert!(tracks[1].has_elst && !tracks[0].has_elst, "audio starts 5 ms late");
    check_samples(&f.data, &tracks);
}

#[test]
fn finished_file_recovers_as_final() {
    let mut f = record(MemFile::new(), &feed(1.0), 250_000, true).unwrap().unwrap();
    let before = f.data.clone();
    assert_eq!(recover(&mut f).unwrap(), Recovered::Final);
    assert_eq!(f.data, before);
}

#[test]
fn samples_before_the_first_key_frame_are_dropped() {
    let mut w = HybridWriter::create(MemFile::new(), &[video(), audio()], WriterOptions::default()).unwrap();
    assert!(!w.write(1, &payload(1, 0, 10), 0, true).unwrap(), "audio before video origin");
    assert!(!w.write(0, &payload(0, 0, 10), 0, false).unwrap(), "non-key video");
    assert!(w.write(0, &payload(0, 0, 10), 33_333, true).unwrap());
    assert!(!w.write(0, &payload(0, 1, 10), 33_333, false).unwrap(), "same timestamp");
    assert!(w.write(0, &payload(0, 1, 10), 66_666, false).unwrap());
    let f = w.finish().unwrap();
    let t = read_plain(&f.data);
    assert_eq!(t[0].samples.len(), 2);
    check_samples(&f.data, &t);
}

/// Fragments in the unfinished file, from its own boxes.
fn complete_fragments(b: &[u8], upto: usize) -> usize {
    let mut at = 0;
    let mut n = 0;
    let mut last_moof_ok = false;
    while at + 8 <= upto {
        let size = be32(b, at) as usize;
        if size < 8 || at + size > upto {
            break;
        }
        match &b[at + 4..at + 8] {
            b"moof" => last_moof_ok = true,
            b"mdat" if last_moof_ok => {
                n += 1;
                last_moof_ok = false
            }
            _ => last_moof_ok = false,
        }
        at += size;
    }
    n
}

#[test]
fn truncation_at_every_byte_recovers_exactly_the_complete_fragments() {
    let mut raw = MemFile::new();
    record(&mut raw, &feed(1.2), 200_000, false).unwrap();
    let data = raw.data;
    let init_end = {
        let ftyp = be32(&data, 0) as usize;
        ftyp + 16 + be32(&data, ftyp + 16) as usize
    };
    let full = complete_fragments(&data, data.len());
    assert!(full >= 5, "need several fragments, got {full}");

    let mut last = 0;
    for cut in init_end..=data.len() {
        let mut f = MemFile::from_bytes(data[..cut].to_vec());
        let frags = complete_fragments(&data, cut);
        match recover(&mut f).unwrap() {
            Recovered::Empty => assert_eq!(frags, 0, "cut {cut}"),
            Recovered::Repaired { fragments, .. } => {
                assert_eq!(fragments as usize, frags, "cut {cut}");
                let tracks = read_plain(&f.data);
                check_samples(&f.data, &tracks);
                let n = sample_count(&tracks);
                assert!(n >= last, "cut {cut}: recovered fewer samples than a shorter cut");
                last = n;
                assert_eq!(recover(&mut f).unwrap(), Recovered::Final, "cut {cut}: second pass");
            }
            Recovered::Final => panic!("cut {cut}: unfinished file reported final"),
        }
    }
    assert!(last > 0);
}

/// A file cut inside its header (an engine that died while opening it) holds no sample: it is
/// reported empty, so it gets deleted instead of retried forever.
#[test]
fn a_file_cut_inside_its_header_is_empty() {
    let mut raw = MemFile::new();
    record(&mut raw, &feed(1.0), 250_000, false).unwrap();
    let data = raw.data;
    let init_end = {
        let ftyp = be32(&data, 0) as usize;
        ftyp + 16 + be32(&data, ftyp + 16) as usize
    };
    for cut in 0..init_end {
        let mut f = MemFile::from_bytes(data[..cut].to_vec());
        assert_eq!(recover(&mut f).unwrap(), Recovered::Empty, "cut {cut}");
    }
}

#[test]
fn power_loss_at_every_write_leaves_a_recoverable_file() {
    let input = feed(1.5);
    let mut probe = MemFile::new();
    record(&mut probe, &input, 250_000, true).unwrap();
    let writes = probe.writes;
    let mut recovered_any = 0;
    for crash in 0..writes {
        let mut f = MemFile::crash_at(crash);
        let r = record(&mut f, &input, 250_000, true);
        assert!(r.is_err(), "write {crash} should have failed");
        assert!(f.crashed());
        let mut disk = f.reboot();
        match recover(&mut disk) {
            // Torn ftyp/placeholder/init moov: nothing was recorded yet.
            Err(_) => assert!(crash == 0, "write {crash}: unrecoverable"),
            Ok(Recovered::Empty) => {}
            Ok(Recovered::Final) => {
                let t = read_plain(&disk.data);
                check_samples(&disk.data, &t);
                recovered_any += 1;
            }
            Ok(Recovered::Repaired { .. }) => {
                let t = read_plain(&disk.data);
                check_samples(&disk.data, &t);
                assert_eq!(recover(&mut disk).unwrap(), Recovered::Final);
                recovered_any += 1;
            }
        }
    }
    assert!(recovered_any > writes / 2, "most crash points keep data ({recovered_any}/{writes})");
}

#[test]
fn every_fragment_is_synced() {
    let mut f = MemFile::new();
    record(&mut f, &feed(2.0), 500_000, true).unwrap();
    // create + 4 fragments (+1 for the tail) + 2 in finalize
    assert!(f.syncs >= 1 + 4 + 2, "syncs {}", f.syncs);
}

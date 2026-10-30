// SPDX-FileCopyrightText: The Yasvara Project
// SPDX-License-Identifier: Apache-2.0

//! Files built from real onyx encoder output (tests/data, dumped by RekamProbe's FixtureDump),
//! checked by an independent decoder: ffmpeg must decode every frame without an error, both
//! for a finished recording and for one recovered after a mid-file cut. Skipped when ffmpeg is
//! not installed (the Soong host runner).

use rekam_media::{recover, Codec, HybridWriter, MemFile, Recovered, TrackConfig, WriterOptions};
use std::path::{Path, PathBuf};
use std::process::Command;

fn data_dir() -> PathBuf {
    let candidates = [
        option_env!("CARGO_MANIFEST_DIR").map(|d| Path::new(d).join("tests/data")),
        std::env::current_exe().ok().and_then(|e| e.parent().map(|p| p.join("tests/data"))),
    ];
    candidates.into_iter().flatten().find(|p| p.join("hevc.pkts").exists()).expect("tests/data fixtures")
}

struct Packet {
    pts_us: i64,
    key: bool,
    data: Vec<u8>,
}

fn csd(name: &str) -> Vec<u8> {
    let b = std::fs::read(data_dir().join(format!("{name}.csd"))).unwrap();
    let mut out = Vec::new();
    let mut at = 0;
    while at + 4 <= b.len() {
        let n = u32::from_be_bytes(b[at..at + 4].try_into().unwrap()) as usize;
        out.extend_from_slice(&b[at + 4..at + 4 + n]);
        at += 4 + n;
    }
    out
}

fn packets(name: &str) -> Vec<Packet> {
    let b = std::fs::read(data_dir().join(format!("{name}.pkts"))).unwrap();
    let mut out = Vec::new();
    let mut at = 0;
    while at + 13 <= b.len() {
        let n = u32::from_be_bytes(b[at..at + 4].try_into().unwrap()) as usize;
        let pts_us = i64::from_be_bytes(b[at + 4..at + 12].try_into().unwrap());
        let key = b[at + 12] != 0;
        out.push(Packet { pts_us, key, data: b[at + 13..at + 13 + n].to_vec() });
        at += 13 + n;
    }
    // The fixtures come from separate encoder runs with unrelated clocks; the engine feeds one
    // CLOCK_MONOTONIC timeline, so line every stream up at zero.
    let base = out.first().map_or(0, |p| p.pts_us);
    out.iter_mut().for_each(|p| p.pts_us -= base);
    out
}

fn track(codec: Codec, name: &str) -> TrackConfig {
    let video = codec.is_video();
    TrackConfig {
        codec,
        timescale: if video { 90_000 } else { 48_000 },
        width: if video { 640 } else { 0 },
        height: if video { 360 } else { 0 },
        sample_rate: 48_000,
        channels: 2,
        avg_bitrate: 128_000,
        csd: csd(name),
        enabled: true,
        alternate_group: 0,
    }
}

/// Interleaves the streams by timestamp, as the engine's packet bus does.
fn mux(video: (Codec, &str), audio: (Codec, &str), finish: bool) -> MemFile {
    let tracks = [track(video.0, video.1), track(audio.0, audio.1)];
    let mut all: Vec<(usize, Packet)> = packets(video.1).into_iter().map(|p| (0, p)).collect();
    all.extend(packets(audio.1).into_iter().map(|p| (1, p)));
    all.sort_by_key(|(_, p)| p.pts_us);
    let mut file = MemFile::new();
    let mut w = HybridWriter::create(&mut file, &tracks, WriterOptions { fragment_us: 250_000, ..Default::default() }).unwrap();
    for (t, p) in &all {
        w.write(*t, &p.data, p.pts_us, p.key).unwrap();
    }
    if finish {
        w.finish().unwrap();
    }
    file
}

fn ffmpeg_available() -> bool {
    Command::new("ffmpeg").arg("-version").output().is_ok_and(|o| o.status.success())
}

/// Decodes every stream; returns (frames per stream, ffmpeg stderr).
fn decode(bytes: &[u8], tag: &str) -> (Vec<u64>, String) {
    let path = std::env::temp_dir().join(format!("rekam-{tag}-{}.mp4", std::process::id()));
    std::fs::write(&path, bytes).unwrap();
    let out = Command::new("ffmpeg").args(["-v", "error", "-xerror", "-i"]).arg(&path).args(["-f", "null", "-"]).output().unwrap();
    let probe = Command::new("ffprobe")
        .args(["-v", "error", "-count_frames", "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0"])
        .arg(&path)
        .output()
        .unwrap();
    if std::env::var_os("REKAM_KEEP").is_none() {
        std::fs::remove_file(&path).ok();
    }
    let frames = String::from_utf8_lossy(&probe.stdout).lines().filter_map(|l| l.trim().trim_end_matches(',').parse().ok()).collect();
    let err = String::from_utf8_lossy(&out.stderr).into_owned() + &String::from_utf8_lossy(&probe.stderr);
    assert!(out.status.success(), "{tag}: ffmpeg failed: {err}");
    (frames, err)
}

fn check(video: (Codec, &str), audio: (Codec, &str)) {
    if !ffmpeg_available() {
        eprintln!("ffmpeg not installed: skipping decode check");
        return;
    }
    let tag = format!("{}-{}", video.1, audio.1);
    let vframes = packets(video.1).len() as u64;

    let done = mux(video, audio, true);
    let (frames, err) = decode(&done.data, &tag);
    assert!(err.trim().is_empty(), "{tag}: decoder complained: {err}");
    assert_eq!(frames[0], vframes, "{tag}: every video frame decodes");
    assert!(frames[1] > 0, "{tag}: audio decodes");

    // Crash mid-recording: cut the unfinished file inside its last fragment, recover, decode.
    let raw = mux(video, audio, false);
    let mut cut = MemFile::from_bytes(raw.data[..raw.data.len() * 2 / 3].to_vec());
    assert!(matches!(recover(&mut cut).unwrap(), Recovered::Repaired { .. }));
    let (frames, err) = decode(&cut.data, &format!("{tag}-recovered"));
    assert!(err.trim().is_empty(), "{tag}: recovered file: {err}");
    assert!(frames[0] > 0 && frames[0] < vframes, "{tag}: recovered a prefix ({} of {vframes})", frames[0]);
}

#[test]
fn hevc_with_aac() {
    check((Codec::Hevc, "hevc"), (Codec::Aac, "aac"));
}

#[test]
fn avc_with_opus() {
    check((Codec::Avc, "avc"), (Codec::Opus, "opus"));
}

#[test]
fn hevc_with_flac() {
    check((Codec::Hevc, "hevc"), (Codec::Flac, "flac"));
}

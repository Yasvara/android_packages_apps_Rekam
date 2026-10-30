// SPDX-FileCopyrightText: The Yasvara Project
// SPDX-License-Identifier: Apache-2.0

//! Encoder threads hand packets to [`Recorder::write`]; one `rk-io` thread owns the file.
//!
//! Packets are copied once, into pooled buffers that come back after the write, so steady state
//! allocates nothing. In-flight bytes are bounded: past the budget, non-key video is dropped
//! until the track's next key frame (a decoder can't use frames after a gap), and audio and key
//! frames still go through so the file keeps its timeline.

use rekam_media::{HybridWriter, RandomAccess, Result as MediaResult, TrackConfig, WriterOptions};
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicUsize, Ordering};
use std::sync::mpsc::{sync_channel, Receiver, SyncSender};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;

/// What happened to one packet.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Queued {
    /// On its way to the file.
    Yes,
    /// Dropped for backpressure (or waiting for a key frame after a drop).
    Dropped,
    /// The writer failed; stop the session.
    Failed,
}

/// Live counters, readable from any thread.
#[derive(Debug, Default)]
pub struct Stats {
    /// Bytes on disk.
    pub bytes: AtomicU64,
    /// Media time written, microseconds.
    pub duration_us: AtomicU64,
    /// Packets dropped for backpressure.
    pub dropped: AtomicU64,
    /// Bytes queued and not yet written.
    pub in_flight: AtomicUsize,
    /// Set once the writer fails.
    pub failed: AtomicBool,
}

enum Msg {
    Sample { track: usize, data: Vec<u8>, pts_us: i64, sync: bool },
    Flush,
    Finish,
}

/// Owns the `rk-io` thread and its [`HybridWriter`].
pub struct Recorder {
    tx: SyncSender<Msg>,
    pool: Arc<Mutex<Vec<Vec<u8>>>>,
    stats: Arc<Stats>,
    budget: usize,
    video: Vec<bool>,
    need_key: Vec<AtomicBool>,
    thread: Option<JoinHandle<MediaResult<()>>>,
    error: Arc<Mutex<Option<String>>>,
}

impl Recorder {
    /// Writes the file header and starts the thread. `budget` bounds in-flight bytes (about 4 s
    /// of the session bitrate).
    pub fn start<F: RandomAccess + Send + 'static>(
        file: F,
        tracks: &[TrackConfig],
        opts: WriterOptions,
        budget: usize,
    ) -> MediaResult<Recorder> {
        let writer = HybridWriter::create(file, tracks, opts)?;
        let (tx, rx) = sync_channel(4096);
        let pool = Arc::new(Mutex::new(Vec::new()));
        let stats = Arc::new(Stats::default());
        let error = Arc::new(Mutex::new(None));
        let thread = {
            let (pool, stats, error) = (pool.clone(), stats.clone(), error.clone());
            std::thread::Builder::new()
                .name("rk-io".into())
                .spawn(move || run(writer, rx, &pool, &stats, &error))
                .map_err(rekam_media::Error::Io)?
        };
        Ok(Recorder {
            tx,
            pool,
            stats,
            budget,
            video: tracks.iter().map(|t| t.codec.is_video()).collect(),
            need_key: tracks.iter().map(|_| AtomicBool::new(false)).collect(),
            thread: Some(thread),
            error,
        })
    }

    /// Queues one encoded packet; safe from several encoder threads at once.
    pub fn write(&self, track: usize, data: &[u8], pts_us: i64, sync: bool) -> Queued {
        if self.stats.failed.load(Ordering::Acquire) {
            return Queued::Failed;
        }
        let Some(&video) = self.video.get(track) else { return Queued::Failed };
        if video {
            let need = &self.need_key[track];
            let over = self.stats.in_flight.load(Ordering::Acquire) + data.len() > self.budget;
            if !sync && (over || need.load(Ordering::Acquire)) {
                need.store(true, Ordering::Release);
                self.stats.dropped.fetch_add(1, Ordering::Relaxed);
                return Queued::Dropped;
            }
            if sync {
                need.store(false, Ordering::Release);
            }
        }
        let mut buf = self.pool.lock().unwrap().pop().unwrap_or_default();
        buf.clear();
        buf.extend_from_slice(data);
        self.stats.in_flight.fetch_add(buf.len(), Ordering::AcqRel);
        match self.tx.send(Msg::Sample { track, data: buf, pts_us, sync }) {
            Ok(()) => Queued::Yes,
            Err(_) => Queued::Failed,
        }
    }

    /// Writes the current fragment now (pause, split).
    pub fn flush(&self) {
        self.tx.send(Msg::Flush).ok();
    }

    /// Counters for the UI and `dumpsys`.
    pub fn stats(&self) -> &Stats {
        &self.stats
    }

    /// The writer's error, if it failed.
    pub fn error(&self) -> Option<String> {
        self.error.lock().unwrap().clone()
    }

    /// Drains the queue, writes the last fragment and finalizes the file.
    pub fn finish(mut self) -> MediaResult<()> {
        self.tx.send(Msg::Finish).ok();
        match self.thread.take().map(JoinHandle::join) {
            Some(Ok(r)) => r,
            _ => Err(rekam_media::Error::Config("rk-io thread panicked")),
        }
    }
}

impl Drop for Recorder {
    /// A recorder dropped without `finish` behaves like a crash: the file stays recoverable.
    fn drop(&mut self) {
        if let Some(t) = self.thread.take() {
            drop(std::mem::replace(&mut self.tx, sync_channel(0).0));
            t.join().ok();
        }
    }
}

fn run<F: RandomAccess>(
    mut writer: HybridWriter<F>,
    rx: Receiver<Msg>,
    pool: &Mutex<Vec<Vec<u8>>>,
    stats: &Stats,
    error: &Mutex<Option<String>>,
) -> MediaResult<()> {
    let fail = |e: &rekam_media::Error| {
        *error.lock().unwrap() = Some(e.to_string());
        stats.failed.store(true, Ordering::Release);
    };
    for msg in rx {
        match msg {
            Msg::Sample { track, data, pts_us, sync } => {
                let r = writer.write(track, &data, pts_us, sync);
                stats.in_flight.fetch_sub(data.len(), Ordering::AcqRel);
                pool.lock().unwrap().push(data);
                if let Err(e) = r {
                    fail(&e);
                    return Err(e);
                }
                stats.bytes.store(writer.bytes(), Ordering::Relaxed);
                stats.duration_us.store(writer.duration_us().max(0) as u64, Ordering::Relaxed);
            }
            Msg::Flush => {
                if let Err(e) = writer.flush() {
                    fail(&e);
                    return Err(e);
                }
            }
            Msg::Finish => {
                return writer.finish().map(|_| ()).inspect_err(|e| fail(e));
            }
        }
    }
    // Every sender is gone without Finish: leave the file as a crash would.
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use rekam_media::{recover, Codec, MemFile, Recovered};
    use std::sync::Arc;

    const AVC_CSD: &[u8] = &[
        0, 0, 0, 1, 0x67, 0x64, 0, 0x28, 0xac, 0xd9, 0x40, 0x78, 0x02, 0x27, 0xe5, 0xc0, 0x44, 0, 0, 3, 0, 4, 0, 0,
        3, 0, 0xf0, 0x3c, 0x60, 0xc6, 0x58, 0, 0, 0, 1, 0x68, 0xeb, 0xe3, 0xcb, 0x22, 0xc0,
    ];

    fn tracks() -> Vec<TrackConfig> {
        vec![
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
            },
            TrackConfig {
                codec: Codec::Aac,
                timescale: 48_000,
                width: 0,
                height: 0,
                sample_rate: 48_000,
                channels: 2,
                avg_bitrate: 128_000,
                csd: vec![0x11, 0x90],
                enabled: true,
                alternate_group: 0,
            },
        ]
    }

    /// A shared in-memory file the test can inspect after the recorder is gone.
    #[derive(Clone, Default)]
    struct Shared(Arc<Mutex<MemFile>>);
    impl RandomAccess for Shared {
        fn write_at(&mut self, o: u64, d: &[u8]) -> std::io::Result<()> {
            self.0.lock().unwrap().write_at(o, d)
        }
        fn read_at(&mut self, o: u64, b: &mut [u8]) -> std::io::Result<()> {
            self.0.lock().unwrap().read_at(o, b)
        }
        fn set_len(&mut self, l: u64) -> std::io::Result<()> {
            self.0.lock().unwrap().set_len(l)
        }
        fn sync_data(&mut self) -> std::io::Result<()> {
            self.0.lock().unwrap().sync_data()
        }
        fn size(&mut self) -> std::io::Result<u64> {
            self.0.lock().unwrap().size()
        }
    }

    #[test]
    fn two_encoder_threads_one_file() {
        let file = Shared::default();
        let rec = Arc::new(Recorder::start(file.clone(), &tracks(), WriterOptions::default(), 1 << 20).unwrap());
        let v = {
            let rec = rec.clone();
            std::thread::spawn(move || {
                for i in 0..90i64 {
                    assert_eq!(rec.write(0, &[0u8; 500], i * 33_333, i % 30 == 0), Queued::Yes);
                }
            })
        };
        let a = {
            let rec = rec.clone();
            std::thread::spawn(move || {
                for i in 0..140i64 {
                    rec.write(1, &[1u8; 60], 10_000 + i * 21_333, true);
                }
            })
        };
        v.join().unwrap();
        a.join().unwrap();
        Arc::try_unwrap(rec).ok().unwrap().finish().unwrap();
        let mut f = file.0.lock().unwrap().clone();
        assert_eq!(recover(&mut f).unwrap(), Recovered::Final);
    }

    /// A file whose writes take 3 ms, so in-flight bytes pile up like on a stalled disk.
    #[derive(Clone, Default)]
    struct Slow(Shared);
    impl RandomAccess for Slow {
        fn write_at(&mut self, o: u64, d: &[u8]) -> std::io::Result<()> {
            std::thread::sleep(std::time::Duration::from_millis(3));
            self.0.write_at(o, d)
        }
        fn read_at(&mut self, o: u64, b: &mut [u8]) -> std::io::Result<()> {
            self.0.read_at(o, b)
        }
        fn set_len(&mut self, l: u64) -> std::io::Result<()> {
            self.0.set_len(l)
        }
        fn sync_data(&mut self) -> std::io::Result<()> {
            self.0.sync_data()
        }
        fn size(&mut self) -> std::io::Result<u64> {
            self.0.size()
        }
    }

    #[test]
    fn over_budget_drops_until_the_next_key_frame() {
        let opts = WriterOptions { fragment_us: 1, ..Default::default() }; // a write per sample
        let rec = Recorder::start(Slow::default(), &tracks(), opts, 4000).unwrap();
        let mut dropped = 0;
        let mut after_drop_ok = true;
        let mut waiting = false;
        for i in 0..300i64 {
            let key = i % 30 == 0;
            match rec.write(0, &[0u8; 900], i * 33_333, key) {
                Queued::Dropped => {
                    dropped += 1;
                    waiting = true;
                }
                Queued::Yes => {
                    if waiting && !key {
                        after_drop_ok = false;
                    }
                    if key {
                        waiting = false;
                    }
                }
                Queued::Failed => panic!("writer failed"),
            }
        }
        assert!(dropped > 0, "a stalled disk must cause drops");
        assert!(after_drop_ok, "a non-key frame went through after a drop, before a key frame");
        assert_eq!(rec.stats().dropped.load(Ordering::Relaxed), dropped);
        rec.finish().unwrap();
    }

    #[test]
    fn dropping_without_finish_leaves_a_recoverable_file() {
        let file = Shared::default();
        {
            let rec = Recorder::start(file.clone(), &tracks(), WriterOptions { fragment_us: 200_000, ..Default::default() }, 1 << 20).unwrap();
            for i in 0..60i64 {
                rec.write(0, &[0u8; 200], i * 33_333, i % 15 == 0);
            }
        }
        let mut f = file.0.lock().unwrap().clone();
        assert!(matches!(recover(&mut f).unwrap(), Recovered::Repaired { .. }));
    }

    #[test]
    fn writer_failure_surfaces_to_callers() {
        let file = Shared(Arc::new(Mutex::new(MemFile::crash_at(1))));
        let rec = Recorder::start(file, &tracks(), WriterOptions { fragment_us: 100_000, ..Default::default() }, 1 << 20).unwrap();
        let mut failed = false;
        for i in 0..200i64 {
            if rec.write(0, &[0u8; 100], i * 33_333, i % 10 == 0) == Queued::Failed {
                failed = true;
                break;
            }
            std::thread::sleep(std::time::Duration::from_millis(1));
        }
        assert!(failed, "callers learn about the failed writer");
        assert!(rec.error().is_some());
        assert!(rec.finish().is_err());
    }
}

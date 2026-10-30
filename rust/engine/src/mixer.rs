// SPDX-FileCopyrightText: The Yasvara Project
// SPDX-License-Identifier: Apache-2.0

//! Mixer: a fixed set of slots, each an interleaved-stereo f32 source at 48 kHz, mixed into one
//! s16 stream for the audio encoder.
//!
//! Sources join and leave at any time ([`Mixer::set_active`]); nothing is allocated after
//! construction. Slot 0 is the master: its blocks pace the output, so the file follows the
//! playback-capture clock. Other slots contribute what they have and are zero-filled on
//! underrun. Each slot buffers at most 200 ms; older audio is dropped rather than letting latency
//! grow.
//!
//! Per slot, in order: mono fold, gain (fader and boost as one), balance, mute. The sum goes
//! through the master fader and a peak limiter whose ceiling no sample exceeds, so the file never
//! clips however hot the sources are.
//!
//! Every block is metered per slot after the fader, before mute (a muted source still shows what
//! it would contribute, as on a desk), plus the master after the limiter, and published to the
//! attached [`MeterPage`]: slots first, master last.

use crate::meters::MeterPage;
use std::collections::VecDeque;
use std::sync::{Condvar, Mutex};
use std::time::Duration;

/// Channels per frame.
pub const CHANNELS: usize = 2;
/// Level of silence, and of a slot with no source.
pub const FLOOR_DB: f32 = -100.0;
const MAX_FRAMES: usize = 48_000 / 5;
/// Limiter ceiling, −0.3 dBFS: headroom for the encoder's reconstruction peaks.
pub const LIMIT_CEILING: f32 = 0.966_051;
/// Limiter release: the gain recovers with this time constant (100 ms at 48 kHz).
const LIMIT_RELEASE: f32 = 1.0 / (0.1 * 48_000.0);

fn linear(db: f32) -> f32 {
    if db <= -60.0 {
        0.0
    } else {
        10f32.powf(db / 20.0)
    }
}

struct Slot {
    active: bool,
    buf: VecDeque<f32>,
    gain: f32,
    muted: bool,
    mono: bool,
    /// Balance as per-channel gains: 1 at centre, the far side falls linearly to 0.
    left: f32,
    right: f32,
    overflowed: u64,
    /// The last block's (peak, RMS) in dBFS.
    level: (f32, f32),
}

impl Slot {
    fn new() -> Self {
        Self {
            active: false,
            buf: VecDeque::with_capacity(MAX_FRAMES * CHANNELS),
            gain: 1.0,
            muted: false,
            mono: false,
            left: 1.0,
            right: 1.0,
            overflowed: 0,
            level: (FLOOR_DB, FLOOR_DB),
        }
    }
}

struct State {
    slots: Vec<Slot>,
    /// Reused accumulator: no allocation per block.
    acc: Vec<f32>,
    master: f32,
    /// How far the limiter turns down now: its gain is `1 - reduction`. Kept as the reduction so
    /// the release decays it multiplicatively; stepping a gain towards 1 stalls on f32 rounding.
    reduction: f32,
    master_level: (f32, f32),
    meters: Option<MeterPage<'static>>,
}

/// Thread-safe: capture threads `push`, the mix thread `pull`s, controls come from anywhere.
pub struct Mixer {
    state: Mutex<State>,
    ready: Condvar,
}

fn db(linear: f32) -> f32 {
    if linear <= 1e-5 {
        FLOOR_DB
    } else {
        20.0 * linear.log10()
    }
}

impl Mixer {
    /// A mixer with `slots` slots (at least one), all inactive, at unity gain.
    pub fn new(slots: usize) -> Self {
        let slots = (0..slots.max(1)).map(|_| Slot::new()).collect();
        Self {
            state: Mutex::new(State {
                slots,
                acc: Vec::with_capacity(MAX_FRAMES * CHANNELS),
                master: 1.0,
                reduction: 0.0,
                master_level: (FLOOR_DB, FLOOR_DB),
                meters: None,
            }),
            ready: Condvar::new(),
        }
    }

    /// Starts or ends a source in `slot`. Either way its buffered audio and level are cleared;
    /// gain and mute are kept.
    pub fn set_active(&self, slot: usize, active: bool) {
        let mut s = self.state.lock().unwrap();
        if let Some(src) = s.slots.get_mut(slot) {
            src.active = active;
            src.buf.clear();
            src.level = (FLOOR_DB, FLOOR_DB);
        }
    }

    /// Appends interleaved stereo samples to an active slot.
    pub fn push(&self, slot: usize, samples: &[f32]) {
        let mut s = self.state.lock().unwrap();
        let Some(src) = s.slots.get_mut(slot).filter(|s| s.active) else { return };
        src.buf.extend(samples.iter().copied());
        let over = src.buf.len().saturating_sub(MAX_FRAMES * CHANNELS);
        if over > 0 {
            src.buf.drain(..over.next_multiple_of(CHANNELS));
            src.overflowed += 1;
        }
        if slot == 0 {
            self.ready.notify_one();
        }
    }

    /// Linear gain from decibels; −60 dB and below is silence.
    pub fn set_gain_db(&self, slot: usize, db: f32) {
        if let Some(src) = self.state.lock().unwrap().slots.get_mut(slot) {
            src.gain = linear(db);
        }
    }

    /// Folds a slot to mono, and balances it from −1 (left only) to 1 (right only).
    pub fn set_shape(&self, slot: usize, mono: bool, balance: f32) {
        if let Some(src) = self.state.lock().unwrap().slots.get_mut(slot) {
            let b = if balance.is_finite() { balance.clamp(-1.0, 1.0) } else { 0.0 };
            src.mono = mono;
            src.left = (1.0 - b).min(1.0);
            src.right = (1.0 + b).min(1.0);
        }
    }

    /// The master fader, in decibels like a slot's.
    pub fn set_master_db(&self, db: f32) {
        self.state.lock().unwrap().master = linear(db);
    }

    /// Mutes or unmutes a slot.
    pub fn set_muted(&self, slot: usize, muted: bool) {
        if let Some(src) = self.state.lock().unwrap().slots.get_mut(slot) {
            src.muted = muted;
        }
    }

    /// Publishes every block's levels to `page` from now on.
    pub fn attach_meters(&self, page: MeterPage<'static>) {
        let mut s = self.state.lock().unwrap();
        let floor = (0..=s.slots.len()).map(|_| (FLOOR_DB, FLOOR_DB));
        page.write(floor);
        s.meters = Some(page);
    }

    /// Times a slot dropped its oldest audio because nobody pulled.
    pub fn overflows(&self, slot: usize) -> u64 {
        self.state.lock().unwrap().slots.get(slot).map_or(0, |s| s.overflowed)
    }

    /// Mixes one block of `out.len() / 2` frames once the master has it, waiting up to `timeout`.
    /// Returns false on timeout, leaving `out` untouched.
    pub fn pull(&self, out: &mut [i16], timeout: Duration) -> bool {
        let need = out.len();
        let guard = self.state.lock().unwrap();
        let (mut guard, _) =
            self.ready.wait_timeout_while(guard, timeout, |s| !s.slots[0].active || s.slots[0].buf.len() < need).unwrap();
        if !guard.slots[0].active || guard.slots[0].buf.len() < need {
            return false;
        }
        let State { slots, acc, master, reduction, master_level, meters } = &mut *guard;
        acc.clear();
        acc.resize(need, 0.0);
        for src in slots.iter_mut().filter(|s| s.active) {
            let frames = need.min(src.buf.len()) / CHANNELS;
            let keep = if src.muted { 0.0 } else { 1.0 };
            let (gl, gr) = (src.gain * src.left, src.gain * src.right);
            let (mut peak, mut sum_sq) = (0f32, 0f64);
            for f in 0..frames {
                let (mut l, mut r) = (src.buf.pop_front().unwrap_or(0.0), src.buf.pop_front().unwrap_or(0.0));
                if src.mono {
                    l = 0.5 * (l + r);
                    r = l;
                }
                let (yl, yr) = (l * gl, r * gr);
                peak = peak.max(yl.abs()).max(yr.abs());
                sum_sq += f64::from(yl * yl + yr * yr);
                acc[2 * f] += yl * keep;
                acc[2 * f + 1] += yr * keep;
            }
            // An underrun is silence the listener hears; it counts in the RMS as such.
            src.level = (db(peak), db((sum_sq / need.max(1) as f64).sqrt() as f32));
        }
        let (mut peak, mut sum_sq) = (0f32, 0f64);
        for (frame, o) in acc.chunks_exact(CHANNELS).zip(out.chunks_exact_mut(CHANNELS)) {
            let (l, r) = (frame[0] * *master, frame[1] * *master);
            // Instant attack to exactly the ceiling, exponential release back to unity.
            let hottest = l.abs().max(r.abs());
            let needed = if hottest > LIMIT_CEILING { 1.0 - LIMIT_CEILING / hottest } else { 0.0 };
            *reduction = needed.max(*reduction * (1.0 - LIMIT_RELEASE));
            let gain = 1.0 - *reduction;
            let (l, r) = (l * gain, r * gain);
            peak = peak.max(l.abs()).max(r.abs());
            sum_sq += f64::from(l * l + r * r);
            o[0] = (l * 32767.0).round() as i16;
            o[1] = (r * 32767.0).round() as i16;
        }
        *master_level = (db(peak), db((sum_sq / need.max(1) as f64).sqrt() as f32));
        if let Some(page) = meters {
            let level = *master_level;
            page.write(
                slots
                    .iter()
                    .map(|s| if s.active { s.level } else { (FLOOR_DB, FLOOR_DB) })
                    .chain(std::iter::once(level)),
            );
        }
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::meters::page_words;
    use std::sync::atomic::AtomicU32;

    const T: Duration = Duration::from_millis(1);

    fn mixer(slots: usize) -> Mixer {
        let m = Mixer::new(slots);
        for i in 0..slots {
            m.set_active(i, true);
        }
        m
    }

    /// A page for `slots` slots and the master.
    fn page(slots: usize) -> MeterPage<'static> {
        let words: &'static [AtomicU32] = Box::leak((0..page_words(slots + 1)).map(|_| AtomicU32::new(0)).collect());
        MeterPage::new(words).unwrap()
    }

    fn read(p: &MeterPage<'_>, slots: usize) -> Vec<(f32, f32)> {
        let mut out = vec![(0.0, 0.0); slots];
        let n = p.read(&mut out, 1).unwrap();
        out.truncate(n);
        out
    }

    fn near(a: f32, b: f32) -> bool {
        (a - b).abs() < 0.01
    }

    #[test]
    fn sums_slots_with_gain_and_mute() {
        let m = mixer(3);
        m.push(0, &[0.25; 8]);
        m.push(1, &[0.25; 8]);
        m.push(2, &[0.5; 8]);
        m.set_gain_db(1, -6.0206); // ×0.5
        m.set_muted(2, true);
        let mut out = [0i16; 8];
        assert!(m.pull(&mut out, T));
        assert!(out.iter().all(|&v| (v - 12288).abs() <= 1), "{out:?}"); // 0.25 + 0.125
    }

    #[test]
    fn waits_for_the_master_and_zero_fills_others() {
        let m = mixer(2);
        m.push(1, &[0.5; 4]);
        let mut out = [7i16; 8];
        assert!(!m.pull(&mut out, T), "no master audio yet");
        assert_eq!(out, [7; 8]);
        m.push(0, &[0.0; 8]);
        assert!(m.pull(&mut out, T));
        assert_eq!(out, [16384, 16384, 16384, 16384, 0, 0, 0, 0]);
    }

    fn ceiling() -> i16 {
        (LIMIT_CEILING * 32767.0).round() as i16
    }

    #[test]
    fn the_limiter_holds_the_ceiling() {
        let m = mixer(2);
        m.push(0, &[0.9, -0.9]);
        m.push(1, &[0.9, -0.9]);
        let mut out = [0i16; 2];
        assert!(m.pull(&mut out, T));
        assert_eq!(out, [ceiling(), -ceiling()]);
    }

    #[test]
    fn no_sample_ever_exceeds_the_ceiling() {
        let m = mixer(3);
        m.set_gain_db(1, 20.0);
        m.set_master_db(6.0);
        let frames = 4096;
        let loud: Vec<f32> = (0..frames * CHANNELS).map(|i| ((i * 7919) % 2001) as f32 / 1000.0 - 1.0).collect();
        for slot in 0..3 {
            m.push(slot, &loud);
        }
        let mut out = vec![0i16; frames * CHANNELS];
        assert!(m.pull(&mut out, T));
        assert!(out.iter().all(|&v| v.unsigned_abs() <= ceiling() as u16), "max {:?}", out.iter().map(|v| v.unsigned_abs()).max());
    }

    #[test]
    fn the_limiter_lets_go_slowly() {
        let m = mixer(1);
        let mut out = vec![0i16; 2];
        m.push(0, &[1.0, 1.0]);
        assert!(m.pull(&mut out, T));
        // Right after a peak a quiet sample is still turned down…
        m.push(0, &[0.5, 0.5]);
        assert!(m.pull(&mut out, T));
        assert!(i32::from(out[0]) < 16384, "{out:?}");
        // …and after a second of quiet it is back at unity.
        let quiet = vec![0.5f32; 48_000 * CHANNELS / 5];
        for _ in 0..5 {
            m.push(0, &quiet);
            let mut block = vec![0i16; quiet.len()];
            assert!(m.pull(&mut block, T));
            out.copy_from_slice(&block[block.len() - 2..]);
        }
        assert!((i32::from(out[0]) - 16384).abs() <= 1, "{out:?}");
    }

    #[test]
    fn mono_and_balance() {
        let m = mixer(2);
        m.set_shape(0, true, 0.0);
        m.push(0, &[0.4, 0.0]);
        m.push(1, &[0.0, 0.0]);
        let mut out = [0i16; 2];
        assert!(m.pull(&mut out, T));
        assert_eq!(out, [6553, 6553], "mono folds to the average");
        m.set_shape(0, false, 1.0);
        m.push(0, &[0.4, 0.4]);
        assert!(m.pull(&mut out, T));
        assert_eq!(out, [0, 13107], "full right");
        m.set_shape(0, false, -0.5);
        m.push(0, &[0.4, 0.4]);
        assert!(m.pull(&mut out, T));
        assert_eq!(out, [13107, 6553], "half left");
        m.set_shape(0, false, f32::NAN);
        m.push(0, &[0.4, 0.4]);
        assert!(m.pull(&mut out, T));
        assert_eq!(out, [13107, 13107], "nonsense is centre");
    }

    #[test]
    fn master_fader_and_meter() {
        let m = mixer(2);
        let p = page(2);
        m.attach_meters(p);
        m.set_master_db(-6.0206);
        m.push(0, &[0.5, 0.5]);
        m.push(1, &[0.0, 0.0]);
        let mut out = [0i16; 2];
        assert!(m.pull(&mut out, T));
        assert_eq!(out, [8192, 8192]);
        let lv = read(&p, 3);
        assert_eq!(lv.len(), 3, "two slots and the master");
        assert!(near(lv[0].0, -6.0206), "the slot is metered before the master: {lv:?}");
        assert!(near(lv[2].0, -12.041) && near(lv[2].1, -12.041), "master after its fader: {lv:?}");
    }

    #[test]
    fn latency_is_bounded() {
        let m = mixer(1);
        m.push(0, &vec![0.1f32; 48_000 * CHANNELS]);
        assert!(m.overflows(0) > 0);
        let mut out = vec![0i16; MAX_FRAMES * CHANNELS];
        assert!(m.pull(&mut out, T));
        assert!(!m.pull(&mut [0i16; 2], T), "only 200 ms were kept");
    }

    #[test]
    fn unity_and_silence_gains() {
        let m = mixer(1);
        m.set_gain_db(0, -80.0);
        m.push(0, &[1.0; 2]);
        let mut out = [1i16; 2];
        assert!(m.pull(&mut out, T));
        assert_eq!(out, [0, 0]);
    }

    #[test]
    fn inactive_slots_take_nothing_and_meter_the_floor() {
        let m = Mixer::new(3);
        m.set_active(0, true);
        m.push(1, &[0.9; 4]); // inactive: dropped
        m.set_active(2, true);
        m.push(2, &[0.5; 4]);
        m.set_active(2, false); // leaving clears what it had
        m.push(0, &[0.25; 4]);
        let p = page(3);
        m.attach_meters(p);
        let mut out = [0i16; 4];
        assert!(m.pull(&mut out, T));
        assert!(out.iter().all(|&v| (v - 8192).abs() <= 1), "{out:?}");
        let lv = read(&p, 4);
        assert!(near(lv[0].0, -12.041) && near(lv[0].1, -12.041), "{lv:?}");
        assert_eq!(lv[1], (FLOOR_DB, FLOOR_DB));
        assert_eq!(lv[2], (FLOOR_DB, FLOOR_DB));
    }

    #[test]
    fn a_rejoining_slot_starts_clean() {
        let m = mixer(2);
        m.push(1, &[0.5; 4]);
        m.set_active(1, false);
        m.set_active(1, true);
        m.push(0, &[0.0; 4]);
        let mut out = [9i16; 4];
        assert!(m.pull(&mut out, T));
        assert_eq!(out, [0; 4]);
    }

    #[test]
    fn no_master_no_output() {
        let m = Mixer::new(2);
        m.set_active(1, true);
        m.push(1, &[0.5; 4]);
        assert!(!m.pull(&mut [0i16; 4], T));
    }

    #[test]
    fn meters_peak_and_rms_after_gain_before_mute() {
        let m = mixer(2);
        let p = page(2);
        m.attach_meters(p);
        assert_eq!(read(&p, 3), vec![(FLOOR_DB, FLOOR_DB); 3], "attaching publishes the floor");
        m.push(0, &[0.5, -0.5, 0.5, -0.5]);
        m.push(1, &[1.0, 0.0, 0.0, 0.0]);
        m.set_gain_db(1, -6.0206);
        m.set_muted(1, true);
        let mut out = [0i16; 4];
        assert!(m.pull(&mut out, T));
        let lv = read(&p, 2);
        assert!(near(lv[0].0, -6.0206) && near(lv[0].1, -6.0206), "{lv:?}");
        assert!(near(lv[1].0, -6.0206), "a muted slot is still metered: {lv:?}");
        assert!(near(lv[1].1, -12.041), "rms of one 0.5 in four: {lv:?}");
        assert!(out.iter().all(|&v| v.abs() == 16384), "mute keeps it out of the mix: {out:?}");
    }

    #[test]
    fn meters_count_an_underrun_as_silence() {
        let m = mixer(2);
        let p = page(2);
        m.attach_meters(p);
        m.push(0, &[0.0; 4]);
        m.push(1, &[0.5, 0.5]); // half a block
        assert!(m.pull(&mut [0i16; 4], T));
        let lv = read(&p, 2);
        assert!(near(lv[1].0, -6.0206), "{lv:?}");
        assert!(near(lv[1].1, 20.0 * (0.125f32).sqrt().log10()), "{lv:?}");
        assert_eq!(lv[0], (FLOOR_DB, FLOOR_DB), "digital silence reads the floor");
    }

    #[test]
    fn a_sine_meters_at_its_analytic_level() {
        let m = mixer(1);
        let p = page(1);
        m.attach_meters(p);
        // −20 dBFS, 1 kHz, exactly 48 periods in 2304 frames.
        let amp = 0.1f32;
        let frames = 2304;
        let sine: Vec<f32> = (0..frames)
            .flat_map(|i| {
                let v = amp * (2.0 * std::f32::consts::PI * 1000.0 * i as f32 / 48_000.0).sin();
                [v, v]
            })
            .collect();
        m.push(0, &sine);
        assert!(m.pull(&mut vec![0i16; frames * CHANNELS], T));
        let lv = read(&p, 1);
        assert!((lv[0].0 - -20.0).abs() < 0.05, "peak {lv:?}");
        assert!((lv[0].1 - (-20.0 - 3.0103)).abs() < 0.05, "rms = peak − 3.01 dB: {lv:?}");
    }
}

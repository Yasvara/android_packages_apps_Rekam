// SPDX-FileCopyrightText: The Yasvara Project
// SPDX-License-Identifier: Apache-2.0

//! Meter page: the mixer's per-slot levels in shared memory, read by any process per frame.
//!
//! A seqlock over 32-bit words, so the writer (the mix thread) never waits for a reader and a
//! reader never sees a torn block:
//!
//! | word | content |
//! |---|---|
//! | 0 | sequence: odd while a block is being written, +2 per block |
//! | 1 | slots in the page |
//! | 2 + 2·i | slot i peak, dBFS as `f32` bits |
//! | 3 + 2·i | slot i RMS, dBFS as `f32` bits |
//!
//! Readers in Kotlin follow the same protocol (`MeterPage.kt`): acquire-load the sequence, copy
//! the words, acquire fence, reload the sequence, retry if it was odd or moved.

use std::sync::atomic::{fence, AtomicU32, Ordering};

const HEADER_WORDS: usize = 2;

/// Words a page for `slots` slots needs.
pub const fn page_words(slots: usize) -> usize {
    HEADER_WORDS + 2 * slots
}

/// A view of a meter page; copies are views of the same words.
#[derive(Clone, Copy)]
pub struct MeterPage<'a> {
    words: &'a [AtomicU32],
}

impl<'a> MeterPage<'a> {
    /// A page over `words`; `None` if it can't hold one slot.
    pub fn new(words: &'a [AtomicU32]) -> Option<Self> {
        (words.len() >= page_words(1)).then_some(Self { words })
    }

    /// Slots this page holds.
    pub fn capacity(&self) -> usize {
        (self.words.len() - HEADER_WORDS) / 2
    }

    /// Publishes one block: `(peak_db, rms_db)` per slot, truncated to [`Self::capacity`].
    /// Single writer.
    pub fn write(&self, levels: impl IntoIterator<Item = (f32, f32)>) {
        let seq = &self.words[0];
        let s = seq.load(Ordering::Relaxed);
        seq.store(s.wrapping_add(1), Ordering::Relaxed);
        fence(Ordering::Release);
        let mut n = 0;
        for (i, (peak, rms)) in levels.into_iter().take(self.capacity()).enumerate() {
            self.words[HEADER_WORDS + 2 * i].store(peak.to_bits(), Ordering::Relaxed);
            self.words[HEADER_WORDS + 2 * i + 1].store(rms.to_bits(), Ordering::Relaxed);
            n = i + 1;
        }
        self.words[1].store(n as u32, Ordering::Relaxed);
        seq.store(s.wrapping_add(2), Ordering::Release);
    }

    /// A consistent copy of the last block into `out` (`(peak, rms)` per slot); returns the
    /// slot count, or `None` if a write kept racing for `tries` attempts.
    pub fn read(&self, out: &mut [(f32, f32)], tries: usize) -> Option<usize> {
        let seq = &self.words[0];
        for _ in 0..tries {
            let s1 = seq.load(Ordering::Acquire);
            if s1 & 1 == 1 {
                std::hint::spin_loop();
                continue;
            }
            let n = (self.words[1].load(Ordering::Relaxed) as usize).min(self.capacity()).min(out.len());
            for (i, o) in out.iter_mut().take(n).enumerate() {
                *o = (
                    f32::from_bits(self.words[HEADER_WORDS + 2 * i].load(Ordering::Relaxed)),
                    f32::from_bits(self.words[HEADER_WORDS + 2 * i + 1].load(Ordering::Relaxed)),
                );
            }
            fence(Ordering::Acquire);
            if seq.load(Ordering::Relaxed) == s1 {
                return Some(n);
            }
        }
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    fn words(n: usize) -> Vec<AtomicU32> {
        (0..n).map(|_| AtomicU32::new(0)).collect()
    }

    #[test]
    fn round_trip_and_layout() {
        let w = words(page_words(3));
        let page = MeterPage::new(&w).unwrap();
        assert_eq!(page.capacity(), 3);
        page.write([(-6.0, -9.0), (-20.0, -30.0)]);
        assert_eq!(w[0].load(Ordering::Relaxed), 2, "one block, even sequence");
        assert_eq!(w[1].load(Ordering::Relaxed), 2);
        assert_eq!(f32::from_bits(w[2].load(Ordering::Relaxed)), -6.0);
        assert_eq!(f32::from_bits(w[5].load(Ordering::Relaxed)), -30.0);
        let mut out = [(0.0, 0.0); 3];
        assert_eq!(page.read(&mut out, 1), Some(2));
        assert_eq!(&out[..2], &[(-6.0, -9.0), (-20.0, -30.0)]);
    }

    #[test]
    fn truncates_to_capacity_and_rejects_tiny_pages() {
        assert!(MeterPage::new(&words(2)).is_none());
        let w = words(page_words(1));
        let page = MeterPage::new(&w).unwrap();
        page.write([(1.0, 2.0), (3.0, 4.0)]);
        let mut out = [(0.0, 0.0); 4];
        assert_eq!(page.read(&mut out, 1), Some(1));
        assert_eq!(out[0], (1.0, 2.0));
    }

    #[test]
    fn an_odd_sequence_is_never_read() {
        let w = words(page_words(1));
        let page = MeterPage::new(&w).unwrap();
        w[0].store(1, Ordering::Relaxed);
        assert_eq!(page.read(&mut [(0.0, 0.0)], 8), None);
    }

    /// Every block the writer publishes has all values equal; a torn read would mix two blocks.
    #[test]
    fn concurrent_reads_are_never_torn() {
        const SLOTS: usize = 8;
        let w: Arc<Vec<AtomicU32>> = Arc::new(words(page_words(SLOTS)));
        let writer = {
            let w = Arc::clone(&w);
            std::thread::spawn(move || {
                let page = MeterPage::new(&w).unwrap();
                for k in 0..200_000u32 {
                    let v = -((k % 100) as f32);
                    page.write((0..SLOTS).map(|_| (v, v)));
                }
            })
        };
        let page = MeterPage::new(&w).unwrap();
        let mut out = [(0.0f32, 0.0f32); SLOTS];
        let mut reads = 0;
        while !writer.is_finished() {
            if let Some(n) = page.read(&mut out, 64) {
                let first = out[0].0;
                assert!(out[..n].iter().all(|&(p, r)| p == first && r == first), "torn block: {out:?}");
                reads += 1;
            }
        }
        writer.join().unwrap();
        assert!(reads > 0);
    }
}

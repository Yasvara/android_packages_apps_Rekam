// SPDX-FileCopyrightText: The Yasvara Project
// SPDX-License-Identifier: Apache-2.0

//! Rekam's container layer: a crash-resilient "hybrid" MP4.
//!
//! While recording the file is a fragmented MP4 (`ftyp · placeholder · moov(init) · {moof mdat}…`),
//! so everything up to the last complete fragment survives a crash or power cut. Finalizing
//! appends a full `moov` and rewrites the 16-byte placeholder as a 64-bit `mdat` header that hides
//! the init moov and every `moof`, leaving a plain MP4. Stop, split and [`recover`] share that
//! one finalize routine.

mod bmff;
mod entry;
mod file;
mod moov;
mod recovery;
mod writer;

pub use entry::sample_description;
pub use file::{MemFile, RandomAccess};
pub use moov::{Chunk, Sample, TrackIndex};
pub use recovery::{recover, Recovered};
pub use writer::{HybridWriter, SyncPolicy, WriterOptions};

use std::fmt;

/// Encoded stream kinds Rekam writes.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Codec {
    /// H.264.
    Avc,
    /// H.265.
    Hevc,
    /// AAC (LC, HE).
    Aac,
    /// Opus.
    Opus,
    /// FLAC.
    Flac,
}

impl Codec {
    /// AVC or HEVC.
    pub fn is_video(self) -> bool {
        matches!(self, Codec::Avc | Codec::Hevc)
    }
}

/// One track as the encoder produced it; `csd` is MediaCodec's csd-0 followed by csd-1.
///
/// AVC/HEVC: Annex-B parameter sets. AAC: AudioSpecificConfig. Opus: OpusHead. FLAC: STREAMINFO.
#[derive(Clone, Debug)]
pub struct TrackConfig {
    /// Stream kind.
    pub codec: Codec,
    /// Video 90 kHz; audio its sample rate (Opus: always 48 kHz).
    pub timescale: u32,
    /// Video width in pixels.
    pub width: u16,
    /// Video height in pixels.
    pub height: u16,
    /// Audio sample rate.
    pub sample_rate: u32,
    /// Audio channels.
    pub channels: u16,
    /// Bits per second, for the AAC decoder config.
    pub avg_bitrate: u32,
    /// Codec-specific data (see the type docs).
    pub csd: Vec<u8>,
    /// Players play enabled tracks; isolated stems are disabled and share an alternate group.
    pub enabled: bool,
    /// Tracks in the same non-zero group are alternatives of each other.
    pub alternate_group: i16,
}

/// Writer and recovery errors.
#[derive(Debug)]
pub enum Error {
    /// The file operation failed.
    Io(std::io::Error),
    /// The caller passed something unusable (bad csd, unknown track, writer finished).
    Config(&'static str),
    /// The file is not one of ours, or damaged beyond the last complete fragment.
    Corrupt(&'static str),
}

impl From<std::io::Error> for Error {
    fn from(e: std::io::Error) -> Self {
        Error::Io(e)
    }
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Error::Io(e) => write!(f, "i/o: {e}"),
            Error::Config(m) => write!(f, "config: {m}"),
            Error::Corrupt(m) => write!(f, "corrupt: {m}"),
        }
    }
}

impl std::error::Error for Error {}

/// Result with [`Error`].
pub type Result<T> = std::result::Result<T, Error>;

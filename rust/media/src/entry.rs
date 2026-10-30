// SPDX-FileCopyrightText: The Yasvara Project
// SPDX-License-Identifier: Apache-2.0

//! `stsd` sample descriptions built from MediaCodec codec-specific data.

use crate::bmff::BoxWriter;
use crate::{Codec, Error, Result, TrackConfig};

/// The complete `stsd` box for a track.
pub fn sample_description(c: &TrackConfig) -> Result<Vec<u8>> {
    let mut out = Vec::new();
    let mut w = BoxWriter::new(&mut out);
    w.begin_full(b"stsd", 0, 0);
    w.u32(1);
    match c.codec {
        Codec::Avc => avc(&mut w, c)?,
        Codec::Hevc => hevc(&mut w, c)?,
        Codec::Aac => aac(&mut w, c)?,
        Codec::Opus => opus(&mut w, c)?,
        Codec::Flac => flac(&mut w, c)?,
    }
    w.end();
    Ok(out)
}

/// NAL units of an Annex-B stream, start codes stripped.
pub(crate) fn nal_units(b: &[u8]) -> Vec<&[u8]> {
    let mut starts = Vec::new();
    let mut i = 0;
    while i + 2 < b.len() {
        if b[i] == 0 && b[i + 1] == 0 && b[i + 2] == 1 {
            starts.push(i + 3);
            i += 3;
        } else {
            i += 1;
        }
    }
    starts
        .iter()
        .enumerate()
        .map(|(k, &s)| {
            let mut end = starts.get(k + 1).map_or(b.len(), |&n| n - 3);
            while end > s && b[end - 1] == 0 {
                end -= 1; // leading zero of a 4-byte start code
            }
            &b[s..end]
        })
        .filter(|n| !n.is_empty())
        .collect()
}

/// Exp-Golomb reader over the RBSP (emulation-prevention bytes removed).
struct Bits {
    rbsp: Vec<u8>,
    pos: usize,
}

impl Bits {
    fn new(nal: &[u8], header: usize) -> Self {
        let mut rbsp = Vec::with_capacity(nal.len());
        for (i, &b) in nal.iter().enumerate().skip(header) {
            if b == 3 && i >= header + 2 && nal[i - 1] == 0 && nal[i - 2] == 0 {
                continue;
            }
            rbsp.push(b);
        }
        Self { rbsp, pos: 0 }
    }

    fn bits(&mut self, n: u32) -> Result<u32> {
        let mut v = 0u32;
        for _ in 0..n {
            let byte = *self.rbsp.get(self.pos / 8).ok_or(Error::Config("short parameter set"))?;
            v = v << 1 | u32::from(byte >> (7 - self.pos % 8) & 1);
            self.pos += 1;
        }
        Ok(v)
    }

    fn skip(&mut self, mut n: u32) -> Result<()> {
        while n > 0 {
            let k = n.min(32);
            self.bits(k)?;
            n -= k;
        }
        Ok(())
    }

    fn ue(&mut self) -> Result<u32> {
        let mut zeros = 0;
        while self.bits(1)? == 0 {
            zeros += 1;
            if zeros > 31 {
                return Err(Error::Config("bad exp-golomb"));
            }
        }
        Ok((1u32 << zeros) - 1 + self.bits(zeros)?)
    }
}

fn visual_header(w: &mut BoxWriter, c: &TrackConfig) {
    w.zeros(6);
    w.u16(1); // data_reference_index
    w.zeros(16);
    w.u16(c.width);
    w.u16(c.height);
    w.u32(0x0048_0000);
    w.u32(0x0048_0000);
    w.u32(0);
    w.u16(1); // frame_count
    w.zeros(32); // compressorname
    w.u16(0x0018);
    w.i16(-1);
}

/// BT.709, limited range: what the encoder is configured for.
fn colr(w: &mut BoxWriter) {
    w.begin(b"colr");
    w.bytes(b"nclx");
    w.u16(1);
    w.u16(1);
    w.u16(1);
    w.u8(0);
    w.end();
}

fn len16(n: &[u8]) -> Result<u16> {
    u16::try_from(n.len()).map_err(|_| Error::Config("parameter set too large"))
}

fn avc(w: &mut BoxWriter, c: &TrackConfig) -> Result<()> {
    let nals = nal_units(&c.csd);
    let sps: Vec<_> = nals.iter().filter(|n| n[0] & 0x1f == 7).collect();
    let pps: Vec<_> = nals.iter().filter(|n| n[0] & 0x1f == 8).collect();
    let s = **sps.first().ok_or(Error::Config("avc csd has no SPS"))?;
    if pps.is_empty() || s.len() < 4 {
        return Err(Error::Config("avc csd needs SPS and PPS"));
    }
    w.begin(b"avc1");
    visual_header(w, c);
    w.begin(b"avcC");
    w.u8(1);
    w.u8(s[1]);
    w.u8(s[2]);
    w.u8(s[3]);
    w.u8(0xff); // lengthSizeMinusOne = 3
    w.u8(0xe0 | sps.len() as u8);
    for n in &sps {
        w.u16(len16(n)?);
        w.bytes(n);
    }
    w.u8(pps.len() as u8);
    for n in &pps {
        w.u16(len16(n)?);
        w.bytes(n);
    }
    if matches!(s[1], 100 | 110 | 122 | 144) {
        let mut b = Bits::new(s, 4);
        b.ue()?; // seq_parameter_set_id
        let chroma = b.ue()?;
        if chroma == 3 {
            b.bits(1)?;
        }
        let luma8 = b.ue()?;
        let chroma8 = b.ue()?;
        w.u8(0xfc | (chroma & 3) as u8);
        w.u8(0xf8 | (luma8 & 7) as u8);
        w.u8(0xf8 | (chroma8 & 7) as u8);
        w.u8(0); // numOfSequenceParameterSetExt
    }
    w.end();
    colr(w);
    w.end();
    Ok(())
}

fn hevc(w: &mut BoxWriter, c: &TrackConfig) -> Result<()> {
    let nals = nal_units(&c.csd);
    let of = |t: u8| -> Vec<&[u8]> { nals.iter().copied().filter(|n| n.len() > 1 && (n[0] >> 1) & 0x3f == t).collect() };
    let sets = [of(32), of(33), of(34)]; // VPS, SPS, PPS
    if sets.iter().any(|s| s.is_empty()) {
        return Err(Error::Config("hevc csd needs VPS, SPS and PPS"));
    }

    let mut b = Bits::new(sets[1][0], 2);
    b.bits(4)?; // sps_video_parameter_set_id
    let sub_layers = b.bits(3)?;
    let nesting = b.bits(1)?;
    let ptl_first = b.bits(8)?; // profile_space, tier, profile_idc
    let compat = b.bits(32)?;
    let mut constraint = [0u8; 6];
    for v in &mut constraint {
        *v = b.bits(8)? as u8;
    }
    let level = b.bits(8)?;
    let mut present = [(false, false); 8];
    for p in present.iter_mut().take(sub_layers as usize) {
        *p = (b.bits(1)? == 1, b.bits(1)? == 1);
    }
    if sub_layers > 0 {
        b.skip(2 * (8 - sub_layers))?;
    }
    for &(profile, lvl) in present.iter().take(sub_layers as usize) {
        if profile {
            b.skip(88)?;
        }
        if lvl {
            b.skip(8)?;
        }
    }
    b.ue()?; // sps_seq_parameter_set_id
    let chroma = b.ue()?;
    if chroma == 3 {
        b.bits(1)?;
    }
    b.ue()?;
    b.ue()?; // pic size
    if b.bits(1)? == 1 {
        for _ in 0..4 {
            b.ue()?; // conformance window
        }
    }
    let luma8 = b.ue()?;
    let chroma8 = b.ue()?;

    w.begin(b"hvc1");
    visual_header(w, c);
    w.begin(b"hvcC");
    w.u8(1);
    w.u8(ptl_first as u8);
    w.u32(compat);
    w.bytes(&constraint);
    w.u8(level as u8);
    w.u16(0xf000);
    w.u8(0xfc);
    w.u8(0xfc | (chroma & 3) as u8);
    w.u8(0xf8 | (luma8 & 7) as u8);
    w.u8(0xf8 | (chroma8 & 7) as u8);
    w.u16(0); // avgFrameRate
    w.u8(((sub_layers + 1) as u8 & 7) << 3 | (nesting as u8 & 1) << 2 | 3);
    w.u8(3);
    for (t, set) in sets.iter().enumerate() {
        w.u8(0x80 | (32 + t as u8));
        w.u16(set.len() as u16);
        for n in set {
            w.u16(len16(n)?);
            w.bytes(n);
        }
    }
    w.end();
    colr(w);
    w.end();
    Ok(())
}

fn audio_header(w: &mut BoxWriter, channels: u16, rate: u32) {
    w.zeros(6);
    w.u16(1);
    w.zeros(8);
    w.u16(channels);
    w.u16(16);
    w.u32(0);
    w.u32(if rate <= 0xffff { rate << 16 } else { 0 });
}

/// MPEG-4 descriptor header with the 4-byte length form.
fn descriptor(w: &mut BoxWriter, tag: u8, len: u32) {
    w.u8(tag);
    w.u8(0x80 | (len >> 21 & 0x7f) as u8);
    w.u8(0x80 | (len >> 14 & 0x7f) as u8);
    w.u8(0x80 | (len >> 7 & 0x7f) as u8);
    w.u8((len & 0x7f) as u8);
}

fn aac(w: &mut BoxWriter, c: &TrackConfig) -> Result<()> {
    if c.csd.len() < 2 {
        return Err(Error::Config("aac csd needs an AudioSpecificConfig"));
    }
    let asc = c.csd.len() as u32;
    let dcd = 13 + 5 + asc;
    w.begin(b"mp4a");
    audio_header(w, c.channels, c.sample_rate);
    w.begin_full(b"esds", 0, 0);
    descriptor(w, 0x03, 3 + 5 + dcd + 5 + 1);
    w.u16(0); // ES_ID
    w.u8(0);
    descriptor(w, 0x04, dcd);
    w.u8(0x40); // MPEG-4 audio
    w.u8(0x15); // audio stream
    w.u24(0);
    w.u32(c.avg_bitrate);
    w.u32(c.avg_bitrate);
    descriptor(w, 0x05, asc);
    w.bytes(&c.csd);
    descriptor(w, 0x06, 1);
    w.u8(0x02);
    w.end();
    w.end();
    Ok(())
}

fn opus(w: &mut BoxWriter, c: &TrackConfig) -> Result<()> {
    // Android's encoder wraps OpusHead in its marker format ("AOPUSHDR", length, OpusHead,
    // "AOPUSDLY"…); a bare OpusHead works too. OpusHead is little-endian, dOps big-endian.
    let at = c.csd.windows(8).position(|w| w == b"OpusHead").ok_or(Error::Config("opus csd needs OpusHead"))?;
    let h = &c.csd[at..];
    if h.len() < 19 {
        return Err(Error::Config("opus csd needs OpusHead"));
    }
    let channels = h[9];
    w.begin(b"Opus");
    audio_header(w, u16::from(channels), 48_000);
    w.begin(b"dOps");
    w.u8(0);
    w.u8(channels);
    w.u16(u16::from_le_bytes([h[10], h[11]]));
    w.u32(u32::from_le_bytes([h[12], h[13], h[14], h[15]]));
    w.u16(u16::from_le_bytes([h[16], h[17]]));
    w.u8(h[18]);
    if h[18] != 0 {
        let table = h.get(19..21 + usize::from(channels)).ok_or(Error::Config("opus channel mapping"))?;
        w.bytes(table);
    }
    w.end();
    w.end();
    Ok(())
}

fn flac(w: &mut BoxWriter, c: &TrackConfig) -> Result<()> {
    let mut s: &[u8] = &c.csd;
    if s.starts_with(b"fLaC") {
        s = &s[4..];
    }
    // A STREAMINFO block with its 4-byte header, or the bare 34 bytes.
    if s.len() >= 38 && s[0] & 0x7f == 0 && u32::from_be_bytes([0, s[1], s[2], s[3]]) == 34 {
        s = &s[4..];
    }
    let info = s.get(..34).ok_or(Error::Config("flac csd needs STREAMINFO"))?;
    w.begin(b"fLaC");
    audio_header(w, c.channels, c.sample_rate);
    w.begin_full(b"dfLa", 0, 0);
    w.u8(0x80); // last metadata block, STREAMINFO
    w.u24(34);
    w.bytes(info);
    w.end();
    w.end();
    Ok(())
}

/// Copies one encoded video sample into `out` in MP4 form: each NAL with a 4-byte length instead
/// of an Annex-B start code. Parameter sets and access-unit delimiters are dropped; they live in
/// avcC/hvcC (`hvc1` forbids them in band). Input that is not Annex-B is copied as is.
pub(crate) fn video_sample(codec: Codec, src: &[u8], out: &mut Vec<u8>) {
    out.clear();
    if !(src.starts_with(&[0, 0, 1]) || src.starts_with(&[0, 0, 0, 1])) {
        out.extend_from_slice(src);
        return;
    }
    for nal in nal_units(src) {
        let drop = match codec {
            Codec::Avc => matches!(nal[0] & 0x1f, 7..=9),
            Codec::Hevc => nal.len() > 1 && matches!((nal[0] >> 1) & 0x3f, 32..=35),
            _ => false,
        };
        if !drop {
            out.extend_from_slice(&(nal.len() as u32).to_be_bytes());
            out.extend_from_slice(nal);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cfg(codec: Codec, csd: &[u8]) -> TrackConfig {
        TrackConfig {
            codec,
            timescale: 90_000,
            width: 1920,
            height: 1080,
            sample_rate: 48_000,
            channels: 2,
            avg_bitrate: 128_000,
            csd: csd.to_vec(),
            enabled: true,
            alternate_group: 0,
        }
    }

    // x264 1920x1080 High 4:2:0 8-bit.
    const AVC_CSD: &[u8] = &[
        0, 0, 0, 1, 0x67, 0x64, 0, 0x28, 0xac, 0xd9, 0x40, 0x78, 0x02, 0x27, 0xe5, 0xc0, 0x44, 0, 0, 3, 0, 4, 0, 0, 3,
        0, 0xf0, 0x3c, 0x60, 0xc6, 0x58, 0, 0, 0, 1, 0x68, 0xeb, 0xe3, 0xcb, 0x22, 0xc0,
    ];

    #[test]
    fn annex_b_split_handles_3_and_4_byte_start_codes() {
        let n = nal_units(&[0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3]);
        assert_eq!(n, vec![&[0x67, 1, 2][..], &[0x68, 3][..]]);
    }

    #[test]
    fn avcc_has_high_profile_extension() {
        let stsd = sample_description(&cfg(Codec::Avc, AVC_CSD)).unwrap();
        let at = stsd.windows(4).position(|w| w == b"avcC").unwrap();
        // version, profile 100, compat 0, level 40, 0xff, one SPS
        assert_eq!(&stsd[at + 4..at + 10], &[1, 100, 0, 40, 0xff, 0xe1]);
        // chroma 4:2:0, 8-bit luma/chroma, no SPS ext
        let end = at - 4 + u32::from_be_bytes(stsd[at - 4..at].try_into().unwrap()) as usize;
        assert_eq!(&stsd[end - 4..end], &[0xfd, 0xf8, 0xf8, 0]);
    }

    #[test]
    fn bad_csd_is_rejected() {
        assert!(sample_description(&cfg(Codec::Avc, &[0, 0, 1, 0x67])).is_err());
        assert!(sample_description(&cfg(Codec::Hevc, AVC_CSD)).is_err());
        assert!(sample_description(&cfg(Codec::Opus, b"NotOpus")).is_err());
        assert!(sample_description(&cfg(Codec::Flac, &[0; 10])).is_err());
    }

    #[test]
    fn annex_b_samples_become_length_prefixed_without_parameter_sets() {
        let mut out = Vec::new();
        // AUD, SPS, PPS, IDR slice
        let au = [0, 0, 0, 1, 0x09, 0xf0, 0, 0, 0, 1, 0x67, 1, 0, 0, 1, 0x68, 2, 0, 0, 0, 1, 0x65, 0xaa, 0xbb];
        video_sample(Codec::Avc, &au, &mut out);
        assert_eq!(out, [0, 0, 0, 3, 0x65, 0xaa, 0xbb]);
        // HEVC: VPS 0x40, then an IDR (type 19 = 0x26)
        video_sample(Codec::Hevc, &[0, 0, 0, 1, 0x40, 1, 7, 0, 0, 1, 0x26, 1, 9], &mut out);
        assert_eq!(out, [0, 0, 0, 3, 0x26, 1, 9]);
        video_sample(Codec::Hevc, &[0, 0, 0, 2, 0x26, 1], &mut out);
        assert_eq!(out, [0, 0, 0, 2, 0x26, 1], "already length-prefixed");
    }

    #[test]
    fn android_wrapped_opus_head_is_found() {
        let mut csd = b"AOPUSHDR\x13\0\0\0\0\0\0\0OpusHead".to_vec();
        csd.extend_from_slice(&[1, 2, 0x38, 0x01, 0x80, 0xbb, 0, 0, 0, 0, 0]);
        csd.extend_from_slice(b"AOPUSDLY\x08\0\0\0\0\0\0\0\0\xa0\x2e\x63\0\0\0\0");
        assert!(sample_description(&cfg(Codec::Opus, &csd)).is_ok());
    }

    #[test]
    fn opus_head_becomes_big_endian_dops() {
        let mut head = b"OpusHead".to_vec();
        head.extend_from_slice(&[1, 2, 0x38, 0x01, 0x80, 0xbb, 0, 0, 0, 0, 0]);
        let stsd = sample_description(&cfg(Codec::Opus, &head)).unwrap();
        let at = stsd.windows(4).position(|w| w == b"dOps").unwrap();
        assert_eq!(&stsd[at + 4..at + 15], &[0, 2, 0x01, 0x38, 0, 0, 0xbb, 0x80, 0, 0, 0]);
    }
}

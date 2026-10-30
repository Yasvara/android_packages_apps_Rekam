// SPDX-FileCopyrightText: The Yasvara Project
// SPDX-License-Identifier: Apache-2.0

//! ISO-BMFF byte plumbing: a nesting box writer and a bounds-checked reader.

use crate::{Error, Result};

pub const fn fourcc(s: &[u8; 4]) -> u32 {
    u32::from_be_bytes(*s)
}

/// Big-endian serializer. `begin`/`end` nest boxes; `end` patches the 32-bit size.
pub struct BoxWriter<'a> {
    out: &'a mut Vec<u8>,
    stack: Vec<usize>,
}

impl<'a> BoxWriter<'a> {
    pub fn new(out: &'a mut Vec<u8>) -> Self {
        Self { out, stack: Vec::new() }
    }

    pub fn u8(&mut self, v: u8) {
        self.out.push(v);
    }
    pub fn u16(&mut self, v: u16) {
        self.out.extend_from_slice(&v.to_be_bytes());
    }
    pub fn u24(&mut self, v: u32) {
        self.out.extend_from_slice(&v.to_be_bytes()[1..]);
    }
    pub fn u32(&mut self, v: u32) {
        self.out.extend_from_slice(&v.to_be_bytes());
    }
    pub fn u64(&mut self, v: u64) {
        self.out.extend_from_slice(&v.to_be_bytes());
    }
    pub fn i16(&mut self, v: i16) {
        self.u16(v as u16);
    }
    pub fn i64(&mut self, v: i64) {
        self.u64(v as u64);
    }
    pub fn bytes(&mut self, b: &[u8]) {
        self.out.extend_from_slice(b);
    }
    pub fn zeros(&mut self, n: usize) {
        self.out.resize(self.out.len() + n, 0);
    }
    pub fn len(&self) -> usize {
        self.out.len()
    }

    pub fn begin(&mut self, kind: &[u8; 4]) {
        self.stack.push(self.out.len());
        self.u32(0);
        self.bytes(kind);
    }

    pub fn begin_full(&mut self, kind: &[u8; 4], version: u8, flags: u32) {
        self.begin(kind);
        self.u8(version);
        self.u24(flags);
    }

    pub fn end(&mut self) {
        let start = self.stack.pop().expect("end() without begin()");
        let size = u32::try_from(self.out.len() - start).expect("box over 4 GiB");
        self.out[start..start + 4].copy_from_slice(&size.to_be_bytes());
    }
}

/// Bounds-checked big-endian reader; every overrun is `Error::Corrupt`.
pub struct Reader<'a> {
    buf: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    pub fn new(buf: &'a [u8]) -> Self {
        Self { buf, pos: 0 }
    }

    pub fn left(&self) -> usize {
        self.buf.len() - self.pos
    }

    pub fn take(&mut self, n: usize) -> Result<&'a [u8]> {
        if self.left() < n {
            return Err(Error::Corrupt("truncated box"));
        }
        let s = &self.buf[self.pos..self.pos + n];
        self.pos += n;
        Ok(s)
    }

    pub fn skip(&mut self, n: usize) -> Result<()> {
        self.take(n).map(|_| ())
    }
    pub fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }
    pub fn u16(&mut self) -> Result<u16> {
        Ok(u16::from_be_bytes(self.take(2)?.try_into().unwrap()))
    }
    pub fn u24(&mut self) -> Result<u32> {
        let b = self.take(3)?;
        Ok(u32::from_be_bytes([0, b[0], b[1], b[2]]))
    }
    pub fn u32(&mut self) -> Result<u32> {
        Ok(u32::from_be_bytes(self.take(4)?.try_into().unwrap()))
    }
    pub fn u64(&mut self) -> Result<u64> {
        Ok(u64::from_be_bytes(self.take(8)?.try_into().unwrap()))
    }

    /// Next child box as (type, payload); payload excludes the header.
    pub fn child(&mut self) -> Result<([u8; 4], &'a [u8])> {
        let size = self.u32()? as u64;
        let kind: [u8; 4] = self.take(4)?.try_into().unwrap();
        let (header, size) = match size {
            1 => (16, self.u64()?),
            0 => (8, (self.left() + 8) as u64),
            n => (8, n),
        };
        let body = size.checked_sub(header).ok_or(Error::Corrupt("box size"))?;
        let body = usize::try_from(body).map_err(|_| Error::Corrupt("box size"))?;
        Ok((kind, self.take(body)?))
    }

    /// Finds the first direct child of `kind`.
    pub fn find(buf: &'a [u8], kind: &[u8; 4]) -> Result<&'a [u8]> {
        let mut r = Reader::new(buf);
        while r.left() > 0 {
            let (k, body) = r.child()?;
            if &k == kind {
                return Ok(body);
            }
        }
        Err(Error::Corrupt("missing box"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn nested_sizes_are_patched() {
        let mut out = Vec::new();
        let mut w = BoxWriter::new(&mut out);
        w.begin(b"moov");
        w.begin_full(b"mvhd", 1, 0);
        w.u32(7);
        w.end();
        w.end();
        assert_eq!(
            out,
            [0, 0, 0, 24, b'm', b'o', b'o', b'v', 0, 0, 0, 16, b'm', b'v', b'h', b'd', 1, 0, 0, 0, 0, 0, 0, 7]
        );
        let (kind, body) = Reader::new(&out).child().unwrap();
        assert_eq!(&kind, b"moov");
        let mvhd = Reader::find(body, b"mvhd").unwrap();
        assert_eq!(mvhd, [1, 0, 0, 0, 0, 0, 0, 7]);
    }

    #[test]
    fn overruns_are_errors() {
        let mut r = Reader::new(&[0, 0, 0, 40, b'm', b'o', b'o', b'f']);
        assert!(r.child().is_err());
        assert!(Reader::new(&[0, 0]).u32().is_err());
    }
}

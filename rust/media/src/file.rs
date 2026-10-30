// SPDX-FileCopyrightText: The Yasvara Project
// SPDX-License-Identifier: Apache-2.0

use std::io;
use std::os::unix::fs::FileExt;

/// Positional I/O used by the writer and recovery.
pub trait RandomAccess {
    /// Writes all of `data` at `offset`, extending the file as needed.
    fn write_at(&mut self, offset: u64, data: &[u8]) -> io::Result<()>;
    /// Fills `buf` from `offset`; short files are an error.
    fn read_at(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<()>;
    /// Truncates or extends the file.
    fn set_len(&mut self, len: u64) -> io::Result<()>;
    /// Data, and the metadata needed to read it back, are durable (fdatasync).
    fn sync_data(&mut self) -> io::Result<()>;
    /// Current file length.
    fn size(&mut self) -> io::Result<u64>;
}

impl RandomAccess for std::fs::File {
    fn write_at(&mut self, offset: u64, data: &[u8]) -> io::Result<()> {
        self.write_all_at(data, offset)
    }
    fn read_at(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<()> {
        self.read_exact_at(buf, offset)
    }
    fn set_len(&mut self, len: u64) -> io::Result<()> {
        std::fs::File::set_len(self, len)
    }
    fn sync_data(&mut self) -> io::Result<()> {
        std::fs::File::sync_data(self)
    }
    fn size(&mut self) -> io::Result<u64> {
        Ok(self.metadata()?.len())
    }
}

impl<T: RandomAccess + ?Sized> RandomAccess for &mut T {
    fn write_at(&mut self, offset: u64, data: &[u8]) -> io::Result<()> {
        (**self).write_at(offset, data)
    }
    fn read_at(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<()> {
        (**self).read_at(offset, buf)
    }
    fn set_len(&mut self, len: u64) -> io::Result<()> {
        (**self).set_len(len)
    }
    fn sync_data(&mut self) -> io::Result<()> {
        (**self).sync_data()
    }
    fn size(&mut self) -> io::Result<u64> {
        (**self).size()
    }
}

/// In-memory file for tests. [`MemFile::crash_at`] simulates power loss: the n-th write is torn
/// (only its first half lands) and every operation after it fails.
#[derive(Clone, Debug, Default)]
pub struct MemFile {
    /// File contents.
    pub data: Vec<u8>,
    /// Writes applied so far.
    pub writes: usize,
    /// `sync_data` calls so far.
    pub syncs: usize,
    crash_at: Option<usize>,
    crashed: bool,
}

impl MemFile {
    /// An empty file.
    pub fn new() -> Self {
        Self::default()
    }

    /// A file holding `data`.
    pub fn from_bytes(data: Vec<u8>) -> Self {
        Self { data, ..Self::default() }
    }

    /// An empty file that loses power during write number `write` (0-based).
    pub fn crash_at(write: usize) -> Self {
        Self { crash_at: Some(write), ..Self::default() }
    }

    /// Whether the simulated power loss has happened.
    pub fn crashed(&self) -> bool {
        self.crashed
    }

    /// The bytes that would be on disk after the crash, ready for recovery.
    pub fn reboot(&self) -> MemFile {
        MemFile::from_bytes(self.data.clone())
    }

    fn check(&self) -> io::Result<()> {
        if self.crashed {
            Err(io::Error::other("crashed"))
        } else {
            Ok(())
        }
    }
}

impl RandomAccess for MemFile {
    fn write_at(&mut self, offset: u64, data: &[u8]) -> io::Result<()> {
        self.check()?;
        let n = if self.crash_at == Some(self.writes) {
            self.crashed = true;
            data.len() / 2
        } else {
            data.len()
        };
        let start = offset as usize;
        if self.data.len() < start + n {
            self.data.resize(start + n, 0);
        }
        self.data[start..start + n].copy_from_slice(&data[..n]);
        self.writes += 1;
        self.check()
    }

    fn read_at(&mut self, offset: u64, buf: &mut [u8]) -> io::Result<()> {
        self.check()?;
        let start = offset as usize;
        let end = start.checked_add(buf.len()).filter(|&e| e <= self.data.len());
        let end = end.ok_or_else(|| io::Error::from(io::ErrorKind::UnexpectedEof))?;
        buf.copy_from_slice(&self.data[start..end]);
        Ok(())
    }

    fn set_len(&mut self, len: u64) -> io::Result<()> {
        self.check()?;
        self.data.resize(len as usize, 0);
        Ok(())
    }

    fn sync_data(&mut self) -> io::Result<()> {
        self.check()?;
        self.syncs += 1;
        Ok(())
    }

    fn size(&mut self) -> io::Result<u64> {
        self.check()?;
        Ok(self.data.len() as u64)
    }
}

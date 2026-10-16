//! On-disk layout of one slot file (format 1).
//!
//! ```text
//! 0x000  Header (256 bytes)
//!          0x00  magic "BANNERIN" (u64), format major/minor, sizes, slot, instance id,
//!                retired flag, writer pid, creation time
//!          0x40  bell (futex word, bumped on every publish and on retirement), published index
//!          0x80  writer statistics
//!          0xc0  reader statistics (readers add to these)
//! 0x100  4 state copies x 128 bytes (sequence word + 31 data words, see W_*)
//! 0x300  journal: 256 x u64 button-edge entries
//! 0xb00  end of format-1 data; the file is FILE_SIZE (4096) bytes and never grows
//! ```
//!
//! Compatibility: readers accept major == 1 and a file of at least FILE_SIZE bytes. A compatible
//! extension appends a new region after 0xb00 (inside the same 4 KiB, or by growing FILE_SIZE for
//! new files) and bumps `minor`; format-1 readers ignore it. Anything incompatible bumps `major`.

use core::mem::{offset_of, size_of};
use core::sync::atomic::Ordering::{Acquire, Relaxed};
use core::sync::atomic::{AtomicU32, AtomicU64};

use crate::abi::*;
use crate::proto::{self, StateCopy};

pub const MAGIC: u64 = u64::from_le_bytes(*b"BANNERIN");
pub const FORMAT_MAJOR: u32 = 1;
pub const FORMAT_MINOR: u32 = 0;

pub const HEADER_SIZE: usize = 256;
pub const COPY_WORDS: usize = 31;
pub const COPY_SIZE: usize = 128;
pub const COPY_COUNT: usize = 4;
pub const JOURNAL_LEN: usize = 256;
pub const COPIES_OFF: usize = HEADER_SIZE;
pub const JOURNAL_OFF: usize = COPIES_OFF + COPY_COUNT * COPY_SIZE;
pub const LAYOUT_END: usize = JOURNAL_OFF + JOURNAL_LEN * 8;
/// Size of every slot file, and of every mapping of one.
pub const FILE_SIZE: usize = 4096;

// Data words of one state copy.
pub const W_PUB: usize = 0;
pub const W_FLAGS: usize = 1;
pub const W_UNPLUG: usize = 2;
pub const W_EDGES: usize = 3;
pub const W_COUNTS: usize = 4;
pub const W_AXES: usize = W_COUNTS + BUTTONS; // 15
pub const W_TIME_LO: usize = W_AXES + AXES; // 23
pub const W_TIME_HI: usize = W_TIME_LO + 1; // 24

/// `W_FLAGS`: a writer has opened the slot (a controller is assigned to it).
pub const FLAG_CONNECTED: u32 = 1;

// Journal payload bits.
pub const J_BUTTON_MASK: u32 = 0xff;
pub const J_PRESS: u32 = 1 << 8;
/// First edge of one publish: edges up to the next entry carrying this bit form one frame.
pub const J_GROUP: u32 = 1 << 9;

#[repr(C)]
pub struct Header {
    pub magic: AtomicU64,
    pub major: AtomicU32,
    pub minor: AtomicU32,
    pub file_size: AtomicU32,
    pub header_size: AtomicU32,
    pub copy_size: AtomicU32,
    pub copy_count: AtomicU32,
    pub journal_len: AtomicU32,
    pub slot: AtomicU32,
    pub instance: AtomicU64,
    /// Set by the writer when it abandons this file (replaced or released). Readers then reopen
    /// the path, or show a neutral pad until a new file appears.
    pub retired: AtomicU32,
    pub writer_pid: AtomicU32,
    pub created_ns: AtomicU64,
    // 0x40: the wake line
    pub bell: AtomicU32,
    pub published: AtomicU32,
    pub _pad1: [AtomicU32; 14],
    // 0x80: writer statistics
    pub w_publishes: AtomicU64,
    pub w_wake_calls: AtomicU64,
    pub w_woken: AtomicU64,
    pub w_unplugs: AtomicU64,
    pub w_edges: AtomicU64,
    pub _pad2: [AtomicU64; 3],
    // 0xc0: reader statistics, summed over every reader of this file
    pub r_attaches: AtomicU64,
    pub r_reattaches: AtomicU64,
    pub r_taps: AtomicU64,
    pub r_coalesced: AtomicU64,
    pub r_resyncs: AtomicU64,
    pub r_frames: AtomicU64,
    pub r_events: AtomicU64,
    pub r_removals: AtomicU64,
}

pub type CopyBlock = StateCopy<AtomicU32, COPY_WORDS>;

#[repr(C)]
pub struct Shared {
    pub header: Header,
    pub copies: [CopyBlock; COPY_COUNT],
    pub journal: [AtomicU64; JOURNAL_LEN],
}

const _: () = {
    assert!(size_of::<Header>() == HEADER_SIZE);
    assert!(offset_of!(Header, instance) == 40);
    assert!(offset_of!(Header, bell) == 64);
    assert!(offset_of!(Header, published) == 68);
    assert!(offset_of!(Header, w_publishes) == 128);
    assert!(offset_of!(Header, r_attaches) == 192);
    assert!(size_of::<CopyBlock>() == COPY_SIZE);
    assert!(offset_of!(Shared, copies) == COPIES_OFF);
    assert!(offset_of!(Shared, journal) == JOURNAL_OFF);
    assert!(size_of::<Shared>() == LAYOUT_END);
    assert!(LAYOUT_END <= FILE_SIZE);
    assert!(W_TIME_HI < COPY_WORDS);
    assert!(JOURNAL_LEN.is_power_of_two());
};

/// One consistent published state.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Snapshot {
    pub pub_seq: u32,
    pub flags: u32,
    /// Number of unplugs (`destroy()`) of this slot so far. A reader that saw a different value
    /// when it attached has been unplugged.
    pub unplug: u32,
    /// Total button edges so far (index of the newest journal entry).
    pub edges: u32,
    /// Per-button edge counters; parity = pressed.
    pub counts: [u32; BUTTONS],
    pub axes: [i32; AXES],
    pub time_ns: u64,
}

impl Snapshot {
    /// What a reader shows while it has no usable backing: every button up, every axis 0.
    pub const NEUTRAL: Snapshot = Snapshot {
        pub_seq: 0,
        flags: 0,
        unplug: 0,
        edges: 0,
        counts: [0; BUTTONS],
        axes: [0; AXES],
        time_ns: 0,
    };

    #[inline]
    pub fn buttons(&self) -> u16 {
        parity(&self.counts)
    }

    pub fn encode(&self) -> [u32; COPY_WORDS] {
        let mut w = [0u32; COPY_WORDS];
        w[W_PUB] = self.pub_seq;
        w[W_FLAGS] = self.flags;
        w[W_UNPLUG] = self.unplug;
        w[W_EDGES] = self.edges;
        w[W_COUNTS..W_COUNTS + BUTTONS].copy_from_slice(&self.counts);
        for i in 0..AXES {
            w[W_AXES + i] = self.axes[i] as u32;
        }
        w[W_TIME_LO] = self.time_ns as u32;
        w[W_TIME_HI] = (self.time_ns >> 32) as u32;
        w
    }

    pub fn decode(w: &[u32; COPY_WORDS]) -> Snapshot {
        let mut counts = [0u32; BUTTONS];
        counts.copy_from_slice(&w[W_COUNTS..W_COUNTS + BUTTONS]);
        let mut axes = [0i32; AXES];
        for i in 0..AXES {
            axes[i] = w[W_AXES + i] as i32;
        }
        Snapshot {
            pub_seq: w[W_PUB],
            flags: w[W_FLAGS],
            unplug: w[W_UNPLUG],
            edges: w[W_EDGES],
            counts,
            axes,
            time_ns: (w[W_TIME_LO] as u64) | ((w[W_TIME_HI] as u64) << 32),
        }
    }
}

impl Shared {
    /// # Safety
    /// `p` must point at a live mapping of at least `FILE_SIZE` bytes.
    #[inline]
    pub unsafe fn at<'a>(p: *const u8) -> &'a Shared {
        &*(p as *const Shared)
    }

    /// True if this is a format-1 slot file for `slot` (magic checked first, with Acquire, so the
    /// rest of the header the writer initialised before it is visible).
    pub fn valid_for(&self, slot: u32) -> bool {
        let h = &self.header;
        h.magic.load(Acquire) == MAGIC
            && h.major.load(Relaxed) == FORMAT_MAJOR
            && h.header_size.load(Relaxed) as usize == HEADER_SIZE
            && h.copy_size.load(Relaxed) as usize == COPY_SIZE
            && h.copy_count.load(Relaxed) as usize == COPY_COUNT
            && h.journal_len.load(Relaxed) as usize == JOURNAL_LEN
            && h.file_size.load(Relaxed) as usize >= FILE_SIZE
            && h.slot.load(Relaxed) == slot
    }

    #[inline]
    pub fn is_retired(&self) -> bool {
        self.header.retired.load(Acquire) != 0
    }

    /// A consistent copy of the latest published state.
    #[inline]
    pub fn snapshot(&self, attempts: u32) -> Option<Snapshot> {
        let mut w = [0u32; COPY_WORDS];
        if proto::snapshot(&self.copies, &self.header.published, &mut w, attempts) {
            Some(Snapshot::decode(&w))
        } else {
            None
        }
    }

    #[inline]
    pub fn journal_get(&self, idx: u32) -> Option<u32> {
        proto::journal_get(&self.journal, idx)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn snapshot_roundtrip() {
        let s = Snapshot {
            pub_seq: 7,
            flags: FLAG_CONNECTED,
            unplug: 2,
            edges: 99,
            counts: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11],
            axes: [-32768, 32767, -1, 1, 0, 255, -1, 1],
            time_ns: 0x1234_5678_9abc_def0,
        };
        assert_eq!(Snapshot::decode(&s.encode()), s);
        assert_eq!(s.buttons(), 0b101_0101_0101);
    }

    #[test]
    fn magic_is_the_ascii_tag() {
        assert_eq!(&MAGIC.to_le_bytes(), b"BANNERIN");
    }
}

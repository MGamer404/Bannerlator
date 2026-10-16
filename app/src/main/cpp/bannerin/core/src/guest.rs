//! Guest-side runtime behind the C ABI: one handle per fake evdev fd.
//!
//! **Handles.** A fixed table (no allocation, nothing ever freed back to libc, no constructors or
//! destructors: everything is a const-initialised static) of `MAX_HANDLES` entries. A handle value
//! is `generation << 8 | index`, so a stale handle is refused with EBADF. Every operation takes a
//! reference on the entry for its whole duration, so `detach` from another thread never frees
//! state under a reader; the last reference frees it. The per-entry lock is only ever held for
//! short, non-blocking work, never while sleeping and never across a call back into the host.
//!
//! **The guest fd** is one end of an AF_UNIX stream socketpair. It carries no data a program could
//! mistake for input: it is used purely as a level-triggered readiness flag. Exactly one byte is
//! queued on it while this handle has undelivered events, and none otherwise, so a plain kernel
//! `poll`/`select`/`epoll` on it works without any help from the interposer. When the controller
//! is unplugged the peer end is closed, which the kernel reports as POLLHUP. A raw `read` of the
//! fd (say, by a program that inherited it across `exec`) returns at most one byte, never anything
//! shaped like an `input_event`.
//!
//! **Wake-up.** The writer bumps a futex word (the "bell") in the shared file and wakes it on every
//! publish. A blocked `bannerin_read` sleeps on that bell directly. For native readiness, one
//! helper thread per slot (started lazily, all signals blocked) sleeps on the bell and re-levels
//! the readiness byte of every handle of that slot when it rings; it also re-checks every 4 ms in
//! case a wake is ever missed. Every 500 ms it checks whether the path now names a different file
//! and whether the writer process is still alive (it holds an flock on the file).
//!
//! **fork.** A pthread_atfork child handler resets every lock, forgets the helper threads (they do
//! not exist in the child; the next call into the library restarts them), and gives each open
//! handle a fresh socketpair dup3()ed onto the same fd number, so parent and child keep the same
//! fd numbers with independent readiness and independent read positions. The child's first read
//! is a full-state frame.

use core::cell::UnsafeCell;
use core::ffi::{c_char, c_int, c_void};
use core::ptr;
use core::sync::atomic::Ordering::{AcqRel, Acquire, Relaxed, Release};
use core::sync::atomic::{AtomicU32, AtomicU64, AtomicUsize};

use crate::abi::*;
use crate::frame::{Cursor, Due, Ev, Frame, FrameStats, Journal, NoJournal};
use crate::layout::{Shared, Snapshot, FILE_SIZE};
use crate::msg::Msg;
use crate::sys;

pub const MAX_HANDLES: usize = 128;
pub const MAX_SLOTS: usize = 4;
pub const PATH_CAP: usize = 1024;
pub const ENV_PATHS: &[u8] = b"FAKE_EVDEV_MEMFD_PATHS\0";
pub const ENV_AXIS_HZ: &[u8] = b"BANNERIN_AXIS_HZ\0";
/// Axis-only frame rate per handle when BANNERIN_AXIS_HZ is unset (T = 8 ms).
pub const AXIS_HZ_DEFAULT: u32 = 125;
pub const AXIS_HZ_MIN: u32 = 30;
pub const AXIS_HZ_MAX: u32 = 1000;
pub const API_VERSION: u32 = 1;

/// Upper bound on how long a missed wake could delay input (the fallback re-check).
const FALLBACK_NS: u64 = 4_000_000;
/// Cadence of path / writer-liveness checks and of re-attach attempts for orphaned handles.
const SLOW_NS: u64 = 500_000_000;
const SNAP_ATTEMPTS: u32 = 64;
const HELPER_STACK: usize = 256 * 1024;

// ---------------------------------------------------------------------------------------------
// configuration (FAKE_EVDEV_MEMFD_PATHS)

struct Config {
    state: AtomicU32, // 0 = not parsed, 1 = parsing, 2 = ready
    mask: AtomicU32,
    paths: UnsafeCell<[[u8; PATH_CAP]; MAX_SLOTS]>,
}
unsafe impl Sync for Config {}

static CONFIG: Config = Config {
    state: AtomicU32::new(0),
    mask: AtomicU32::new(0),
    paths: UnsafeCell::new([[0; PATH_CAP]; MAX_SLOTS]),
};

fn trim(mut s: &[u8]) -> &[u8] {
    while let [b' ' | b'\t' | b'\n' | b'\r', rest @ ..] = s {
        s = rest;
    }
    while let [rest @ .., b' ' | b'\t' | b'\n' | b'\r'] = s {
        s = rest;
    }
    s
}

/// Parses `<slot>=<path>[;<slot>=<path>...]`. Returns the mask of slots configured; malformed
/// entries are skipped, the first entry for a slot wins.
pub fn parse_paths(s: &[u8], out: &mut [[u8; PATH_CAP]; MAX_SLOTS]) -> u32 {
    let mut mask = 0u32;
    for entry in s.split(|c| *c == b';') {
        let entry = trim(entry);
        let eq = match entry.iter().position(|c| *c == b'=') {
            Some(e) => e,
            None => continue,
        };
        let key = trim(&entry[..eq]);
        let path = trim(&entry[eq + 1..]);
        if key.len() != 1 || !(b'0'..=b'3').contains(&key[0]) {
            continue;
        }
        let slot = (key[0] - b'0') as usize;
        if path.is_empty() || path.len() >= PATH_CAP || path.contains(&0) || mask & (1 << slot) != 0 {
            continue;
        }
        out[slot][..path.len()].copy_from_slice(path);
        out[slot][path.len()] = 0;
        mask |= 1 << slot;
    }
    mask
}

/// Lazy, idempotent, safe before constructors and after destructors (static data only).
pub fn init() -> u32 {
    let mut spins = 0u32;
    loop {
        match CONFIG.state.load(Acquire) {
            2 => return CONFIG.mask.load(Relaxed),
            0 => {
                if CONFIG.state.compare_exchange(0, 1, Acquire, Relaxed).is_ok() {
                    let paths = unsafe { &mut *CONFIG.paths.get() };
                    let mask = match sys::env(ENV_PATHS) {
                        Some(v) => parse_paths(v, paths),
                        None => 0,
                    };
                    CONFIG.mask.store(mask, Relaxed);
                    let hz = parse_axis_hz(sys::env(ENV_AXIS_HZ));
                    AXIS_HZ.store(hz, Relaxed);
                    AXIS_NS.store(if hz == 0 { 0 } else { 1_000_000_000 / hz as u64 }, Relaxed);
                    CONFIG.state.store(2, Release);
                    return mask;
                }
            }
            _ => {
                spins += 1;
                if spins > 200_000 {
                    return 0; // re-entered from a signal handler mid-parse: report nothing
                }
                if spins % 64 == 0 {
                    sys::sched_yield();
                } else {
                    core::hint::spin_loop();
                }
            }
        }
    }
}

/// `BANNERIN_AXIS_HZ`: unset or unparsable = the default, 0 = unpaced, otherwise clamped to
/// AXIS_HZ_MIN..=AXIS_HZ_MAX.
pub fn parse_axis_hz(v: Option<&[u8]>) -> u32 {
    let v = match v {
        Some(v) => trim(v),
        None => return AXIS_HZ_DEFAULT,
    };
    if v.is_empty() || v.len() > 9 || !v.iter().all(|c| c.is_ascii_digit()) {
        return AXIS_HZ_DEFAULT;
    }
    let n = v.iter().fold(0u32, |n, c| n * 10 + (c - b'0') as u32);
    if n == 0 {
        0
    } else {
        n.clamp(AXIS_HZ_MIN, AXIS_HZ_MAX)
    }
}

static AXIS_HZ: AtomicU32 = AtomicU32::new(AXIS_HZ_DEFAULT);
static AXIS_NS: AtomicU64 = AtomicU64::new(1_000_000_000 / AXIS_HZ_DEFAULT as u64);
static CONFIG_LOGGED: AtomicU32 = AtomicU32::new(0);

/// The pacing interval T for axis-only frames (0 = unpaced).
#[inline]
fn axis_ns() -> u64 {
    AXIS_NS.load(Relaxed)
}

/// The effective BANNERIN_AXIS_HZ (0 = unpaced).
pub fn axis_hz() -> u32 {
    init();
    AXIS_HZ.load(Relaxed)
}

/// Log the effective pacing once, the first time a logger is there to hear it.
fn log_config_once() {
    if LOGGER.load(Acquire) == 0 || CONFIG.state.load(Acquire) != 2 {
        return;
    }
    if CONFIG_LOGGED.compare_exchange(0, 1, AcqRel, Relaxed).is_err() {
        return;
    }
    let hz = AXIS_HZ.load(Relaxed);
    let mut m = Msg::new();
    if hz == 0 {
        m.s(b"bannerin: axis-only frames unpaced (BANNERIN_AXIS_HZ=0)");
    } else {
        m.s(b"bannerin: axis-only frames paced to ")
            .u(hz as u64)
            .s(b" Hz per handle (T = ")
            .u(AXIS_NS.load(Relaxed) / 1000)
            .s(b" us); button and full-state frames are never delayed");
    }
    log(0, &mut m);
}

fn slot_path(slot: usize) -> *const c_char {
    unsafe { (*CONFIG.paths.get())[slot].as_ptr() as *const c_char }
}

// ---------------------------------------------------------------------------------------------
// statistics and logging

pub struct Stats {
    pub attaches: AtomicU64,
    pub detaches: AtomicU64,
    pub reattaches: AtomicU64,
    pub orphaned: AtomicU64,
    pub removals: AtomicU64,
    pub writer_dead: AtomicU64,
    pub frames: AtomicU64,
    pub events: AtomicU64,
    pub taps: AtomicU64,
    pub full_frames: AtomicU64,
    pub journal_frames: AtomicU64,
    pub counter_frames: AtomicU64,
    pub coalesced_taps: AtomicU64,
    pub resyncs: AtomicU64,
    pub helper_wakes: AtomicU64,
    pub helper_timeouts: AtomicU64,
    pub ready_signals: AtomicU64,
    pub snapshot_busy: AtomicU64,
    pub fork_rebinds: AtomicU64,
    pub paced_frames: AtomicU64,
    pub merged_axis_publishes: AtomicU64,
}

const Z: AtomicU64 = AtomicU64::new(0);
pub static STATS: Stats = Stats {
    attaches: Z,
    detaches: Z,
    reattaches: Z,
    orphaned: Z,
    removals: Z,
    writer_dead: Z,
    frames: Z,
    events: Z,
    taps: Z,
    full_frames: Z,
    journal_frames: Z,
    counter_frames: Z,
    coalesced_taps: Z,
    resyncs: Z,
    helper_wakes: Z,
    helper_timeouts: Z,
    ready_signals: Z,
    snapshot_busy: Z,
    fork_rebinds: Z,
    paced_frames: Z,
    merged_axis_publishes: Z,
};

/// Process-wide reader statistics (`struct bannerin_stats`).
#[repr(C)]
#[derive(Clone, Copy, Default, Debug)]
pub struct StatsView {
    pub attaches: u64,
    pub detaches: u64,
    pub reattaches: u64,
    pub orphaned: u64,
    pub removals: u64,
    pub writer_dead: u64,
    pub frames: u64,
    pub events: u64,
    pub taps: u64,
    pub full_frames: u64,
    pub journal_frames: u64,
    pub counter_frames: u64,
    pub coalesced_taps: u64,
    pub resyncs: u64,
    pub helper_wakes: u64,
    pub helper_timeouts: u64,
    pub ready_signals: u64,
    pub snapshot_busy: u64,
    pub fork_rebinds: u64,
    /// Axis-only frames held back by pacing before being delivered.
    pub paced_frames: u64,
    /// Publishes merged into a later frame instead of delivered one each.
    pub merged_axis_publishes: u64,
}

pub fn stats() -> StatsView {
    let s = &STATS;
    StatsView {
        attaches: s.attaches.load(Relaxed),
        detaches: s.detaches.load(Relaxed),
        reattaches: s.reattaches.load(Relaxed),
        orphaned: s.orphaned.load(Relaxed),
        removals: s.removals.load(Relaxed),
        writer_dead: s.writer_dead.load(Relaxed),
        frames: s.frames.load(Relaxed),
        events: s.events.load(Relaxed),
        taps: s.taps.load(Relaxed),
        full_frames: s.full_frames.load(Relaxed),
        journal_frames: s.journal_frames.load(Relaxed),
        counter_frames: s.counter_frames.load(Relaxed),
        coalesced_taps: s.coalesced_taps.load(Relaxed),
        resyncs: s.resyncs.load(Relaxed),
        helper_wakes: s.helper_wakes.load(Relaxed),
        helper_timeouts: s.helper_timeouts.load(Relaxed),
        ready_signals: s.ready_signals.load(Relaxed),
        snapshot_busy: s.snapshot_busy.load(Relaxed),
        fork_rebinds: s.fork_rebinds.load(Relaxed),
        paced_frames: s.paced_frames.load(Relaxed),
        merged_axis_publishes: s.merged_axis_publishes.load(Relaxed),
    }
}

/// Counters kept in a slot file, summed over its writer and every reader (`struct bannerin_slot_stats`).
#[repr(C)]
#[derive(Clone, Copy, Default, Debug)]
pub struct SlotStatsView {
    pub instance: u64,
    pub publishes: u64,
    pub wake_calls: u64,
    pub woken: u64,
    pub unplugs: u64,
    pub edges: u64,
    pub reader_attaches: u64,
    pub reader_reattaches: u64,
    pub reader_taps: u64,
    pub reader_coalesced: u64,
    pub reader_resyncs: u64,
    pub reader_frames: u64,
    pub reader_events: u64,
    pub reader_removals: u64,
    pub writer_pid: u32,
    pub retired: u32,
    pub reader_paced_frames: u64,
    pub reader_merged_axis_publishes: u64,
}

pub type LogFn = unsafe extern "C" fn(level: c_int, line: *const c_char);
static LOGGER: AtomicUsize = AtomicUsize::new(0);

pub fn set_logger(f: Option<LogFn>) {
    LOGGER.store(f.map_or(0, |f| f as usize), Release);
    log_config_once();
}

fn log(level: c_int, m: &mut Msg) {
    let f = LOGGER.load(Acquire);
    if f != 0 {
        let f: LogFn = unsafe { core::mem::transmute::<usize, LogFn>(f) };
        unsafe { f(level, m.c_str()) };
    }
}

// ---------------------------------------------------------------------------------------------
// mappings

#[derive(Clone, Copy)]
struct Mapped {
    ptr: *mut u8,
    fd: i32,
    dev: u64,
    ino: u64,
    instance: u64,
    writable: bool,
}

impl Mapped {
    const NONE: Mapped = Mapped { ptr: ptr::null_mut(), fd: -1, dev: 0, ino: 0, instance: 0, writable: false };

    fn shared<'a>(&self) -> Option<&'a Shared> {
        if self.ptr.is_null() {
            None
        } else {
            Some(unsafe { Shared::at(self.ptr) })
        }
    }

    fn release(&mut self) {
        sys::munmap(self.ptr, FILE_SIZE);
        sys::close(self.fd);
        *self = Mapped::NONE;
    }
}

/// Map the file `FAKE_EVDEV_MEMFD_PATHS` names for `slot`, if it is a live format-1 file for that
/// slot. The size is checked before mapping, so a short or foreign file is refused, never touched.
fn map_slot(slot: usize, keep_fd: bool) -> Result<Mapped, i32> {
    let path = slot_path(slot);
    let (fd, writable) = match sys::openat(path, sys::O_RDWR | sys::O_CLOEXEC, 0) {
        Ok(fd) => (fd, true),
        Err(_) => (sys::openat(path, sys::O_RDONLY | sys::O_CLOEXEC, 0)?, false),
    };
    let st = match sys::fstat(fd) {
        Ok(s) => s,
        Err(e) => {
            sys::close(fd);
            return Err(e);
        }
    };
    if !st.is_regular() || st.size < FILE_SIZE as i64 {
        sys::close(fd);
        return Err(sys::EINVAL);
    }
    let prot = sys::PROT_READ | if writable { sys::PROT_WRITE } else { 0 };
    let p = match sys::mmap(FILE_SIZE, prot, sys::MAP_SHARED, fd) {
        Ok(p) => p,
        Err(e) => {
            sys::close(fd);
            return Err(e);
        }
    };
    let sh = unsafe { Shared::at(p) };
    if !sh.valid_for(slot as u32) || sh.is_retired() {
        sys::munmap(p, FILE_SIZE);
        sys::close(fd);
        return Err(sys::EINVAL);
    }
    let instance = sh.header.instance.load(Relaxed);
    let fd = if keep_fd {
        fd
    } else {
        sys::close(fd);
        -1
    };
    Ok(Mapped { ptr: p, fd, dev: st.dev, ino: st.ino, instance, writable })
}

// ---------------------------------------------------------------------------------------------
// per-slot runtime (helper thread state)

struct SlotRt {
    helper: AtomicU32, // 0 = not running, 1 = running, 2 = start failed (retry later)
    retry_at: AtomicU64,
    file_gen: AtomicU32,
    instance: AtomicU64,
    dead_instance: AtomicU64,
    handles: AtomicU32,
    kick: AtomicU32,
    /// Consecutive slow checks that found the writer's lock free (helper-owned).
    dead_votes: AtomicU32,
    hm: UnsafeCell<Mapped>, // owned by the slot's helper thread
}

struct SlotTable {
    s: [SlotRt; MAX_SLOTS],
}
unsafe impl Sync for SlotTable {}

const SLOT_RT: SlotRt = SlotRt {
    helper: AtomicU32::new(0),
    retry_at: AtomicU64::new(0),
    file_gen: AtomicU32::new(0),
    instance: AtomicU64::new(0),
    dead_instance: AtomicU64::new(0),
    handles: AtomicU32::new(0),
    kick: AtomicU32::new(0),
    dead_votes: AtomicU32::new(0),
    hm: UnsafeCell::new(Mapped::NONE),
};
static SLOTS: SlotTable = SlotTable { s: [SLOT_RT; MAX_SLOTS] };

/// A word nobody ever changes: sleeping on it is a plain timed sleep.
static IDLE: AtomicU32 = AtomicU32::new(0);
static ATFORK: AtomicU32 = AtomicU32::new(0);

// ---------------------------------------------------------------------------------------------
// handle table

const FREE: u32 = 0;
const ALLOC: u32 = 1;
const ACTIVE: u32 = 2;
const CLOSED: u32 = 3;
const FREEING: u32 = 4;

struct Data {
    slot: usize,
    guest_fd: i32,
    drain_fd: i32,
    wake_fd: i32,
    map: *mut u8,
    writable: bool,
    instance: u64,
    map_gen: u32,
    base_pending: bool,
    unplug_base: u32,
    removed: bool,
    dead: bool,
    last_retry: u64,
    cursor: Cursor,
    frame: Frame,
    fpos: usize,
    ftime: (i64, i64),
    ready: bool,
}

impl Data {
    const EMPTY: Data = Data {
        slot: 0,
        guest_fd: -1,
        drain_fd: -1,
        wake_fd: -1,
        map: ptr::null_mut(),
        writable: false,
        instance: 0,
        map_gen: 0,
        base_pending: false,
        unplug_base: 0,
        removed: false,
        dead: false,
        last_retry: 0,
        cursor: Cursor::new(),
        frame: Frame::new(),
        fpos: 0,
        ftime: (0, 0),
        ready: false,
    };
}

struct Entry {
    state: AtomicU32,
    gen: AtomicU32,
    refs: AtomicU32,
    lock: AtomicU32,
    data: UnsafeCell<Data>,
}

struct Table {
    e: [Entry; MAX_HANDLES],
}
unsafe impl Sync for Table {}

const ENTRY: Entry = Entry {
    state: AtomicU32::new(FREE),
    gen: AtomicU32::new(0),
    refs: AtomicU32::new(0),
    lock: AtomicU32::new(0),
    data: UnsafeCell::new(Data::EMPTY),
};
static TABLE: Table = Table { e: [ENTRY; MAX_HANDLES] };

#[inline]
fn lock(e: &Entry) {
    let mut n = 0u32;
    while e.lock.compare_exchange_weak(0, 1, Acquire, Relaxed).is_err() {
        n += 1;
        if n < 64 {
            core::hint::spin_loop();
        } else {
            sys::sched_yield();
        }
    }
}

#[inline]
fn unlock(e: &Entry) {
    e.lock.store(0, Release);
}

/// # Safety
/// The caller holds the entry lock (or has exclusive use of the entry).
#[allow(clippy::mut_from_ref)]
unsafe fn data(e: &Entry) -> &mut Data {
    &mut *e.data.get()
}

fn acquire(h: u32) -> Option<&'static Entry> {
    let idx = (h & 0xff) as usize;
    let gen = h >> 8;
    if idx >= MAX_HANDLES || gen == 0 {
        return None;
    }
    let e = &TABLE.e[idx];
    e.refs.fetch_add(1, Acquire);
    if e.state.load(Acquire) == ACTIVE && e.gen.load(Acquire) == gen {
        Some(e)
    } else {
        release(e);
        None
    }
}

fn release(e: &Entry) {
    if e.refs.fetch_sub(1, AcqRel) == 1 && e.state.compare_exchange(CLOSED, FREEING, AcqRel, Relaxed).is_ok() {
        unsafe { free_data(data(e)) };
        e.state.store(FREE, Release);
    }
}

fn alloc() -> Option<(usize, &'static Entry)> {
    for (i, e) in TABLE.e.iter().enumerate() {
        if e.state.load(Relaxed) == FREE && e.state.compare_exchange(FREE, ALLOC, Acquire, Relaxed).is_ok() {
            return Some((i, e));
        }
    }
    None
}

fn next_gen(e: &Entry) -> u32 {
    let g = e.gen.load(Relaxed).wrapping_add(1) & 0x00ff_ffff;
    if g == 0 {
        1
    } else {
        g
    }
}

fn shared<'a>(d: &Data) -> Option<&'a Shared> {
    if d.map.is_null() {
        None
    } else {
        Some(unsafe { Shared::at(d.map) })
    }
}

fn unmap_data(d: &mut Data) {
    sys::munmap(d.map, FILE_SIZE);
    d.map = ptr::null_mut();
    d.writable = false;
    d.instance = 0;
}

fn free_data(d: &mut Data) {
    sys::close(d.drain_fd);
    sys::close(d.wake_fd);
    unmap_data(d);
    *d = Data::EMPTY;
}

// ---------------------------------------------------------------------------------------------
// per-handle state machine (all under the entry lock)

#[derive(Default)]
struct Notes {
    reattached: bool,
    orphaned: bool,
    removed: bool,
    dead: bool,
    resyncs: u64,
    coalesced: u64,
}

struct ShJournal<'a>(&'a Shared);

impl Journal for ShJournal<'_> {
    fn get(&self, idx: u32) -> Option<u32> {
        self.0.journal_get(idx)
    }
}

fn install(d: &mut Data, m: Mapped, first: bool) {
    d.map = m.ptr;
    d.writable = m.writable;
    d.instance = m.instance;
    d.base_pending = true;
    d.dead = SLOTS.s[d.slot].dead_instance.load(Acquire) == m.instance;
    d.cursor.force_full();
    if d.writable {
        if let Some(sh) = shared(d) {
            if first {
                sh.header.r_attaches.fetch_add(1, Relaxed);
            } else {
                sh.header.r_reattaches.fetch_add(1, Relaxed);
            }
        }
    }
    if !first {
        STATS.reattaches.fetch_add(1, Relaxed);
    }
}

fn reattach(d: &mut Data, now: u64, notes: &mut Notes) {
    d.last_retry = now;
    match map_slot(d.slot, false) {
        Ok(mut m) if m.instance == d.instance => m.release(), // same live file: keep ours
        Ok(m) => {
            unmap_data(d);
            install(d, m, false);
            notes.reattached = true;
        }
        Err(_) => {
            if !d.map.is_null() {
                unmap_data(d);
                d.dead = false;
                d.cursor.force_full(); // neutral full state: nothing stays held
                notes.orphaned = true;
                STATS.orphaned.fetch_add(1, Relaxed);
            }
        }
    }
}

/// Follow the backing: re-attach after retirement / replacement, go neutral when the writer is
/// gone, retry an orphaned handle.
fn refresh(d: &mut Data, now: u64, notes: &mut Notes) {
    if d.removed {
        return;
    }
    let rt = &SLOTS.s[d.slot];
    let gen = rt.file_gen.load(Acquire);
    match shared(d) {
        Some(sh) => {
            let moved = gen != d.map_gen && rt.instance.load(Acquire) != d.instance;
            d.map_gen = gen;
            if sh.is_retired() || moved {
                reattach(d, now, notes);
            } else {
                let dead = rt.dead_instance.load(Acquire) == d.instance;
                if dead != d.dead {
                    d.dead = dead;
                    d.cursor.force_full();
                    if dead {
                        notes.dead = true;
                        STATS.writer_dead.fetch_add(1, Relaxed);
                    }
                }
            }
        }
        None => {
            if gen != d.map_gen || now.wrapping_sub(d.last_retry) >= SLOW_NS {
                d.map_gen = gen;
                d.last_retry = now;
                if let Ok(m) = map_slot(d.slot, false) {
                    install(d, m, false);
                    notes.reattached = true;
                }
            }
        }
    }
}

/// The state this handle should currently show. Detects removal.
fn observe(d: &mut Data, notes: &mut Notes) -> Snapshot {
    let sh = match shared(d) {
        Some(sh) if !d.dead && !d.removed => sh,
        _ => return Snapshot::NEUTRAL,
    };
    for k in 0..8 {
        if let Some(s) = sh.snapshot(SNAP_ATTEMPTS) {
            if d.base_pending {
                d.unplug_base = s.unplug;
                d.base_pending = false;
            } else if s.unplug != d.unplug_base {
                d.removed = true;
                notes.removed = true;
                STATS.removals.fetch_add(1, Relaxed);
                if d.writable {
                    sh.header.r_removals.fetch_add(1, Relaxed);
                }
            }
            return s;
        }
        STATS.snapshot_busy.fetch_add(1, Relaxed);
        if k > 0 {
            sys::sched_yield();
        }
    }
    d.cursor.as_snapshot() // the writer kept lapping us: deliver nothing new this time
}

/// When this handle's next events are due at `now` (a partly delivered frame is due at once).
fn due(d: &mut Data, snap: &Snapshot, now: u64) -> Due {
    if d.fpos < d.frame.len {
        Due::Now
    } else {
        d.cursor.due(snap, now, axis_ns())
    }
}

fn earlier(a: Option<u64>, b: Option<u64>) -> Option<u64> {
    match (a, b) {
        (Some(x), Some(y)) => Some(x.min(y)),
        (x, None) => x,
        (None, y) => y,
    }
}

/// How long to sleep: the fallback re-check, or less if a held frame falls due sooner.
fn wait_ns(base: u64, deadline: Option<u64>) -> u64 {
    match deadline {
        Some(t) => base.min(t.saturating_sub(sys::mono_ns()).max(1_000)),
        None => base,
    }
}

/// Keep exactly one readiness byte queued while events are due; close the peer on removal.
/// Returns when a held (paced) frame becomes due, if one is held.
fn sync_ready(d: &mut Data, snap: &Snapshot, now: u64) -> Option<u64> {
    if d.removed {
        if d.wake_fd >= 0 {
            if !d.ready {
                let _ = sys::send_byte(d.wake_fd);
                d.ready = true;
            }
            sys::close(d.wake_fd);
            d.wake_fd = -1;
        }
        return None;
    }
    let due = due(d, snap, now);
    let held = match due {
        Due::At(t) => Some(t),
        _ => None,
    };
    if d.wake_fd < 0 {
        return held;
    }
    let want = due == Due::Now;
    if want && !d.ready {
        match sys::send_byte(d.wake_fd) {
            Ok(_) | Err(sys::EAGAIN) => {
                d.ready = true;
                STATS.ready_signals.fetch_add(1, Relaxed);
            }
            Err(_) => {}
        }
    } else if !want && d.ready {
        sys::drain(d.drain_fd);
        d.ready = false;
    }
    held
}

#[repr(C)]
#[allow(dead_code)] // written to the caller's buffer, never read back
struct InputEvent {
    sec: i64,
    usec: i64,
    kind: u16,
    code: u16,
    value: i32,
}
const _: () = assert!(core::mem::size_of::<InputEvent>() == EVENT_SIZE);

unsafe fn deliver(d: &mut Data, snap: &Snapshot, out: *mut u8, max: usize, fs: &mut FrameStats, now: u64) -> usize {
    let t = axis_ns();
    let mut n = 0usize;
    while n < max {
        if d.fpos >= d.frame.len {
            let r = match shared(d) {
                Some(sh) if !d.dead => d.cursor.next_frame_at(snap, &ShJournal(sh), &mut d.frame, fs, now, t),
                _ => d.cursor.next_frame_at(snap, &NoJournal, &mut d.frame, fs, now, t),
            };
            d.fpos = 0;
            if r != Due::Now {
                d.frame.len = 0;
                break;
            }
            d.ftime = sys::realtime_us();
        }
        let e: Ev = d.frame.ev[d.fpos];
        d.fpos += 1;
        ptr::write_unaligned(
            out.add(n * EVENT_SIZE) as *mut InputEvent,
            InputEvent { sec: d.ftime.0, usec: d.ftime.1, kind: e.kind, code: e.code, value: e.value },
        );
        n += 1;
    }
    n
}

fn account(d: &Data, fs: &FrameStats, notes: &mut Notes) {
    notes.resyncs += fs.resyncs;
    notes.coalesced += fs.coalesced_taps;
    if fs.frames == 0 && fs.coalesced_taps == 0 {
        return;
    }
    let s = &STATS;
    s.frames.fetch_add(fs.frames, Relaxed);
    s.events.fetch_add(fs.events, Relaxed);
    s.taps.fetch_add(fs.taps, Relaxed);
    s.full_frames.fetch_add(fs.full_frames, Relaxed);
    s.journal_frames.fetch_add(fs.journal_frames, Relaxed);
    s.counter_frames.fetch_add(fs.counter_frames, Relaxed);
    s.coalesced_taps.fetch_add(fs.coalesced_taps, Relaxed);
    s.resyncs.fetch_add(fs.resyncs, Relaxed);
    s.paced_frames.fetch_add(fs.paced_frames, Relaxed);
    s.merged_axis_publishes.fetch_add(fs.merged_axis_publishes, Relaxed);
    if d.writable && !d.dead {
        if let Some(sh) = shared(d) {
            let h = &sh.header;
            h.r_frames.fetch_add(fs.frames, Relaxed);
            h.r_events.fetch_add(fs.events, Relaxed);
            h.r_taps.fetch_add(fs.taps, Relaxed);
            h.r_coalesced.fetch_add(fs.coalesced_taps, Relaxed);
            h.r_resyncs.fetch_add(fs.resyncs, Relaxed);
            h.r_paced_frames.fetch_add(fs.paced_frames, Relaxed);
            h.r_merged_axis.fetch_add(fs.merged_axis_publishes, Relaxed);
        }
    }
}

fn emit_notes(slot: usize, fd: i32, n: &Notes) {
    if LOGGER.load(Relaxed) == 0 {
        return;
    }
    let head = |m: &mut Msg| {
        m.s(b"bannerin: slot ").u(slot as u64).s(b" fd ").i(fd as i64).s(b": ");
    };
    if n.reattached {
        let mut m = Msg::new();
        head(&mut m);
        log(0, m.s(b"attached to a new backing file, full state follows"));
    }
    if n.orphaned {
        let mut m = Msg::new();
        head(&mut m);
        log(1, m.s(b"backing file gone or not ours, showing a neutral pad until it returns"));
    }
    if n.dead {
        let mut m = Msg::new();
        head(&mut m);
        log(1, m.s(b"writer process gone, showing a neutral pad"));
    }
    if n.removed {
        let mut m = Msg::new();
        head(&mut m);
        log(0, m.s(b"controller unplugged, handle removed"));
    }
    if n.resyncs > 0 {
        let mut m = Msg::new();
        head(&mut m);
        log(1, m.s(b"inconsistent state, resynced with a full-state frame"));
    }
    if n.coalesced > 0 {
        let mut m = Msg::new();
        head(&mut m);
        log(1, m.s(b"reader fell behind, taps coalesced: ").u(n.coalesced));
    }
}

fn wait_target(d: &Data) -> (*const AtomicU32, u32, bool) {
    match shared(d) {
        Some(sh) => (&sh.header.bell as *const AtomicU32, sh.header.bell.load(Acquire), true),
        None => (&IDLE as *const AtomicU32, IDLE.load(Relaxed), false),
    }
}

// ---------------------------------------------------------------------------------------------
// helper threads

fn ensure_helper(slot: usize) {
    let rt = &SLOTS.s[slot];
    let st = rt.helper.load(Acquire);
    if st == 1 {
        return;
    }
    if st == 2 && sys::mono_ns() < rt.retry_at.load(Relaxed) {
        return;
    }
    if rt.helper.compare_exchange(st, 1, AcqRel, Relaxed).is_err() {
        return;
    }
    if !sys::spawn_detached(helper_main, slot as *mut c_void, HELPER_STACK) {
        rt.retry_at.store(sys::mono_ns().wrapping_add(1_000_000_000), Relaxed);
        rt.helper.store(2, Release);
    }
}

/// Every 500 ms: does the path still name the file we know, and is its writer alive?
fn slow_check(slot: usize, rt: &SlotRt) {
    let hm = unsafe { &mut *rt.hm.get() };
    let st = sys::stat(slot_path(slot));
    let remap = match (&st, hm.shared()) {
        (Ok(s), Some(sh)) => s.dev != hm.dev || s.ino != hm.ino || sh.is_retired(),
        (Ok(_), None) => true,
        (Err(_), Some(_)) => {
            hm.release();
            rt.instance.store(0, Release);
            rt.file_gen.fetch_add(1, Release);
            false
        }
        (Err(_), None) => false,
    };
    if remap {
        match map_slot(slot, true) {
            Ok(m) => {
                hm.release();
                *hm = m;
                rt.dead_votes.store(0, Relaxed);
                rt.instance.store(m.instance, Release);
                rt.file_gen.fetch_add(1, Release);
            }
            Err(_) => {
                if hm.shared().is_some() {
                    hm.release();
                    rt.instance.store(0, Release);
                    rt.file_gen.fetch_add(1, Release);
                }
            }
        }
    }
    if hm.shared().is_some() {
        // The writer holds an exclusive flock for as long as it lives, so getting a shared one
        // means it is gone. Neutralising a live pad is worse than noticing a dead writer a little
        // later, so the verdict needs two consecutive checks (0.5 - 1 s), and a free lock only
        // counts if the probed fd really is the mapped file. Any other answer means alive.
        let free = matches!(sys::flock(hm.fd, sys::LOCK_SH | sys::LOCK_NB), Ok(()));
        if free {
            let _ = sys::flock(hm.fd, sys::LOCK_UN);
        }
        let ours = !free || matches!(sys::fstat(hm.fd), Ok(st) if st.dev == hm.dev && st.ino == hm.ino);
        if free && !ours {
            // Not the file we think it is: forget it, the next check maps the path again.
            hm.release();
            rt.dead_votes.store(0, Relaxed);
            return;
        }
        if free {
            let votes = rt.dead_votes.load(Relaxed).saturating_add(1);
            rt.dead_votes.store(votes, Relaxed);
            if votes >= 2 {
                rt.dead_instance.store(hm.instance, Release);
            }
        } else {
            rt.dead_votes.store(0, Relaxed);
            if rt.dead_instance.load(Acquire) == hm.instance {
                rt.dead_instance.store(0, Release);
            }
        }
    }
}

/// Re-level every handle of `slot`.
fn service(slot: usize, now: u64, verify: bool) -> Option<u64> {
    let mut earliest = None;
    for e in TABLE.e.iter() {
        if e.state.load(Acquire) != ACTIVE {
            continue;
        }
        e.refs.fetch_add(1, Acquire);
        if e.state.load(Acquire) != ACTIVE {
            release(e);
            continue;
        }
        let mut notes = Notes::default();
        lock(e);
        let d = unsafe { data(e) };
        let mine = d.slot == slot;
        let fd = d.guest_fd;
        if mine {
            refresh(d, now, &mut notes);
            let snap = observe(d, &mut notes);
            // Self-heal a readiness byte someone consumed behind our back (a raw read).
            if verify && d.ready && d.wake_fd >= 0 && matches!(sys::fionread(d.drain_fd), Ok(0)) {
                d.ready = false;
            }
            earliest = earlier(earliest, sync_ready(d, &snap, sys::mono_ns()));
        }
        unlock(e);
        release(e);
        if mine {
            emit_notes(slot, fd, &notes);
        }
    }
    earliest
}

extern "C" fn helper_main(arg: *mut c_void) -> *mut c_void {
    let slot = arg as usize;
    sys::set_thread_name(b"bannerin-wake\0");
    let rt = &SLOTS.s[slot];
    let mut last_slow = 0u64;
    let mut first = true;
    loop {
        let now = sys::mono_ns();
        let slow = first || now.wrapping_sub(last_slow) >= SLOW_NS;
        if slow {
            slow_check(slot, rt);
            last_slow = now;
            first = false;
        }
        if rt.handles.load(Acquire) == 0 {
            let k = rt.kick.load(Acquire);
            if rt.handles.load(Acquire) == 0 {
                let _ = sys::futex_wait(&rt.kick, k, Some(SLOW_NS), false);
            }
            continue;
        }
        let target = unsafe { (*rt.hm.get()).shared() }
            .map(|sh| (&sh.header.bell as *const AtomicU32, sh.header.bell.load(Acquire)));
        // Wake on the bell, at the earliest paced frame falling due, or after the fallback.
        let earliest = service(slot, now, slow);
        let r = match target {
            Some((w, v)) => sys::futex_wait(w, v, Some(wait_ns(FALLBACK_NS, earliest)), true),
            None => {
                let k = rt.kick.load(Acquire);
                sys::futex_wait(&rt.kick, k, Some(wait_ns(SLOW_NS / 2, earliest)), false)
            }
        };
        match r {
            Err(sys::ETIMEDOUT) => STATS.helper_timeouts.fetch_add(1, Relaxed),
            _ => STATS.helper_wakes.fetch_add(1, Relaxed),
        };
    }
}

// ---------------------------------------------------------------------------------------------
// fork

/// Give a handle inherited by a forked child its own readiness socket on the same fd number.
unsafe fn rebind_child(d: &mut Data) -> bool {
    d.frame.len = 0;
    d.fpos = 0;
    d.cursor.force_full();
    d.ready = false;
    let sv = match sys::socketpair() {
        Ok(sv) => sv,
        Err(_) => return false,
    };
    let nonblock = matches!(sys::fcntl(d.guest_fd, sys::F_GETFL, 0), Ok(f) if f & sys::O_NONBLOCK as usize != 0);
    let cloexec = matches!(sys::fcntl(d.guest_fd, sys::F_GETFD, 0), Ok(f) if f & sys::FD_CLOEXEC != 0);
    if d.guest_fd < 0 || sys::dup3(sv[0], d.guest_fd, if cloexec { sys::O_CLOEXEC } else { 0 }).is_err() {
        sys::close(sv[0]);
        sys::close(sv[1]);
        return false;
    }
    if nonblock {
        if let Ok(f) = sys::fcntl(d.guest_fd, sys::F_GETFL, 0) {
            let _ = sys::fcntl(d.guest_fd, sys::F_SETFL, f | sys::O_NONBLOCK as usize);
        }
    }
    // The inherited private ends belong to the parent's socket; holding them here would keep the
    // parent's peer alive (no POLLHUP on unplug).
    sys::close(d.drain_fd);
    sys::close(d.wake_fd);
    d.drain_fd = sv[0]; // same socket as the guest fd now
    d.wake_fd = sv[1];
    if sys::send_byte(sv[1]).is_ok() {
        d.ready = true; // the child owes its reader a full-state frame
    }
    if d.removed {
        sys::close(sv[1]);
        d.wake_fd = -1;
    }
    true
}

unsafe extern "C" fn atfork_child() {
    if CONFIG.state.load(Relaxed) == 1 {
        CONFIG.state.store(0, Relaxed);
    }
    for rt in SLOTS.s.iter() {
        rt.helper.store(0, Relaxed);
        rt.retry_at.store(0, Relaxed);
        rt.dead_votes.store(0, Relaxed);
        // The helper's mapping and fd belonged to the parent's helper thread, which may have been
        // in the middle of replacing them; the child's helper opens its own on its first check.
        // (Its fd shares the parent's open file description, which must not be probed from here.)
        let hm = &mut *rt.hm.get();
        hm.release();
    }
    let mut counts = [0u32; MAX_SLOTS];
    let mut rebound = 0u64;
    for e in TABLE.e.iter() {
        e.lock.store(0, Relaxed);
        let d = &mut *e.data.get();
        match e.state.load(Relaxed) {
            ACTIVE => {
                e.refs.store(1, Relaxed);
                if rebind_child(d) {
                    rebound += 1;
                }
                if d.slot < MAX_SLOTS {
                    counts[d.slot] += 1;
                }
            }
            CLOSED | FREEING => {
                free_data(d);
                e.refs.store(0, Relaxed);
                e.state.store(FREE, Relaxed);
            }
            ALLOC => {
                e.refs.store(0, Relaxed);
                e.state.store(FREE, Relaxed);
            }
            _ => e.refs.store(0, Relaxed),
        }
    }
    for (s, rt) in SLOTS.s.iter().enumerate() {
        rt.handles.store(counts[s], Relaxed);
    }
    STATS.fork_rebinds.fetch_add(rebound, Relaxed);
}

fn register_atfork() {
    if ATFORK.load(Acquire) == 0 && ATFORK.compare_exchange(0, 1, AcqRel, Relaxed).is_ok() {
        sys::register_atfork_child(atfork_child);
    }
}

// ---------------------------------------------------------------------------------------------
// public API (wrapped 1:1 by the C ABI in the guest crate)

/// Attach a new handle to `slot`. Returns (guest fd, handle).
pub fn attach(slot: i32, open_flags: i32) -> Result<(i32, u32), i32> {
    let mask = init();
    if !(0..MAX_SLOTS as i32).contains(&slot) || mask & (1 << slot) == 0 {
        return Err(sys::ENODEV);
    }
    let slot = slot as usize;
    register_atfork();
    let (idx, e) = alloc().ok_or(sys::EMFILE)?;
    let sv = match sys::socketpair() {
        Ok(sv) => sv,
        Err(err) => {
            e.state.store(FREE, Release);
            return Err(err);
        }
    };
    let (guest, wake) = (sv[0], sv[1]);
    let drain = match sys::fcntl(guest, sys::F_DUPFD_CLOEXEC, 0) {
        Ok(fd) => fd as i32,
        Err(err) => {
            sys::close(guest);
            sys::close(wake);
            e.state.store(FREE, Release);
            return Err(err);
        }
    };
    if open_flags & sys::O_NONBLOCK != 0 {
        if let Ok(f) = sys::fcntl(guest, sys::F_GETFL, 0) {
            let _ = sys::fcntl(guest, sys::F_SETFL, f | sys::O_NONBLOCK as usize);
        }
    }
    if open_flags & sys::O_CLOEXEC == 0 {
        let _ = sys::fcntl(guest, sys::F_SETFD, 0);
    }
    let rt = &SLOTS.s[slot];
    let now = sys::mono_ns();
    let mut notes = Notes::default();
    let d = unsafe { data(e) };
    *d = Data::EMPTY;
    d.slot = slot;
    d.guest_fd = guest;
    d.drain_fd = drain;
    d.wake_fd = wake;
    d.map_gen = rt.file_gen.load(Acquire);
    d.last_retry = now;
    match map_slot(slot, false) {
        Ok(m) => install(d, m, true),
        Err(_) => notes.orphaned = true,
    }
    d.cursor = Cursor::new(); // the first thing this handle delivers is the full state
    if sys::send_byte(wake).is_ok() {
        d.ready = true;
    }
    let gen = next_gen(e);
    e.gen.store(gen, Relaxed);
    e.refs.fetch_add(1, AcqRel); // the open reference, dropped by detach
    e.state.store(ACTIVE, Release);
    rt.handles.fetch_add(1, AcqRel);
    rt.kick.fetch_add(1, Release);
    sys::futex_wake(&rt.kick, 1, false);
    ensure_helper(slot);
    STATS.attaches.fetch_add(1, Relaxed);
    log_config_once();
    if LOGGER.load(Relaxed) != 0 {
        let mut m = Msg::new();
        m.s(b"bannerin: slot ").u(slot as u64).s(b" fd ").i(guest as i64);
        if notes.orphaned {
            m.s(b": attached, no usable backing yet (neutral pad)");
        } else {
            m.s(b": attached");
        }
        log(0, &mut m);
    }
    Ok((guest, (gen << 8) | idx as u32))
}

/// Release a handle and close its guest fd.
pub fn detach(h: u32) -> Result<(), i32> {
    let e = acquire(h).ok_or(sys::EBADF)?;
    if e.state.compare_exchange(ACTIVE, CLOSED, AcqRel, Acquire).is_err() {
        release(e);
        return Err(sys::EBADF);
    }
    lock(e);
    let d = unsafe { data(e) };
    let fd = d.guest_fd;
    d.guest_fd = -1;
    let slot = d.slot;
    unlock(e);
    SLOTS.s[slot].handles.fetch_sub(1, AcqRel);
    sys::close(fd);
    STATS.detaches.fetch_add(1, Relaxed);
    release(e); // the open reference
    release(e); // ours
    Ok(())
}

fn with_handle<R>(h: u32, f: impl FnOnce(&mut Data, &mut Notes) -> R) -> Result<R, i32> {
    let e = acquire(h).ok_or(sys::EBADF)?;
    let mut notes = Notes::default();
    lock(e);
    let d = unsafe { data(e) };
    let slot = d.slot;
    let fd = d.guest_fd;
    refresh(d, sys::mono_ns(), &mut notes);
    let r = f(d, &mut notes);
    unlock(e);
    release(e);
    ensure_helper(slot);
    emit_notes(slot, fd, &notes);
    Ok(r)
}

pub fn fd(h: u32) -> Result<i32, i32> {
    with_handle(h, |d, _| d.guest_fd)
}

pub fn slot(h: u32) -> Result<i32, i32> {
    with_handle(h, |d, _| d.slot as i32)
}

pub fn is_removed(h: u32) -> Result<bool, i32> {
    with_handle(h, |d, notes| {
        let snap = observe(d, notes);
        sync_ready(d, &snap, sys::mono_ns());
        d.removed
    })
}

pub fn has_pending(h: u32) -> Result<bool, i32> {
    with_handle(h, |d, notes| {
        let snap = observe(d, notes);
        let now = sys::mono_ns();
        sync_ready(d, &snap, now);
        if d.removed {
            Err(sys::ENODEV)
        } else {
            // A stick change held by pacing is not pending yet.
            Ok(due(d, &snap, now) == Due::Now)
        }
    })?
}

/// What `poll` should report for this handle, evdev-style.
pub fn poll_revents(h: u32, events: i16) -> i16 {
    let rd = events & (sys::POLLIN | sys::POLLRDNORM);
    let wr = events & (sys::POLLOUT | sys::POLLWRNORM);
    match has_pending(h) {
        Ok(true) => rd | wr,
        Ok(false) => wr,
        Err(sys::ENODEV) => sys::POLLHUP | sys::POLLERR,
        Err(_) => sys::POLLNVAL,
    }
}

/// Read whole events into `buf` (`len` bytes). Blocking unless `nonblocking`.
///
/// # Safety
/// `buf` must be valid for `len` bytes of writes.
pub unsafe fn read(h: u32, buf: *mut u8, len: usize, nonblocking: bool) -> Result<usize, i32> {
    if len < EVENT_SIZE {
        return Err(sys::EINVAL);
    }
    if buf.is_null() {
        return Err(sys::EFAULT);
    }
    let max = len / EVENT_SIZE;
    let e = acquire(h).ok_or(sys::EBADF)?;
    let result = loop {
        if e.state.load(Acquire) != ACTIVE {
            break Err(sys::EBADF);
        }
        let mut notes = Notes::default();
        let mut fs = FrameStats::default();
        lock(e);
        let d = data(e);
        let slot = d.slot;
        let fd = d.guest_fd;
        refresh(d, sys::mono_ns(), &mut notes);
        let (word, val, shared_word) = wait_target(d);
        let snap = observe(d, &mut notes);
        let now = sys::mono_ns();
        let outcome = if d.removed {
            Err(sys::ENODEV)
        } else {
            let n = deliver(d, &snap, buf, max, &mut fs, now);
            account(d, &fs, &mut notes);
            Ok(n)
        };
        let held = sync_ready(d, &snap, now);
        unlock(e);
        ensure_helper(slot);
        emit_notes(slot, fd, &notes);
        match outcome {
            Err(err) => break Err(err),
            Ok(n) if n > 0 => break Ok(n * EVENT_SIZE),
            Ok(_) => {}
        }
        if nonblocking {
            break Err(sys::EAGAIN);
        }
        // Sleep until a publish, the moment a held stick frame falls due, or the fallback.
        if let Err(sys::EINTR) = sys::futex_wait(word, val, Some(wait_ns(FALLBACK_NS, held)), shared_word) {
            break Err(sys::EINTR);
        }
    };
    release(e);
    result
}

/// The slot's current state: 11 buttons (0/1) and 8 axes, canonical order, never torn.
pub fn current_state(h: u32, buttons: &mut [i32; BUTTONS], axes: &mut [i32; AXES]) -> Result<(), i32> {
    with_handle(h, |d, notes| {
        let s = observe(d, notes);
        sync_ready(d, &s, sys::mono_ns());
        if d.removed {
            return Err(sys::ENODEV);
        }
        let b = s.buttons();
        for i in 0..BUTTONS {
            buttons[i] = ((b >> i) & 1) as i32;
        }
        for i in 0..AXES {
            axes[i] = clamp_axis(i, s.axes[i]);
        }
        Ok(())
    })?
}

/// Counters from the slot's current file.
pub fn slot_stats(slot: i32) -> Result<SlotStatsView, i32> {
    if !(0..MAX_SLOTS as i32).contains(&slot) || init() & (1 << slot) == 0 {
        return Err(sys::ENODEV);
    }
    let mut m = map_slot(slot as usize, false)?;
    let mut v = SlotStatsView::default();
    if let Some(sh) = m.shared() {
        let h = &sh.header;
        v.instance = h.instance.load(Relaxed);
        v.publishes = h.w_publishes.load(Relaxed);
        v.wake_calls = h.w_wake_calls.load(Relaxed);
        v.woken = h.w_woken.load(Relaxed);
        v.unplugs = h.w_unplugs.load(Relaxed);
        v.edges = h.w_edges.load(Relaxed);
        v.reader_attaches = h.r_attaches.load(Relaxed);
        v.reader_reattaches = h.r_reattaches.load(Relaxed);
        v.reader_taps = h.r_taps.load(Relaxed);
        v.reader_coalesced = h.r_coalesced.load(Relaxed);
        v.reader_resyncs = h.r_resyncs.load(Relaxed);
        v.reader_frames = h.r_frames.load(Relaxed);
        v.reader_events = h.r_events.load(Relaxed);
        v.reader_removals = h.r_removals.load(Relaxed);
        v.writer_pid = h.writer_pid.load(Relaxed);
        v.retired = h.retired.load(Relaxed);
        v.reader_paced_frames = h.r_paced_frames.load(Relaxed);
        v.reader_merged_axis_publishes = h.r_merged_axis.load(Relaxed);
    }
    m.release();
    Ok(v)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn env_grammar() {
        let mut p = [[0u8; PATH_CAP]; MAX_SLOTS];
        let m = parse_paths(b"0=/a/pad0;1=/b/pad1; 3 = /d/pad3 ;x=/bad;7=/bad;2=;0=/dup", &mut p);
        assert_eq!(m, 0b1011);
        assert_eq!(&p[0][..9], b"/a/pad0\0\0");
        assert_eq!(&p[1][..8], b"/b/pad1\0");
        assert_eq!(&p[3][..8], b"/d/pad3\0");
        let mut q = [[0u8; PATH_CAP]; MAX_SLOTS];
        assert_eq!(parse_paths(b"", &mut q), 0);
        assert_eq!(parse_paths(b";;;", &mut q), 0);
        assert_eq!(parse_paths(b"2=/only", &mut q), 0b100);
    }

    #[test]
    fn axis_hz_parsing() {
        assert_eq!(parse_axis_hz(None), 125);
        assert_eq!(parse_axis_hz(Some(b"")), 125);
        assert_eq!(parse_axis_hz(Some(b"abc")), 125);
        assert_eq!(parse_axis_hz(Some(b"-5")), 125);
        assert_eq!(parse_axis_hz(Some(b"0")), 0);
        assert_eq!(parse_axis_hz(Some(b" 60 ")), 60);
        assert_eq!(parse_axis_hz(Some(b"250")), 250);
        assert_eq!(parse_axis_hz(Some(b"10")), 30);
        assert_eq!(parse_axis_hz(Some(b"5000")), 1000);
        assert_eq!(parse_axis_hz(Some(b"99999999999")), 125);
    }

    #[test]
    fn handle_encoding_rejects_stale_and_garbage() {
        assert!(acquire(0).is_none());
        assert!(acquire(0xff).is_none());
        assert!(acquire((5 << 8) | 200).is_none());
        assert!(acquire((5 << 8) | 3).is_none());
    }
}

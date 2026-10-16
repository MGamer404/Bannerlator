//! End-to-end scenarios through the C ABI. Every scenario runs in forked child processes: the
//! guest runtime is process-global (it reads FAKE_EVDEV_MEMFD_PATHS once, owns helper threads and
//! a fork handler), so each scenario gets a fresh process, and the multi-process ones (writer and
//! reader in different processes, a reader that forks, a writer that is killed) are real.
//! Run with `--test-threads=1`.

use super::*;
use bannerin_core::abi::*;
use bannerin_core::pad::PadInput;
use bannerin_core::sys::{self, PollFd, POLLERR, POLLHUP, POLLIN};
use bannerin_core::writer::SlotWriter;
use std::path::PathBuf;
use std::sync::atomic::AtomicU64;
use std::sync::atomic::Ordering::{Acquire, Relaxed, Release, SeqCst};
use std::time::{Duration, Instant};

extern "C" {
    fn fork() -> i32;
    fn waitpid(pid: i32, status: *mut i32, options: i32) -> i32;
    fn _exit(code: i32) -> !;
    fn kill(pid: i32, sig: i32) -> i32;
}

const SIGKILL: i32 = 9;

fn say(msg: &str) {
    let _ = sys::write(2, msg.as_bytes());
}

/// Run `f` in a forked child; returns the child's pid. The child never returns into the harness.
fn spawn<F: FnOnce() -> Result<(), String>>(name: &'static str, f: F) -> i32 {
    let pid = unsafe { fork() };
    assert!(pid >= 0, "fork failed");
    if pid == 0 {
        std::panic::set_hook(Box::new(move |info| say(&format!("[{}] panic: {}\n", name, info))));
        let code = match std::panic::catch_unwind(std::panic::AssertUnwindSafe(f)) {
            Ok(Ok(())) => 0,
            Ok(Err(m)) => {
                say(&format!("[{}] FAILED: {}\n", name, m));
                1
            }
            Err(_) => 2,
        };
        unsafe { _exit(code) }
    }
    pid
}

fn wait_pid(pid: i32) -> i32 {
    let mut st = 0i32;
    unsafe { waitpid(pid, &mut st, 0) };
    if st & 0x7f == 0 {
        (st >> 8) & 0xff
    } else {
        128 + (st & 0x7f)
    }
}

fn run<F: FnOnce() -> Result<(), String>>(name: &'static str, f: F) {
    let pid = spawn(name, f);
    assert_eq!(wait_pid(pid), 0, "scenario '{}' failed, see stderr", name);
}

macro_rules! ensure {
    ($cond:expr, $($fmt:tt)+) => {
        if !$cond {
            return Err(format!($($fmt)+));
        }
    };
}

/// Cross-process coordination words (MAP_SHARED | MAP_ANONYMOUS, inherited across fork).
struct Shm(*mut AtomicU64);
impl Shm {
    fn new() -> Shm {
        let p = sys::mmap(4096, sys::PROT_READ | sys::PROT_WRITE, sys::MAP_SHARED | sys::MAP_ANONYMOUS, -1).unwrap();
        Shm(p as *mut AtomicU64)
    }
    fn at(&self, i: usize) -> &AtomicU64 {
        assert!(i < 512);
        unsafe { &*self.0.add(i) }
    }
    fn get(&self, i: usize) -> u64 {
        self.at(i).load(SeqCst)
    }
    fn set(&self, i: usize, v: u64) {
        self.at(i).store(v, SeqCst)
    }
    fn wait(&self, i: usize, at_least: u64, secs: u64) -> Result<(), String> {
        let end = Instant::now() + Duration::from_secs(secs);
        while self.get(i) < at_least {
            if Instant::now() > end {
                return Err(format!("timed out waiting for shm[{}] >= {} (is {})", i, at_least, self.get(i)));
            }
            std::thread::yield_now();
        }
        Ok(())
    }
}
unsafe impl Send for Shm {}
unsafe impl Sync for Shm {}

struct Scene {
    dir: PathBuf,
    env: String,
}

impl Scene {
    fn new(tag: &str) -> Scene {
        use std::sync::atomic::AtomicU32;
        static N: AtomicU32 = AtomicU32::new(0);
        let dir = std::env::temp_dir().join(format!(
            "bannerin-g-{}-{}-{}",
            tag,
            std::process::id(),
            N.fetch_add(1, Relaxed)
        ));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join("bannerin")).unwrap();
        let env = (0..4)
            .map(|s| format!("{}={}/bannerin/pad{}", s, dir.display(), s))
            .collect::<Vec<_>>()
            .join(";");
        Scene { dir, env }
    }
    fn backing(&self) -> String {
        format!("{}/bannerin", self.dir.display())
    }
    fn pad(&self, slot: u32) -> PathBuf {
        self.dir.join("bannerin").join(format!("pad{}", slot))
    }
    /// Inside a child, before the first call into the library.
    fn enter(&self) {
        std::env::set_var("FAKE_EVDEV_MEMFD_PATHS", &self.env);
    }
    fn writer(&self, slot: u32) -> SlotWriter {
        let mut w = SlotWriter::new(slot);
        w.prepare(self.backing().as_bytes()).expect("prepare");
        w
    }
}

fn pad(buttons: u16) -> PadInput {
    PadInput { buttons, axes: [0; AXES] }
}

fn pad_axes(buttons: u16, axes: [i32; AXES]) -> PadInput {
    PadInput { buttons, axes }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct E {
    t: u16,
    c: u16,
    v: i32,
}

fn parse(buf: &[u8]) -> Vec<E> {
    buf.chunks_exact(EVENT_SIZE)
        .map(|b| E {
            t: u16::from_le_bytes([b[16], b[17]]),
            c: u16::from_le_bytes([b[18], b[19]]),
            v: i32::from_le_bytes([b[20], b[21], b[22], b[23]]),
        })
        .collect()
}

fn key(f: &[E], code: u16) -> Option<i32> {
    f.iter().find(|e| e.t == EV_KEY && e.c == code).map(|e| e.v)
}

fn abs(f: &[E], code: u16) -> Option<i32> {
    f.iter().find(|e| e.t == EV_ABS && e.c == code).map(|e| e.v)
}

fn is_full(f: &[E]) -> bool {
    f.len() == 20
        && (0..BUTTONS).all(|b| f[b].t == EV_KEY && f[b].c == BUTTON_CODES[b])
        && (0..AXES).all(|i| f[BUTTONS + i].t == EV_ABS && f[BUTTONS + i].c == AXIS_CODES[i])
        && f[19] == E { t: EV_SYN, c: SYN_REPORT, v: 0 }
}

fn neutral_full(f: &[E]) -> bool {
    is_full(f) && f[..19].iter().all(|e| e.v == 0)
}

/// A reader over one handle: buffers events and hands out whole frames.
struct Rd {
    h: u32,
    fd: i32,
    pending: Vec<E>,
}

impl Rd {
    fn attach(slot: i32, flags: i32) -> Result<Rd, String> {
        let mut h = 0u32;
        let fd = unsafe { bannerin_attach(slot, flags, &mut h) };
        ensure!(fd >= 0, "attach({}) failed: {}", slot, fd);
        ensure!(bannerin_fd(h) == fd, "bannerin_fd mismatch");
        Ok(Rd { h, fd, pending: Vec::new() })
    }

    /// One nonblocking read; Err(errno).
    fn read_nb(&mut self) -> Result<usize, i32> {
        let mut buf = [0u8; EVENT_SIZE * 64];
        let r = unsafe { bannerin_read(self.h, buf.as_mut_ptr() as *mut c_void, buf.len(), 1) };
        if r < 0 {
            return Err((-r) as i32);
        }
        let ev = parse(&buf[..r as usize]);
        let n = ev.len();
        self.pending.extend(ev);
        Ok(n)
    }

    fn poll(&self, ms: i32) -> i16 {
        let mut p = PollFd { fd: self.fd, events: POLLIN, revents: 0 };
        match sys::poll(&mut p, 1, ms) {
            Ok(0) => 0,
            Ok(_) => p.revents,
            Err(_) => 0,
        }
    }

    fn take_frame(&mut self) -> Option<Vec<E>> {
        let end = self.pending.iter().position(|e| e.t == EV_SYN && e.c == SYN_REPORT)?;
        Some(self.pending.drain(..=end).collect())
    }

    /// The next whole frame, waiting (natively, with poll) up to `ms`.
    fn frame(&mut self, ms: u64) -> Result<Vec<E>, String> {
        let end = Instant::now() + Duration::from_millis(ms);
        loop {
            if let Some(f) = self.take_frame() {
                return Ok(f);
            }
            let left = end.saturating_duration_since(Instant::now());
            if left.is_zero() {
                return Err(format!("no frame within {} ms (partial: {:?})", ms, self.pending));
            }
            self.poll(left.as_millis().clamp(1, 1000) as i32);
            match self.read_nb() {
                Ok(_) | Err(sys::EAGAIN) => {}
                Err(e) => return Err(format!("read failed: errno {}", e)),
            }
        }
    }

    /// Wait for a frame satisfying `pred`, skipping others.
    fn frame_where(&mut self, ms: u64, what: &str, pred: impl Fn(&[E]) -> bool) -> Result<Vec<E>, String> {
        let end = Instant::now() + Duration::from_millis(ms);
        loop {
            let left = end.saturating_duration_since(Instant::now());
            if left.is_zero() {
                return Err(format!("never saw {}", what));
            }
            match self.frame(left.as_millis().max(1) as u64) {
                Ok(f) if pred(&f) => return Ok(f),
                Ok(_) => {}
                Err(e) => return Err(format!("waiting for {}: {}", what, e)),
            }
        }
    }

    /// Everything deliverable right now.
    fn drain(&mut self) -> Result<Vec<Vec<E>>, String> {
        loop {
            match self.read_nb() {
                Ok(_) => {}
                Err(sys::EAGAIN) => break,
                Err(e) => return Err(format!("read failed: errno {}", e)),
            }
        }
        let mut out = Vec::new();
        while let Some(f) = self.take_frame() {
            out.push(f);
        }
        ensure!(self.pending.is_empty(), "torn frame left over: {:?}", self.pending);
        Ok(out)
    }

    fn state(&self) -> Result<BannerinState, i32> {
        let mut s = BannerinState { buttons: [0; BUTTONS], axes: [0; AXES] };
        let r = unsafe { bannerin_current_state(self.h, &mut s) };
        if r < 0 {
            Err(-r)
        } else {
            Ok(s)
        }
    }
}

/// Tracks one button's edges across frames: strict alternation, MSC_SCAN before each EV_KEY,
/// never two edges of the same button inside one frame.
struct Taps {
    code: u16,
    down: bool,
    presses: u64,
    releases: u64,
}

impl Taps {
    fn new(code: u16) -> Taps {
        Taps { code, down: false, presses: 0, releases: 0 }
    }
    fn feed(&mut self, f: &[E]) -> Result<(), String> {
        let mut edges = 0;
        for (i, e) in f.iter().enumerate() {
            if e.t != EV_KEY || e.c != self.code {
                continue;
            }
            if is_full(f) {
                self.down = e.v == 1;
                continue;
            }
            edges += 1;
            ensure!(edges == 1, "two edges of {:#x} in one frame: {:?}", self.code, f);
            ensure!(
                i > 0 && f[i - 1] == E { t: EV_MSC, c: MSC_SCAN, v: self.code as i32 },
                "EV_KEY without MSC_SCAN before it: {:?}",
                f
            );
            if e.v == 1 {
                ensure!(!self.down, "press while already down (press #{})", self.presses + 1);
                self.down = true;
                self.presses += 1;
            } else {
                ensure!(self.down, "release while already up (release #{})", self.releases + 1);
                self.down = false;
                self.releases += 1;
            }
        }
        Ok(())
    }
}

fn stats() -> StatsView {
    let mut s = StatsView::default();
    unsafe { bannerin_get_stats(&mut s, core::mem::size_of::<StatsView>()) };
    s
}

// ---------------------------------------------------------------------------------------------

#[test]
fn unknown_slots_bad_handles_and_small_buffers() {
    let sc = Scene::new("args");
    run("args", || {
        std::env::set_var("FAKE_EVDEV_MEMFD_PATHS", format!("0={0}/pad0;2={0}/pad2", sc.backing()));
        ensure!(bannerin_init() == 0b101, "init mask {}", bannerin_init());
        let mut h = 0u32;
        for s in [1, 3, 4, -1, 99] {
            let r = unsafe { bannerin_attach(s, 0, &mut h) };
            ensure!(r == -sys::ENODEV, "slot {} gave {}", s, r);
        }
        ensure!(unsafe { bannerin_attach(0, 0, core::ptr::null_mut()) } == -sys::EINVAL, "null out handle");
        let mut buf = [0u8; 64];
        ensure!(unsafe { bannerin_read(12345, buf.as_mut_ptr() as *mut c_void, 64, 1) } == -(sys::EBADF as isize), "bad handle read");
        ensure!(bannerin_detach(0) == -sys::EBADF, "detach 0");
        let mut r = Rd::attach(0, sys::O_NONBLOCK)?;
        ensure!(bannerin_slot(r.h) == 0, "slot");
        let n = unsafe { bannerin_read(r.h, buf.as_mut_ptr() as *mut c_void, 23, 1) };
        ensure!(n == -(sys::EINVAL as isize), "short buffer gave {}", n);
        // one event at a time: the full-state frame continues across reads
        let mut evs = Vec::new();
        for _ in 0..20 {
            let n = unsafe { bannerin_read(r.h, buf.as_mut_ptr() as *mut c_void, 47, 1) };
            ensure!(n == 24, "a 47-byte buffer takes exactly one event, got {}", n);
            evs.extend(parse(&buf[..24]));
        }
        ensure!(neutral_full(&evs), "split full frame {:?}", evs);
        ensure!(r.read_nb() == Err(sys::EAGAIN), "nothing more");
        let h = r.h;
        ensure!(bannerin_detach(h) == 0, "detach");
        ensure!(bannerin_detach(h) == -sys::EBADF, "double detach");
        ensure!(bannerin_fd(h) == -sys::EBADF, "fd after detach");
        ensure!(r.read_nb() == Err(sys::EBADF), "read after detach");
        Ok(())
    });
}

#[test]
fn late_reader_gets_full_current_state_first() {
    let sc = Scene::new("late");
    run("late", || {
        sc.enter();
        let mut w = sc.writer(0);
        w.open();
        w.set_pad(pad_axes(1 << B_A, [20000, -300, 0, 0, 128, 0, -1, 0]));
        w.set_overlay(1 << B_MODE);
        let mut r = Rd::attach(0, sys::O_NONBLOCK | sys::O_CLOEXEC)?;
        ensure!(r.poll(0) & POLLIN != 0, "a new handle is readable at once");
        let f = r.frame(1000)?;
        ensure!(is_full(&f), "first frame must be full state: {:?}", f);
        ensure!(key(&f, BTN_A) == Some(1) && key(&f, BTN_MODE) == Some(1) && key(&f, BTN_B) == Some(0), "{:?}", f);
        ensure!(abs(&f, ABS_X) == Some(20000) && abs(&f, ABS_Y) == Some(-300), "{:?}", f);
        ensure!(abs(&f, ABS_BRAKE) == Some(128) && abs(&f, ABS_HAT0X) == Some(-1), "{:?}", f);
        ensure!(r.drain()?.is_empty(), "nothing after the full state");
        ensure!(r.poll(0) == 0, "not readable once drained");
        ensure!(bannerin_has_pending(r.h) == 0, "has_pending");
        let s = r.state().map_err(|e| format!("state {}", e))?;
        ensure!(s.buttons[B_A] == 1 && s.buttons[B_MODE] == 1 && s.axes[A_LX] == 20000, "state");
        let fdflags = sys::fcntl(r.fd, sys::F_GETFD, 0).unwrap();
        ensure!(fdflags & sys::FD_CLOEXEC != 0, "O_CLOEXEC honoured");
        let fl = sys::fcntl(r.fd, sys::F_GETFL, 0).unwrap();
        ensure!(fl & sys::O_NONBLOCK as usize != 0, "O_NONBLOCK honoured");
        let r2 = Rd::attach(0, 0)?;
        let fdflags = sys::fcntl(r2.fd, sys::F_GETFD, 0).unwrap();
        ensure!(fdflags & sys::FD_CLOEXEC == 0, "no O_CLOEXEC unless asked");
        Ok(())
    });
}

#[test]
fn reader_before_writer_and_first_open_is_not_a_removal() {
    let sc = Scene::new("before");
    run("before", || {
        sc.enter();
        let mut r = Rd::attach(0, sys::O_NONBLOCK)?; // nothing exists yet
        let f = r.frame(1000)?;
        ensure!(neutral_full(&f), "neutral pad while there is no backing: {:?}", f);
        ensure!(r.drain()?.is_empty(), "quiet");
        let mut w = sc.writer(0); // backing appears, never opened
        r.frame_where(3000, "re-attach full state", |f| is_full(f))?;
        let mut r2 = Rd::attach(0, sys::O_NONBLOCK)?;
        ensure!(neutral_full(&r2.frame(1000)?), "second reader neutral");
        w.open();
        w.set_pad(pad(1 << B_B));
        for rd in [&mut r, &mut r2] {
            let f = rd.frame_where(2000, "B press", |f| key(f, BTN_B) == Some(1))?;
            ensure!(!is_full(&f), "a change frame, not a resync: {:?}", f);
            ensure!(bannerin_is_removed(rd.h) == 0, "first open must not look like an unplug");
        }
        Ok(())
    });
}

#[test]
fn taps_between_reads_keep_order_across_buttons() {
    let sc = Scene::new("qam");
    run("qam", || {
        sc.enter();
        let mut w = sc.writer(0);
        w.open();
        let mut r = Rd::attach(0, sys::O_NONBLOCK)?;
        r.frame(1000)?;
        // Quick Access Menu: Guide down, A down, A up, Guide up, all before the reader looks.
        w.set_overlay(1 << B_MODE);
        w.set_overlay((1 << B_MODE) | (1 << B_A));
        w.set_overlay(1 << B_MODE);
        w.set_overlay(0);
        // and a plain tap of X
        w.set_pad(pad(1 << B_X));
        w.set_pad(pad(0));
        std::thread::sleep(Duration::from_millis(20));
        let frames = r.drain()?;
        let seq: Vec<(u16, i32)> = frames
            .iter()
            .map(|f| {
                let k: Vec<_> = f.iter().filter(|e| e.t == EV_KEY).collect();
                assert_eq!(k.len(), 1, "one edge per frame here: {:?}", f);
                (k[0].c, k[0].v)
            })
            .collect();
        ensure!(
            seq == vec![(BTN_MODE, 1), (BTN_A, 1), (BTN_A, 0), (BTN_MODE, 0), (BTN_X, 1), (BTN_X, 0)],
            "edges out of order: {:?}",
            seq
        );
        // the overlay never releases what the pad holds
        w.set_pad(pad(1 << B_A));
        w.set_overlay(1 << B_A);
        w.set_overlay(0);
        std::thread::sleep(Duration::from_millis(20));
        let frames = r.drain()?;
        let a: Vec<i32> = frames.iter().filter_map(|f| key(f, BTN_A)).collect();
        ensure!(a == vec![1], "clearing the overlay released a held button: {:?}", a);
        Ok(())
    });
}

#[test]
fn two_process_100k_taps_zero_loss() {
    const N: u64 = 100_000;
    const WINDOW: u64 = 32;
    let sc = Scene::new("100k");
    let shm = Shm::new();
    // shm: 0 writer ready, 1 reader ready, 2 taps fully seen by the reader, 3 reader done
    let wpid = spawn("100k-writer", || {
        let mut w = sc.writer(0);
        w.open();
        shm.set(0, 1);
        shm.wait(1, 1, 30)?;
        let end = Instant::now() + Duration::from_secs(120);
        for i in 0..N {
            while i >= shm.get(2) + WINDOW {
                ensure!(Instant::now() < end, "reader stalled at {} of {}", shm.get(2), N);
                std::thread::yield_now();
            }
            w.set_pad(pad(1 << B_A));
            w.set_pad(pad(0));
        }
        shm.wait(3, 1, 120)?;
        Ok(())
    });
    let rpid = spawn("100k-reader", || {
        shm.wait(0, 1, 30)?;
        sc.enter();
        let mut r = Rd::attach(0, sys::O_NONBLOCK)?;
        ensure!(is_full(&r.frame(1000)?), "full state first");
        shm.set(1, 1);
        let mut t = Taps::new(BTN_A);
        while t.releases < N {
            if r.poll(5000) == 0 {
                return Err(format!("no readiness for 5 s at {} presses / {} releases", t.presses, t.releases));
            }
            for f in r.drain()? {
                t.feed(&f)?;
            }
            shm.set(2, t.releases);
        }
        shm.set(3, 1);
        let s = stats();
        ensure!(t.presses == N && t.releases == N, "presses {} releases {}", t.presses, t.releases);
        ensure!(s.coalesced_taps == 0 && s.resyncs == 0, "coalesced {} resyncs {}", s.coalesced_taps, s.resyncs);
        say(&format!(
            "[100k] journal frames {} counter frames {} helper wakes {}\n",
            s.journal_frames, s.counter_frames, s.helper_wakes
        ));
        Ok(())
    });
    let (a, b) = (wait_pid(wpid), wait_pid(rpid));
    assert_eq!((a, b), (0, 0), "writer/reader exit codes");
}

#[test]
fn burst_taps_are_all_delivered_or_accounted_for() {
    const N: u64 = 100_000;
    let sc = Scene::new("burst");
    let shm = Shm::new();
    // shm: 0 writer ready, 1 reader ready, 2 writer finished, 3 reader done
    let wpid = spawn("burst-writer", || {
        let mut w = sc.writer(0);
        w.open();
        shm.set(0, 1);
        shm.wait(1, 1, 30)?;
        for _ in 0..N {
            w.set_pad(pad(1 << B_Y));
            w.set_pad(pad(0));
        }
        w.set_pad(pad_axes(0, [777, 0, 0, 0, 0, 0, 0, 0]));
        shm.set(2, 1);
        shm.wait(3, 1, 120)?;
        Ok(())
    });
    let rpid = spawn("burst-reader", || {
        shm.wait(0, 1, 30)?;
        sc.enter();
        let mut r = Rd::attach(0, sys::O_NONBLOCK)?;
        r.frame(1000)?;
        shm.set(1, 1);
        let mut t = Taps::new(BTN_Y);
        let mut x = 0;
        let end = Instant::now() + Duration::from_secs(120);
        loop {
            ensure!(Instant::now() < end, "never caught up");
            r.poll(100);
            for f in r.drain()? {
                t.feed(&f)?;
                if let Some(v) = abs(&f, ABS_X) {
                    x = v;
                }
            }
            if shm.get(2) == 1 && x == 777 && bannerin_has_pending(r.h) == 0 {
                break;
            }
        }
        shm.set(3, 1);
        let s = stats();
        ensure!(!t.down && t.presses == t.releases, "ends released: {} / {}", t.presses, t.releases);
        ensure!(
            t.presses + s.coalesced_taps == N,
            "taps delivered {} + coalesced {} != {}",
            t.presses,
            s.coalesced_taps,
            N
        );
        ensure!(s.resyncs == 0, "resyncs {}", s.resyncs);
        say(&format!("[burst] delivered {} coalesced {} counter frames {}\n", t.presses, s.coalesced_taps, s.counter_frames));
        Ok(())
    });
    assert_eq!((wait_pid(wpid), wait_pid(rpid)), (0, 0));
}

#[test]
fn stick_spam_never_starves_buttons_and_catches_up_at_once() {
    const PUBLISHES: u64 = 300_000;
    const EVERY: u64 = 1000;
    const TAPS: u64 = PUBLISHES / EVERY;
    let sc = Scene::new("spam");
    let shm = Shm::new();
    // shm: 0 writer ready, 1 reader ready, 2 B taps fully seen, 3 writer finished, 4 reader done
    let wpid = spawn("spam-writer", || {
        let mut w = sc.writer(0);
        w.open();
        shm.set(0, 1);
        shm.wait(1, 1, 30)?;
        let mut seed = 0x1234_5678u64;
        let end = Instant::now() + Duration::from_secs(120);
        let mut taps = 0u64;
        for i in 0..PUBLISHES {
            seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            let v = (seed >> 33) as i32;
            let axes = [v % 32767, -(v % 30000), v % 1000, v % 77, v & 255, (v >> 8) & 255, 0, 0];
            let b = if i % EVERY == 0 {
                while taps >= shm.get(2) + 32 {
                    ensure!(Instant::now() < end, "reader stalled");
                    std::thread::yield_now();
                }
                taps += 1;
                1 << B_B
            } else {
                0
            };
            w.set_pad(pad_axes(b, axes));
        }
        // every tap delivered before the final state, so the catch-up below measures sticks only
        shm.wait(2, TAPS, 120)?;
        w.set_pad(pad_axes(0, [111, -222, 333, -444, 55, 66, 1, -1]));
        shm.set(3, 1);
        shm.wait(4, 1, 120)?;
        Ok(())
    });
    let rpid = spawn("spam-reader", || {
        shm.wait(0, 1, 30)?;
        sc.enter();
        let mut r = Rd::attach(0, sys::O_NONBLOCK)?;
        r.frame(1000)?;
        shm.set(1, 1);
        let mut t = Taps::new(BTN_B);
        let mut axes = [0i32; AXES];
        let (mut reads, mut axis_only) = (0u64, 0u64);
        let absorb = |f: &[E], t: &mut Taps, axes: &mut [i32; AXES], axis_only: &mut u64| -> Result<(), String> {
            t.feed(f)?;
            if !f.iter().any(|e| e.t == EV_KEY) {
                *axis_only += 1;
            }
            for e in f {
                if e.t == EV_ABS {
                    axes[AXIS_CODES.iter().position(|c| *c == e.c).unwrap()] = e.v;
                }
            }
            Ok(())
        };
        let end = Instant::now() + Duration::from_secs(120);
        while shm.get(3) == 0 {
            ensure!(Instant::now() < end, "writer never finished");
            std::thread::sleep(Duration::from_micros(500)); // a deliberately slow reader
            r.poll(10);
            match r.read_nb() {
                Ok(_) => reads += 1,
                Err(sys::EAGAIN) => {}
                Err(e) => return Err(format!("read errno {}", e)),
            }
            while let Some(f) = r.take_frame() {
                absorb(&f, &mut t, &mut axes, &mut axis_only)?;
            }
            shm.set(2, t.releases);
        }
        // The writer is done: whatever is left is at most the one latest stick frame.
        let frames = r.drain()?;
        ensure!(frames.len() <= 1, "catch-up took {} frames", frames.len());
        for f in &frames {
            absorb(f, &mut t, &mut axes, &mut axis_only)?;
        }
        shm.set(4, 1);
        say(&format!("[spam] {} reads, {} stick-only frames for {} publishes\n", reads, axis_only, PUBLISHES));
        ensure!(axis_only <= reads + 2, "sticks were not coalesced: {} frames over {} reads", axis_only, reads);
        ensure!(axes == [111, -222, 333, -444, 55, 66, 1, -1], "final axes {:?}", axes);
        ensure!(t.presses == TAPS && t.releases == TAPS, "B taps {} / {} of {}", t.presses, t.releases, TAPS);
        ensure!(stats().coalesced_taps == 0, "no tap may be coalesced");
        Ok(())
    });
    assert_eq!((wait_pid(wpid), wait_pid(rpid)), (0, 0));
}

#[test]
fn writer_restart_mid_stream_goes_neutral_then_resyncs() {
    let sc = Scene::new("restart");
    let shm = Shm::new();
    // shm: 0 writer A ready, 1 reader saw A, 2 reader saw neutral, 3 reader saw B, 4 reader saw Y tap, 5 B ready
    let wa = spawn("writer-a", || {
        let mut w = sc.writer(0);
        w.open();
        w.set_pad(pad_axes(1 << B_A, [1000, 0, 0, 0, 0, 0, 0, 0]));
        shm.set(0, 1);
        loop {
            std::thread::sleep(Duration::from_millis(100));
        }
    });
    shm.wait(0, 1, 30).unwrap();
    let rp = spawn("restart-reader", || {
        sc.enter();
        let mut r = Rd::attach(0, sys::O_NONBLOCK)?;
        let f = r.frame(1000)?;
        ensure!(key(&f, BTN_A) == Some(1) && abs(&f, ABS_X) == Some(1000), "writer A state: {:?}", f);
        shm.set(1, 1);
        r.frame_where(5000, "neutral after the writer died", neutral_full)?;
        ensure!(bannerin_is_removed(r.h) == 0, "a dead writer is not an unplug");
        shm.set(2, 1);
        let f = r.frame_where(5000, "writer B state", |f| key(f, BTN_B) == Some(1))?;
        ensure!(abs(&f, ABS_X) == Some(-2000) && key(&f, BTN_A) != Some(1), "{:?}", f);
        let st = r.state().map_err(|e| format!("state {}", e))?;
        ensure!(st.buttons[B_B] == 1 && st.buttons[B_A] == 0 && st.axes[A_LX] == -2000, "state after B");
        shm.set(3, 1);
        r.frame_where(3000, "Y press", |f| key(f, BTN_Y) == Some(1) && !is_full(f))?;
        r.frame_where(3000, "Y release", |f| key(f, BTN_Y) == Some(0) && !is_full(f))?;
        ensure!(bannerin_is_removed(r.h) == 0, "never removed");
        let s = stats();
        ensure!(s.writer_dead >= 1 && s.reattaches >= 1, "stats {:?}", s);
        shm.set(4, 1);
        Ok(())
    });
    shm.wait(1, 1, 30).unwrap();
    unsafe { kill(wa, SIGKILL) };
    wait_pid(wa);
    shm.wait(2, 1, 30).unwrap();
    let wb = spawn("writer-b", || {
        let mut w = sc.writer(0);
        w.open();
        w.set_pad(pad_axes(1 << B_B, [-2000, 0, 0, 0, 0, 0, 0, 0]));
        shm.set(5, 1);
        shm.wait(3, 1, 30)?;
        w.set_pad(pad_axes((1 << B_B) | (1 << B_Y), [-2000, 0, 0, 0, 0, 0, 0, 0]));
        w.set_pad(pad_axes(1 << B_B, [-2000, 0, 0, 0, 0, 0, 0, 0]));
        shm.wait(4, 1, 30)?;
        Ok(())
    });
    assert_eq!((wait_pid(rp), wait_pid(wb)), (0, 0));
}

#[test]
fn foreign_truncated_empty_and_missing_backings_stay_present_then_heal() {
    let sc = Scene::new("foreign");
    run("foreign", || {
        std::fs::write(sc.pad(0), b"NOT_OURS".repeat(512)).unwrap();
        let mut trunc = b"BANNERIN".to_vec();
        trunc.resize(100, 0);
        std::fs::write(sc.pad(1), &trunc).unwrap();
        std::fs::write(sc.pad(2), b"").unwrap();
        std::fs::create_dir_all(sc.pad(3)).unwrap();
        sc.enter();
        let mut rs = Vec::new();
        for s in 0..4 {
            let mut r = Rd::attach(s, sys::O_NONBLOCK)?;
            let f = r.frame(1000)?;
            ensure!(neutral_full(&f), "slot {}: neutral full state expected: {:?}", s, f);
            ensure!(r.read_nb() == Err(sys::EAGAIN), "slot {}: quiet", s);
            ensure!(bannerin_is_removed(r.h) == 0 && bannerin_has_pending(r.h) == 0, "slot {}", s);
            let st = r.state().map_err(|e| format!("state {}", e))?;
            ensure!(st.buttons.iter().all(|b| *b == 0) && st.axes.iter().all(|a| *a == 0), "neutral state");
            rs.push(r);
        }
        let mut w0 = sc.writer(0); // replaces the foreign file
        w0.open();
        w0.set_pad(pad(1 << B_A));
        rs[0].frame_where(3000, "slot 0 healed", |f| key(f, BTN_A) == Some(1))?;
        let mut w1 = sc.writer(1); // replaces the truncated one
        w1.open();
        w1.set_pad(pad(1 << B_X));
        rs[1].frame_where(3000, "slot 1 healed", |f| key(f, BTN_X) == Some(1))?;
        for r in &rs {
            ensure!(bannerin_is_removed(r.h) == 0, "never removed");
        }
        Ok(())
    });
}

#[test]
fn forked_reader_gets_its_own_stream() {
    const T: u64 = 500;
    let sc = Scene::new("fork");
    let shm = Shm::new();
    // shm: 0 writer ready, 1 taps requested, 2 taps done, 3 parent progress, 4 child progress, 9 quit
    let wp = spawn("fork-writer", || {
        let mut w = sc.writer(0);
        w.open();
        shm.set(0, 1);
        let mut done = 0;
        let end = Instant::now() + Duration::from_secs(120);
        while shm.get(9) == 0 {
            ensure!(Instant::now() < end, "never told to quit");
            while done < shm.get(1) {
                w.set_pad(pad(1 << B_X));
                w.set_pad(pad(0));
                done += 1;
                shm.set(2, done);
            }
            std::thread::yield_now();
        }
        Ok(())
    });
    let rp = spawn("fork-reader", || {
        shm.wait(0, 1, 30)?;
        sc.enter();
        let mut r = Rd::attach(0, 0)?; // blocking fd: the library never blocks on it internally
        ensure!(is_full(&r.frame(1000)?), "full first");
        let child = unsafe { fork() };
        ensure!(child >= 0, "fork");
        if child == 0 {
            let res = (|| -> Result<(), String> {
                // same fd number, own readiness, own position: a full state comes first
                ensure!(r.poll(2000) & POLLIN != 0, "child fd readable after fork");
                let f = r.frame(2000)?;
                ensure!(is_full(&f), "child starts with a full-state frame: {:?}", f);
                let mut t = Taps::new(BTN_X);
                let end = Instant::now() + Duration::from_secs(60);
                while t.releases < T {
                    ensure!(Instant::now() < end, "child stuck at {}", t.releases);
                    r.poll(100);
                    for f in r.drain()? {
                        t.feed(&f)?;
                    }
                    shm.set(4, t.releases);
                }
                ensure!(t.presses == T, "child presses {}", t.presses);
                Ok(())
            })();
            if let Err(e) = &res {
                say(&format!("[fork-child] FAILED: {}\n", e));
            }
            unsafe { _exit(if res.is_ok() { 0 } else { 1 }) }
        }
        let mut t = Taps::new(BTN_X);
        let mut issued = 0u64;
        let end = Instant::now() + Duration::from_secs(60);
        while t.releases < T {
            ensure!(Instant::now() < end, "parent stuck at {} (issued {})", t.releases, issued);
            let slowest = t.releases.min(shm.get(4));
            if issued < T && issued < slowest + 16 {
                issued += 1;
                shm.set(1, issued);
            }
            r.poll(5);
            for f in r.drain()? {
                t.feed(&f)?;
            }
            shm.set(3, t.releases);
        }
        let code = wait_pid(child);
        shm.set(9, 1);
        ensure!(code == 0, "forked reader exited {}", code);
        ensure!(t.presses == T, "parent presses {}", t.presses);
        Ok(())
    });
    assert_eq!((wait_pid(rp), wait_pid(wp)), (0, 0));
}

#[test]
fn unplug_is_a_sticky_removal_with_pollhup_but_reset_is_not() {
    let sc = Scene::new("unplug");
    run("unplug", || {
        sc.enter();
        let mut w = sc.writer(0);
        w.open();
        let mut r = Rd::attach(0, sys::O_NONBLOCK)?;
        r.frame(1000)?;
        w.set_pad(pad(1 << B_A));
        r.frame_where(1000, "A", |f| key(f, BTN_A) == Some(1))?;
        w.reset(); // in-game menu neutralise / soft release: not an unplug
        r.frame_where(1000, "A released", |f| key(f, BTN_A) == Some(0))?;
        ensure!(bannerin_is_removed(r.h) == 0, "reset is not a removal");
        w.set_pad(pad(1 << B_A));
        r.frame_where(1000, "A again", |f| key(f, BTN_A) == Some(1))?;
        w.unplug();
        let end = Instant::now() + Duration::from_secs(2);
        loop {
            let rev = r.poll(100);
            if rev & POLLHUP != 0 {
                break;
            }
            ensure!(Instant::now() < end, "no native POLLHUP after unplug (revents {:#x})", rev);
            let _ = r.read_nb();
        }
        ensure!(r.read_nb() == Err(sys::ENODEV), "read after unplug must be ENODEV");
        ensure!(bannerin_is_removed(r.h) == 1, "removed");
        ensure!(bannerin_has_pending(r.h) == -sys::ENODEV, "has_pending");
        ensure!(r.state().err() == Some(sys::ENODEV), "current_state");
        let rev = bannerin_poll_revents(r.h, POLLIN);
        ensure!(rev & POLLHUP != 0 && rev & POLLERR != 0, "poll_revents {:#x}", rev);
        // a new controller on the same slot: the old handle stays removed, a fresh open works
        w.open();
        w.set_pad(pad(1 << B_B));
        std::thread::sleep(Duration::from_millis(50));
        ensure!(bannerin_is_removed(r.h) == 1, "removal is sticky");
        let mut r2 = Rd::attach(0, sys::O_NONBLOCK)?;
        let f = r2.frame(1000)?;
        ensure!(is_full(&f) && key(&f, BTN_B) == Some(1) && key(&f, BTN_A) == Some(0), "fresh open sees the new pad: {:?}", f);
        ensure!(bannerin_is_removed(r2.h) == 0, "fresh handle not removed");
        ensure!(bannerin_detach(r.h) == 0 && bannerin_detach(r2.h) == 0, "detach");
        Ok(())
    });
}

#[test]
fn native_poll_wakes_within_two_ms() {
    let sc = Scene::new("latency");
    run("latency", || {
        sc.enter();
        let mut w = sc.writer(0);
        w.open();
        let mut r = Rd::attach(0, sys::O_NONBLOCK)?;
        r.frame(1000)?;
        let mut lat = Vec::new();
        for i in 0..300 {
            r.drain()?;
            ensure!(r.poll(0) == 0, "readable with nothing pending (iteration {})", i);
            let t0 = Instant::now();
            w.set_pad(pad(if i % 2 == 0 { 1 << B_A } else { 0 }));
            ensure!(r.poll(1000) & POLLIN != 0, "no wake within 1 s (iteration {})", i);
            lat.push(t0.elapsed());
            r.frame(100)?;
        }
        lat.sort();
        let (p50, p99, max) = (lat[150], lat[296], lat[299]);
        say(&format!("[latency] publish->poll wake p50 {:?} p99 {:?} max {:?}\n", p50, p99, max));
        ensure!(p50 < Duration::from_millis(2), "median wake {:?} exceeds 2 ms", p50);
        ensure!(p99 < Duration::from_millis(50), "p99 wake {:?}", p99);
        Ok(())
    });
}

#[test]
fn blocking_read_wakes_on_input_and_fails_ebadf_on_concurrent_detach() {
    let sc = Scene::new("block");
    run("block", || {
        sc.enter();
        let mut w = sc.writer(0);
        w.open();
        let mut r = Rd::attach(0, 0)?;
        r.frame(1000)?;
        let h = r.h;
        let t = std::thread::spawn(move || {
            let mut buf = [0u8; EVENT_SIZE * 8];
            let t0 = Instant::now();
            let n = unsafe { bannerin_read(h, buf.as_mut_ptr() as *mut c_void, buf.len(), 0) };
            (n, t0.elapsed(), parse(&buf[..n.max(0) as usize]))
        });
        std::thread::sleep(Duration::from_millis(50));
        let t0 = Instant::now();
        w.set_pad(pad(1 << B_START));
        let (n, _, ev) = t.join().unwrap();
        ensure!(n > 0 && key(&ev, BTN_START) == Some(1), "blocking read returned {} {:?}", n, ev);
        ensure!(t0.elapsed() < Duration::from_millis(500), "slow wake {:?}", t0.elapsed());
        let t = std::thread::spawn(move || {
            let mut buf = [0u8; EVENT_SIZE * 8];
            let n = unsafe { bannerin_read(h, buf.as_mut_ptr() as *mut c_void, buf.len(), 0) };
            n
        });
        std::thread::sleep(Duration::from_millis(50));
        let t0 = Instant::now();
        ensure!(bannerin_detach(h) == 0, "detach");
        let n = t.join().unwrap();
        ensure!(n == -(sys::EBADF as isize), "blocked read after detach gave {}", n);
        ensure!(t0.elapsed() < Duration::from_millis(500), "EBADF took {:?}", t0.elapsed());
        Ok(())
    });
}

#[test]
fn session_release_goes_neutral_and_next_prepare_reattaches() {
    let sc = Scene::new("session");
    run("session", || {
        sc.enter();
        let mut w = sc.writer(0);
        w.open();
        w.set_pad(pad(1 << B_A));
        let mut r = Rd::attach(0, sys::O_NONBLOCK)?;
        ensure!(key(&r.frame(1000)?, BTN_A) == Some(1), "A held");
        w.release(); // session end
        r.frame_where(2000, "neutral after release", neutral_full)?;
        ensure!(bannerin_is_removed(r.h) == 0, "release is not an unplug");
        w.prepare(sc.backing().as_bytes()).map_err(|e| format!("prepare {}", e))?; // next session
        let f = r.frame_where(3000, "re-attach", |f| is_full(f))?;
        ensure!(key(&f, BTN_A) == Some(1), "the writer's state carried into the new file: {:?}", f);
        w.set_pad(pad(1 << B_B));
        let f = r.frame_where(1000, "B", |f| key(f, BTN_B) == Some(1))?;
        ensure!(key(&f, BTN_A) == Some(0) && !is_full(&f), "{:?}", f);
        let mut st = SlotStatsView::default();
        ensure!(unsafe { bannerin_slot_stats(0, &mut st, core::mem::size_of::<SlotStatsView>()) } == 0, "slot stats");
        ensure!(st.publishes >= 2 && st.reader_reattaches >= 1 && st.retired == 0, "{:?}", st);
        Ok(())
    });
}

#[test]
fn current_state_is_never_torn() {
    let sc = Scene::new("torn");
    run("torn", || {
        sc.enter();
        let mut w = sc.writer(0);
        w.open();
        let r = Rd::attach(0, sys::O_NONBLOCK)?;
        let stop = std::sync::Arc::new(std::sync::atomic::AtomicBool::new(false));
        let s2 = stop.clone();
        let t = std::thread::spawn(move || {
            let mut v = 0i32;
            while !s2.load(Acquire) {
                v = (v + 1) % 30000;
                let b = if v % 2 == 1 { 1 << B_A } else { 0 };
                w.set_pad(pad_axes(b, [v, v, v, v, v & 255, v & 255, 0, 0]));
            }
        });
        for _ in 0..100_000 {
            let s = r.state().map_err(|e| format!("state {}", e))?;
            let v = s.axes[0];
            ensure!(s.axes[..4].iter().all(|a| *a == v), "torn sticks {:?}", s.axes);
            ensure!(s.axes[4] == v & 255 && s.axes[5] == v & 255, "torn triggers {:?}", s.axes);
            ensure!(s.buttons[B_A] == v & 1, "torn buttons {:?} vs {}", s.buttons, v);
        }
        stop.store(true, Release);
        t.join().unwrap();
        Ok(())
    });
}

#[test]
fn idle_pad_sleeps_and_a_raw_read_cannot_wedge_readiness() {
    let sc = Scene::new("idle");
    run("idle", || {
        sc.enter();
        let mut w = sc.writer(0);
        w.open();
        let mut r = Rd::attach(0, sys::O_NONBLOCK)?;
        // a program that reads the fd raw (it inherited it across exec, say) gets one byte, not an event
        let mut raw = [0u8; 24];
        let n = sys::read(r.fd, &mut raw).map_err(|e| format!("raw read {}", e))?;
        ensure!(n == 1, "raw read returned {} bytes", n);
        // the readiness flag heals by itself (the full state is still owed)
        let end = Instant::now() + Duration::from_secs(3);
        while r.poll(100) & POLLIN == 0 {
            ensure!(Instant::now() < end, "readiness never came back");
        }
        ensure!(is_full(&r.frame(100)?), "full state");
        let s0 = stats();
        std::thread::sleep(Duration::from_millis(400));
        let s1 = stats();
        let wakes = (s1.helper_wakes + s1.helper_timeouts) - (s0.helper_wakes + s0.helper_timeouts);
        say(&format!("[idle] helper wake-ups in 400 ms: {}\n", wakes));
        ensure!(wakes <= 400 / 4 + 40, "idle helper woke {} times in 400 ms", wakes);
        ensure!(s1.frames == s0.frames && r.poll(0) == 0, "idle pad delivered something");
        Ok(())
    });
}

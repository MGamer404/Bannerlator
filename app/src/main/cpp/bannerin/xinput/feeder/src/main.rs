//! bannerin-xinput-feeder: the app side of the BANNERIN-XInput CI tests.
//!
//! ```text
//! bannerin-xinput-feeder script <slot dir> <control file> <udp port>
//!     Slot 0: a connected pad, slot 1: a file with no controller. Writes "<control file>.ready",
//!     then performs one scripted step whenever xinput_test.exe writes a new step number into the
//!     control file ("%08u %08u\n": n, 99999999 - n), until step 999. Meanwhile it records the
//!     rumble datagrams the DLL sends to 127.0.0.1:<udp port> and checks them at the end.
//! bannerin-xinput-feeder stress <slot dir> <seconds>
//!     Publishes as fast as it can into slot 0, every field derived from the low 16 bits of a
//!     counter (test/host_test.c checks every snapshot it reads against that rule).
//! ```

use std::fs::{self, File};
use std::net::UdpSocket;
use std::os::unix::fs::FileExt;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

use bannerin_core::abi::*;
use bannerin_core::pad::PadInput;
use bannerin_core::writer::SlotWriter;

const Z: [i32; AXES] = [0; AXES];

fn pad(buttons: u16, axes: [i32; AXES]) -> PadInput {
    PadInput { buttons, axes }
}

fn b(i: usize) -> u16 {
    1 << i
}

fn writer(dir: &[u8], slot: u32, open: bool, p: PadInput) -> Box<SlotWriter> {
    let mut w = Box::new(SlotWriter::new(slot));
    w.prepare(dir).unwrap_or_else(|e| panic!("prepare slot {slot}: errno {e}"));
    if open {
        w.open();
    }
    w.set_pad(p);
    w
}

fn read_step(f: &File) -> Option<u32> {
    let mut buf = [0u8; 18];
    let n = f.read_at(&mut buf, 0).ok()?;
    if n < 18 || buf[8] != b' ' || buf[17] != b'\n' {
        return None;
    }
    let s = std::str::from_utf8(&buf).ok()?;
    let a: u32 = s[0..8].parse().ok()?;
    let c: u32 = s[9..17].parse().ok()?;
    if a > 99_999_999 || 99_999_999 - a != c {
        return None;
    }
    Some(a)
}

fn script(dir: &str, ctl: &str, port: u16) -> i32 {
    fs::create_dir_all(dir).expect("slot dir");
    let d = dir.as_bytes();
    let t0 = Instant::now();
    let log = |m: &str| println!("[feeder {:8.3} s] {m}", t0.elapsed().as_secs_f64());

    let mut slot0: Vec<Box<SlotWriter>> = vec![writer(d, 0, true, PadInput::NEUTRAL)];
    let _slot1 = writer(d, 1, false, PadInput::NEUTRAL);

    let sock = UdpSocket::bind(("127.0.0.1", port)).expect("udp bind");
    sock.set_read_timeout(Some(Duration::from_millis(50))).unwrap();
    let packets: Arc<Mutex<Vec<(f64, Vec<u8>)>>> = Arc::default();
    let stop = Arc::new(AtomicBool::new(false));
    let rx = {
        let packets = packets.clone();
        let stop = stop.clone();
        thread::spawn(move || {
            let mut buf = [0u8; 256];
            while !stop.load(Ordering::Relaxed) {
                if let Ok((n, _)) = sock.recv_from(&mut buf) {
                    packets.lock().unwrap().push((t0.elapsed().as_secs_f64(), buf[..n].to_vec()));
                }
            }
        })
    };

    fs::write(ctl, b"").expect("control file");
    fs::write(format!("{ctl}.ready"), b"ready\n").expect("ready file");
    let f = File::open(ctl).expect("control file");
    log("slot 0 connected (neutral), slot 1 has a file but no controller; waiting for steps");

    let mut last = 0u32;
    let mut ok = true;
    loop {
        if t0.elapsed() > Duration::from_secs(180) {
            log("timed out waiting for step 999");
            ok = false;
            break;
        }
        let n = match read_step(&f) {
            Some(n) if n != last => n,
            _ => {
                thread::sleep(Duration::from_micros(20));
                continue;
            }
        };
        last = n;
        match n {
            400 => {
                slot0.push(writer(d, 0, true, pad(b(B_Y), Z)));
                log("step 400: a new writer replaced slot 0's file (the old one is retired)");
            }
            601 => {
                slot0.push(writer(d, 0, true, pad(b(B_A), Z)));
                log("step 601: a new controller on slot 0 (new writer, new file)");
            }
            999 => {
                log("step 999: done");
                break;
            }
            _ => {
                let w = slot0.last_mut().unwrap();
                match n {
                    1 => {
                        w.set_pad(pad(b(B_A), [1000, -2000, 32767, -32768, 255, 10, -1, -1]));
                    }
                    2 => {
                        w.set_pad(pad(b(B_B) | b(B_START) | b(B_MODE), [-32768, 32767, 0, 1, 0, 0, 1, 1]));
                    }
                    1000..=1199 => {
                        let k = (n - 1000) as i32;
                        w.set_pad(pad(0, [k * 100 + 1, 0, 0, 0, 0, 0, 0, 0]));
                    }
                    300 => {
                        // a tap that starts and ends between two polls, then a marker state
                        w.set_pad(pad(b(B_X), Z));
                        w.set_pad(pad(0, [777, 0, 0, 0, 0, 0, 0, 0]));
                    }
                    500 => {
                        w.release();
                        log("step 500: slot 0's file released (retired + unlinked)");
                    }
                    501 => {
                        w.prepare(d).expect("prepare");
                        log("step 501: slot 0's file created again");
                    }
                    600 => {
                        w.unplug();
                        log("step 600: slot 0 unplugged");
                    }
                    801 => {
                        w.set_pad(pad(b(B_B), Z));
                    }
                    802 | 804 | 900 | 902 => {
                        w.set_pad(PadInput::NEUTRAL);
                    }
                    803 => {
                        w.set_pad(pad(0, [30000, -30000, 0, 0, 0, 0, 0, 0]));
                    }
                    901 => {
                        w.set_pad(pad(b(B_MODE), Z));
                    }
                    _ => log(&format!("step {n}: unknown, ignored")),
                }
            }
        }
        if !(1000..=1199).contains(&n) || n == 1000 || n == 1199 {
            log(&format!("step {n} published"));
        }
    }

    thread::sleep(Duration::from_millis(100));
    stop.store(true, Ordering::Relaxed);
    rx.join().unwrap();
    let got = packets.lock().unwrap().clone();
    log(&format!("{} rumble datagram(s):", got.len()));
    let mut seq = Vec::new();
    for (t, p) in &got {
        let well_formed = p.len() == 12 && p[0] == 14 && &p[1..5] == b"BXR1";
        if !well_formed {
            log(&format!("  {t:.3} s: malformed {p:?}"));
            ok = false;
            continue;
        }
        let u16le = |i: usize| u16::from_le_bytes([p[i], p[i + 1]]);
        let (slot, l, r, dur) = (p[5], u16le(6), u16le(8), u16le(10));
        log(&format!("  {t:.3} s: slot {slot} low {l} high {r} for {dur} ms"));
        if dur != 3000 {
            ok = false;
        }
        seq.push((slot, l, r));
    }
    let want = vec![(0u8, 40000u16, 20000u16), (0, 0, 0), (0, 1000, 2000), (0, 0, 0)];
    if seq != want {
        log(&format!("rumble sequence {seq:?}, want {want:?} (start, 100 repeats deduplicated, stop, \
                      nothing while disabled, resume on XInputEnable(TRUE), stop)"));
        ok = false;
    } else {
        log("rumble sequence as expected");
    }
    if ok {
        0
    } else {
        1
    }
}

fn stress(dir: &str, secs: f64) -> i32 {
    fs::create_dir_all(dir).expect("slot dir");
    let mut w = writer(dir.as_bytes(), 0, true, PadInput::NEUTRAL);
    let t0 = Instant::now();
    let mut k: u32 = 0;
    while t0.elapsed().as_secs_f64() < secs {
        for _ in 0..1000 {
            k = k.wrapping_add(1);
            let low = k & 0xffff;
            let lx = low as i32 - 32768;
            let axes = [
                lx,
                -1 - lx,
                lx,
                -1 - lx,
                (low & 0xff) as i32,
                255 - (low & 0xff) as i32,
                (low % 3) as i32 - 1,
                ((low / 3) % 3) as i32 - 1,
            ];
            w.set_pad(pad((low & 0x7ff) as u16, axes));
        }
    }
    println!("[feeder] stress: {k} states published in {secs} s");
    0
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let code = match (a.get(1).map(String::as_str), a.len()) {
        (Some("script"), 5) => script(&a[2], &a[3], a[4].parse().expect("port")),
        (Some("stress"), 4) => stress(&a[2], a[3].parse().expect("seconds")),
        _ => {
            eprintln!("usage: {} script <slot dir> <control file> <udp port> | stress <slot dir> <seconds>", a[0]);
            2
        }
    };
    std::process::exit(code);
}

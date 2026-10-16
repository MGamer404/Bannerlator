//! libbannerin.so: native side of com.winlator.star.inputcontrols.BannerinWriter.
//!
//! One `SlotWriter` per slot, process-wide (it outlives the Java writer objects, which come and
//! go with connects, so state and edge counters survive reconnects and sessions), each behind its
//! own mutex because several Java writer objects can address the same slot. Every entry point
//! takes primitives only; the publish path does no allocation, no JNI callbacks and no file I/O.

use std::ffi::{c_char, c_void};
use std::sync::{Mutex, MutexGuard};

use bannerin_core::pad::{overlay_from_bits, PadInput};
use bannerin_core::writer::SlotWriter;

type JEnv = *mut *const *const c_void;
type JClass = *mut c_void;
type JString = *mut c_void;
type JBoolean = u8;
type JInt = i32;
type JLong = i64;
type JFloat = f32;

// Indices into the JNINativeInterface function table (stable JNI ABI).
const JNI_GET_STRING_UTF_CHARS: usize = 169;
const JNI_RELEASE_STRING_UTF_CHARS: usize = 170;

static SLOTS: [Mutex<SlotWriter>; 4] = [
    Mutex::new(SlotWriter::new(0)),
    Mutex::new(SlotWriter::new(1)),
    Mutex::new(SlotWriter::new(2)),
    Mutex::new(SlotWriter::new(3)),
];

fn slot(slot: JInt) -> Option<MutexGuard<'static, SlotWriter>> {
    let m = SLOTS.get(usize::try_from(slot).ok()?)?;
    Some(m.lock().unwrap_or_else(|p| p.into_inner()))
}

/// Copies a Java string (modified UTF-8) into `out`; None for null or over-long strings.
unsafe fn jstring(env: JEnv, s: JString, out: &mut [u8; 1024]) -> Option<usize> {
    if env.is_null() || s.is_null() {
        return None;
    }
    let fns = *env;
    let get: unsafe extern "system" fn(JEnv, JString, *mut JBoolean) -> *const c_char =
        std::mem::transmute(*fns.add(JNI_GET_STRING_UTF_CHARS));
    let rel: unsafe extern "system" fn(JEnv, JString, *const c_char) =
        std::mem::transmute(*fns.add(JNI_RELEASE_STRING_UTF_CHARS));
    let p = get(env, s, std::ptr::null_mut());
    if p.is_null() {
        return None;
    }
    let mut n = 0usize;
    while n < out.len() - 1 && *p.add(n) != 0 {
        out[n] = *p.add(n) as u8;
        n += 1;
    }
    let complete = *p.add(n) == 0;
    rel(env, s, p);
    if complete && n > 0 {
        Some(n)
    } else {
        None
    }
}

fn jbool(b: bool) -> JBoolean {
    b as JBoolean
}

/// `static native boolean nativePrepare(String backingDir, int slot)`
#[no_mangle]
pub unsafe extern "system" fn Java_com_winlator_star_inputcontrols_BannerinWriter_nativePrepare(
    env: JEnv,
    _cls: JClass,
    dir: JString,
    s: JInt,
) -> JBoolean {
    let mut buf = [0u8; 1024];
    let n = match jstring(env, dir, &mut buf) {
        Some(n) => n,
        None => return 0,
    };
    match slot(s) {
        Some(mut w) => jbool(w.prepare(&buf[..n]).is_ok()),
        None => 0,
    }
}

/// `static native void nativeRelease(int slot)`
#[no_mangle]
pub extern "system" fn Java_com_winlator_star_inputcontrols_BannerinWriter_nativeRelease(
    _env: JEnv,
    _cls: JClass,
    s: JInt,
) {
    if let Some(mut w) = slot(s) {
        w.release();
    }
}

/// `static native boolean nativeOpen(int slot, String backingDir)`: the slot has a controller.
/// Creates the backing first if it does not exist yet (backingDir may be null).
#[no_mangle]
pub unsafe extern "system" fn Java_com_winlator_star_inputcontrols_BannerinWriter_nativeOpen(
    env: JEnv,
    _cls: JClass,
    s: JInt,
    dir: JString,
) -> JBoolean {
    let mut buf = [0u8; 1024];
    let n = jstring(env, dir, &mut buf);
    match slot(s) {
        Some(mut w) => {
            if !w.has_backing() {
                if let Some(n) = n {
                    let _ = w.prepare(&buf[..n]);
                }
            }
            w.open();
            1
        }
        None => 0,
    }
}

/// `static native boolean nativePublish(int slot, int buttons, float lx, float ly, float rx,
/// float ry, float lt, float rt, int dpad)`: the pad's complete state. False if it could not be
/// made visible to readers (the state is kept and published by the next successful call).
#[no_mangle]
#[allow(clippy::too_many_arguments)]
pub extern "system" fn Java_com_winlator_star_inputcontrols_BannerinWriter_nativePublish(
    _env: JEnv,
    _cls: JClass,
    s: JInt,
    buttons: JInt,
    lx: JFloat,
    ly: JFloat,
    rx: JFloat,
    ry: JFloat,
    lt: JFloat,
    rt: JFloat,
    dpad: JInt,
) -> JBoolean {
    match slot(s) {
        Some(mut w) => jbool(w.set_pad(PadInput::from_java(buttons, lx, ly, rx, ry, lt, rt, dpad))),
        None => 0,
    }
}

/// `static native boolean nativeSetOverlay(int slot, int bits)`: bit 0 Guide, bit 1 A.
#[no_mangle]
pub extern "system" fn Java_com_winlator_star_inputcontrols_BannerinWriter_nativeSetOverlay(
    _env: JEnv,
    _cls: JClass,
    s: JInt,
    bits: JInt,
) -> JBoolean {
    match slot(s) {
        Some(mut w) => jbool(w.set_overlay(overlay_from_bits(bits))),
        None => 0,
    }
}

/// `static native boolean nativeReset(int slot)`: neutral, still connected.
#[no_mangle]
pub extern "system" fn Java_com_winlator_star_inputcontrols_BannerinWriter_nativeReset(
    _env: JEnv,
    _cls: JClass,
    s: JInt,
) -> JBoolean {
    match slot(s) {
        Some(mut w) => jbool(w.reset()),
        None => 0,
    }
}

/// `static native boolean nativeUnplug(int slot)`: neutralise, then removal for every reader.
#[no_mangle]
pub extern "system" fn Java_com_winlator_star_inputcontrols_BannerinWriter_nativeUnplug(
    _env: JEnv,
    _cls: JClass,
    s: JInt,
) -> JBoolean {
    match slot(s) {
        Some(mut w) => jbool(w.unplug()),
        None => 0,
    }
}

/// `static native long nativeStat(int slot, int which)`: see `bannerin_core::writer::STAT_*`.
#[no_mangle]
pub extern "system" fn Java_com_winlator_star_inputcontrols_BannerinWriter_nativeStat(
    _env: JEnv,
    _cls: JClass,
    s: JInt,
    which: JInt,
) -> JLong {
    match slot(s) {
        Some(w) => w.stat(which as u32) as JLong,
        None => 0,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bad_slots_are_refused() {
        assert!(slot(-1).is_none());
        assert!(slot(4).is_none());
        assert!(slot(0).is_some());
        assert_eq!(Java_com_winlator_star_inputcontrols_BannerinWriter_nativeReset(std::ptr::null_mut(), std::ptr::null_mut(), 7), 0);
        assert_eq!(Java_com_winlator_star_inputcontrols_BannerinWriter_nativeStat(std::ptr::null_mut(), std::ptr::null_mut(), 9, 0), 0);
    }

    #[test]
    fn publish_without_backing_reports_failure_but_keeps_state() {
        let r = Java_com_winlator_star_inputcontrols_BannerinWriter_nativePublish(
            std::ptr::null_mut(),
            std::ptr::null_mut(),
            3,
            1,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0.0,
            0,
        );
        assert_eq!(r, 0, "no backing has been prepared for slot 3 in this process");
        assert_eq!(slot(3).unwrap().buttons(), 1, "the state is kept for the next backing");
    }
}

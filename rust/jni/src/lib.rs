// SPDX-FileCopyrightText: The Yasvara Project
// SPDX-License-Identifier: Apache-2.0

//! JNI face of `rekam_engine` for `org.yasvara.rekam.engine.Native`.
//!
//! Handles are `Box` pointers in a `jlong`. Kotlin owns their lifetime: it never calls into a
//! handle after `*Finish`/`*Abandon`/`*Free`, and stops every encoder thread before finishing.
//! Buffers are direct `ByteBuffer`s, read and written in place.

use jni::objects::{JBooleanArray, JByteArray, JByteBuffer, JClass, JIntArray, JLongArray, JObjectArray};
use jni::sys::{jboolean, jfloat, jint, jlong, jstring, JNI_TRUE};
use jni::JNIEnv;
use rekam_engine::{MeterPage, Mixer, Queued, Recorder};
use rekam_media::{recover, Codec, Recovered, SyncPolicy, TrackConfig, WriterOptions};
use std::fs::File;
use std::os::fd::FromRawFd;
use std::sync::atomic::{AtomicU32, Ordering};
use std::time::Duration;

fn init_log() {
    android_logger::init_once(android_logger::Config::default().with_tag("Rekam").with_max_level(log::LevelFilter::Info));
}

/// Borrow of a direct buffer's bytes `[offset, offset + len)`, or `None` if out of range.
///
/// SAFETY of the returned slice: the buffer is direct, Kotlin keeps it alive and does not touch
/// it for the duration of the call.
unsafe fn direct<'a>(env: &JNIEnv, buf: &JByteBuffer, offset: jint, len: jint) -> Option<&'a mut [u8]> {
    let addr = env.get_direct_buffer_address(buf).ok()?;
    let cap = env.get_direct_buffer_capacity(buf).ok()?;
    let (offset, len) = (usize::try_from(offset).ok()?, usize::try_from(len).ok()?);
    if addr.is_null() || offset.checked_add(len)? > cap {
        return None;
    }
    // SAFETY: in range of a live direct buffer, per the function contract.
    Some(unsafe { std::slice::from_raw_parts_mut(addr.add(offset), len) })
}

fn codec(v: jint) -> Option<Codec> {
    Some(match v {
        0 => Codec::Avc,
        1 => Codec::Hevc,
        2 => Codec::Aac,
        3 => Codec::Opus,
        4 => Codec::Flac,
        _ => return None,
    })
}

fn ints(env: &mut JNIEnv, a: &JIntArray) -> Option<Vec<i32>> {
    let n = env.get_array_length(a).ok()? as usize;
    let mut v = vec![0; n];
    env.get_int_array_region(a, 0, &mut v).ok()?;
    Some(v)
}

/// Opens a recording on `fd` (ownership passes to native). Track `i` is described by element `i`
/// of every array; `ints` per track is [codec, timescale, width, height, sampleRate, channels,
/// avgBitrate, alternateGroup]. Returns 0 on failure.
#[no_mangle]
pub extern "system" fn Java_org_yasvara_rekam_engine_Native_recorderOpen<'l>(
    mut env: JNIEnv<'l>,
    _: JClass<'l>,
    fd: jint,
    ints_per_track: JIntArray<'l>,
    enabled: JBooleanArray<'l>,
    csd: JObjectArray<'l>,
    fragment_us: jlong,
    sync_every: jint,
    budget: jint,
) -> jlong {
    init_log();
    // SAFETY: Kotlin detached this fd from its ParcelFileDescriptor; nobody else closes it.
    let file = unsafe { File::from_raw_fd(fd) };
    let build = |env: &mut JNIEnv<'l>| -> Option<Vec<TrackConfig>> {
        let v = ints(env, &ints_per_track)?;
        let n = env.get_array_length(&csd).ok()? as usize;
        let mut en = vec![0u8; n];
        env.get_boolean_array_region(&enabled, 0, &mut en).ok()?;
        if v.len() != n * 8 {
            return None;
        }
        let mut tracks = Vec::with_capacity(n);
        for i in 0..n {
            let t = &v[i * 8..i * 8 + 8];
            let bytes = env.get_object_array_element(&csd, i as i32).ok()?;
            let csd = env.convert_byte_array(JByteArray::from(bytes)).ok()?;
            tracks.push(TrackConfig {
                codec: codec(t[0])?,
                timescale: t[1] as u32,
                width: t[2] as u16,
                height: t[3] as u16,
                sample_rate: t[4] as u32,
                channels: t[5] as u16,
                avg_bitrate: t[6] as u32,
                csd,
                enabled: en[i] != 0,
                alternate_group: t[7] as i16,
            });
        }
        Some(tracks)
    };
    let Some(tracks) = build(&mut env) else {
        log::error!("recorderOpen: bad track arguments");
        return 0;
    };
    let sync = match sync_every {
        0 => SyncPolicy::Never,
        1 => SyncPolicy::EveryFragment,
        n => SyncPolicy::Every(n as u32),
    };
    let opts = WriterOptions { fragment_us, sync };
    match Recorder::start(file, &tracks, opts, budget.max(1) as usize) {
        Ok(r) => Box::into_raw(Box::new(r)) as jlong,
        Err(e) => {
            log::error!("recorderOpen: {e}");
            0
        }
    }
}

/// 0 queued, 1 dropped (backpressure), -1 the writer failed.
#[no_mangle]
pub extern "system" fn Java_org_yasvara_rekam_engine_Native_recorderWrite<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    track: jint,
    buf: JByteBuffer<'l>,
    offset: jint,
    size: jint,
    pts_us: jlong,
    key: jboolean,
) -> jint {
    // SAFETY: a live handle from recorderOpen; Recorder::write is safe from several threads.
    let rec = unsafe { &*(handle as *const Recorder) };
    // SAFETY: direct codec output buffer, held by the caller until this returns.
    let Some(data) = (unsafe { direct(&env, &buf, offset, size) }) else { return -1 };
    match rec.write(track as usize, data, pts_us, key == JNI_TRUE) {
        Queued::Yes => 0,
        Queued::Dropped => 1,
        Queued::Failed => -1,
    }
}

/// Fills `out` with [bytes, durationUs, dropped, inFlightBytes, failed].
#[no_mangle]
pub extern "system" fn Java_org_yasvara_rekam_engine_Native_recorderStats<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    out: JLongArray<'l>,
) {
    // SAFETY: a live handle from recorderOpen.
    let s = unsafe { &*(handle as *const Recorder) }.stats();
    let v = [
        s.bytes.load(Ordering::Relaxed) as i64,
        s.duration_us.load(Ordering::Relaxed) as i64,
        s.dropped.load(Ordering::Relaxed) as i64,
        s.in_flight.load(Ordering::Relaxed) as i64,
        i64::from(s.failed.load(Ordering::Relaxed)),
    ];
    env.set_long_array_region(&out, 0, &v).ok();
}

/// Writes the current fragment now.
#[no_mangle]
pub extern "system" fn Java_org_yasvara_rekam_engine_Native_recorderFlush<'l>(_: JNIEnv<'l>, _: JClass<'l>, handle: jlong) {
    // SAFETY: a live handle from recorderOpen.
    unsafe { &*(handle as *const Recorder) }.flush();
}

/// Finalizes and frees the recorder. Returns null on success, else the error.
#[no_mangle]
pub extern "system" fn Java_org_yasvara_rekam_engine_Native_recorderFinish<'l>(env: JNIEnv<'l>, _: JClass<'l>, handle: jlong) -> jstring {
    // SAFETY: the handle from recorderOpen, consumed here; no other thread uses it any more.
    let rec = unsafe { Box::from_raw(handle as *mut Recorder) };
    match rec.finish() {
        Ok(()) => std::ptr::null_mut(),
        Err(e) => env.new_string(e.to_string()).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut()),
    }
}

/// Frees the recorder without finalizing: the file stays as a crash leaves it, for recovery.
#[no_mangle]
pub extern "system" fn Java_org_yasvara_rekam_engine_Native_recorderAbandon<'l>(_: JNIEnv<'l>, _: JClass<'l>, handle: jlong) {
    // SAFETY: the handle from recorderOpen, consumed here.
    drop(unsafe { Box::from_raw(handle as *mut Recorder) });
}

/// Recovers the file on `fd` (ownership passes to native): 0 already final, 1 repaired,
/// 2 nothing to keep, -1 not ours or corrupt, -2 i/o error.
#[no_mangle]
pub extern "system" fn Java_org_yasvara_rekam_engine_Native_recover<'l>(_: JNIEnv<'l>, _: JClass<'l>, fd: jint) -> jint {
    init_log();
    // SAFETY: Kotlin detached this fd; it is closed when `file` drops.
    let mut file = unsafe { File::from_raw_fd(fd) };
    match recover(&mut file) {
        Ok(Recovered::Final) => 0,
        Ok(Recovered::Repaired { fragments, samples, dropped_bytes }) => {
            log::info!("recovered {fragments} fragments, {samples} samples, cut {dropped_bytes} bytes");
            1
        }
        Ok(Recovered::Empty) => 2,
        Err(rekam_media::Error::Io(e)) => {
            log::error!("recover: {e}");
            -2
        }
        Err(e) => {
            log::warn!("recover: {e}");
            -1
        }
    }
}

/// A mixer with `slots` slots, all inactive; slot 0 is the master clock.
#[no_mangle]
pub extern "system" fn Java_org_yasvara_rekam_engine_Native_mixerNew<'l>(_: JNIEnv<'l>, _: JClass<'l>, slots: jint) -> jlong {
    Box::into_raw(Box::new(Mixer::new(slots.max(1) as usize))) as jlong
}

/// Starts or ends the source in `slot`.
#[no_mangle]
pub extern "system" fn Java_org_yasvara_rekam_engine_Native_mixerActive<'l>(_: JNIEnv<'l>, _: JClass<'l>, handle: jlong, slot: jint, active: jboolean) {
    // SAFETY: a live mixer handle.
    unsafe { &*(handle as *const Mixer) }.set_active(slot as usize, active == JNI_TRUE);
}

/// Publishes levels to the meter page in `page`, a direct buffer over shared memory that
/// Kotlin keeps mapped until `mixerFree`. False if it is too small or misaligned.
#[no_mangle]
pub extern "system" fn Java_org_yasvara_rekam_engine_Native_mixerAttachMeters<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    page: JByteBuffer<'l>,
) -> jboolean {
    let (Ok(addr), Ok(cap)) = (env.get_direct_buffer_address(&page), env.get_direct_buffer_capacity(&page)) else { return 0 };
    if addr.is_null() || addr.align_offset(std::mem::align_of::<AtomicU32>()) != 0 {
        return 0;
    }
    // SAFETY: aligned, in range of the mapping, which outlives the mixer (contract above); the
    // words are only accessed atomically from here on.
    let words: &'static [AtomicU32] = unsafe { std::slice::from_raw_parts(addr as *const AtomicU32, cap / 4) };
    let Some(meters) = MeterPage::new(words) else { return 0 };
    // SAFETY: a live mixer handle.
    unsafe { &*(handle as *const Mixer) }.attach_meters(meters);
    JNI_TRUE
}

/// Appends `bytes` of interleaved stereo f32 from a direct buffer to `slot`.
#[no_mangle]
pub extern "system" fn Java_org_yasvara_rekam_engine_Native_mixerPush<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    slot: jint,
    buf: JByteBuffer<'l>,
    bytes: jint,
) {
    // SAFETY: a live mixer handle; Mixer is thread-safe.
    let m = unsafe { &*(handle as *const Mixer) };
    // SAFETY: direct AudioRecord buffer held by the caller for this call.
    let Some(b) = (unsafe { direct(&env, &buf, 0, bytes) }) else { return };
    // SAFETY: f32 has no invalid bit patterns; a misaligned buffer is ignored below.
    let (head, samples, _) = unsafe { b.align_to::<f32>() };
    if head.is_empty() {
        m.push(slot as usize, samples);
    }
}

/// Mixes `bytes` of s16 stereo into a direct buffer once the master has them; false on timeout.
#[no_mangle]
pub extern "system" fn Java_org_yasvara_rekam_engine_Native_mixerPull<'l>(
    env: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    buf: JByteBuffer<'l>,
    offset: jint,
    bytes: jint,
    timeout_ms: jint,
) -> jboolean {
    // SAFETY: a live mixer handle.
    let m = unsafe { &*(handle as *const Mixer) };
    // SAFETY: direct codec input buffer held by the caller for this call.
    let Some(b) = (unsafe { direct(&env, &buf, offset, bytes) }) else { return 0 };
    // SAFETY: i16 has no invalid bit patterns.
    let (head, out, _) = unsafe { b.align_to_mut::<i16>() };
    if !head.is_empty() {
        return 0;
    }
    u8::from(m.pull(out, Duration::from_millis(timeout_ms.max(0) as u64)))
}

/// Gain in dB for a slot.
#[no_mangle]
pub extern "system" fn Java_org_yasvara_rekam_engine_Native_mixerGain<'l>(_: JNIEnv<'l>, _: JClass<'l>, handle: jlong, slot: jint, db: jfloat) {
    // SAFETY: a live mixer handle.
    unsafe { &*(handle as *const Mixer) }.set_gain_db(slot as usize, db);
}

/// Mutes or unmutes a slot.
#[no_mangle]
pub extern "system" fn Java_org_yasvara_rekam_engine_Native_mixerMute<'l>(_: JNIEnv<'l>, _: JClass<'l>, handle: jlong, slot: jint, muted: jboolean) {
    // SAFETY: a live mixer handle.
    unsafe { &*(handle as *const Mixer) }.set_muted(slot as usize, muted == JNI_TRUE);
}

/// Mono fold and balance (−1 left … 1 right) for a slot.
#[no_mangle]
pub extern "system" fn Java_org_yasvara_rekam_engine_Native_mixerShape<'l>(
    _: JNIEnv<'l>,
    _: JClass<'l>,
    handle: jlong,
    slot: jint,
    mono: jboolean,
    balance: jfloat,
) {
    // SAFETY: a live mixer handle.
    unsafe { &*(handle as *const Mixer) }.set_shape(slot as usize, mono == JNI_TRUE, balance);
}

/// The master fader in dB.
#[no_mangle]
pub extern "system" fn Java_org_yasvara_rekam_engine_Native_mixerMaster<'l>(_: JNIEnv<'l>, _: JClass<'l>, handle: jlong, db: jfloat) {
    // SAFETY: a live mixer handle.
    unsafe { &*(handle as *const Mixer) }.set_master_db(db);
}

/// Frees a mixer; no thread may use it afterwards.
#[no_mangle]
pub extern "system" fn Java_org_yasvara_rekam_engine_Native_mixerFree<'l>(_: JNIEnv<'l>, _: JClass<'l>, handle: jlong) {
    // SAFETY: the handle from mixerNew, consumed here.
    drop(unsafe { Box::from_raw(handle as *mut Mixer) });
}

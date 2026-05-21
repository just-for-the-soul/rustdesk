use jni::objects::JByteBuffer;
use jni::objects::JString;
use jni::objects::JValue;
use jni::sys::{jboolean, jlong};
use jni::JNIEnv;
use jni::{
    objects::{GlobalRef, JClass, JObject},
    strings::JNIString,
    JavaVM,
};

use hbb_common::{message_proto::MultiClipboards, protobuf::Message};
use jni::errors::{Error as JniError, Result as JniResult};
use lazy_static::lazy_static;
use serde::Deserialize;
use std::collections::VecDeque;
use std::ops::Not;
use std::os::raw::c_void;
use std::sync::atomic::{AtomicBool, AtomicPtr, Ordering::SeqCst};
use std::sync::{Mutex, RwLock};
use std::time::{Duration, Instant};

lazy_static! {
    static ref JVM: RwLock<Option<JavaVM>> = RwLock::new(None);
    static ref MAIN_SERVICE_CTX: RwLock<Option<GlobalRef>> = RwLock::new(None); // MainService -> video service / audio service / info
    static ref APPLICATION_CONTEXT: RwLock<Option<GlobalRef>> = RwLock::new(None);
    static ref VIDEO_RAW: Mutex<FrameRaw> = Mutex::new(FrameRaw::new("video", MAX_VIDEO_FRAME_TIMEOUT));
    static ref AUDIO_RAW: Mutex<FrameRaw> = Mutex::new(FrameRaw::new("audio", MAX_AUDIO_FRAME_TIMEOUT));
    static ref NDK_CONTEXT_INITED: Mutex<bool> = Default::default();
    static ref MEDIA_CODEC_INFOS: RwLock<Option<MediaCodecInfos>> = RwLock::new(None);
    static ref CLIPBOARD_MANAGER: RwLock<Option<GlobalRef>> = RwLock::new(None);
    static ref CLIPBOARDS_HOST: Mutex<Option<MultiClipboards>> = Mutex::new(None);
    static ref CLIPBOARDS_CLIENT: Mutex<Option<MultiClipboards>> = Mutex::new(None);

    // Queue of pre-encoded H.265 frames coming straight from the Android
    // MediaCodec encoder (Surface-input path), bypassing the RGBA capture
    // and FFmpeg encode entirely. Bounded — old frames are dropped if the
    // network side falls behind, so the encoder never stalls and the
    // viewer always sees the freshest available frame.
    static ref ENCODED_VIDEO_QUEUE: Mutex<VecDeque<EncodedVideoFrameJni>> = Mutex::new(VecDeque::new());
}

// Native encoder active flag — set from Kotlin when the MediaCodec HEVC encoder
// has been wired to VirtualDisplay. video_service.rs polls this to decide
// whether to bypass the capture/encode pipeline.
static NATIVE_ENCODER_ACTIVE: AtomicBool = AtomicBool::new(false);

// Cap on the encoded-frame queue. 4 frames at 30fps = ~130ms of buffering;
// anything older is stale on a remote-control link.
const ENCODED_VIDEO_QUEUE_MAX: usize = 4;

const MAX_VIDEO_FRAME_TIMEOUT: Duration = Duration::from_millis(33); // два кадра при 30fps — успевает даже при переходах между окнами
const MAX_AUDIO_FRAME_TIMEOUT: Duration = Duration::from_millis(500);

/// One H.265 access unit ready to ship over the wire. `data` already contains
/// the NAL units in Annex-B framing (with start codes); for keyframes, the
/// Kotlin side prepends VPS/SPS/PPS so each IDR is self-contained.
#[derive(Debug, Clone)]
pub struct EncodedVideoFrameJni {
    pub data: Vec<u8>,
    pub pts_ms: i64,
    pub is_keyframe: bool,
}

struct FrameRaw {
    name: &'static str,
    ptr: AtomicPtr<u8>,
    len: usize,
    last_update: Instant,
    timeout: Duration,
    enable: bool,
}

impl FrameRaw {
    fn new(name: &'static str, timeout: Duration) -> Self {
        FrameRaw {
            name,
            ptr: AtomicPtr::default(),
            len: 0,
            last_update: Instant::now(),
            timeout,
            enable: false,
        }
    }

    fn set_enable(&mut self, value: bool) {
        self.enable = value;
        self.ptr.store(std::ptr::null_mut(), SeqCst);
        self.len = 0;
    }

    fn update(&mut self, data: *mut u8, len: usize) {
        if self.enable.not() {
            return;
        }
        self.len = len;
        self.ptr.store(data, SeqCst);
        self.last_update = Instant::now();
    }

    // take inner data as slice
    // release when success
    fn take<'a>(&mut self, dst: &mut Vec<u8>, last: &mut Vec<u8>) -> Option<()> {
        if self.enable.not() {
            return None;
        }
        let ptr = self.ptr.load(SeqCst);
        if ptr.is_null() || self.len == 0 {
            None
        } else {
            if self.last_update.elapsed() > self.timeout {
                // Кадр устарел — сбрасываем и не отправляем
                self.release();
                return None;
            }
            let slice = unsafe { std::slice::from_raw_parts(ptr, self.len) };
            self.release();
            // Пропускаем would_block_if_equal для видео — это сравнение ~6MB RGBA
            // занимает 2-5мс и добавляет задержку. Rust кодек сам определит
            // изменился ли кадр через delta encoding.
            // Для аудио оставляем проверку (буфер маленький).
            if self.name == "audio" {
                if last.len() == slice.len() && crate::would_block_if_equal(last, slice).is_err() {
                    return None;
                }
            }
            dst.resize(slice.len(), 0);
            unsafe {
                std::ptr::copy_nonoverlapping(slice.as_ptr(), dst.as_mut_ptr(), slice.len());
            }
            Some(())
        }
    }

    fn release(&mut self) {
        self.len = 0;
        self.ptr.store(std::ptr::null_mut(), SeqCst);
    }
}

pub fn get_video_raw<'a>(dst: &mut Vec<u8>, last: &mut Vec<u8>) -> Option<()> {
    VIDEO_RAW.lock().ok()?.take(dst, last)
}

pub fn get_audio_raw<'a>(dst: &mut Vec<u8>, last: &mut Vec<u8>) -> Option<()> {
    AUDIO_RAW.lock().ok()?.take(dst, last)
}

/// Returns true while the Kotlin side is delivering pre-encoded H.265 frames
/// via `Java_ffi_FFI_onEncodedVideoFrame`. video_service.rs uses this to
/// short-circuit the capture+encode pipeline.
#[inline]
pub fn is_native_encoder_active() -> bool {
    NATIVE_ENCODER_ACTIVE.load(SeqCst)
}

/// Pop the oldest queued encoded H.265 access unit. Returns None if no frame
/// is available right now; the caller is expected to wait for `spf` and retry.
pub fn take_encoded_video_frame() -> Option<EncodedVideoFrameJni> {
    ENCODED_VIDEO_QUEUE.lock().ok()?.pop_front()
}

/// Drain the queue — called when switching out of the native-encoder path so
/// stale buffers from the previous session don't leak into the next.
pub fn clear_encoded_video_queue() {
    if let Ok(mut q) = ENCODED_VIDEO_QUEUE.lock() {
        q.clear();
    }
}

pub fn get_clipboards(client: bool) -> Option<MultiClipboards> {
    if client {
        CLIPBOARDS_CLIENT.lock().ok()?.take()
    } else {
        CLIPBOARDS_HOST.lock().ok()?.take()
    }
}

#[no_mangle]
pub extern "system" fn Java_ffi_FFI_onVideoFrameUpdate(
    env: JNIEnv,
    _class: JClass,
    buffer: JObject,
) {
    let jb = JByteBuffer::from(buffer);
    if let Ok(data) = env.get_direct_buffer_address(&jb) {
        if let Ok(len) = env.get_direct_buffer_capacity(&jb) {
            VIDEO_RAW.lock().unwrap().update(data, len);
        }
    }
}

/// Receive a pre-encoded H.265 access unit from the Kotlin MediaCodec encoder.
/// `buf` is a *direct* ByteBuffer whose position/limit demarcate the NAL units
/// (Annex-B framing, with VPS/SPS/PPS prepended on keyframes). We copy out to
/// own the lifetime, then enqueue with a drop-old policy so the encoder never
/// stalls if Rust falls behind.
#[no_mangle]
pub extern "system" fn Java_ffi_FFI_onEncodedVideoFrame(
    env: JNIEnv,
    _class: JClass,
    buffer: JObject,
    pts_ms: jlong,
    is_keyframe: jboolean,
) {
    if !NATIVE_ENCODER_ACTIVE.load(SeqCst) {
        return;
    }
    let jb = JByteBuffer::from(buffer);
    let Ok(addr) = env.get_direct_buffer_address(&jb) else {
        return;
    };
    let Ok(cap) = env.get_direct_buffer_capacity(&jb) else {
        return;
    };
    if addr.is_null() || cap == 0 {
        return;
    }
    let slice = unsafe { std::slice::from_raw_parts(addr, cap) };
    let frame = EncodedVideoFrameJni {
        data: slice.to_vec(),
        pts_ms,
        is_keyframe: is_keyframe.eq(&1),
    };
    let Ok(mut q) = ENCODED_VIDEO_QUEUE.lock() else {
        return;
    };
    // Drop-old: keep only the freshest ENCODED_VIDEO_QUEUE_MAX frames. For
    // a remote-control session, an older P-frame is useless once a newer
    // one exists — better to skip than to send and lag.
    while q.len() >= ENCODED_VIDEO_QUEUE_MAX {
        q.pop_front();
    }
    q.push_back(frame);
}

/// Toggle the native-encoder path. Called from Kotlin around start/stop of
/// the MediaCodec encoder. When transitioning off, also drains the queue so
/// no stale data leaks into the next session.
#[no_mangle]
pub extern "system" fn Java_ffi_FFI_setNativeEncoderActive(
    _env: JNIEnv,
    _class: JClass,
    active: jboolean,
) {
    let on = active.eq(&1);
    NATIVE_ENCODER_ACTIVE.store(on, SeqCst);
    if !on {
        if let Ok(mut q) = ENCODED_VIDEO_QUEUE.lock() {
            q.clear();
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ffi_FFI_onAudioFrameUpdate(
    env: JNIEnv,
    _class: JClass,
    buffer: JObject,
) {
    let jb = JByteBuffer::from(buffer);
    if let Ok(data) = env.get_direct_buffer_address(&jb) {
        if let Ok(len) = env.get_direct_buffer_capacity(&jb) {
            AUDIO_RAW.lock().unwrap().update(data, len);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ffi_FFI_onClipboardUpdate(
    env: JNIEnv,
    _class: JClass,
    buffer: JByteBuffer,
) {
    if let Ok(data) = env.get_direct_buffer_address(&buffer) {
        if let Ok(len) = env.get_direct_buffer_capacity(&buffer) {
            let data = unsafe { std::slice::from_raw_parts(data, len) };
            if let Ok(clips) = MultiClipboards::parse_from_bytes(&data[1..]) {
                let is_client = data[0] == 1;
                if is_client {
                    *CLIPBOARDS_CLIENT.lock().unwrap() = Some(clips);
                } else {
                    *CLIPBOARDS_HOST.lock().unwrap() = Some(clips);
                }
            }
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ffi_FFI_setFrameRawEnable(
    env: JNIEnv,
    _class: JClass,
    name: JString,
    value: jboolean,
) {
    let mut env = env;
    if let Ok(name) = env.get_string(&name) {
        let name: String = name.into();
        let value = value.eq(&1);
        if name.eq("video") {
            VIDEO_RAW.lock().unwrap().set_enable(value);
        } else if name.eq("audio") {
            AUDIO_RAW.lock().unwrap().set_enable(value);
        }
    };
}

#[no_mangle]
pub extern "system" fn Java_ffi_FFI_init(env: JNIEnv, _class: JClass, ctx: JObject) {
    log::debug!("MainService init from java");
    if let Ok(jvm) = env.get_java_vm() {
        let java_vm = jvm.get_java_vm_pointer() as *mut c_void;
        let mut jvm_lock = JVM.write().unwrap();
        if jvm_lock.is_none() {
            *jvm_lock = Some(jvm);
        }
        drop(jvm_lock);
        if let Ok(context) = env.new_global_ref(ctx) {
            let context_jobject = context.as_obj().as_raw() as *mut c_void;
            *MAIN_SERVICE_CTX.write().unwrap() = Some(context);
            init_ndk_context(java_vm, context_jobject);
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_ffi_FFI_setClipboardManager(
    env: JNIEnv,
    _class: JClass,
    clipboard_manager: JObject,
) {
    log::debug!("ClipboardManager init from java");
    if let Ok(jvm) = env.get_java_vm() {
        let java_vm = jvm.get_java_vm_pointer() as *mut c_void;
        let mut jvm_lock = JVM.write().unwrap();
        if jvm_lock.is_none() {
            *jvm_lock = Some(jvm);
        }
        drop(jvm_lock);
        if let Ok(manager) = env.new_global_ref(clipboard_manager) {
            *CLIPBOARD_MANAGER.write().unwrap() = Some(manager);
        }
    }
}

#[derive(Debug, Deserialize, Clone)]
pub struct MediaCodecInfo {
    pub name: String,
    pub is_encoder: bool,
    #[serde(default)]
    pub hw: Option<bool>, // api 29+
    pub mime_type: String,
    pub surface: bool,
    pub nv12: bool,
    #[serde(default)]
    pub low_latency: Option<bool>, // api 30+, decoder
    pub min_bitrate: u32,
    pub max_bitrate: u32,
    pub min_width: usize,
    pub max_width: usize,
    pub min_height: usize,
    pub max_height: usize,
}

#[derive(Debug, Deserialize, Clone)]
pub struct MediaCodecInfos {
    pub version: usize,
    pub w: usize, // aligned
    pub h: usize, // aligned
    pub codecs: Vec<MediaCodecInfo>,
}

#[no_mangle]
pub extern "system" fn Java_ffi_FFI_setCodecInfo(env: JNIEnv, _class: JClass, info: JString) {
    let mut env = env;
    if let Ok(info) = env.get_string(&info) {
        let info: String = info.into();
        if let Ok(infos) = serde_json::from_str::<MediaCodecInfos>(&info) {
            *MEDIA_CODEC_INFOS.write().unwrap() = Some(infos);
        }
    }
}

pub fn get_codec_info() -> Option<MediaCodecInfos> {
    MEDIA_CODEC_INFOS.read().unwrap().as_ref().cloned()
}

pub fn clear_codec_info() {
    *MEDIA_CODEC_INFOS.write().unwrap() = None;
}

// another way to fix "reference table overflow" error caused by new_string and call_main_service_pointer_input frequently calld
// is below, but here I change kind from string to int for performance
/*
        env.with_local_frame(10, || {
            let kind = env.new_string(kind)?;
            env.call_method(
                ctx,
                "rustPointerInput",
                "(Ljava/lang/String;III)V",
                &[
                    JValue::Object(&JObject::from(kind)),
                    JValue::Int(mask),
                    JValue::Int(x),
                    JValue::Int(y),
                ],
            )?;
            Ok(JObject::null())
        })?;
*/
pub fn call_main_service_pointer_input(kind: &str, mask: i32, x: i32, y: i32) -> JniResult<()> {
    if let (Some(jvm), Some(ctx)) = (
        JVM.read().unwrap().as_ref(),
        MAIN_SERVICE_CTX.read().unwrap().as_ref(),
    ) {
        let mut env = jvm.attach_current_thread_as_daemon()?;
        let kind = if kind == "touch" { 0 } else { 1 };
        env.call_method(
            ctx,
            "rustPointerInput",
            "(IIII)V",
            &[
                JValue::Int(kind),
                JValue::Int(mask),
                JValue::Int(x),
                JValue::Int(y),
            ],
        )?;
        return Ok(());
    } else {
        return Err(JniError::ThrowFailed(-1));
    }
}

pub fn call_main_service_key_event(data: &[u8]) -> JniResult<()> {
    if let (Some(jvm), Some(ctx)) = (
        JVM.read().unwrap().as_ref(),
        MAIN_SERVICE_CTX.read().unwrap().as_ref(),
    ) {
        let mut env = jvm.attach_current_thread_as_daemon()?;
        let data = env.byte_array_from_slice(data)?;

        env.call_method(
            ctx,
            "rustKeyEventInput",
            "([B)V",
            &[JValue::Object(&JObject::from(data))],
        )?;
        return Ok(());
    } else {
        return Err(JniError::ThrowFailed(-1));
    }
}

fn _call_clipboard_manager<S, T>(name: S, sig: T, args: &[JValue]) -> JniResult<()>
where
    S: Into<JNIString>,
    T: Into<JNIString> + AsRef<str>,
{
    if let (Some(jvm), Some(cm)) = (
        JVM.read().unwrap().as_ref(),
        CLIPBOARD_MANAGER.read().unwrap().as_ref(),
    ) {
        let mut env = jvm.attach_current_thread()?;
        env.call_method(cm, name, sig, args)?;
        return Ok(());
    } else {
        return Err(JniError::ThrowFailed(-1));
    }
}

pub fn call_clipboard_manager_update_clipboard(data: &[u8]) -> JniResult<()> {
    if let (Some(jvm), Some(cm)) = (
        JVM.read().unwrap().as_ref(),
        CLIPBOARD_MANAGER.read().unwrap().as_ref(),
    ) {
        let mut env = jvm.attach_current_thread()?;
        let data = env.byte_array_from_slice(data)?;

        env.call_method(
            cm,
            "rustUpdateClipboard",
            "([B)V",
            &[JValue::Object(&JObject::from(data))],
        )?;
        return Ok(());
    } else {
        return Err(JniError::ThrowFailed(-1));
    }
}

pub fn call_clipboard_manager_enable_client_clipboard(enable: bool) -> JniResult<()> {
    _call_clipboard_manager(
        "rustEnableClientClipboard",
        "(Z)V",
        &[JValue::Bool(jboolean::from(enable))],
    )
}

pub fn call_main_service_get_by_name(name: &str) -> JniResult<String> {
    if let (Some(jvm), Some(ctx)) = (
        JVM.read().unwrap().as_ref(),
        MAIN_SERVICE_CTX.read().unwrap().as_ref(),
    ) {
        let mut env = jvm.attach_current_thread_as_daemon()?;
        let res = env.with_local_frame(10, |env| -> JniResult<String> {
            let name = env.new_string(name)?;
            let res = env
                .call_method(
                    ctx,
                    "rustGetByName",
                    "(Ljava/lang/String;)Ljava/lang/String;",
                    &[JValue::Object(&JObject::from(name))],
                )?
                .l()?;
            let res = JString::from(res);
            let res = env.get_string(&res)?;
            let res = res.to_string_lossy().to_string();
            Ok(res)
        })?;
        Ok(res)
    } else {
        return Err(JniError::ThrowFailed(-1));
    }
}

/// Push a new target bitrate (kbps) into the native MediaCodec encoder.
/// Wraps the existing `rustSetByName("set_encoder_bitrate", kbps, "")`
/// channel — Kotlin side dispatches `MediaCodec.setParameters(KEY_VIDEO_BITRATE)`.
/// No-op (and returns Ok) when the native encoder is not active.
pub fn call_main_service_set_encoder_bitrate(kbps: u32) -> JniResult<()> {
    if !NATIVE_ENCODER_ACTIVE.load(SeqCst) {
        return Ok(());
    }
    call_main_service_set_by_name("set_encoder_bitrate", Some(&kbps.to_string()), None)
}

/// Ask the native MediaCodec encoder to produce a sync frame (IDR) as soon as
/// possible. Used for fresh subscribers and after a "switch" event.
pub fn call_main_service_request_key_frame() -> JniResult<()> {
    if !NATIVE_ENCODER_ACTIVE.load(SeqCst) {
        return Ok(());
    }
    call_main_service_set_by_name("request_key_frame", None, None)
}

pub fn call_main_service_set_by_name(
    name: &str,
    arg1: Option<&str>,
    arg2: Option<&str>,
) -> JniResult<()> {
    if let (Some(jvm), Some(ctx)) = (
        JVM.read().unwrap().as_ref(),
        MAIN_SERVICE_CTX.read().unwrap().as_ref(),
    ) {
        let mut env = jvm.attach_current_thread_as_daemon()?;
        env.with_local_frame(10, |env| -> JniResult<()> {
            let name = env.new_string(name)?;
            let arg1 = env.new_string(arg1.unwrap_or(""))?;
            let arg2 = env.new_string(arg2.unwrap_or(""))?;

            env.call_method(
                ctx,
                "rustSetByName",
                "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
                &[
                    JValue::Object(&JObject::from(name)),
                    JValue::Object(&JObject::from(arg1)),
                    JValue::Object(&JObject::from(arg2)),
                ],
            )?;
            Ok(())
        })?;
        return Ok(());
    } else {
        return Err(JniError::ThrowFailed(-1));
    }
}

// Difference between MainService, MainActivity, JNI_OnLoad:
//  jvm is the same, ctx is differen and ctx of JNI_OnLoad is null.
//  cpal: all three works
//  Service(GetByName, ...): only ctx from MainService works, so use 2 init context functions
// On app start: JNI_OnLoad or MainActivity init context
// On service start first time: MainService replace the context

fn init_ndk_context(java_vm: *mut c_void, context_jobject: *mut c_void) {
    let mut lock = NDK_CONTEXT_INITED.lock().unwrap();
    if *lock {
        unsafe {
            ndk_context::release_android_context();
        }
        *lock = false;
    }
    unsafe {
        ndk_context::initialize_android_context(java_vm, context_jobject);
        #[cfg(feature = "hwcodec")]
        hwcodec::android::ffmpeg_set_java_vm(java_vm);
    }
    *lock = true;
}

fn try_init_rustls_platform_verifier(env: &mut JNIEnv, context_jobject: *mut c_void) {
    use hbb_common::config::ANDROID_RUSTLS_PLATFORM_VERIFIER_INITIALIZED as INITIALIZED;
    use std::sync::atomic::Ordering;
    let initialized = INITIALIZED.load(Ordering::Relaxed);
    if !initialized {
        let ctx_for_rustls = unsafe { JObject::from_raw(context_jobject as jni::sys::jobject) };
        if let Err(e) =
            hbb_common::rustls_platform_verifier::android::init_hosted(env, ctx_for_rustls)
        {
            log::error!("Failed to initialize rustls-platform-verifier: {:?}", e);
        } else {
            INITIALIZED.store(true, Ordering::Relaxed);
            log::info!("rustls-platform-verifier initialized successfully");
        }
    }
}

// https://cjycode.com/flutter_rust_bridge/guides/how-to/ndk-init
#[no_mangle]
pub extern "C" fn JNI_OnLoad(vm: jni::JavaVM, res: *mut std::os::raw::c_void) -> jni::sys::jint {
    if let Ok(env) = vm.get_env() {
        let vm = vm.get_java_vm_pointer() as *mut std::os::raw::c_void;
        init_ndk_context(vm, res);
    }
    jni::JNIVersion::V6.into()
}

#[no_mangle]
pub extern "system" fn Java_ffi_FFI_onAppStart(mut env: JNIEnv, _class: JClass, ctx: JObject) {
    if ctx.is_null() {
        log::error!("application context is null");
        return;
    }
    if APPLICATION_CONTEXT.read().unwrap().is_some() {
        log::info!("application context already initialized");
        return;
    }
    if let Ok(jvm) = env.get_java_vm() {
        if let Ok(context) = env.new_global_ref(ctx) {
            let java_vm = jvm.get_java_vm_pointer() as *mut c_void;
            let context_jobject = context.as_obj().as_raw() as *mut c_void;
            *APPLICATION_CONTEXT.write().unwrap() = Some(context);
            try_init_rustls_platform_verifier(&mut env, context_jobject);
        }
    }
}

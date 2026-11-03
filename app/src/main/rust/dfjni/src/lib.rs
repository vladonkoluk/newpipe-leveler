//! JNI-обвязка DeepFilterNet3 для NewPipe Leveler: покадровое шумоподавление
//! (48 кГц, моно, кадр 480 сэмплов, звук в диапазоне −1..1).
//! Модель DeepFilterNet3 встроена в библиотеку (feature `default-model` в libDF).
//! Методы связаны с `org.schabi.newpipe.player.leveler.DeepFilterNet`.

use std::panic::{catch_unwind, AssertUnwindSafe};

use df::tract::{DfParams, DfTract, ReduceMask, RuntimeParams};
use jni::objects::{JClass, JFloatArray};
use jni::sys::{jfloat, jint, jlong};
use jni::JNIEnv;
use ndarray::{ArrayView2, ArrayViewMut2};

struct State {
    model: DfTract,
    input: Vec<f32>,
    output: Vec<f32>,
}

/// Создаёт обработчик. `atten_lim_db` — предел подавления (100 — без предела).
/// Возвращает 0, если модель не удалось загрузить.
#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_player_leveler_DeepFilterNet_nativeCreate(
    _env: JNIEnv,
    _class: JClass,
    atten_lim_db: jfloat,
) -> jlong {
    catch_unwind(|| {
        // те же параметры, что у официального C-интерфейса libDF (capi.rs)
        let params = RuntimeParams::default_with_ch(1)
            .with_atten_lim(atten_lim_db)
            .with_thresholds(-15.0, 35.0, 35.0)
            .with_post_filter(0.0)
            .with_mask_reduce(ReduceMask::MAX);
        let model = DfTract::new(DfParams::default(), &params).ok()?;
        let hop = model.hop_size;
        let state = State { model, input: vec![0.0; hop], output: vec![0.0; hop] };
        Some(Box::into_raw(Box::new(state)) as jlong)
    })
    .ok()
    .flatten()
    .unwrap_or(0)
}

/// Длина кадра в сэмплах (у DeepFilterNet3 — 480 при 48 кГц).
#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_player_leveler_DeepFilterNet_nativeFrameLength(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jint {
    if handle == 0 {
        return 0;
    }
    let state = unsafe { &*(handle as *const State) };
    state.model.hop_size as jint
}

#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_player_leveler_DeepFilterNet_nativeSetAttenLim(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
    atten_lim_db: jfloat,
) {
    if handle == 0 {
        return;
    }
    let state = unsafe { &mut *(handle as *mut State) };
    let _ = catch_unwind(AssertUnwindSafe(|| state.model.set_atten_lim(atten_lim_db)));
}

/// Обрабатывает кадр на месте. Возвращает локальное отношение сигнал/шум кадра, dB
/// (по нему Java решает, звучит ли речь), или −100 при ошибке.
#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_player_leveler_DeepFilterNet_nativeProcess<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    frame: JFloatArray<'local>,
) -> jfloat {
    if handle == 0 {
        return -100.0;
    }
    let state = unsafe { &mut *(handle as *mut State) };
    if env.get_float_array_region(&frame, 0, &mut state.input).is_err() {
        return -100.0;
    }
    let hop = state.input.len();
    let result = catch_unwind(AssertUnwindSafe(|| {
        let noisy = ArrayView2::from_shape((1, hop), &state.input[..]).ok()?;
        let enh = ArrayViewMut2::from_shape((1, hop), &mut state.output[..]).ok()?;
        state.model.process(noisy, enh).ok()
    }));
    match result {
        Ok(Some(lsnr)) => {
            if env.set_float_array_region(&frame, 0, &state.output).is_err() {
                return -100.0;
            }
            lsnr
        }
        _ => -100.0,
    }
}

#[no_mangle]
pub extern "system" fn Java_org_schabi_newpipe_player_leveler_DeepFilterNet_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut State) });
    }
}

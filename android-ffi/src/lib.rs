//! Hàm JNI cho `com.hannom.ime.NativeCore` và `com.hannom.ime.NativeHandwriting` (Kotlin).
//!
//! Chuỗi đi qua UTF-16 (`GetStringChars`/`NewString`) vì `NewStringUTF` dùng "modified UTF-8",
//! không nhận ký tự ngoài BMP như 𡨸. Danh sách trả về là chuỗi nối bằng ký tự phân cách
//! `RS` (giữa các bản ghi) và `US` (giữa các trường) để giữ giao diện JNI đơn giản.
//!
//! Engine chỉ được dùng từ luồng chính của bàn phím; handle là con trỏ `Box<Engine>`.

#![allow(non_snake_case)]
#![allow(clippy::missing_safety_doc)]

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::ptr;

use hannom_core::dict::Entry;
use hannom_core::{compose, compose_joined, compose_words, decompose, decompose_with, display, display_telex, backspace, backspace_telex, normalize, Dictionary, Engine, ImportReport, Part, ToneStyle};
use hannom_handwriting::{Memory, Recognizer, Stroke};
use jni_sys::{jboolean, jbyte, jbyteArray, jclass, jfloat, jfloatArray, jint, jlong, jsize, jstring, JNIEnv, JNI_TRUE};

const RS: char = '\u{1E}';
const US: char = '\u{1F}';

unsafe fn from_java(env: *mut JNIEnv, s: jstring) -> String {
    if s.is_null() {
        return String::new();
    }
    let f = &**env;
    let len = (f.GetStringLength.unwrap())(env, s);
    let chars = (f.GetStringChars.unwrap())(env, s, ptr::null_mut());
    if chars.is_null() {
        return String::new();
    }
    let out = String::from_utf16_lossy(std::slice::from_raw_parts(chars, len as usize));
    (f.ReleaseStringChars.unwrap())(env, s, chars);
    out
}

unsafe fn to_java(env: *mut JNIEnv, s: &str) -> jstring {
    let units: Vec<u16> = s.encode_utf16().collect();
    ((**env).NewString.unwrap())(env, units.as_ptr(), units.len() as jsize)
}

/// Chặn panic không cho vượt qua ranh giới FFI (sẽ làm sập ứng dụng đang gõ).
fn guard<T>(fallback: T, f: impl FnOnce() -> T) -> T {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(fallback)
}

unsafe fn engine<'a>(handle: jlong) -> &'a mut Engine {
    &mut *(handle as *mut Engine)
}

fn tone_style(v: jint) -> ToneStyle {
    if v == 1 {
        ToneStyle::New
    } else {
        ToneStyle::Old
    }
}

fn report_text(r: &ImportReport) -> String {
    let mut out = format!("{}{US}{}", r.added, r.duplicates);
    for e in &r.errors {
        out.push(US);
        out.push_str(&e.to_string());
    }
    out
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_create(
    env: *mut JNIEnv,
    _: jclass,
    dict: jstring,
) -> jlong {
    let dict = from_java(env, dict);
    guard(0, || Box::into_raw(Box::new(Engine::new(Dictionary::from_text(&dict).0))) as jlong)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_destroy(_: *mut JNIEnv, _: jclass, handle: jlong) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut Engine));
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_setOptions(
    _: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    style: jint,
    dz: jboolean,
    learning: jboolean,
    telex: jboolean,
) {
    let e = engine(handle);
    e.options.tone_style = tone_style(style);
    e.options.dz = dz == JNI_TRUE;
    e.options.learning = learning == JNI_TRUE;
    e.options.telex = telex == JNI_TRUE;
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_compose(
    env: *mut JNIEnv,
    _: jclass,
    keys: jstring,
    style: jint,
    hannom: jboolean,
    dz: jboolean,
) -> jstring {
    let keys = from_java(env, keys);
    let out = guard(keys.clone(), || {
        if hannom == JNI_TRUE {
            compose_words(&keys, tone_style(style), dz == JNI_TRUE)
        } else {
            compose(&keys, tone_style(style))
        }
    });
    to_java(env, &out)
}

/// Chuỗi phím Hán Nôm chuẩn hoá: mỗi chữ một dấu thanh, một dấu phụ (`tasf` → `taf`).
#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_normalize(
    env: *mut JNIEnv,
    _: jclass,
    keys: jstring,
    dz: jboolean,
) -> jstring {
    let keys = from_java(env, keys);
    let out = guard(keys.clone(), || normalize(&keys, dz == JNI_TRUE));
    to_java(env, &out)
}

/// Chữ hiện khi đang gõ, dấu thanh là ký hiệu ở cuối: `tots` → `totˊ`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_display(
    env: *mut JNIEnv,
    _: jclass,
    keys: jstring,
    dz: jboolean,
) -> jstring {
    let keys = from_java(env, keys);
    let out = guard(keys.clone(), || display(&keys, dz == JNI_TRUE));
    to_java(env, &out)
}

/// Khung gõ của bàn phím Hán Nôm kiểu Telex: các chữ đã ghép dấu, cách nhau một khoảng (`chuwxnoom` → chữ nôm).
#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_displayTelex(
    env: *mut JNIEnv,
    _: jclass,
    keys: jstring,
    style: jint,
) -> jstring {
    let keys = from_java(env, keys);
    let out = guard(keys.clone(), || display_telex(&keys, tone_style(style)));
    to_java(env, &out)
}

/// Phím xoá của bàn phím Hán Nôm kiểu Telex: phím dấu thanh đã thay dấu cũ thì xoá hẳn dấu (`asf` → `a`).
#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_backspaceTelex(
    env: *mut JNIEnv,
    _: jclass,
    keys: jstring,
    style: jint,
) -> jstring {
    let keys = from_java(env, keys);
    let fallback: String = keys.chars().take(keys.chars().count().saturating_sub(1)).collect();
    let out = guard(fallback, || backspace_telex(&keys, tone_style(style)));
    to_java(env, &out)
}

/// Chuỗi phím sinh ra một chữ: Telex `ệ` → `eej`; kiểu Hán Nôm `ệ` → `eqj`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_decompose(
    env: *mut JNIEnv,
    _: jclass,
    word: jstring,
    hannom: jboolean,
    dz: jboolean,
) -> jstring {
    let word = from_java(env, word);
    let out = guard(word.clone(), || {
        if hannom == JNI_TRUE {
            decompose_with(&word, dz == JNI_TRUE)
        } else {
            decompose(&word)
        }
    });
    to_java(env, &out)
}

/// Xoá lùi kiểu Unikey (chế độ Quốc ngữ): bỏ ký tự cuối của chữ hiển thị, trả lại chuỗi phím mới.
#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_backspace(
    env: *mut JNIEnv,
    _: jclass,
    keys: jstring,
    style: jint,
) -> jstring {
    let keys = from_java(env, keys);
    let out = guard(String::new(), || backspace(&keys, tone_style(style)));
    to_java(env, &out)
}

/// Gợi ý: mỗi bản ghi `chữ US âm đọc US số phím đã dùng US 1 nếu là gõ tắt`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_candidates(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    keys: jstring,
    limit: jint,
    quoc_ngu: jboolean,
) -> jstring {
    let keys = from_java(env, keys);
    let out = guard(String::new(), || {
        let e = engine(handle);
        let cands = if quoc_ngu == JNI_TRUE { e.readings(&keys) } else { e.candidates(&keys) };
        candidate_records(&cands, limit)
    });
    to_java(env, &out)
}

/// Mỗi gợi ý một bản ghi: chữ, âm đọc, số phím, gõ tắt, vị trí tách âm tiết, ghép câu.
fn candidate_records(cands: &[hannom_core::Candidate], limit: jint) -> String {
    let records: Vec<String> = cands
        .iter()
        .take(limit.max(0) as usize)
        .map(|c| {
            let breaks: Vec<String> = c.breaks.iter().map(usize::to_string).collect();
            format!("{}{US}{}{US}{}{US}{}{US}{}{US}{}", c.text, c.reading, c.consumed, u8::from(c.abbreviated), breaks.join(","), u8::from(c.joined))
        })
        .collect();
    records.join(&RS.to_string())
}

/// Các lựa chọn đã chọn ở cột trái, mỗi lựa chọn một bản ghi `cấp US giá trị` ([`Engine::narrow`]).
fn picked_parts(raw: &str) -> Vec<(Part, String)> {
    raw.split(RS)
        .filter_map(|r| {
            let (level, value) = r.split_once(US)?;
            Some((Part::from_index(level.parse().ok()?)?, value.to_string()))
        })
        .collect()
}

/// Cấp thu hẹp kế tiếp ([`Engine::narrow`]): `cấp US lựa chọn US lựa chọn…`; rỗng nếu hết cấp.
#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_narrow(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    keys: jstring,
    picked: jstring,
) -> jstring {
    let keys = from_java(env, keys);
    let picked = picked_parts(&from_java(env, picked));
    let out = guard(String::new(), || match engine(handle).narrow(&keys, &picked) {
        Some((level, options)) => std::iter::once((level as usize).to_string()).chain(options).collect::<Vec<_>>().join(&US.to_string()),
        None => String::new(),
    });
    to_java(env, &out)
}

/// Các gợi ý có âm tiết đầu khớp các lựa chọn ([`Engine::candidates_narrowed`]), như `candidates`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_candidatesNarrowed(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    keys: jstring,
    picked: jstring,
    limit: jint,
) -> jstring {
    let keys = from_java(env, keys);
    let picked = picked_parts(&from_java(env, picked));
    let out = guard(String::new(), || candidate_records(&engine(handle).candidates_narrowed(&keys, &picked), limit));
    to_java(env, &out)
}

/// Gợi ý tiếp theo chữ đã chọn `before` ([`Engine::after`]): mỗi bản ghi là chữ, âm đọc, số phím, rồi cả
/// cụm (chữ, âm đọc) để học khi chốt.
#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_candidatesAfter(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    before: jstring,
    keys: jstring,
    limit: jint,
) -> jstring {
    let before = from_java(env, before);
    let keys = from_java(env, keys);
    let out = guard(String::new(), || {
        let records: Vec<String> = engine(handle)
            .after(&before, &keys)
            .iter()
            .take(limit.max(0) as usize)
            .map(|(c, text, reading)| format!("{}{US}{}{US}{}{US}{}{US}{}", c.text, c.reading, c.consumed, text, reading))
            .collect();
        records.join(&RS.to_string())
    });
    to_java(env, &out)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_select(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    text: jstring,
    reading: jstring,
) -> jboolean {
    let (text, reading) = (from_java(env, text), from_java(env, reading));
    let changed = guard(false, || {
        let e = engine(handle);
        e.options.learning && e.dict.bump(&text, &reading)
    });
    changed as jboolean
}

/// Xoá dữ liệu học: độ phổ biến về như từ điển mặc định `defaults`, cặp tự thêm về 0.
#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_clearLearned(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    defaults: jstring,
) {
    let defaults = from_java(env, defaults);
    guard((), || engine(handle).dict.reset_freqs(&Dictionary::from_text(&defaults).0));
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_dictText(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
) -> jstring {
    let out = guard(String::new(), || engine(handle).dict.to_text());
    to_java(env, &out)
}

/// Thay cả từ điển bằng nội dung `content` (nhập lại từ điển mặc định); dòng lỗi bị bỏ qua.
#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_dictLoad(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    content: jstring,
) {
    let content = from_java(env, content);
    guard((), || engine(handle).dict = Dictionary::from_text(&content).0);
}

/// Gộp nhiều tệp (nội dung nối bằng `RS`) vào từ điển, lần lượt từng tệp.
/// Mỗi tệp một bản ghi `số mục thêm US số mục trùng US lỗi 1 US lỗi 2 ...`; số mục trùng tính
/// cả mục đã có ở tệp trước. `apply` sai thì chỉ xem trước, không đổi từ điển.
#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_dictImport(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    contents: jstring,
    apply: jboolean,
) -> jstring {
    let contents = from_java(env, contents);
    let out = guard(String::new(), || {
        let e = engine(handle);
        let mut preview;
        let dict = if apply == JNI_TRUE {
            &mut e.dict
        } else {
            preview = e.dict.clone();
            &mut preview
        };
        let reports: Vec<String> = contents.split(RS).map(|c| report_text(&dict.import(c))).collect();
        reports.join(&RS.to_string())
    });
    to_java(env, &out)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_dictClear(_: *mut JNIEnv, _: jclass, handle: jlong) {
    engine(handle).dict.clear();
}

/// Từ điển, mỗi cặp chữ – âm một bản ghi `chữ US âm US độ phổ biến`, xếp a → z.
#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_dictRows(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    query: jstring,
) -> jstring {
    let query = from_java(env, query);
    let out = guard(String::new(), || {
        let records: Vec<String> =
            engine(handle).rows(&query).iter().map(|r| format!("{}{US}{}{US}{}", r.text, r.reading, r.freq)).collect();
        records.join(&RS.to_string())
    });
    to_java(env, &out)
}

fn capitalize(msg: &str) -> String {
    let mut chars = msg.chars();
    chars.next().map(|c| c.to_uppercase().chain(chars).collect()).unwrap_or_default()
}

/// Thêm (`old_text` rỗng) các cặp `text` – từng âm trong `readings` (`cố,có`), hoặc sửa cặp
/// `old_text` – `old_reading` thành `text` – `readings` (một âm); độ phổ biến `freq`.
/// Trả chuỗi rỗng nếu thành công, ngược lại là lỗi (không đổi gì).
#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_dictPut(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    old_text: jstring,
    old_reading: jstring,
    text: jstring,
    readings: jstring,
    freq: jint,
) -> jstring {
    let (old_text, old_reading) = (from_java(env, old_text), from_java(env, old_reading));
    let (text, readings) = (from_java(env, text), from_java(env, readings));
    let out = guard("Lỗi không xác định".to_string(), || {
        let entries = match Entry::parse_many(&text, &readings) {
            Ok(e) => e,
            Err(err) => return capitalize(&err.to_string()),
        };
        let dict = &mut engine(handle).dict;
        let freq = freq.max(0) as u32;
        if old_text.is_empty() {
            if let Some(e) = entries.iter().find(|e| dict.freq_of(&e.text, &e.reading).is_some()) {
                return format!("Đã có {} – {} trong từ điển", e.text, e.reading);
            }
            entries.into_iter().for_each(|e| {
                dict.put(None, e, freq);
            });
            return String::new();
        }
        if entries.len() != 1 {
            return "Sửa một dòng thì chỉ nhập một âm đọc".to_string();
        }
        let entry = entries.into_iter().next().unwrap();
        let old = Entry { text: old_text, reading: old_reading };
        if dict.put(Some(&old), entry.clone(), freq) {
            String::new()
        } else {
            format!("Đã có {} – {} trong từ điển", entry.text, entry.reading)
        }
    });
    to_java(env, &out)
}

/// Xoá một cặp chữ – âm.
#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_dictRemove(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    text: jstring,
    reading: jstring,
) {
    let (text, reading) = (from_java(env, text), from_java(env, reading));
    guard((), || {
        engine(handle).dict.remove(&Entry { text, reading });
    });
}

// ---- Viết tay (`com.hannom.ime.NativeHandwriting`) ----
//
// Bộ nhận dạng không đổi sau khi nạp nên gọi được từ luồng nền; handle là con trỏ `Box<Recognizer>`.

#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeHandwriting_create(
    env: *mut JNIEnv,
    _: jclass,
    model: jbyteArray,
) -> jlong {
    let f = &**env;
    let len = (f.GetArrayLength.unwrap())(env, model) as usize;
    let mut bytes = vec![0u8; len];
    (f.GetByteArrayRegion.unwrap())(env, model, 0, len as jsize, bytes.as_mut_ptr() as *mut jbyte);
    guard(0, || match Recognizer::from_bytes(&bytes) {
        Ok(r) => Box::into_raw(Box::new(r)) as jlong,
        Err(_) => 0,
    })
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeHandwriting_destroy(_: *mut JNIEnv, _: jclass, handle: jlong) {
    if handle != 0 {
        drop(Box::from_raw(handle as *mut Recognizer));
    }
}

/// `xy`: x, y nối liền, các nét ngăn bằng cặp NaN.
unsafe fn strokes_from_java(env: *mut JNIEnv, xy: jfloatArray) -> Vec<Stroke> {
    let f = &**env;
    let len = (f.GetArrayLength.unwrap())(env, xy) as usize;
    let mut buf = vec![0f32; len];
    (f.GetFloatArrayRegion.unwrap())(env, xy, 0, len as jsize, buf.as_mut_ptr());
    let mut strokes: Vec<Stroke> = vec![Vec::new()];
    for p in buf.chunks_exact(2) {
        if p[0].is_nan() {
            strokes.push(Vec::new());
        } else {
            strokes.last_mut().unwrap().push((p[0], p[1]));
        }
    }
    strokes.retain(|s| !s.is_empty());
    strokes
}

/// Các chữ khả dĩ nhất, mỗi chữ một dòng, đã xếp lại theo chữ người dùng dạy (`memory`, 0 = không có).
#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeHandwriting_recognize(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    memory: jlong,
    xy: jfloatArray,
    canvas: jfloat,
    limit: jint,
) -> jstring {
    let strokes = strokes_from_java(env, xy);
    let out = guard(String::new(), || {
        let r = &*(handle as *const Recognizer);
        let limit = limit.max(0) as usize;
        let Some(a) = r.analyze(&strokes, canvas, limit) else { return String::new() };
        let ranked = if memory == 0 {
            a.top.iter().map(|(c, _)| c.to_string()).collect()
        } else {
            (*(memory as *const Memory)).rank(&a, limit)
        };
        ranked.join("\n")
    });
    to_java(env, &out)
}

/// Nhận dạng nhiều chữ viết liền một dòng. Mỗi chữ (trái sang phải) một bản ghi: chỉ số các nét nối
/// bằng `,`, rồi các chữ khả dĩ nhất (đã xếp theo chữ người dùng dạy), các trường ngăn bằng `US`.
/// Có từ hai chữ trở lên thì thêm một bản ghi cuối, trường đầu `*`: gợi ý khi coi cả bảng là một chữ.
#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeHandwriting_recognizeLine(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    memory: jlong,
    xy: jfloatArray,
    canvas: jfloat,
    limit: jint,
) -> jstring {
    let strokes = strokes_from_java(env, xy);
    let out = guard(String::new(), || {
        let r = &*(handle as *const Recognizer);
        let limit = limit.max(0) as usize;
        let rank = |a: &hannom_handwriting::Analysis| -> Vec<String> {
            if memory == 0 {
                a.top.iter().map(|(c, _)| c.to_string()).collect()
            } else {
                (*(memory as *const Memory)).rank(a, limit)
            }
        };
        let (segments, alt) = r.recognize_line_alternatives(&strokes, canvas, limit);
        let mut records: Vec<String> = segments
            .iter()
            .map(|s| {
                let ids: Vec<String> = s.strokes.iter().map(|i| i.to_string()).collect();
                std::iter::once(ids.join(",")).chain(rank(&s.analysis)).collect::<Vec<_>>().join(&US.to_string())
            })
            .collect();
        if segments.len() > 1 {
            if let Some(a) = r.analyze(&strokes, canvas, limit) {
                records.push(std::iter::once("*".to_string()).chain(rank(&a)).collect::<Vec<_>>().join(&US.to_string()));
            }
        }
        // Cách tách có số chữ khác (𠊛 ↔ 人㝵): chữ đầu của từng chữ ghép lại.
        if let Some(alt) = alt {
            let text: String = alt.iter().filter_map(|s| rank(&s.analysis).into_iter().next()).collect();
            if !text.is_empty() {
                records.push(["~".to_string(), text].join(&US.to_string()));
            }
        }
        records.join(&RS.to_string())
    });
    to_java(env, &out)
}

// Chữ người dùng dạy: handle là con trỏ `Box<Memory>`, chỉ dùng trên luồng nền của `Handwriting.kt`.

#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeHandwriting_memoryLoad(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    text: jstring,
) -> jlong {
    let text = from_java(env, text);
    guard(0, || Box::into_raw(Box::new(Memory::from_text(&*(handle as *const Recognizer), &text))) as jlong)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeHandwriting_memoryFree(_: *mut JNIEnv, _: jclass, memory: jlong) {
    if memory != 0 {
        drop(Box::from_raw(memory as *mut Memory));
    }
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeHandwriting_memoryAdd(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    memory: jlong,
    xy: jfloatArray,
    canvas: jfloat,
    text: jstring,
) -> jboolean {
    let strokes = strokes_from_java(env, xy);
    let text = from_java(env, text);
    let ok = guard(false, || (*(memory as *mut Memory)).add(&*(handle as *const Recognizer), &text, &strokes, canvas));
    ok as jboolean
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeHandwriting_memoryText(
    env: *mut JNIEnv,
    _: jclass,
    handle: jlong,
    memory: jlong,
) -> jstring {
    let out = guard(String::new(), || (*(memory as *const Memory)).to_text(&*(handle as *const Recognizer)));
    to_java(env, &out)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeHandwriting_memorySize(_: *mut JNIEnv, _: jclass, memory: jlong) -> jint {
    guard(0, || (*(memory as *const Memory)).len() as jint)
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeHandwriting_memoryClear(_: *mut JNIEnv, _: jclass, memory: jlong) {
    guard((), || (*(memory as *mut Memory)).clear())
}

/// Âm đọc để thêm từ vào từ điển: ghép từng chữ như khung gõ, nối liền (`vmdhs` → vmđhs khi bật d/z).
#[no_mangle]
pub unsafe extern "system" fn Java_com_hannom_ime_NativeCore_composeJoined(
    env: *mut JNIEnv,
    _: jclass,
    keys: jstring,
    style: jint,
    dz: jboolean,
) -> jstring {
    let keys = from_java(env, keys);
    let out = guard(keys.clone(), || compose_joined(&keys, tone_style(style), dz == JNI_TRUE));
    to_java(env, &out)
}

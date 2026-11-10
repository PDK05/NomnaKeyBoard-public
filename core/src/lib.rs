//! Lõi Bàn phím Hán Nôm, dùng chung cho desktop và mobile.
//!
//! - [`telex`]: biến chuỗi phím Telex thành âm tiết có dấu (clone Unikey).
//! - [`syllable`]: kiểm tra cấu trúc âm tiết tiếng Việt, vị trí đặt dấu.
//! - [`dict`]: từ điển dạng `chữ;âm đọc`.
//! - [`segment`]: tách chuỗi phím gõ liền thành các âm tiết.
//! - [`engine`]: tra cứu, xếp hạng gợi ý Hán Nôm, học thói quen.

pub mod dict;
pub mod engine;
pub mod segment;
pub mod syllable;
pub mod telex;
pub mod text;

pub use dict::{Dictionary, Entry, EntryError, ImportReport, LineError};
pub use engine::{unicode_codes, Candidate, Engine, Options, Part, Row};
pub use syllable::ToneStyle;
pub use telex::{compose, compose_joined, compose_with, compose_words, decompose, decompose_with, display, display_telex, backspace, backspace_raw, backspace_telex, normalize, Composer};
pub use text::Tone;

//! Từ điển dạng văn bản `chữ;âm đọc;độ phổ biến`, mỗi dòng một cặp chữ – âm, `#` là chú thích.
//! Chữ có nhiều âm thì mỗi âm một dòng. Một "âm" không có dấu cách và không phải âm tiết tiếng Việt
//! là **mã gõ tắt** do người dùng đặt (`vn`, `k`).
//!
//! Độ phổ biến tính trên số mục của từ điển: tổng mọi độ phổ biến xấp xỉ số mục (trung bình mỗi mục 1,
//! chữ thông dụng hàng trăm, chữ hiếm 0). Gợi ý xếp theo số này; mỗi lần chọn cộng [`Dictionary::step`]
//! (một phần nghìn số mục); tổng vượt gấp đôi số mục thì mọi số chia đôi (thứ tự giữ nguyên, không tràn
//! số, thói quen cũ phai dần).
//!
//! ```text
//! 喃;nôm;30
//! 樂;lạc;12
//! 樂;nhạc;9
//! 𡨸喃;chữ nôm;1
//! 越南;việt nam;40
//! 越南;vn;0
//! ```

use std::collections::{BTreeMap, HashMap, HashSet};
use std::fmt;

use unicode_normalization::UnicodeNormalization;

use crate::syllable::{self, Check};
use crate::text::{self, Tone};

pub const MAX_TEXT_CHARS: usize = 16;
pub const MAX_SHORTCUT_CHARS: usize = 12;

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct Entry {
    pub text: String,
    /// Chữ thường, NFC, các âm tiết cách nhau một dấu cách; hoặc một mã gõ tắt.
    pub reading: String,
}

impl Entry {
    /// Kiểm tra và chuẩn hoá một mục từ điển.
    pub fn new(text: &str, reading: &str) -> Result<Entry, EntryError> {
        let text = text.trim();
        let reading = reading
            .nfc()
            .collect::<String>()
            .split_whitespace()
            .map(|s| s.chars().map(text::lower).collect::<String>())
            .collect::<Vec<_>>()
            .join(" ");
        if text.is_empty() {
            return Err(EntryError::EmptyText);
        }
        if reading.is_empty() {
            return Err(EntryError::EmptyReading);
        }
        let chars = text.chars().filter(|&c| !is_variation_selector(c)).count();
        if chars > MAX_TEXT_CHARS {
            return Err(EntryError::TextTooLong(chars));
        }
        if is_shortcut(&reading) {
            let valid = reading.chars().count() <= MAX_SHORTCUT_CHARS && reading.chars().all(char::is_alphabetic);
            if !valid {
                return Err(EntryError::InvalidSyllable(reading));
            }
            return Ok(Entry { text: text.to_string(), reading });
        }
        if let Some(syl) = reading.split(' ').find(|s| !is_syllable(s)) {
            return Err(EntryError::InvalidSyllable(syl.to_string()));
        }
        // Số chữ và số âm tiết không bắt phải bằng nhau (媽媽;ma): người dùng tự quyết định.
        Ok(Entry { text: text.to_string(), reading })
    }

    /// Chữ với một hoặc nhiều âm ngăn cách bằng `,` (`lạc,nhạc`), như ô nhập ở màn hình sửa từ điển.
    /// Dấu phẩy Hán `，` và dấu ngắt `、` cũng là dấu ngăn cách như `,` ([`is_reading_separator`]).
    /// Chỉ cần một âm sai là cả nhóm bị từ chối.
    pub fn parse_many(text: &str, readings: &str) -> Result<Vec<Entry>, EntryError> {
        readings.split(is_reading_separator).map(|r| Entry::new(text, r)).collect()
    }

    /// Âm đọc là mã gõ tắt người dùng đặt (`vn`), không phải âm tiết.
    pub fn is_shortcut(&self) -> bool {
        is_shortcut(&self.reading)
    }

    /// Khoá tra cứu: âm đọc (hoặc mã gõ tắt) bỏ hết dấu.
    pub fn key(&self) -> String {
        text::fold_key(&self.reading)
    }

    /// Khoá của mã gõ tắt: chữ thường, giữ `đ` (mã `vmđhs` khác `vmdhs`).
    pub fn shortcut_key(&self) -> String {
        self.reading.chars().map(text::lower).collect()
    }

    /// Chữ cái đầu của mỗi âm tiết (`việt nam` → `vn`), dùng cho gõ tắt.
    pub fn initials(&self) -> String {
        self.key().split(' ').filter_map(|s| s.chars().next()).collect()
    }
}

fn is_syllable(s: &str) -> bool {
    text::split_syllable(s).is_some_and(|(l, t)| syllable::check(&l, t, Check::Strict))
}

/// Một từ không dấu cách, không phải âm tiết tiếng Việt: coi là mã gõ tắt.
pub fn is_shortcut(reading: &str) -> bool {
    !reading.contains(' ') && !is_syllable(reading)
}

/// Dấu ngăn các âm đọc: `,`, dấu phẩy Hán `，` hay dấu ngắt `、`, đều như nhau.
pub fn is_reading_separator(c: char) -> bool {
    matches!(c, ',' | '，' | '、')
}

fn is_variation_selector(c: char) -> bool {
    matches!(c, '\u{FE00}'..='\u{FE0F}' | '\u{E0100}'..='\u{E01EF}')
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum EntryError {
    MissingSeparator,
    MissingFreq,
    TooManyFields,
    ManyReadings,
    InvalidFreq(String),
    EmptyText,
    EmptyReading,
    TextTooLong(usize),
    InvalidSyllable(String),
}

impl fmt::Display for EntryError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            EntryError::MissingSeparator => write!(f, "thiếu dấu ; (mỗi dòng có dạng chữ;âm đọc;độ phổ biến)"),
            EntryError::MissingFreq => write!(f, "thiếu độ phổ biến (mỗi dòng có dạng chữ;âm đọc;độ phổ biến)"),
            EntryError::TooManyFields => write!(f, "thừa dấu ; (mỗi dòng có dạng chữ;âm đọc;độ phổ biến)"),
            EntryError::ManyReadings => write!(f, "mỗi dòng chỉ một âm đọc; chữ có nhiều âm thì viết nhiều dòng"),
            EntryError::InvalidFreq(s) => write!(f, "độ phổ biến \"{s}\" phải là số nguyên không âm"),
            EntryError::EmptyText => write!(f, "thiếu chữ trước dấu ;"),
            EntryError::EmptyReading => write!(f, "thiếu âm đọc sau dấu ;"),
            EntryError::TextTooLong(n) => write!(f, "chữ dài {n} ký tự, tối đa {MAX_TEXT_CHARS}"),
            EntryError::InvalidSyllable(s) => write!(
                f,
                "\"{s}\" không phải âm tiết tiếng Việt, cũng không phải mã gõ tắt (chỉ gồm chữ cái, tối đa {MAX_SHORTCUT_CHARS})"
            ),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct LineError {
    /// Số dòng, đếm từ 1.
    pub line: usize,
    pub error: EntryError,
}

impl fmt::Display for LineError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "Dòng {}: {}", self.line, self.error)
    }
}

/// Một dòng `chữ;âm đọc;độ phổ biến`.
fn parse_line(line: &str) -> Result<(Entry, u32), EntryError> {
    let fields: Vec<&str> = line.split(';').collect();
    let [text, reading, freq] = fields[..] else {
        return Err(match fields.len() {
            1 => EntryError::MissingSeparator,
            2 => EntryError::MissingFreq,
            _ => EntryError::TooManyFields,
        });
    };
    if reading.contains(is_reading_separator) {
        return Err(EntryError::ManyReadings);
    }
    let freq = freq.trim();
    let n = freq.parse::<u32>().map_err(|_| EntryError::InvalidFreq(freq.to_string()))?;
    Ok((Entry::new(text, reading)?, n))
}

/// Đọc nội dung tệp từ điển: các cặp (mục, độ phổ biến). Dòng lỗi được báo riêng, dòng hợp lệ vẫn dùng được.
pub fn parse(content: &str) -> (Vec<(Entry, u32)>, Vec<LineError>) {
    let mut entries = Vec::new();
    let mut errors = Vec::new();
    for (i, line) in content.trim_start_matches('\u{FEFF}').lines().enumerate() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        match parse_line(line) {
            Ok(e) => entries.push(e),
            Err(error) => errors.push(LineError { line: i + 1, error }),
        }
    }
    (entries, errors)
}

/// Kết quả (hoặc bản xem trước) của một lần nhập tệp.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ImportReport {
    pub added: usize,
    pub duplicates: usize,
    pub errors: Vec<LineError>,
}

#[derive(Clone, Debug, Default)]
pub struct Dictionary {
    entries: Vec<Entry>,
    /// Độ phổ biến của từng mục (cùng vị trí với `entries`), và tổng của chúng.
    freqs: Vec<u32>,
    total: u64,
    index: HashMap<String, Vec<usize>>,
    /// Cụm từ ≥ 2 âm tiết theo chữ cái đầu (`vn` → 越南), cho gõ tắt tự động.
    initials: HashMap<String, Vec<usize>>,
    /// Mã gõ tắt người dùng đặt (`越南;vn`).
    shortcuts: BTreeMap<String, Vec<usize>>,
    /// Chữ một âm tiết theo khoá đã sắp xếp, để tìm theo phần đầu (`h` → 漢, 𠄩…).
    syllables: BTreeMap<String, Vec<usize>>,
    /// Cụm ≥ 2 âm tiết theo khoá đã sắp xếp, để gõ phần đầu âm tiết đầu đã thấy cả cụm (`v` → 越南).
    phrases: BTreeMap<String, Vec<usize>>,
    /// Số mục của mỗi chữ.
    texts: HashMap<String, usize>,
}

impl Dictionary {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn from_text(content: &str) -> (Self, ImportReport) {
        let mut dict = Self::new();
        let report = dict.import(content);
        (dict, report)
    }

    pub fn len(&self) -> usize {
        self.entries.len()
    }

    pub fn is_empty(&self) -> bool {
        self.entries.is_empty()
    }

    pub fn entries(&self) -> &[Entry] {
        &self.entries
    }

    pub fn contains(&self, entry: &Entry) -> bool {
        let key = entry.key();
        let ids = if entry.is_shortcut() { self.shortcuts.get(&entry.shortcut_key()) } else { self.index.get(&key) };
        ids.is_some_and(|ids| ids.iter().any(|&i| self.entries[i] == *entry))
    }

    /// Thêm một mục với độ phổ biến 0; trả `false` nếu đã có.
    pub fn insert(&mut self, entry: Entry) -> bool {
        if self.contains(&entry) {
            return false;
        }
        self.push(entry, 0);
        true
    }

    fn push(&mut self, entry: Entry, freq: u32) {
        self.entries.push(entry);
        self.freqs.push(freq);
        self.total += freq as u64;
        self.index_entry(self.entries.len() - 1);
    }

    /// Độ phổ biến của mục thứ `i` (vị trí trả về từ các hàm tra cứu).
    pub fn freq(&self, i: usize) -> u32 {
        self.freqs[i]
    }

    fn position(&self, text: &str, reading: &str) -> Option<usize> {
        let key = text::fold_key(reading);
        let ids = if is_shortcut(reading) { self.shortcuts.get(reading) } else { self.index.get(&key) };
        ids?.iter().copied().find(|&i| self.entries[i].text == text && self.entries[i].reading == reading)
    }

    pub fn freq_of(&self, text: &str, reading: &str) -> Option<u32> {
        self.position(text, reading).map(|i| self.freqs[i])
    }

    /// Mỗi lần chọn cộng chừng này: một phần nghìn số mục, ít nhất 1.
    pub fn step(&self) -> u32 {
        (self.entries.len() / 1000).max(1) as u32
    }

    /// Người dùng chọn cặp này: cộng [`Self::step`]; tổng vượt gấp đôi số mục thì mọi số chia đôi.
    /// Trả `false` nếu không có cặp này.
    pub fn bump(&mut self, text: &str, reading: &str) -> bool {
        let Some(i) = self.position(text, reading) else { return false };
        let step = self.step();
        self.freqs[i] = self.freqs[i].saturating_add(step);
        self.total += step as u64;
        if self.total > 2 * self.entries.len() as u64 {
            self.freqs.iter_mut().for_each(|f| *f /= 2);
            self.total = self.freqs.iter().map(|&f| f as u64).sum();
        }
        true
    }

    /// Xoá dữ liệu học: cặp có trong `defaults` về số của nó, cặp người dùng tự thêm về 0.
    pub fn reset_freqs(&mut self, defaults: &Dictionary) {
        for i in 0..self.entries.len() {
            let e = &self.entries[i];
            self.freqs[i] = defaults.freq_of(&e.text, &e.reading).unwrap_or(0);
        }
        self.total = self.freqs.iter().map(|&f| f as u64).sum();
    }

    fn index_entry(&mut self, i: usize) {
        let e = &self.entries[i];
        *self.texts.entry(e.text.clone()).or_default() += 1;
        if e.is_shortcut() {
            self.shortcuts.entry(e.shortcut_key()).or_default().push(i);
            return;
        }
        self.index.entry(e.key()).or_default().push(i);
        if e.reading.contains(' ') {
            self.phrases.entry(e.key()).or_default().push(i);
        } else {
            self.syllables.entry(e.key()).or_default().push(i);
        }
        let initials = e.initials();
        if initials.chars().count() >= 2 {
            self.initials.entry(initials).or_default().push(i);
        }
    }

    pub fn remove(&mut self, entry: &Entry) -> bool {
        let Some(pos) = self.entries.iter().position(|e| e == entry) else {
            return false;
        };
        self.entries.remove(pos);
        self.total -= self.freqs.remove(pos) as u64;
        self.rebuild_index();
        true
    }

    pub fn clear(&mut self) {
        self.entries.clear();
        self.freqs.clear();
        self.total = 0;
        self.rebuild_index();
    }

    /// Có mục nào của chữ `text` không.
    pub fn has_text(&self, text: &str) -> bool {
        self.texts.contains_key(text)
    }

    /// Mọi âm đọc (cả mã gõ tắt) của chữ `text`, theo thứ tự trong từ điển.
    pub fn readings_of(&self, text: &str) -> Vec<&str> {
        if !self.has_text(text) {
            return Vec::new();
        }
        self.entries.iter().filter(|e| e.text == text).map(|e| e.reading.as_str()).collect()
    }

    /// Thêm cặp `entry` (`old` là `None`) hoặc sửa cặp `old` thành `entry` tại chỗ, với độ phổ biến `freq`.
    /// Trả `false` (không đổi gì) nếu `entry` đã có ở một cặp khác, hoặc `old` không có trong từ điển.
    pub fn put(&mut self, old: Option<&Entry>, entry: Entry, freq: u32) -> bool {
        let target = self.position(&entry.text, &entry.reading);
        let Some(old) = old else {
            if target.is_some() {
                return false;
            }
            self.push(entry, freq);
            return true;
        };
        let Some(i) = self.position(&old.text, &old.reading) else { return false };
        if target.is_some_and(|t| t != i) {
            return false;
        }
        self.entries[i] = entry;
        self.total = self.total - self.freqs[i] as u64 + freq as u64;
        self.freqs[i] = freq;
        self.rebuild_index();
        true
    }

    fn rebuild_index(&mut self) {
        self.index.clear();
        self.initials.clear();
        self.shortcuts.clear();
        self.syllables.clear();
        self.phrases.clear();
        self.texts.clear();
        for i in 0..self.entries.len() {
            self.index_entry(i);
        }
    }

    fn plan(&self, content: &str) -> (Vec<(Entry, u32)>, ImportReport) {
        let (entries, errors) = parse(content);
        let mut seen = HashSet::new();
        let mut fresh = Vec::new();
        let mut duplicates = 0;
        for (e, f) in entries {
            if self.contains(&e) || !seen.insert(e.clone()) {
                duplicates += 1;
            } else {
                fresh.push((e, f));
            }
        }
        let report = ImportReport { added: fresh.len(), duplicates, errors };
        (fresh, report)
    }

    /// Xem trước khi nhập: số mục sẽ thêm, trùng, lỗi. Không thay đổi từ điển.
    pub fn preview(&self, content: &str) -> ImportReport {
        self.plan(content).1
    }

    pub fn import(&mut self, content: &str) -> ImportReport {
        let (fresh, report) = self.plan(content);
        fresh.into_iter().for_each(|(e, f)| self.push(e, f));
        report
    }

    /// Các mục có khoá (âm đọc bỏ dấu, vd. `chu nom`) trùng `key`, kèm thứ tự trong từ điển.
    pub fn lookup<'a>(&'a self, key: &str) -> impl Iterator<Item = (usize, &'a Entry)> + 'a {
        self.index.get(key).into_iter().flatten().map(|&i| (i, &self.entries[i]))
    }

    /// Các mục có mã gõ tắt (đã bỏ dấu) là `code`.
    pub fn lookup_shortcut<'a>(&'a self, code: &str) -> impl Iterator<Item = (usize, &'a Entry)> + 'a {
        self.shortcuts.get(code).into_iter().flatten().map(|&i| (i, &self.entries[i]))
    }

    /// Các mục có mã gõ tắt bắt đầu bằng `prefix` nhưng dài hơn (`v` → mã `vmđhs`), kèm thứ tự trong từ điển.
    pub fn lookup_shortcut_prefix<'a>(&'a self, prefix: &'a str) -> impl Iterator<Item = (usize, &'a Entry)> + 'a {
        self.shortcuts
            .range::<str, _>((std::ops::Bound::Excluded(prefix), std::ops::Bound::Unbounded))
            .take_while(move |(k, _)| k.starts_with(prefix))
            .flat_map(|(_, ids)| ids.iter().map(|&i| (i, &self.entries[i])))
    }

    /// Âm đọc thật (không phải mã gõ tắt) đầu tiên của chữ `text`.
    pub fn reading_of(&self, text: &str) -> Option<&str> {
        self.entries.iter().find(|e| e.text == text && !e.is_shortcut()).map(|e| e.reading.as_str())
    }

    /// Các cụm từ có chữ cái đầu mỗi âm tiết là `initials` (vd. `vn`).
    pub fn lookup_initials<'a>(&'a self, initials: &str) -> impl Iterator<Item = (usize, &'a Entry)> + 'a {
        self.initials.get(initials).into_iter().flatten().map(|&i| (i, &self.entries[i]))
    }

    /// Các chữ một âm tiết có khoá bắt đầu bằng `prefix` (đã bỏ dấu), kèm thứ tự trong từ điển.
    pub fn lookup_prefix<'a>(&'a self, prefix: &'a str) -> impl Iterator<Item = (usize, &'a Entry)> + 'a {
        self.syllables
            .range::<str, _>((std::ops::Bound::Included(prefix), std::ops::Bound::Unbounded))
            .take_while(move |(k, _)| k.starts_with(prefix))
            .flat_map(|(_, ids)| ids.iter().map(|&i| (i, &self.entries[i])))
    }

    /// Các cụm ≥ 2 âm tiết có âm tiết đầu bắt đầu bằng `prefix` (đã bỏ dấu), kèm thứ tự trong từ điển.
    pub fn lookup_phrase_prefix<'a>(&'a self, prefix: &'a str) -> impl Iterator<Item = (usize, &'a Entry)> + 'a {
        self.phrases
            .range::<str, _>((std::ops::Bound::Included(prefix), std::ops::Bound::Unbounded))
            .take_while(move |(k, _)| k.starts_with(prefix))
            .flat_map(|(_, ids)| ids.iter().map(|&i| (i, &self.entries[i])))
    }

    /// Tìm theo chữ hoặc âm đọc (không phân biệt dấu), cho màn hình quản lý từ điển.
    pub fn search(&self, query: &str) -> Vec<&Entry> {
        let q = text::fold(query.trim());
        self.entries
            .iter()
            .filter(|e| e.text.contains(query.trim()) || text::fold(&e.reading).contains(&q))
            .collect()
    }

    /// Xuất ra đúng định dạng tệp nhập: mỗi cặp chữ – âm một dòng `chữ;âm;độ phổ biến` (độ phổ biến lúc
    /// lưu), xếp a → z theo âm đọc ([`collation_key`]); cùng âm thì giữ thứ tự trong từ điển.
    pub fn to_text(&self) -> String {
        self.sorted()
            .into_iter()
            .map(|i| format!("{};{};{}\n", self.entries[i].text, self.entries[i].reading, self.freqs[i]))
            .collect()
    }

    /// Vị trí các mục xếp a → z theo âm đọc ([`collation_key`]); cùng âm thì giữ thứ tự trong từ điển.
    pub fn sorted(&self) -> Vec<usize> {
        let mut order: Vec<usize> = (0..self.entries.len()).collect();
        order.sort_by_cached_key(|&i| collation_key(&self.entries[i].reading));
        order
    }
}

/// Bảng chữ cái tiếng Việt (thêm f j w z cho mã gõ tắt).
const ALPHABET: &str = "aăâbcdđeêfghijklmnoôơpqrstuưvwxyz";

/// Khoá xếp a → z kiểu từ điển tiếng Việt: so cả âm đọc đã bỏ thanh theo bảng chữ cái (dấu cách đứng
/// trước mọi chữ: a < a dua < ác), bằng nhau mới so thanh từng âm tiết: ngang < huyền < hỏi < ngã < sắc
/// < nặng (a, à, ả, ã, á, ạ).
pub fn collation_key(reading: &str) -> (Vec<u32>, Vec<u8>) {
    let mut letters = Vec::new();
    let mut tones = Vec::new();
    for syllable in reading.split(' ') {
        if !letters.is_empty() {
            letters.push(0);
        }
        let mut tone = 0;
        for c in syllable.chars() {
            let (base, t) = text::split_tone(text::lower(c));
            letters.push(ALPHABET.chars().position(|a| a == base).map_or(1000 + base as u32, |p| p as u32 + 1));
            tone = match t {
                Tone::Level => tone,
                Tone::Grave => 1,
                Tone::Hook => 2,
                Tone::Tilde => 3,
                Tone::Acute => 4,
                Tone::Dot => 5,
            };
        }
        tones.push(tone);
    }
    (letters, tones)
}

impl Dictionary {
    /// Dòng đầu tiên (đếm từ 1, bỏ chú thích) mà tệp `content` không theo thứ tự a → z của [`collation_key`].
    pub fn first_unsorted(content: &str) -> Option<usize> {
        let mut prev: Option<(Vec<u32>, Vec<u8>)> = None;
        for (i, line) in content.lines().enumerate() {
            let line = line.trim();
            let Some(reading) = line.split(';').nth(1).filter(|_| !line.starts_with('#')) else { continue };
            let key = collation_key(&reading.trim().nfc().map(text::lower).collect::<String>());
            if prev.as_ref().is_some_and(|p| *p > key) {
                return Some(i + 1);
            }
            prev = Some(key);
        }
        None
    }
}


/// Cho test: từ điển viết gọn `chữ;âm1,âm2` (độ phổ biến 0) đổi sang định dạng `chữ;âm;0`.
#[cfg(test)]
pub(crate) fn compact(content: &str) -> String {
    content
        .lines()
        .filter(|l| !l.trim().is_empty())
        .flat_map(|l| {
            let (text, readings) = l.split_once(';').unwrap();
            readings.split(',').map(move |r| format!("{text};{r};0\n")).collect::<Vec<_>>()
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_valid_and_reports_errors() {
        let content = "\u{FEFF}# chú thích\n喃;nôm;5\n\n𡨸喃 ; Chữ  Nôm ; 2 \n字\n𡨸喃;chữ;0\n喃;nom;x\n𡨸喃;chữ xyz;0\n;nôm;0\n樂;lạc,nhạc;3\n中;trung\n中;trung;1;2\n中;trung;-1\n";
        let (entries, errors) = parse(content);
        assert_eq!(entries.len(), 3);
        assert_eq!(entries[0], (Entry { text: "喃".into(), reading: "nôm".into() }, 5));
        assert_eq!(entries[1], (Entry { text: "𡨸喃".into(), reading: "chữ nôm".into() }, 2));
        // Số chữ khác số âm tiết vẫn nhận: người dùng tự quyết định.
        assert_eq!(entries[2], (Entry { text: "𡨸喃".into(), reading: "chữ".into() }, 0));
        let lines: Vec<String> = errors.iter().map(|e| e.to_string()).collect();
        assert_eq!(
            lines,
            [
                "Dòng 5: thiếu dấu ; (mỗi dòng có dạng chữ;âm đọc;độ phổ biến)",
                "Dòng 7: độ phổ biến \"x\" phải là số nguyên không âm",
                "Dòng 8: \"xyz\" không phải âm tiết tiếng Việt, cũng không phải mã gõ tắt (chỉ gồm chữ cái, tối đa 12)",
                "Dòng 9: thiếu chữ trước dấu ;",
                "Dòng 10: mỗi dòng chỉ một âm đọc; chữ có nhiều âm thì viết nhiều dòng",
                "Dòng 11: thiếu độ phổ biến (mỗi dòng có dạng chữ;âm đọc;độ phổ biến)",
                "Dòng 12: thừa dấu ; (mỗi dòng có dạng chữ;âm đọc;độ phổ biến)",
                "Dòng 13: độ phổ biến \"-1\" phải là số nguyên không âm",
            ]
        );
    }

    #[test]
    fn one_reading_per_line() {
        let (dict, report) = Dictionary::from_text("樂;lạc;4\n喃;nôm;7\n樂;nhạc;2\n");
        assert!(report.errors.is_empty());
        assert_eq!(dict.to_text(), "樂;lạc;4\n樂;nhạc;2\n喃;nôm;7\n");
        assert_eq!(dict.readings_of("樂"), ["lạc", "nhạc"]);
        assert_eq!(dict.lookup("nhac").count(), 1);
        assert_eq!(dict.freq_of("樂", "nhạc"), Some(2));
        // Màn hình sửa từ điển vẫn nhập nhiều âm một lúc.
        assert_eq!(Entry::parse_many("樂", "lạc, nhạc").unwrap().len(), 2);
        // `，` và `、` ngăn cách như `,`.
        assert_eq!(Entry::parse_many("樂", "lạc，nhạc").unwrap(), Entry::parse_many("樂", "lạc,nhạc").unwrap());
        assert_eq!(Entry::parse_many("樂", "lạc、 nhạc, lạo").unwrap().len(), 3);
        assert_eq!(Dictionary::from_text("樂;lạc，nhạc;1\n").1.errors.len(), 1);
        assert!(Entry::parse_many("中", "trung,x1").is_err());
    }

    #[test]
    fn shortcut_readings() {
        let (dict, report) = Dictionary::from_text(&compact("越南;việt nam,VN\n空;không,k\n大學;đại học,đh\n"));
        assert!(report.errors.is_empty(), "{:?}", report.errors);
        assert_eq!(dict.len(), 6);
        assert_eq!(dict.lookup_shortcut("vn").next().unwrap().1.text, "越南");
        assert_eq!(dict.lookup_shortcut("đh").next().unwrap().1.text, "大學");
        assert!(dict.lookup_shortcut("dh").next().is_none());
        assert_eq!(dict.lookup("vn").count(), 0);
        assert_eq!(dict.reading_of("空"), Some("không"));
        assert_eq!(dict.freq_of("越南", "vn"), Some(0));
        assert!(Entry::new("喃", "abcdefghijklm").is_err());
    }

    #[test]
    fn put_adds_and_edits_one_pair() {
        let e = |t: &str, r: &str| Entry::new(t, r).unwrap();
        let (mut dict, _) = Dictionary::from_text("固;cố;5\n固;có;3\n南;nam;9\n");
        // Thêm cặp mới; cặp đã có thì không thêm.
        assert!(dict.put(None, e("越", "việt"), 4));
        assert!(!dict.put(None, e("固", "có"), 1));
        // Sửa một cặp: đổi cả chữ, âm, độ phổ biến; cặp khác của chữ đó không đổi.
        assert!(dict.put(Some(&e("固", "cố")), e("故", "cố"), 6));
        assert_eq!(dict.to_text(), "固;có;3\n故;cố;6\n南;nam;9\n越;việt;4\n");
        assert_eq!(dict.lookup("co").count(), 2);
        assert_eq!(dict.freq_of("故", "cố"), Some(6));
        // Sửa thành một cặp đã có, hoặc sửa cặp không có: không đổi gì.
        assert!(!dict.put(Some(&e("故", "cố")), e("南", "nam"), 0));
        assert!(!dict.put(Some(&e("喃", "nôm")), e("喃", "nam"), 0));
        // Chỉ đổi độ phổ biến.
        assert!(dict.put(Some(&e("南", "nam")), e("南", "nam"), 1));
        assert!(dict.remove(&e("固", "có")));
        assert_eq!(dict.to_text(), "故;cố;6\n南;nam;1\n越;việt;4\n");
    }

    #[test]
    fn bump_halves_when_total_doubles_and_resets() {
        // 4 mục: mỗi lần chọn cộng 1; tổng vượt 8 thì mọi số chia đôi.
        let defaults = "南;nam;3\n男;nam;1\n𢆥;năm;0\n喃;nôm;2\n";
        let (mut dict, _) = Dictionary::from_text(defaults);
        assert_eq!(dict.step(), 1);
        assert!(dict.bump("男", "nam"));
        assert!(!dict.bump("男", "nôm"));
        assert_eq!(dict.to_text(), "南;nam;3\n男;nam;2\n𢆥;năm;0\n喃;nôm;2\n");
        dict.bump("男", "nam");
        dict.bump("男", "nam");
        // Tổng 9 > 8: chia đôi, thứ tự giữ nguyên.
        assert_eq!(dict.to_text(), "南;nam;1\n男;nam;2\n𢆥;năm;0\n喃;nôm;1\n");
        // Xoá dữ liệu học: về số mặc định, cặp tự thêm về 0.
        dict.insert(Entry::new("楠", "nam").unwrap());
        dict.bump("楠", "nam");
        dict.reset_freqs(&Dictionary::from_text(defaults).0);
        assert_eq!(dict.to_text(), "南;nam;3\n男;nam;1\n楠;nam;0\n𢆥;năm;0\n喃;nôm;2\n");
        // 2500 mục: mỗi lần chọn cộng 2 (một phần nghìn số mục).
        let many: String = (0..2500u32).map(|i| format!("{};nam;0\n", char::from_u32(0x4E00 + i).unwrap())).collect();
        assert_eq!(Dictionary::from_text(&many).0.step(), 2);
    }

    #[test]
    fn normalizes_decomposed_reading() {
        let nfd = "no\u{0302}m";
        assert_eq!(Entry::new("喃", nfd).unwrap().reading, "nôm");
    }

    #[test]
    fn import_preview_and_duplicates() {
        let (mut dict, _) = Dictionary::from_text("喃;nôm;1\n");
        let report = dict.preview("喃;nôm;5\n南;nam;2\n南;nam;3\nx\n");
        assert_eq!((report.added, report.duplicates, report.errors.len()), (1, 2, 1));
        assert_eq!(dict.len(), 1);
        dict.import("喃;nôm;5\n南;nam;2\n");
        assert_eq!(dict.len(), 2);
        // Mục đã có giữ độ phổ biến của từ điển, không lấy của tệp nhập.
        assert_eq!(dict.to_text(), "南;nam;2\n喃;nôm;1\n");
        assert_eq!(dict.lookup("nam").count(), 1);
        assert!(dict.remove(&Entry::new("喃", "nôm").unwrap()));
        assert_eq!(dict.lookup("nom").count(), 0);
        assert_eq!(dict.to_text(), "南;nam;2\n");
    }

    #[test]
    fn saves_sorted_a_to_z_with_current_freq() {
        let shuffled = "多;đa;0\n𠊛;người;0\n阿諛;a dua;0\n亞;á;2\n惡;ác;0\n喃;ăn;0\n阿;a;1\n啊;ạ;0\n婭;ả;0\n啊;à;0\n亞洲;á châu;0\n阿;ã;0\n";
        let (mut dict, report) = Dictionary::from_text(shuffled);
        assert!(report.errors.is_empty(), "{:?}", report.errors);
        // đa < người (đ trước n); a dua ở dòng 3 là chỗ lệch đầu tiên.
        assert_eq!(Dictionary::first_unsorted(shuffled), Some(3));
        dict.bump("惡", "ác");
        let saved = dict.to_text();
        assert_eq!(
            saved,
            "阿;a;1\n啊;à;0\n婭;ả;0\n阿;ã;0\n亞;á;2\n啊;ạ;0\n亞洲;á châu;0\n阿諛;a dua;0\n惡;ác;1\n喃;ăn;0\n多;đa;0\n𠊛;người;0\n"
        );
        assert_eq!(Dictionary::first_unsorted(&saved), None);
        // Cùng âm: giữ thứ tự trong từ điển.
        let (same, _) = Dictionary::from_text("男;nam;0\n南;nam;0\n喃;nam;0\n");
        assert_eq!(same.to_text(), "男;nam;0\n南;nam;0\n喃;nam;0\n");
    }

    #[test]
    fn search_ignores_diacritics() {
        let (dict, _) = Dictionary::from_text("喃;nôm;0\n南;nam;0\n");
        assert_eq!(dict.search("nom").len(), 1);
        assert_eq!(dict.search("南").len(), 1);
    }
}

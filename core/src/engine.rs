//! Tra cứu và xếp hạng gợi ý Hán Nôm cho thanh gợi ý.

use std::collections::HashSet;

use crate::dict::{self, Dictionary};
use crate::segment;
use crate::syllable::{self, Check, ToneStyle};
use crate::telex;
use crate::text::{self, Tone};

/// Gõ không dấu (`tot`) hay thêm dấu ở cuối (`tots`, `totqs`) đều ra `tốt`; dấu nào gõ thì dùng
/// để lọc, dấu nào không gõ thì khớp mọi dấu. â ê ô chỉ qua `q` (aa / ee / oo là hai chữ).
/// Phím cách (ký tự ' ' trong chuỗi phím) gán thanh ngang (`nam ` ra nam, năm; không ra nám).
#[derive(Clone, Copy, Debug)]
pub struct Options {
    pub tone_style: ToneStyle,
    /// `d` ra đ, `z` ra d (đầu âm tiết); khi bật, `d` không còn khớp chữ đ.
    pub dz: bool,
    /// Học thói quen chọn chữ.
    pub learning: bool,
    /// Bàn phím Hán Nôm kiểu Telex: chuỗi phím gõ theo Telex tự do như Unikey (`aa` → â, `dd` → đ, dấu
    /// gõ đâu trong chữ cũng được: `hasn` là hán) thay cho kiểu gõ Hán Nôm riêng (`q`, dấu kết thúc chữ).
    /// Khi bật, không dùng `dz`.
    pub telex: bool,
}

impl Default for Options {
    fn default() -> Self {
        Options { tone_style: ToneStyle::Old, dz: false, learning: true, telex: false }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Candidate {
    pub text: String,
    pub reading: String,
    /// Số ký tự phím (đầu chuỗi) mà gợi ý này thay thế; phần còn lại gõ tiếp.
    pub consumed: usize,
    /// Khớp nhờ gõ tắt (`vn` → việt nam), không phải gõ đủ âm tiết.
    pub abbreviated: bool,
    /// Gợi ý [ghép](Engine::sentence) từ nhiều mục, không phải một mục có sẵn trong từ điển.
    pub joined: bool,
    /// Vị trí phím (tính theo ký tự) giữa các âm tiết của gợi ý (`chunom` → 𡨸喃: `[3]`),
    /// để khung gõ hiện tách chữ; gõ tắt thì rỗng.
    pub breaks: Vec<usize>,
}

impl Candidate {
    /// Chuỗi phím còn lại sau khi chọn gợi ý này.
    pub fn remaining(&self, keys: &str) -> String {
        keys.chars().skip(self.consumed).collect()
    }
}

/// Chỉ dùng một từ điển: từ điển mặc định là bản đầu tiên, người dùng sửa thẳng vào đó
/// và gộp thêm tệp khác vào. Học thói quen là tăng độ phổ biến ngay trong từ điển.
#[derive(Clone, Debug, Default)]
pub struct Engine {
    pub options: Options,
    pub dict: Dictionary,
    /// Kết quả [`Engine::narrow_rows`] lần gần nhất: cột chọn nhanh hỏi lại cùng chuỗi phím nhiều lần mỗi lần dựng
    /// (nhóm phụ âm lớn thì mỗi lần tính mất vài chục ms).
    narrow_cache: NarrowCache,
}

type NarrowRow = (Candidate, [String; 5], bool);

/// Bộ nhớ đệm cho [`Engine::narrow_rows`] (vài mục gần nhất, theo chuỗi phím, kiểu gõ, số mục từ điển, phụ âm
/// cần chữ thay) và cho [`Engine::initial_has_words`] (theo số mục từ điển).
#[derive(Default)]
struct NarrowCache {
    rows: std::sync::Mutex<Vec<(String, std::sync::Arc<Vec<NarrowRow>>)>>,
    initials: std::sync::Mutex<(usize, std::collections::HashMap<String, bool>)>,
}

/// Số mục giữ trong [`NarrowCache::rows`]: cột trái, lưới và mục chọn nhanh hỏi cùng chuỗi phím với vài phụ âm khác nhau.
const NARROW_CACHE_SIZE: usize = 6;

impl Clone for NarrowCache {
    fn clone(&self) -> Self {
        NarrowCache::default()
    }
}

impl std::fmt::Debug for NarrowCache {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("NarrowCache")
    }
}

impl Engine {
    pub fn new(dict: Dictionary) -> Self {
        Engine { dict, ..Default::default() }
    }

    /// Ghép dấu một chữ theo kiểu gõ đang bật ([`Options::telex`]).
    fn compose(&self, keys: &str) -> String {
        if self.options.telex {
            telex::compose(keys, self.options.tone_style)
        } else {
            telex::compose_with(keys, self.options.tone_style, self.options.dz)
        }
    }

    /// Các cách tách phần đầu chuỗi phím thành âm tiết, theo kiểu gõ đang bật.
    fn segment(&self, keys: &str) -> Vec<Vec<segment::Segment>> {
        if self.options.telex {
            segment::segment_telex(keys, self.options.tone_style)
        } else {
            segment::segment(keys, self.options.tone_style, self.options.dz)
        }
    }

    fn is_vietnamese(&self, keys: &str) -> bool {
        if self.options.telex {
            telex::is_vietnamese_telex(keys)
        } else {
            telex::is_vietnamese(keys, self.options.dz)
        }
    }

    /// Các chữ của chuỗi phím: đoạn `[đầu, cuối)` theo ký tự, và chữ đã gõ dấu kết thúc chữ chưa. Kiểu Telex
    /// thì dấu không kết thúc chữ, cờ này là đã gõ dấu thanh (âm tiết đã đủ).
    fn word_spans(&self, keys: &str) -> Option<Vec<(usize, usize, bool)>> {
        if !self.options.telex {
            return telex::word_spans(keys, self.options.dz);
        }
        let mut pos = 0;
        let spans = telex::telex_words(keys)
            .into_iter()
            .map(|w| {
                let len = w.chars().count();
                let toned = w != "'" && text::split_syllable(&self.compose(&w)).is_some_and(|(_, t)| t != Tone::Level);
                pos += len;
                (pos - len, pos, toned)
            })
            .collect();
        Some(spans)
    }

    /// Gợi ý Hán Nôm cho chuỗi phím đang gõ, đã xếp hạng.
    ///
    /// Thứ tự: phủ nhiều phím hơn → độ phổ biến cao hơn → khớp đúng cả dấu
    /// (`ma` → ma trước má, mà) → thứ tự trong từ điển.
    /// Không gợi ý nào phủ hết chuỗi phím thì thêm lên đầu gợi ý [ghép](Self::sentence).
    pub fn candidates(&self, keys: &str) -> Vec<Candidate> {
        self.with_sentence(keys, self.ranked(keys))
    }

    /// [`Self::candidates`] từ các gợi ý đã xếp hạng `cands`.
    fn with_sentence(&self, keys: &str, mut cands: Vec<Candidate>) -> Vec<Candidate> {
        let len = keys.chars().count();
        // Chuỗi phím không phải tiếng Việt (`greenwich`): không ghép câu, nhưng vẫn gợi ý theo phần đầu (`g` → 家,
        // gà, gia…) để chọn dần từng chữ; mã gõ tắt khớp cả chuỗi đứng trước.
        if !self.is_vietnamese(keys) && !(self.options.telex && is_initials_only(keys)) {
            return cands;
        }
        let limit = if self.options.telex { MAX_SENTENCE_KEYS_TELEX } else { MAX_SENTENCE_KEYS };
        if len <= limit && cands.first().is_some_and(|c| c.consumed < len) {
            if let Some(s) = self.sentence(keys, &cands) {
                // Telex gõ tắt toàn phụ âm (`phn`): cụm có sẵn trong từ điển và chữ của âm đầu (cả p, ph) đứng
                // trước; câu ghép tự động (mảnh đầu chỉ được đúng p, thường là chữ hiếm) xếp sau vài chữ đầu
                // (người dùng muốn 2026-11-14).
                let at = if self.options.telex && is_initials_only(keys) {
                    let phrases = cands.iter().take_while(|c| c.consumed > 1).count();
                    (phrases + RUN_SENTENCE_AFTER).min(cands.len())
                } else {
                    0
                };
                cands.insert(at, s);
            }
        }
        cands
    }

    /// Gợi ý cả câu (`quocqsvietqj` → 國越, `mhb` → 𠬠𠄩𠀧): mỗi vị trí trong chuỗi phím chỉ xét các
    /// gợi ý bắt đầu ở đó (gợi ý đứng đầu cho mỗi độ dài), rồi chọn đường phủ hết chuỗi phím ít mảnh
    /// nhất, ít mảnh gõ tắt nhất (quy hoạch động). `first`: gợi ý tại vị trí đầu, đã tính.
    fn sentence(&self, keys: &str, first: &[Candidate]) -> Option<Candidate> {
        let chars: Vec<char> = keys.chars().collect();
        let n = chars.len();
        // Chuỗi phụ âm liền nhau: mảnh sau (kể cả một phụ âm đứng cuối) cũng không tự gộp phụ âm.
        let run = self.options.telex && is_initials_only(keys);
        // best[i]: (số mảnh, số mảnh gõ tắt, vị trí trước, mảnh) của đường tốt nhất tới i.
        let mut best: Vec<Option<(usize, usize, usize, Candidate)>> = vec![None; n + 1];
        let mut reached = vec![false; n + 1];
        reached[0] = true;
        for i in 0..n {
            if !reached[i] {
                continue;
            }
            let (pieces, abbr) = best[i].as_ref().map_or((0, 0), |b| (b.0, b.1));
            // Mảnh một chữ cái lấy chữ dùng nhiều nhất của âm đó, cả phụ âm dài hơn (`phn`: phải, phố trước pi;
            // người dùng muốn 2026-11-14); mảnh là cụm gõ tắt vẫn giữ mỗi chữ cái một phụ âm.
            let here = if i == 0 { first.to_vec() } else { self.ranked_in(&chars[i..].iter().collect::<String>(), run) };
            let mut lengths = HashSet::new();
            // Đã xếp hạng: gợi ý đầu tiên của mỗi độ dài là tốt nhất.
            for c in here.into_iter().filter(|c| c.consumed > 0 && lengths.insert(c.consumed)) {
                let j = i + c.consumed;
                let cost = (pieces + 1, abbr + usize::from(c.abbreviated));
                if best[j].as_ref().is_none_or(|b| cost < (b.0, b.1)) {
                    best[j] = Some((cost.0, cost.1, i, c));
                    reached[j] = true;
                }
            }
        }
        let mut path = Vec::new();
        let mut j = n;
        while j > 0 {
            let (_, _, i, c) = best[j].clone()?;
            path.push((i, c));
            j = i;
        }
        path.reverse();
        // Cụm dài không giới hạn (kể cả lặp một mảnh, `nomnomnom…` → 喃喃喃…): thanh gợi ý tự thu nhỏ chữ.
        if path.len() < 2 {
            return None;
        }
        let n = path.last().map_or(0, |(i, c)| i + c.consumed);
        let mut breaks = Vec::new();
        for (i, c) in &path {
            if *i > 0 {
                breaks.push(*i);
            }
            breaks.extend(c.breaks.iter().map(|b| i + b));
        }
        Some(Candidate {
            text: path.iter().map(|(_, c)| c.text.as_str()).collect(),
            reading: path.iter().map(|(_, c)| c.reading.as_str()).collect::<Vec<_>>().join(" "),
            consumed: n,
            abbreviated: false,
            joined: true,
            breaks,
        })
    }

    /// Chữ một âm tiết gõ dở ở đầu chuỗi phím (`h` → 漢, 𠄩…, `ng` → 𠊛, 𣈜…), xếp sau chữ gõ đủ.
    /// Đoạn gõ dở kéo dài chừng nào còn là phần đầu hợp lệ của một âm tiết (như tách pinyin):
    /// `mhb` là m + h + b, còn `quan` là một đoạn, không tách `qua`. Đoạn đã gõ dấu là chữ đủ, bỏ qua.
    fn prefixes(&self, keys: &str, strict: bool, seen: &mut HashSet<(String, usize, String)>, found: &mut Vec<(Candidate, u32, Rank)>) {
        let chars: Vec<char> = keys.chars().collect();
        let lead = chars.iter().take_while(|&&c| c == '\'').count();
        let mut chunk = None;
        for k in 1..=MAX_SYLLABLE_LETTERS.min(chars.len() - lead) {
            let part = &chars[lead..lead + k];
            if !part.iter().all(|c| c.is_ascii_alphabetic()) {
                break;
            }
            // Chuỗi phụ âm liền nhau: mỗi chữ cái là một phụ âm đơn (`nh` là n + h), trừ `dd` là đ.
            if strict && k > 1 && !(k == 2 && part == ['d', 'd']) && !part.iter().any(|&c| text::is_vowel(c)) {
                break;
            }
            // `w` một mình là phần đầu ư (`w` → ương, ước…; [`plain_segment`]).
            let composed: String = if part == ['w'] { "ư".to_string() } else { self.compose(&part.iter().collect::<String>()) };
            if !syllable::check(&letters_of(&composed), Tone::Level, Check::Prefix) {
                break;
            }
            chunk = Some((k, composed));
        }
        let Some((k, composed)) = chunk else { return };
        let part: String = chars[lead..lead + k].iter().collect();
        if self.word_spans(&part).is_none_or(|s| s.len() != 1 || s[0].2) {
            return;
        }
        let mut consumed = lead + k;
        while chars.get(consumed) == Some(&'\'') {
            consumed += 1;
        }
        let typed = letters_of(&composed);
        let folded = text::fold_key(&composed.to_lowercase());
        // Chữ một âm thì như khi chỉ gõ chữ cái đó (`t…` có cả chữ t, th, tr), kể cả mảnh giữa chuỗi khi ghép câu
        // (người dùng muốn 2026-11-14); cụm nhiều chữ (dưới) vẫn giữ mỗi chữ cái một phụ âm.
        for (order, e) in self.dict.lookup_prefix(&folded) {
            if !prefix_matches(&typed, &letters_of(&e.reading))
                || !seen.insert((e.text.clone(), consumed, e.reading.clone()))
            {
                continue;
            }
            let freq = self.dict.freq(order);
            let cand = Candidate { text: e.text.clone(), reading: e.reading.clone(), consumed, abbreviated: true, joined: false, breaks: Vec::new() };
            found.push((cand, freq, (true, order)));
        }
        // Đoạn gõ dở là cả chuỗi phím: hiện luôn cụm từ có âm tiết đầu bắt đầu bằng nó (`v` → 越南).
        // Còn phím phía sau thì không, để `vietnam` không chọn 越南 mà chỉ thay mất `viet`.
        if consumed != chars.len() {
            return;
        }
        for (order, e) in self.dict.lookup_phrase_prefix(&folded) {
            let first: Vec<char> = letters_of(e.reading.split(' ').next().unwrap_or(""));
            if !prefix_matches(&typed, &first) || !single_initial(&typed, &first, strict) || !seen.insert((e.text.clone(), consumed, e.reading.clone())) {
                continue;
            }
            let freq = self.dict.freq(order);
            let cand = Candidate { text: e.text.clone(), reading: e.reading.clone(), consumed, abbreviated: true, joined: false, breaks: Vec::new() };
            found.push((cand, freq, (true, order)));
        }
    }

    fn ranked(&self, keys: &str) -> Vec<Candidate> {
        self.ranked_in(keys, false)
    }

    /// [`Self::ranked`]; `run`: `keys` là phần sau của một chuỗi phụ âm liền nhau (mảnh ghép câu), nên phụ âm
    /// đứng một mình cũng không tự gộp ([`single_initial`]).
    fn ranked_in(&self, keys: &str, run: bool) -> Vec<Candidate> {
        let mut shown = HashSet::new();
        self.ranked_readings_in(keys, run).into_iter().filter(|c| shown.insert(c.text.clone())).collect()
    }

    /// Như [`Self::ranked`] nhưng giữ mọi âm đọc khớp của cùng một chữ (極 đọc cọc và cực), để thu hẹp
    /// theo âm ([`Self::narrow`]) không bỏ sót chữ có âm khác đứng trước trong từ điển.
    fn ranked_readings(&self, keys: &str) -> Vec<Candidate> {
        self.ranked_readings_in(keys, false)
    }

    fn ranked_readings_in(&self, keys: &str, run: bool) -> Vec<Candidate> {
        // Telex, nhiều phụ âm liền nhau (`bndtp`): mỗi phụ âm là đúng nó, không tự gộp (n ≠ nh, p ≠ ph).
        let strict = run || (self.options.telex && is_initials_only(keys));
        let mut seen = HashSet::new();
        let mut found = Vec::new();
        for path in self.segment(keys) {
            let typed: Vec<&str> = path.iter().map(|s| s.syllable.as_str()).collect();
            let key = typed.iter().map(|s| text::fold_key(s)).collect::<Vec<_>>().join(" ");
            let exact: Vec<String> = typed.iter().map(|s| s.to_lowercase()).collect();
            let consumed = path.last().map_or(0, |s| s.end);
            for (order, e) in self.dict.lookup(&key) {
                if !e.reading.split(' ').zip(&path).all(|(r, s)| syllable_matches(&s.syllable, r, s.level)) {
                    continue;
                }
                let inexact = !e.reading.split(' ').eq(exact.iter().map(String::as_str));
                if !seen.insert((e.text.clone(), consumed, e.reading.clone())) {
                    continue;
                }
                let freq = self.dict.freq(order);
                let cand = Candidate {
                    text: e.text.clone(),
                    reading: e.reading.clone(),
                    consumed,
                    abbreviated: false,
                    joined: false,
                    breaks: path[..path.len() - 1].iter().map(|s| s.end).collect(),
                };
                found.push((cand, freq, (inexact, order)));
            }
        }
        self.completions(keys, &mut seen, &mut found);
        self.shortcuts(keys, &mut seen, &mut found);
        // Telex: chữ chỉ tách ở phím cách (`'`); riêng chuỗi toàn phụ âm (`qvvtq`) là gõ tắt, mỗi âm tiết một
        // đoạn đầu: gợi ý cả cụm, và cụm khớp phần đầu (`qvv` + `tq`) để ghép câu khi từ điển không có cả cụm.
        let initials_only = self.options.telex && is_initials_only(keys);
        if !self.options.telex {
            self.abbreviations(keys, false, &mut seen, &mut found);
        } else if initials_only {
            let chars: Vec<char> = keys.chars().collect();
            for end in (2..=chars.len().min(MAX_ABBREVIATION)).rev() {
                if end == chars.len() || end <= MAX_PREFIX_ABBREVIATION {
                    self.abbreviations(&chars[..end].iter().collect::<String>(), strict, &mut seen, &mut found);
                }
            }
        }
        self.prefixes(keys, strict, &mut seen, &mut found);
        if self.options.telex && !initials_only {
            // Mỗi gợi ý phải dừng đúng ở phím cách hoặc cuối chuỗi: `quoocsvieetj` không tách thành quốc + việt.
            // Riêng chữ gõ dở ở đầu ([`Self::prefixes`]: đoạn đầu chưa thành chữ, `g` của `greenwich` → 家 gia)
            // vẫn gợi ý, để chuỗi gõ không thành chữ nào vẫn chọn dần được.
            let chars: Vec<char> = keys.chars().collect();
            let at_space = |p: usize| p > 0 && chars[p - 1] == '\'';
            found.retain(|(c, _, _)| {
                let at_end = c.consumed == chars.len() || at_space(c.consumed);
                (at_end || (c.abbreviated && c.breaks.is_empty())) && c.breaks.iter().all(|&b| at_space(b))
            });
        }
        found.sort_by(|(a, la, oa), (b, lb, ob)| {
            b.consumed
                .cmp(&a.consumed)
                .then(a.abbreviated.cmp(&b.abbreviated))
                .then(lb.cmp(la))
                .then(oa.cmp(ob))
        });
        // Cùng một chữ, cùng âm có thể khớp nhiều cách tách (`chuwx` và `chuw` + `x`): giữ cách xếp trên cùng.
        let mut shown = HashSet::new();
        found.into_iter().map(|(c, _, _)| c).filter(|c| shown.insert((c.text.clone(), c.reading.clone()))).collect()
    }

    /// Cụm từ mà chữ cuối mới gõ dở: các chữ trước đủ âm tiết, chữ cuối là phần đầu của âm tiết cuối
    /// (`chuwxn`, `chuwxno` → 𡨸喃 chữ nôm), để không phải gõ hết chữ cuối mới thấy cụm.
    fn completions(&self, keys: &str, seen: &mut HashSet<(String, usize, String)>, found: &mut Vec<(Candidate, u32, Rank)>) {
        let Some((start, end, marked)) = self.word_spans(keys).and_then(|s| s.last().copied()) else {
            return;
        };
        let chars: Vec<char> = keys.chars().collect();
        let partial: String = chars[start..end].iter().collect();
        if marked || start == 0 || partial.contains('\'') {
            return;
        }
        // Telex: chữ cuối gõ dở chỉ tính khi đã cách khỏi chữ trước (`chuwx'n`, không phải `chuwxn`).
        if self.options.telex && keys.chars().nth(start - 1) != Some('\'') {
            return;
        }
        let prefix = text::fold_key(&self.compose(&partial).to_lowercase());
        let Some(first) = prefix.chars().next() else {
            return;
        };
        let head: String = chars[..start].iter().collect();
        let consumed = chars.len();
        for path in self.segment(&head) {
            if path.last().map(|s| s.end) != Some(start) {
                continue;
            }
            let initials: String =
                path.iter().filter_map(|s| text::fold(&s.syllable).chars().next()).chain([first]).collect();
            for (order, e) in self.dict.lookup_initials(&initials) {
                let readings: Vec<&str> = e.reading.split(' ').collect();
                let Some((last, rest)) = readings.split_last() else { continue };
                if rest.len() != path.len()
                    || !rest.iter().zip(&path).all(|(r, s)| syllable_matches(&s.syllable, r, s.level))
                    || !text::fold_key(last).starts_with(&prefix)
                    || !seen.insert((e.text.clone(), consumed, e.reading.clone()))
                {
                    continue;
                }
                let freq = self.dict.freq(order);
                let cand = Candidate {
                    text: e.text.clone(),
                    reading: e.reading.clone(),
                    consumed,
                    abbreviated: false,
                    joined: false,
                    breaks: path.iter().map(|s| s.end).collect(),
                };
                found.push((cand, freq, (true, order)));
            }
        }
    }

    /// Mã gõ tắt người dùng đặt trong từ điển (`越南;việt nam,vn`): gõ đúng mã thì hiện chữ.
    /// `d` và `đ` là hai chữ khác nhau, gõ như chữ thường: tắt d/z thì `dd` là đ, `d` là d (`ddh` khớp
    /// mã `đh`, `dh` thì không); bật d/z thì `d` là đ, `z` là d.
    fn shortcuts(&self, keys: &str, seen: &mut HashSet<(String, usize, String)>, found: &mut Vec<(Candidate, u32, Rank)>) {
        let code = self.shortcut_code(keys);
        if code.is_empty() || !code.chars().all(|c| c.is_ascii_lowercase() || c == 'đ') {
            return;
        }
        let consumed = keys.chars().count();
        for (order, e) in self.dict.lookup_shortcut(&code) {
            if !seen.insert((e.text.clone(), consumed, e.reading.clone())) {
                continue;
            }
            let freq = self.dict.freq(order);
            let cand = Candidate {
                text: e.text.clone(),
                reading: e.reading.clone(),
                consumed,
                abbreviated: true,
                joined: false,
                breaks: Vec::new(),
            };
            found.push((cand, freq, (true, order)));
        }
        // Mới gõ phần đầu của mã (`v`, `vm` → 文明澱學士;vmđhs): xếp sau các mục khớp đủ mã.
        let total = self.dict.entries().len();
        for (order, e) in self.dict.lookup_shortcut_prefix(&code) {
            if !seen.insert((e.text.clone(), consumed, e.reading.clone())) {
                continue;
            }
            let freq = self.dict.freq(order);
            let cand = Candidate {
                text: e.text.clone(),
                reading: e.reading.clone(),
                consumed,
                abbreviated: true,
                joined: false,
                breaks: Vec::new(),
            };
            found.push((cand, freq, (true, total + order)));
        }
    }

    /// Gõ tắt cụm từ: mỗi âm tiết gõ một đoạn đầu (`vn`, `vnam`, `vietn` → việt nam).
    /// Bỏ qua dấu phụ và thanh, trừ d / đ là hai chữ khác nhau (`dh` không khớp đại học, `ddh` mới khớp);
    /// chỉ áp dụng cho cụm ≥ 2 âm tiết.
    fn abbreviations(
        &self,
        keys: &str,
        strict: bool,
        seen: &mut HashSet<(String, usize, String)>,
        found: &mut Vec<(Candidate, u32, Rank)>,
    ) {
        let (letters, at) = self.letters_at(keys);
        if letters.len() < 2
            || letters.len() > MAX_ABBREVIATION
            || !letters.iter().all(|&c| c.is_ascii_lowercase() || c == 'đ' || c == '\'')
        {
            return;
        }
        let consumed = keys.chars().count();
        let locked = self.locked_words(keys, &at);
        let mut initials = HashSet::new();
        collect_initials(&letters, 0, &mut String::new(), &mut initials);
        // `w` một mình là âm bắt đầu bằng ư ([`plain_segment`]); chỉ mục bỏ dấu ghi ư là u.
        let with_u: Vec<String> = initials.iter().filter(|i| i.contains('w')).map(|i| i.replace('w', "u")).collect();
        initials.extend(with_u);
        for init in &initials {
            // Chỉ mục chữ cái đầu đã bỏ dấu (đ → d); abbreviation_matches mới so đúng d / đ.
            for (order, e) in self.dict.lookup_initials(&text::fold(init)) {
                let syllables = syllable_parts(&e.reading);
                if !abbreviation_matches(&letters, 0, &syllables, &locked, strict) || !seen.insert((e.text.clone(), consumed, e.reading.clone())) {
                    continue;
                }
                let freq = self.dict.freq(order);
                let cand = Candidate {
                    text: e.text.clone(),
                    reading: e.reading.clone(),
                    consumed,
                    abbreviated: true,
                    joined: false,
                    breaks: Vec::new(),
                };
                found.push((cand, freq, (true, order)));
            }
        }
    }

    /// Đoạn chữ đã gõ dấu ở đuôi trong `keys` (theo vị trí của [`Self::letters_at`], `at`): một âm tiết trọn,
    /// gõ tắt không tách ngang (`tuongws` không là tướng + s…). Chữ không phải tiếng Việt (`vnam ` gõ tắt
    /// rồi phím cách) thì vẫn tách được.
    fn locked_words(&self, keys: &str, at: &[usize]) -> Vec<(usize, usize)> {
        let chars: Vec<char> = keys.chars().collect();
        self.word_spans(keys)
            .unwrap_or_default()
            .into_iter()
            .filter(|&(s, e, marked)| {
                marked && e > s && self.is_vietnamese(&chars[s..e].iter().collect::<String>())
            })
            // Phím cách (thanh ngang) cuối chữ là ranh giới `'`, không thuộc chữ.
            .map(|(s, e, _)| (at[s], at[e - 1] + 1 - usize::from(chars[e - 1] == ' ')))
            .collect()
    }

    /// Gợi ý tiếp theo chữ đã chọn `before` (chọn từng phần như pinyin: `hstt` chọn 函 thì `stt` gợi ý 數 của
    /// 函數 trước): các cụm trong từ điển bắt đầu bằng phần cuối của `before` (dài nhất trước), phần âm còn
    /// lại khớp phần đầu của `keys` như gõ tắt (mỗi âm một đoạn đầu, hoặc gõ đủ kèm dấu). Mỗi gợi ý là phần
    /// chữ còn lại, kèm cả cụm (chữ, âm đọc) để học khi chốt.
    pub fn after(&self, before: &str, keys: &str) -> Vec<(Candidate, String, String)> {
        let (letters, at) = self.letters_at(keys);
        if before.is_empty()
            || letters.is_empty()
            || !letters.iter().all(|&c| c.is_ascii_lowercase() || c == 'đ' || c == '\'')
        {
            return Vec::new();
        }
        let locked = self.locked_words(keys, &at);
        let key_chars: Vec<char> = keys.chars().collect();
        // Vị trí chữ → vị trí phím (hai phím `dd` là một chữ đ), kèm các dấu `'` ngay sau.
        let consumed_at = |end: usize| {
            let mut consumed = at.iter().take_while(|&&p| p < end).count();
            while key_chars.get(consumed) == Some(&'\'') {
                consumed += 1;
            }
            consumed
        };
        // Telex: như [`Self::ranked`], gợi ý phải dừng ở phím cách hoặc cuối chuỗi (漢 + `noom'la` không lấy 喃
        // cho mỗi `n`); chuỗi toàn phụ âm là gõ tắt, tách đâu cũng được.
        let free = !self.options.telex || is_initials_only(keys);
        // Phụ âm liền nhau là đúng phụ âm đó, không tự gộp ([`single_initial`]).
        let strict = self.options.telex && is_initials_only(keys);
        let fits = |consumed: usize| free || consumed == key_chars.len() || key_chars[consumed - 1] == '\'';
        // Telex: các chữ gõ đủ đầu chuỗi (`noom'laf` → nôm, là), mỗi chữ kèm vị trí phím sau nó và dấu `'`;
        // gõ tắt ở trên chỉ khớp phím thô nên không nhận `oo` là ô.
        let mut words: Vec<(segment::Segment, usize)> = Vec::new();
        if self.options.telex {
            let mut pos = key_chars.iter().take_while(|&&c| c == '\'').count();
            while pos < key_chars.len() {
                let len = key_chars[pos..].iter().take_while(|&&c| c != '\'').count();
                let word: String = key_chars[pos..pos + len].iter().collect();
                let whole = |p: &Vec<segment::Segment>| p.len() == 1 && p[0].end == len;
                let Some(seg) = self.segment(&word).into_iter().find(whole).and_then(|p| p.into_iter().next()) else {
                    break;
                };
                pos += len;
                while key_chars.get(pos) == Some(&'\'') {
                    pos += 1;
                }
                words.push((seg, pos));
            }
        }
        let before: Vec<char> = before.chars().collect();
        // (gợi ý, cụm, âm cụm, số chữ của before dùng, độ phổ biến, thứ tự)
        let mut found: Vec<(Candidate, String, String, usize, u32, usize)> = Vec::new();
        let mut seen = HashSet::new();
        for (order, e) in self.dict.entries().iter().enumerate() {
            if e.is_shortcut() {
                continue;
            }
            let text: Vec<char> = e.text.chars().collect();
            let readings: Vec<&str> = e.reading.split(' ').collect();
            if readings.len() != text.len() || text.len() < 2 {
                continue;
            }
            // Phần cuối dài nhất của before mà cụm bắt đầu bằng nó, chừa lại ít nhất một chữ.
            let Some(n) = (1..=before.len().min(text.len() - 1)).rev().find(|&n| text[..n] == before[before.len() - n..]) else {
                continue;
            };
            let syllables: Vec<(Vec<char>, Tone)> = readings[n..].iter().flat_map(|r| syllable_parts(r)).collect();
            let mut ends = Vec::new();
            abbreviation_ends(&letters, 0, &syllables, &locked, strict, &mut ends);
            let rest_readings = &readings[n..];
            let typed = (rest_readings.len() <= words.len()
                && rest_readings.iter().zip(&words).all(|(r, (s, _))| syllable_matches(&s.syllable, r, s.level)))
            .then(|| words[rest_readings.len() - 1].1);
            let abbreviated = ends.iter().map(|&end| consumed_at(end)).filter(|&c| c > 0 && fits(c)).max();
            let Some(consumed) = typed.max(abbreviated) else {
                continue;
            };
            let rest: String = text[n..].iter().collect();
            if !seen.insert((rest.clone(), consumed)) {
                continue;
            }
            let cand = Candidate {
                text: rest,
                reading: readings[n..].join(" "),
                consumed,
                abbreviated: true,
                joined: false,
                breaks: Vec::new(),
            };
            found.push((cand, e.text.clone(), e.reading.clone(), n, self.dict.freq(order), order));
        }
        found.sort_by(|a, b| b.0.consumed.cmp(&a.0.consumed).then(b.3.cmp(&a.3)).then(b.4.cmp(&a.4)).then(a.5.cmp(&b.5)));
        found.into_iter().map(|(c, t, r, ..)| (c, t, r)).collect()
    }

    /// Âm đọc âm tiết đầu của gợi ý, chữ thường (`線性` tuyến tính → `tuyến`). Gợi ý ghép câu và mã gõ tắt
    /// người dùng đặt (`vn`) thì không có.
    pub fn first_syllable(c: &Candidate) -> Option<String> {
        if c.joined || dict::is_shortcut(&c.reading) {
            return None;
        }
        c.reading.split(' ').next().map(|s| s.chars().map(text::lower).collect())
    }

    /// Các gợi ý cho `keys` mà âm tiết đầu khớp mọi lựa chọn `picked` (xem [`Self::narrow`]), kèm các phần
    /// của âm tiết đầu đó. Chỉ chữ một âm tiết: chọn xong chữ của âm đầu (q → quốc → 國) rồi mới tới các phím
    /// sau (như pinyin), không nhảy cóc sang cả cụm (國務).
    fn narrowed(&self, keys: &str, picked: &[(Part, String)]) -> Vec<(Candidate, [String; 5])> {
        // Chữ khác dấu (gõ `ddos` mà muốn đỏ) theo phụ âm đầu đã gõ, hay phụ âm cùng nhóm (gõ `laf` mà chữ đọc
        // nà) khi đã chọn phụ âm đó ở cột trái.
        let chosen = picked.iter().find(|(p, _)| *p == Part::Initial).map(|(_, v)| v.as_str());
        let typed = self.loose_words(keys).into_iter().next().map(|(label, _)| label);
        let initial = chosen.or(typed.as_deref());
        let mut rows = self.narrow_rows(keys, initial);
        rows.retain(|(_, parts, alt)| !alt || Some(parts[0].as_str()) == initial);
        self.narrowed_from(rows.into_iter().map(|(c, parts, _)| (c, parts)).collect(), picked)
    }

    /// Các gợi ý một âm tiết cho `keys`, kèm các phần của âm tiết, rồi (đánh dấu `true`) các chữ khác thanh,
    /// khác dấu mũ / móc của chữ đầu, với phụ âm đầu đã gõ hay phụ âm cùng nhóm dễ lẫn ([`INITIAL_GROUPS`]),
    /// thay đúng chữ đầu đã gõ.
    /// `wanted`: phụ âm đầu cần chữ thay (đã chọn ở cột trái, hay đã gõ). Chỉ gõ phụ âm đầu (`t`, `r`) thì chỉ tính
    /// chữ của phụ âm đó, không tính sẵn cả nhóm (nhóm lớn mất vài chục ms; cột trái hiện đủ nhóm sẵn rồi).
    fn narrow_rows(&self, keys: &str, wanted: Option<&str>) -> Vec<(Candidate, [String; 5], bool)> {
        // Đã gõ cả vần thì tính sẵn cả nhóm, `wanted` không đổi kết quả: cùng một mục đệm.
        let wanted = if self.typed_initial_only(keys) { wanted } else { None };
        // Phụ âm đã gõ: chữ của nó đã có sẵn (không tính thêm gì), cùng kết quả với `None`, dùng chung mục đệm.
        let typed = self.loose_words(keys).into_iter().next().map(|(label, _)| label);
        let wanted = wanted.filter(|w| Some(*w) != typed.as_deref());
        let id = format!("{keys}\u{0}{:?}\u{0}{}\u{0}{wanted:?}", self.options, self.dict.entries().len());
        if let Ok(cache) = self.narrow_cache.rows.lock() {
            if let Some((_, rows)) = cache.iter().find(|(k, _)| *k == id) {
                return rows.as_ref().clone();
            }
        }
        let rows = self.narrow_rows_uncached(keys, wanted);
        if let Ok(mut cache) = self.narrow_cache.rows.lock() {
            cache.retain(|(k, _)| *k != id);
            cache.insert(0, (id, std::sync::Arc::new(rows.clone())));
            cache.truncate(NARROW_CACHE_SIZE);
        }
        rows
    }

    fn narrow_rows_uncached(&self, keys: &str, wanted: Option<&str>) -> Vec<(Candidate, [String; 5], bool)> {
        // Chỉ lấy chữ một âm tiết nên khỏi ghép câu ([`Self::with_sentence`]).
        let single = |keys: &str| -> Vec<(Candidate, [String; 5])> {
            self.ranked_readings(keys)
                .into_iter()
                .filter(|c| !c.reading.contains(' '))
                .filter_map(|c| {
                    let parts = syllable_split(&Self::first_syllable(&c)?)?;
                    Some((c, parts))
                })
                .collect()
        };
        // Chuỗi toàn phụ âm: chữ của âm đầu tính ở dưới (theo chữ cái đầu), khỏi tính theo cả chuỗi rồi bỏ.
        let first_word: String = keys.chars().take_while(|&c| c != '\'').map(text::lower).collect();
        let mut rows: Vec<_> = if self.options.telex && is_initials_only(&first_word) {
            Vec::new()
        } else {
            single(keys).into_iter().map(|(c, parts)| (c, parts, false)).collect()
        };
        // Chữ đầu đã gõ (tới phím cách) và các dấu `'` ngay sau: phần các chữ khác dấu thay thế.
        let chars: Vec<char> = keys.chars().collect();
        let word = chars.iter().take_while(|&&c| c != '\'').count();
        let end = word + chars[word..].iter().take_while(|&&c| c == '\'').count();
        let typed: String = chars[..word].iter().map(|&c| text::lower(c)).collect();
        let fold = |s: &str| -> String { s.chars().map(|c| text::base(text::split_tone(text::lower(c)).0)).collect() };
        // Kiểu gõ Hán Nôm (không Telex) tách chữ ở đâu cũng được: `dich` có thể là di + chuyển, hay gõ tắt d… +
        // ích…. Lấy cả chữ một âm khớp trọn phần đầu ngắn hơn có nguyên âm (`di`), và mọi chữ bắt đầu bằng phụ
        // âm đầu đã gõ (`d` → dân, di…), để thu hẹp còn hỏi nguyên âm, vần.
        let shorter = |word: &str| -> Vec<(Candidate, [String; 5])> {
            if self.options.telex {
                return Vec::new();
            }
            let chars: Vec<char> = word.chars().collect();
            let mut out = Vec::new();
            // Dài trước: cùng một chữ (đi) thì lấy cách khớp trọn `ddi` hơn gõ tắt `dd`.
            for k in (1..chars.len()).rev() {
                let head: String = chars[..k].iter().collect();
                let target = fold(&self.compose(&head));
                let vowel = target.chars().any(text::is_vowel);
                if !vowel {
                    // Chỉ cả cụm phụ âm đầu (`ngh`, không lấy `n` của `nghieng`).
                    let next: String = chars[..=k].iter().collect();
                    if !fold(&self.compose(&next)).chars().any(text::is_vowel) {
                        continue;
                    }
                }
                out.extend(single(&head).into_iter().filter(|(c, parts)| c.consumed == k && (!vowel || fold(&parts[4]) == target)));
            }
            out
        };
        rows.extend(shorter(&typed).into_iter().map(|(c, parts)| (c, parts, false)));
        let loose_words = self.loose_words(keys);
        // Chữ đã gõ không có phím dấu (`dich`, không phải `ddos`): phím của chữ đổi phụ âm ứng đúng từng phím
        // sau phụ âm đầu, nên chữ ngắn hơn (`zich` → di) thay được đúng phần đầu đó.
        let plain = loose_words.first().is_some_and(|(_, k)| *k == typed);
        let initial_only = !fold(&self.compose(&typed)).chars().any(text::is_vowel);
        let run = self.options.telex && is_initials_only(&typed);
        // Số phím của chữ cái đầu trong chuỗi toàn phụ âm: `dd` (đ) là 2.
        let run_keys = if !self.options.dz && typed.starts_with("dd") { 2 } else { 1 };
        // Chữ của âm đầu như khi chỉ gõ chữ cái đầu (`ph…` như `p`: cả chữ p và ph; `n…`: n, ng, nh, ngh). Gợi ý cả
        // cụm vẫn giữ luật mỗi chữ cái là một phụ âm đơn ([`single_initial`]).
        if run {
            let head: String = typed.chars().take(run_keys).collect();
            // Gõ tiếp `t` → `tp` → `tph`: chữ cái đầu không đổi, dùng lại kết quả đã tính.
            let id = format!("\u{1}{head}\u{0}{:?}\u{0}{}", self.options, self.dict.entries().len());
            let cached = self.narrow_cache.rows.lock().ok().and_then(|c| c.iter().find(|(k, _)| *k == id).map(|(_, r)| r.clone()));
            rows = match cached {
                Some(r) => r.as_ref().clone(),
                None => {
                    let r: Vec<NarrowRow> = single(&head)
                        .into_iter()
                        .map(|(mut c, parts)| {
                            c.consumed = run_keys;
                            c.breaks.clear();
                            (c, parts, false)
                        })
                        .collect();
                    if let Ok(mut cache) = self.narrow_cache.rows.lock() {
                        cache.insert(0, (id, std::sync::Arc::new(r.clone())));
                        cache.truncate(NARROW_CACHE_SIZE);
                    }
                    r
                }
            };
        }
        for (initial, loose) in loose_words {
            if loose == typed {
                continue;
            }
            // Chỉ gõ phụ âm đầu: chữ của phụ âm cùng nhóm chỉ tính khi đã chọn phụ âm đó (cột trái tự biết phụ âm
            // nào có chữ, [`Self::initial_has_words`]).
            if initial_only && Some(initial.as_str()) != wanted {
                continue;
            }
            // Telex gõ tắt toàn phụ âm (`ndtphn`): âm tiết đầu chỉ là chữ cái đầu (n, hay đ của `dd`). Chọn phụ âm
            // khác ở cột trái (b, nh…) thì lấy chữ một âm của phụ âm đó, thay đúng các phím của chữ cái đầu.
            if run {
                let key = match initial.chars().next() {
                    Some('đ') if !self.options.dz => "dd".to_string(),
                    Some('d') if self.options.dz => "z".to_string(),
                    Some(c) => c.to_string(),
                    None => continue,
                };
                for (mut c, parts) in single(&key) {
                    if parts[0] == initial {
                        c.consumed = run_keys;
                        c.breaks.clear();
                        rows.push((c, parts, true));
                    }
                }
                continue;
            }
            // Chỉ chữ khớp trọn chữ đã bỏ dấu (`ki` → `ci` không thành chữ thì không lấy ca cho mỗi `c`); đã gõ
            // nguyên âm thì bỏ dấu đi phải đúng chữ đó (đo: đó, đồ, đỡ, không lấy đoàn, đóng của chữ gõ dở).
            let target = fold(&self.compose(&loose));
            let whole = target.chars().any(text::is_vowel);
            let len = loose.chars().count();
            for (mut c, parts) in single(&loose) {
                if parts[0] != initial || c.consumed < len || (whole && fold(&parts[4]) != target) {
                    continue;
                }
                c.consumed = end;
                c.breaks.clear();
                rows.push((c, parts, true));
            }
            if plain {
                for (mut c, parts) in shorter(&loose) {
                    if parts[0] == initial {
                        c.consumed = c.consumed + word - len;
                        c.breaks.clear();
                        rows.push((c, parts, true));
                    }
                }
            }
        }
        rows
    }

    /// Chữ đầu của `keys` (tới phím cách) chỉ có phụ âm, chưa có nguyên âm (`t`, `ngh`).
    fn typed_initial_only(&self, keys: &str) -> bool {
        let word: String = keys.chars().take_while(|&c| c != '\'').map(text::lower).collect();
        !self.compose(&word).chars().any(|c| text::is_vowel(text::base(text::split_tone(c).0)))
    }

    /// Từ điển có âm nào bắt đầu đúng bằng phụ âm đầu `initial` không (`g` không tính gà của `gh`, `gi`).
    fn initial_has_words(&self, initial: &str) -> bool {
        let size = self.dict.entries().len();
        if let Ok(mut cache) = self.narrow_cache.initials.lock() {
            if cache.0 != size {
                *cache = (size, Default::default());
            }
            if let Some(&found) = cache.1.get(initial) {
                return found;
            }
        }
        let found = self.initial_has_words_uncached(initial);
        if let Ok(mut cache) = self.narrow_cache.initials.lock() {
            cache.1.insert(initial.to_string(), found);
        }
        found
    }

    fn initial_has_words_uncached(&self, initial: &str) -> bool {
        let key = text::fold_key(initial);
        let found = self
            .dict
            .lookup_prefix(&key)
            .take(5000)
            .any(|(_, e)| Self::first_syllable_initial(&e.reading).as_deref() == Some(initial));
        found
    }

    /// Phụ âm đầu của âm tiết đầu trong âm đọc `reading` (chữ thường, giữ đ), như [`syllable_split`].
    fn first_syllable_initial(reading: &str) -> Option<String> {
        let first: String = reading.split(' ').next()?.chars().map(text::lower).collect();
        syllable_split(&first).map(|p| p[0].clone())
    }

    /// Chữ đầu của `keys` bỏ thanh và dấu mũ / móc (`ddos` đó → `ddo`), rồi đổi phụ âm đầu sang từng phụ âm
    /// cùng nhóm dễ lẫn (`laf` → `la`, `na`), kèm phụ âm đầu; đầu tiên là phụ âm đã gõ. `gi` trước phụ âm hay
    /// hết chữ là g + nguyên âm i (`gif` gì ↔ `di` dì), nên phụ âm đầu là g và chỉ đổi chữ g.
    fn loose_words(&self, keys: &str) -> Vec<(String, String)> {
        let word: String = keys.chars().take_while(|&c| c != '\'' && c != ' ').collect();
        let letters: Vec<char> = self.compose(&word).chars().map(|c| text::split_tone(text::lower(c)).0).collect();
        // Telex gõ tắt toàn phụ âm (`ndtphn`): mỗi chữ cái là một âm tiết, phụ âm đầu của âm tiết đầu chỉ là chữ
        // cái đầu (n, không phải cả `ndtphn`, cũng không gộp `nh`), xem [`single_initial`].
        let run = self.options.telex && is_initials_only(&word);
        let mut initial: String =
            if run { letters.iter().take(1).collect() } else { letters.iter().take_while(|&&c| !text::is_vowel(c)).collect() };
        if initial == "g" && letters.get(1) == Some(&'i') {
            initial = "gi".into();
        }
        // q luôn đi với u: phụ âm đầu là qu, nên đổi sang phụ âm khác thì bỏ u (`qua` → ca, ka, ga, gha).
        if initial == "q" && letters.get(1) == Some(&'u') {
            initial = "qu".into();
        }
        let i_is_vowel = initial == "gi" && !letters.iter().skip(2).any(|&c| text::is_vowel(c));
        let skip = if i_is_vowel { 1 } else { initial.chars().count() };
        let body: String = letters.iter().skip(skip).map(|&c| text::base(c)).collect();
        if !body.chars().all(|c| c.is_ascii_lowercase()) {
            return Vec::new();
        }
        // Phím gõ ra phụ âm đầu: đ là `dd`, bật d/z thì đ là `d`, d là `z`.
        let key_of = |initial: &str| -> String {
            match initial {
                "d" if self.options.dz => "z".into(),
                "đ" if self.options.dz => "d".into(),
                "đ" => "dd".into(),
                s => s.into(),
            }
        };
        let typed = if i_is_vowel { "g" } else { initial.as_str() };
        let mut out = vec![(typed.to_string(), format!("{}{body}", key_of(typed)))];
        // Phụ âm có thể ở nhiều nhóm (v ở d/đ/gi/r/v và g/gh/v): lấy mọi phụ âm cùng nhóm với nó.
        let mut mates: Vec<&str> = Vec::new();
        for &m in INITIAL_GROUPS.iter().filter(|g| g.contains(&initial.as_str())).flat_map(|g| g.iter()) {
            if m != initial && !mates.contains(&m) {
                mates.push(m);
            }
        }
        for g in mates {
            // i đã là nguyên âm (`gif` gì, `dif` dì): đổi sang gi chỉ thêm g, chữ đó tách là g + i.
            let (label, key) = if g == "gi" && body.starts_with('i') { ("g", "g".to_string()) } else { (g, key_of(g)) };
            out.push((label.to_string(), format!("{key}{body}")));
        }
        // Chuỗi toàn phụ âm: cột trái hỏi cả các phụ âm dài hơn bắt đầu bằng chữ cái đó (n → ng, nh, ngh), như
        // khi chỉ gõ một chữ cái `n`.
        if run {
            for v in VALID_INITIALS {
                if v.len() > initial.len() && v.starts_with(initial.as_str()) && !out.iter().any(|(l, _)| l == v) {
                    out.push((v.to_string(), format!("{}{body}", key_of(v))));
                }
            }
        }
        out
    }

    /// Lọc `rows` theo các lựa chọn `picked` của [`Self::narrow`], mỗi chữ một lần.
    fn narrowed_from(&self, rows: Vec<(Candidate, [String; 5])>, picked: &[(Part, String)]) -> Vec<(Candidate, [String; 5])> {
        let mut shown = HashSet::new();
        // Phụ âm đầu không có âm nào dùng đúng nó (chữ cái vừa gõ `q`, xem [`Self::narrow`]) khớp mọi phụ âm
        // đầu bắt đầu bằng nó (qu).
        let exact: HashSet<String> = rows.iter().map(|(_, parts)| parts[0].clone()).collect();
        let matches = |p: Part, v: &str, parts: &[String; 5]| {
            let part = &parts[p as usize];
            part == v || (p == Part::Initial && !exact.contains(v) && part.starts_with(v))
        };
        rows.into_iter()
            .filter(|(_, parts)| picked.iter().all(|(p, v)| matches(*p, v, parts)))
            .filter(|(c, _)| shown.insert(c.text.clone()))
            .collect()
    }

    /// Thu hẹp âm tiết đầu từng cấp, cho cột trái của bảng gợi ý: phụ âm đầu → nguyên âm đầu → vần → phụ âm cuối → cả âm
    /// có thanh. `picked`: các lựa chọn đã chọn, theo thứ tự cấp. Trả cấp kế tiếp trong âm đọc của các gợi ý
    /// còn lại, cùng các lựa chọn đó: phụ âm đầu, vần, phụ âm cuối xếp ngắn trước dài (nguyên âm đơn rồi kép,
    /// ba) rồi a → z; cả âm theo thứ hạng gợi ý. Cấp chỉ có một lựa chọn vẫn hỏi (q → qu → quô → vần quô →
    /// phụ âm cuối quôc), trừ khi phím đã gõ ra đúng phần đó (`chu`: ch, u đã rõ, hỏi thẳng thanh) hay đó là
    /// cả âm có thanh (chỉ còn quốc thì lưới đã là chữ quốc). `None` khi không còn gì để thu hẹp.
    /// `c` → c, ch…; chọn ch → a, â, e…, ai, âu…; chọn âu → châu, chầu, chấu…
    pub fn narrow(&self, keys: &str, picked: &[(Part, String)]) -> Option<(Part, Vec<String>)> {
        // Đã chọn cả âm (kể cả chọn nhanh âm đã gõ): vẫn cho đổi sang các thanh khác của chính âm đó (ma → mà, má…).
        if let Some((Part::Syllable, chosen)) = picked.last() {
            let bare = untoned(chosen);
            let mut seen = HashSet::new();
            let mut options: Vec<String> = self
                .narrowed(keys, &picked[..picked.len() - 1])
                .into_iter()
                .map(|(_, parts)| parts[4].clone())
                .filter(|s| untoned(s) == bare && seen.insert(s.clone()))
                .collect();
            if !options.contains(chosen) {
                options.push(chosen.clone());
            }
            sort_by_tone(&mut options);
            return Some((Part::Syllable, options));
        }
        // Chưa chọn gì: phụ âm đầu hỏi cả các phụ âm cùng nhóm dễ lẫn (`laf` → l, n); không phải hỏi phụ âm đầu
        // thì các cấp sau chỉ tính chữ đã gõ.
        if picked.is_empty() {
            let rows = self.narrowed_from(self.narrow_rows(keys, None).into_iter().map(|(c, parts, _)| (c, parts)).collect(), picked);
            if let Some(asked @ (Part::Initial, _)) = self.narrow_options(keys, picked, &rows) {
                return Some(asked);
            }
        }
        let rows = self.narrowed(keys, picked);
        // Phần đã rõ tính theo chữ bỏ dấu, với phụ âm đã chọn (n của `laf`: na): khỏi hỏi lại vần đã gõ, còn
        // các cấp có dấu khác (o, ô, ơ của `ddos`) vẫn hỏi.
        let chosen = picked.iter().find(|(p, _)| *p == Part::Initial).map(|(_, v)| v.as_str());
        let loose = self.loose_words(keys).into_iter().enumerate().find(|(i, (g, _))| chosen.map_or(*i == 0, |c| c == g));
        let loose = loose.map(|(_, (_, k))| k);
        self.narrow_options(loose.as_deref().unwrap_or(keys), picked, &rows)
    }

    /// Cấp kế tiếp và các lựa chọn của nó, từ các gợi ý còn lại `rows` ([`Self::narrowed`]).
    fn narrow_options(&self, keys: &str, picked: &[(Part, String)], rows: &[(Candidate, [String; 5])]) -> Option<(Part, Vec<String>)> {
        // Phần phím đã gõ ra: cấp một lựa chọn mà nhãn đã có trong đó thì khỏi hỏi (cả âm có thanh so cả thanh).
        let composed: String = self.compose(keys).chars().map(text::lower).collect();
        let typed: String = composed.chars().map(|c| text::split_tone(c).0).collect();
        let start = picked.last().map_or(0, |(p, _)| *p as usize + 1);
        for level in start..PARTS.len() {
            let mut seen = HashSet::new();
            let mut options: Vec<String> =
                rows.iter().map(|(_, parts)| parts[level].clone()).filter(|v| seen.insert(v.clone())).collect();
            // Hỏi phụ âm đầu ngay từ chữ cái đầu: chữ cái vừa gõ không tự là phụ âm đầu thì cũng là một lựa chọn
            // (`q` → q, qu; `p` → p, ph), trừ khi đã gõ đủ phụ âm đầu (`ph…`, `qua`).
            if PARTS[level] == Part::Initial && picked.is_empty() {
                let typed: String = keys.chars().take(1).map(text::lower).collect();
                let lower = keys.to_lowercase();
                let spelled = options.iter().any(|v| v.chars().count() > 1 && lower.starts_with(v.as_str()));
                if !typed.is_empty() && typed.chars().all(|c| c.is_ascii_alphabetic()) && !seen.contains(&typed)
                    && !spelled && options.iter().all(|v| !v.is_empty() && v.starts_with(&typed))
                {
                    options.push(typed);
                }
                // Nhóm phụ âm dễ lẫn ([`INITIAL_GROUPS`]): hiện các phụ âm cùng nhóm có chữ, ẩn phụ âm không ra
                // chữ nào cho gọn. Đã gõ cả vần thì `rows` đã có chữ của từng phụ âm (gõ `cuxng`: có gũng thì hiện
                // g, không có kũng thì ẩn k); chỉ gõ phụ âm đầu thì xét từ điển có âm bắt đầu bằng phụ âm đó không.
                if self.typed_initial_only(keys) {
                    for g in INITIAL_GROUPS {
                        if options.iter().any(|o| g.contains(&o.as_str())) {
                            for m in g.iter() {
                                if !options.iter().any(|o| o == m) && self.initial_has_words(m) {
                                    options.push(m.to_string());
                                }
                            }
                        }
                    }
                    // Gõ tắt toàn phụ âm (`ndtphn`): các phụ âm dài hơn bắt đầu bằng chữ cái đầu (ng, nh, ngh).
                    for (label, _) in self.loose_words(keys) {
                        if !options.contains(&label) && self.initial_has_words(&label) {
                            options.push(label);
                        }
                    }
                }
            }
            // Vần không thể có phụ âm cuối (vần đôi ôi, ai, âu…; chỉ còn lựa chọn rỗng): khỏi hỏi phụ âm cuối.
            if PARTS[level] == Part::Final && options.iter().all(|o| o.is_empty()) {
                continue;
            }
            if options.len() < 2 {
                let known = match (PARTS[level], rows.first()) {
                    (_, None) => true,
                    (Part::Syllable, Some((_, p))) => composed.starts_with(&p[4]),
                    (part, Some((_, p))) => typed.starts_with(&spell(part, p)),
                };
                if options.is_empty() || known {
                    continue;
                }
            }
            if PARTS[level] != Part::Syllable {
                options.sort_by_cached_key(|v| (v.chars().count(), dict::collation_key(v)));
            } else {
                sort_by_tone(&mut options);
            }
            // Phụ âm đầu đã gõ đứng đầu (nhóm dài như b/v/p/…/th thì khỏi tìm), chạm là sang vần.
            if PARTS[level] == Part::Initial && picked.is_empty() {
                let typed = self.loose_words(keys).into_iter().next().map(|(label, _)| label);
                let lower = keys.to_lowercase();
                // `qua`: lõi tách q + ua, nhưng phụ âm đầu đã gõ là qu.
                let pos = options.iter().position(|o| Some(o) == typed.as_ref()).or_else(|| {
                    let t = typed.as_deref()?;
                    options.iter().position(|o| !t.is_empty() && o.starts_with(t) && lower.starts_with(o.as_str()))
                });
                if let Some(pos) = pos {
                    let first = options.remove(pos);
                    // Ngay sau đó là các phụ âm dài hơn bắt đầu bằng nó (`n` → ng, nh, ngh; `p` → ph), rồi mới tới
                    // phụ âm cùng nhóm.
                    let (longer, rest): (Vec<String>, Vec<String>) =
                        options.drain(..).partition(|o| o.starts_with(first.as_str()));
                    options.push(first);
                    options.extend(longer);
                    options.extend(rest);
                }
            }
            return Some((PARTS[level], options));
        }
        None
    }

    /// Các gợi ý cho `keys` có âm tiết đầu khớp mọi lựa chọn `picked` của [`Self::narrow`].
    pub fn candidates_narrowed(&self, keys: &str, picked: &[(Part, String)]) -> Vec<Candidate> {
        self.narrowed(keys, picked).into_iter().map(|(c, _)| c).collect()
    }

    /// Gợi ý chữ Quốc ngữ có dấu (Telex rút gọn ở chế độ Quốc ngữ): `nguoi` → `người`.
    /// Mã gõ tắt được thay bằng âm đọc thật của chữ (`k` → `không`).
    pub fn readings(&self, keys: &str) -> Vec<Candidate> {
        let mut seen = HashSet::new();
        self.candidates(keys)
            .into_iter()
            .filter_map(|c| {
                let reading = if dict::is_shortcut(&c.reading) {
                    self.dict.reading_of(&c.text)?.to_string()
                } else {
                    c.reading.clone()
                };
                Some(Candidate { text: reading.clone(), reading, ..c })
            })
            .filter(|c| seen.insert(c.reading.clone()))
            .collect()
    }

    /// Chữ thường của chuỗi phím cho gõ tắt, phân biệt d / đ như khi gõ chữ: tắt d/z thì `dd` là đ,
    /// bật d/z thì `d` là đ, `z` là d. Gõ tắt không có thanh, nên dấu cách (thanh ngang) chỉ là
    /// ranh giới như `'`.
    fn letters(&self, keys: &str) -> Vec<char> {
        self.letters_at(keys).0
    }

    /// Như [`Self::letters`], kèm vị trí trong kết quả của từng phím (hai phím `dd` cùng một vị trí).
    fn letters_at(&self, keys: &str) -> (Vec<char>, Vec<usize>) {
        let raw: Vec<char> = keys
            .chars()
            .map(text::lower)
            .map(|c| if c == ' ' { '\'' } else { c })
            .collect();
        // Chữ z / d thật (giữ phím z / d khi bật d/z) là chính nó.
        let literal = |c: char| match c {
            telex::LITERAL_Z => Some('z'),
            telex::LITERAL_D => Some('d'),
            _ => None,
        };
        if self.options.dz {
            let at = (0..raw.len()).collect();
            return (raw.into_iter().map(|c| match c {
                'd' => 'đ',
                'z' => 'd',
                c => literal(c).unwrap_or(c),
            }).collect(), at);
        }
        let raw: Vec<char> = raw.into_iter().map(|c| literal(c).unwrap_or(c)).collect();
        let mut out = Vec::with_capacity(raw.len());
        let mut at = Vec::with_capacity(raw.len());
        let mut i = 0;
        while i < raw.len() {
            at.push(out.len());
            if raw[i] == 'd' && raw.get(i + 1) == Some(&'d') {
                at.push(out.len());
                out.push('đ');
                i += 2;
            } else {
                out.push(raw[i]);
                i += 1;
            }
        }
        (out, at)
    }

    /// Chuỗi phím → mã gõ tắt (xem [`Self::shortcuts`]).
    fn shortcut_code(&self, keys: &str) -> String {
        self.letters(keys).into_iter().filter(|&c| c != '\'').collect()
    }

    /// Ghi nhận người dùng đã chọn một gợi ý: tăng độ phổ biến của cặp đó. Gợi ý ghép không có trong
    /// từ điển thì không học. Trả `true` nếu từ điển đổi.
    pub fn select(&mut self, cand: &Candidate) -> bool {
        self.options.learning && self.dict.bump(&cand.text, &cand.reading)
    }

    /// Ghi nhận người dùng đã đưa ra cụm `text` (âm `reading`) cho chuỗi phím `keys`, kể cả cụm ghép tự động hay
    /// chọn từng phần (`tphn` → 城 + 舖 + 河內): cụm chưa có thì thêm vào từ điển; gõ tắt toàn phụ âm thì nhớ cả
    /// mã gõ tắt đó (`城舖河內;tphn`), để lần sau gõ đúng chuỗi ấy cụm đứng đầu. Trả `true` nếu từ điển đổi.
    pub fn learn(&mut self, keys: &str, text: &str, reading: &str) -> bool {
        if !self.options.learning {
            return false;
        }
        let syllables = reading.split(' ').count();
        if syllables < 2 || syllables != text.chars().count() {
            return self.dict.bump(text, reading);
        }
        let step = self.dict.step();
        let mut changed = false;
        let mut remember = |dict: &mut Dictionary, reading: &str| {
            if dict.bump(text, reading) {
                changed = true;
            } else if let Ok(entry) = dict::Entry::new(text, reading) {
                changed |= dict.put(None, entry, step);
            }
        };
        remember(&mut self.dict, reading);
        let code = self.shortcut_code(keys);
        if self.options.telex && is_initials_only(keys) && dict::is_shortcut(&code) && code.chars().count() <= MAX_ABBREVIATION {
            remember(&mut self.dict, &code);
        }
        changed
    }

    /// Từ điển cho màn hình quản lý: mỗi cặp chữ – âm một dòng kèm độ phổ biến, xếp a → z như tệp lưu.
    /// `query` khớp chữ, âm đọc (nguyên âm tiết, xem [`text::reading_has`]) hoặc mã Unicode (`U+56FA`); rỗng thì lấy hết.
    pub fn rows(&self, query: &str) -> Vec<Row> {
        let q = query.trim();
        let code = q.to_uppercase();
        self.dict
            .sorted()
            .into_iter()
            .filter_map(|i| {
                let e = &self.dict.entries()[i];
                let matches = q.is_empty()
                    || e.text.contains(q)
                    || text::reading_has(&e.reading, q)
                    || (code.starts_with("U+") && unicode_codes(&e.text).contains(&code));
                matches.then(|| Row { text: e.text.clone(), reading: e.reading.clone(), freq: self.dict.freq(i) })
            })
            .collect()
    }
}

/// Một cặp chữ – âm trong từ điển và độ phổ biến của nó.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Row {
    pub text: String,
    pub reading: String,
    pub freq: u32,
}

/// Mã Unicode của các ký tự, cách nhau bằng dấu cách (`越南` → `U+8D8A U+5357`).
pub fn unicode_codes(text: &str) -> String {
    text.chars().map(|c| format!("U+{:04X}", c as u32)).collect::<Vec<_>>().join(" ")
}

/// Khớp chưa đúng hẳn dấu (`true` xếp sau), rồi thứ tự trong từ điển.
type Rank = (bool, usize);

/// Một cấp thu hẹp âm tiết đầu ([`Engine::narrow`]), theo thứ tự.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Part {
    /// Phụ âm đầu (có thể rỗng: `an`).
    Initial,
    /// Nguyên âm đầu của vần, giữ dấu phụ (`ư` của `ươ`, `ưu`, `ư`): hỏi trước để mỗi lần ít lựa chọn hơn.
    Vowel,
    /// Vần nguyên âm, giữ dấu phụ, bỏ thanh (`âu`, `ươ`).
    Nucleus,
    /// Phụ âm cuối (có thể rỗng).
    Final,
    /// Cả âm tiết có thanh (`châu`, `chấu`).
    Syllable,
}

const PARTS: [Part; 5] = [Part::Initial, Part::Vowel, Part::Nucleus, Part::Final, Part::Syllable];

impl Part {
    /// Cấp thứ `i` theo thứ tự (cho giao diện JNI).
    pub fn from_index(i: usize) -> Option<Part> {
        PARTS.get(i).copied()
    }
}

/// Âm tiết bỏ thanh, giữ dấu mũ / móc (`mả` → `ma`, `chấu` → `châu`).
fn untoned(s: &str) -> String {
    s.chars().map(|c| text::split_tone(c).0).collect()
}

/// Xếp các âm có thanh theo âm bỏ thanh, rồi thanh theo thứ tự cố định: ngang, huyền, sắc, hỏi, ngã, nặng
/// (ma, mà, má, mả, mã, mạ).
fn sort_by_tone(options: &mut [String]) {
    let tone = |s: &str| -> usize {
        let t = s.chars().map(|c| text::split_tone(c).1).find(|t| *t != text::Tone::Level).unwrap_or_default();
        match t {
            text::Tone::Level => 0,
            text::Tone::Grave => 1,
            text::Tone::Acute => 2,
            text::Tone::Hook => 3,
            text::Tone::Tilde => 4,
            text::Tone::Dot => 5,
        }
    };
    options.sort_by_cached_key(|s| (dict::collation_key(&untoned(s)), tone(s)));
}

/// Phần âm tiết `p` ([`syllable_split`]) đã rõ khi tới cấp `part`, ghép liền: phụ âm đầu + nguyên âm đầu (hay
/// vần) + phụ âm cuối; cấp cả âm là cả âm có thanh (qu, quô, quôc, quốc). Để biết phím đã gõ ra phần đó chưa.
fn spell(part: Part, p: &[String; 5]) -> String {
    let level = part as usize;
    if part == Part::Syllable {
        return p[4].clone();
    }
    let rhyme = if level >= Part::Nucleus as usize { &p[2] } else if level >= Part::Vowel as usize { &p[1] } else { "" };
    let last = if level >= Part::Final as usize { p[3].as_str() } else { "" };
    format!("{}{rhyme}{last}", p[0])
}

/// Phụ âm đầu, nguyên âm đầu, vần nguyên âm, phụ âm cuối (bỏ thanh) và cả âm tiết `s` (chữ thường), theo
/// thứ tự [`PARTS`]; `None` nếu không có nguyên âm.
fn syllable_split(s: &str) -> Option<[String; 5]> {
    let (letters, _) = text::split_syllable(s)?;
    let (ns, ne) = syllable::nucleus_range(&letters);
    if ns == ne {
        return None;
    }
    let part = |r: std::ops::Range<usize>| letters[r].iter().collect::<String>();
    Some([part(0..ns), part(ns..ns + 1), part(ns..ne), part(ne..letters.len()), s.to_string()])
}

/// Gõ tắt toàn phụ âm: câu ghép tự động đứng sau chừng này chữ của âm đầu ([`Engine::with_sentence`]).
const RUN_SENTENCE_AFTER: usize = 3;

/// Số phím tối đa cho gõ tắt (giới hạn số cách tách).
const MAX_ABBREVIATION: usize = 14;

/// Nhóm phụ âm đầu dễ lẫn, đúng như người dùng đặt (2026-11-14): cột trái của bảng gợi ý hỏi mọi phụ âm cùng
/// nhóm ([`Engine::narrow`]), trừ phụ âm ghép vào không ra chữ nào. Phụ âm không có trong nhóm nào (h, kh, nh) đứng
/// riêng. Đặc tả: mục F3 của PRD-mobile.md; chỉ người dùng đổi nhóm.
const INITIAL_GROUPS: [&[&str]; 5] = [
    // Nhóm do người dùng đặt; h, kh, nh đứng riêng (không cùng nhóm với phụ âm nào).
    &["b", "m", "v", "l", "n", "p", "ph", "d", "gi", "r", "đ", "t", "th"],
    &["c", "k", "qu", "g", "gh"],
    &["ch", "tr"],
    &["ng", "ngh"],
    &["s", "x"],
];
/// Gõ tắt toàn phụ âm kiểu Telex: cụm khớp phần đầu chuỗi phím dài tối đa chừng này phím (đủ cho cụm 6 chữ).
const MAX_PREFIX_ABBREVIATION: usize = 6;

/// Chuỗi phím Telex toàn phụ âm (bỏ qua dấu tách `'`): không gõ được âm tiết nào, nên là gõ tắt (`qvvtq`).
/// `w` đứng riêng là chữ w (không ra ư), nên cũng tính là phụ âm.
fn is_initials_only(keys: &str) -> bool {
    let letters: Vec<char> = keys.chars().filter(|&c| c != '\'').map(text::lower).collect();
    letters.len() >= 2 && letters.iter().all(|c| c.is_ascii_lowercase() && !"aeiouy".contains(*c))
}
/// Một âm tiết bỏ dấu dài nhất là 7 chữ (`nghieng`).
const MAX_SYLLABLE_LETTERS: usize = 7;
/// Chuỗi phím dài hơn thế thì không ghép cả câu (tránh treo: mỗi vị trí phải tra từ điển một lần).
/// Đo trên máy tính (WASM, từ điển thật): 80 phím ≈ 200 ms, nên không nâng.
const MAX_SENTENCE_KEYS: usize = 80;
/// Như [`MAX_SENTENCE_KEYS`] cho bàn phím Telex: chữ đã tách bằng phím cách nên tra nhanh hơn nhiều
/// (120 phím ≈ 30 ms, 160 phím ≈ 80 ms).
const MAX_SENTENCE_KEYS_TELEX: usize = 120;

/// Mọi chuỗi chữ cái đầu có thể có khi tách `keys` thành ≥ 2 đoạn (dấu `'` ép ranh giới).
fn collect_initials(keys: &[char], pos: usize, acc: &mut String, out: &mut HashSet<String>) {
    let pos = pos + keys[pos..].iter().take_while(|&&c| c == '\'').count();
    if pos == keys.len() {
        if acc.chars().count() >= 2 {
            out.insert(acc.clone());
        }
        return;
    }
    acc.push(keys[pos]);
    for len in 1..=MAX_SYLLABLE_LETTERS.min(keys.len() - pos) {
        if keys[pos + len - 1] == '\'' {
            break;
        }
        collect_initials(keys, pos + len, acc, out);
    }
    acc.pop();
}

/// `keys` có tách được thành các đoạn, mỗi đoạn là phần đầu của âm tiết tương ứng không?
/// Một đoạn có thể kèm phím dấu phụ / thanh ở đuôi (`h'six` → học sĩ), khi đó dấu gõ phải khớp.
/// `syllables`: chữ cái từng âm tiết (giữ dấu phụ, bỏ thanh) và thanh của nó.
/// `locked`: các đoạn không được tách ngang.
fn abbreviation_matches(keys: &[char], pos: usize, syllables: &[(Vec<char>, Tone)], locked: &[(usize, usize)], strict: bool) -> bool {
    let pos = pos + keys[pos..].iter().take_while(|&&c| c == '\'').count();
    let Some(((syl, tone), rest)) = syllables.split_first() else {
        return pos == keys.len();
    };
    // Dài hơn âm tiết vài phím: chỗ cho `w` / `q` và phím thanh ở đuôi.
    (1..=(syl.len() + 3).min(keys.len() - pos))
        .take_while(|&len| keys[pos + len - 1] != '\'')
        .any(|len| {
            let end = pos + len;
            if locked.iter().any(|&(s, e)| s < end && end < e) {
                return false;
            }
            let seg = &keys[pos..end];
            let plain = plain_segment(seg, syl, strict);
            (plain || marked_prefix(seg, syl, *tone)) && abbreviation_matches(keys, end, rest, locked, strict)
        })
}

/// Phụ âm đầu hợp lệ (để biết chữ cái gõ tắt có tự là một phụ âm đầu không).
const VALID_INITIALS: [&str; 27] = [
    "b", "c", "ch", "d", "đ", "g", "gh", "gi", "h", "k", "kh", "l", "m", "n", "ng", "ngh", "nh", "p", "ph", "qu", "r",
    "s", "t", "th", "tr", "v", "x",
];

/// Gõ tắt nhiều phụ âm liền nhau (`bndtp`, `strict`): mỗi chữ cái là một phụ âm đơn, đúng phụ âm đó; không tự
/// gộp sang phụ âm dài hơn (`n` là n, không phải nh) và không gộp hai chữ cái thành phụ âm đôi (`nh` là n + h,
/// người dùng quyết định 2026-11-14). Riêng `q` (không tự là phụ âm đầu) là qu. Đoạn có nguyên âm, hay không
/// phải chuỗi phụ âm liền nhau, giữ như cũ (phần đầu của âm).
fn single_initial(seg: &[char], syl: &[char], strict: bool) -> bool {
    if !strict || seg.iter().any(|&c| text::is_vowel(c)) {
        return true;
    }
    let fold = |c: char| if c == 'đ' { c } else { text::base(c) };
    let (ns, _) = syllable::nucleus_range(syl);
    let initial: String = syl[..ns].iter().map(|&c| fold(c)).collect();
    let typed: String = seg.iter().map(|&c| fold(c)).collect();
    seg.len() == 1 && (typed == initial || (!VALID_INITIALS.contains(&typed.as_str()) && initial.starts_with(&typed)))
}

/// Đoạn phím `seg` (không dấu ở đuôi) là phần đầu của âm tiết `syl`. Gõ tắt bỏ qua dấu phụ, trừ d / đ là hai
/// chữ khác nhau. Riêng `w` đứng một mình là âm bắt đầu bằng ư (`tw` → trung ương; người dùng đặt 2026-11-14).
fn plain_segment(seg: &[char], syl: &[char], strict: bool) -> bool {
    if seg == ['w'] && syl.first() == Some(&'ư') {
        return true;
    }
    let fold = |c: char| if c == 'đ' { c } else { text::base(c) };
    seg.len() <= syl.len() && seg.iter().zip(syl).all(|(&a, &b)| a == fold(b)) && single_initial(seg, syl, strict)
}

/// Chữ cái từng âm tiết của âm đọc (giữ dấu phụ, bỏ thanh) và thanh của nó, cho [`abbreviation_matches`].
fn syllable_parts(reading: &str) -> Vec<(Vec<char>, Tone)> {
    reading
        .split(' ')
        .map(|s| {
            let chars: Vec<(char, Tone)> = s.chars().map(|c| text::split_tone(text::lower(c))).collect();
            let tone = chars.iter().map(|&(_, t)| t).find(|&t| t != Tone::Level).unwrap_or(Tone::Level);
            (chars.into_iter().map(|(c, _)| c).collect(), tone)
        })
        .collect()
}

/// Như [`abbreviation_matches`] nhưng chỉ cần khớp phần đầu của `keys`: ghi mọi vị trí kết thúc vào `out`.
/// Chỉ kết thúc ở ranh giới chữ (không giữa chữ đã gõ dấu).
fn abbreviation_ends(
    keys: &[char],
    pos: usize,
    syllables: &[(Vec<char>, Tone)],
    locked: &[(usize, usize)],
    strict: bool,
    out: &mut Vec<usize>,
) {
    let pos = pos + keys[pos..].iter().take_while(|&&c| c == '\'').count();
    let Some(((syl, tone), rest)) = syllables.split_first() else {
        out.push(pos);
        return;
    };
    for len in 1..=(syl.len() + 3).min(keys.len() - pos) {
        let end = pos + len;
        if keys[end - 1] == '\'' {
            break;
        }
        if locked.iter().any(|&(s, e)| s < end && end < e) {
            continue;
        }
        let seg = &keys[pos..end];
        let plain = plain_segment(seg, syl, strict);
        if plain || marked_prefix(seg, syl, *tone) {
            abbreviation_ends(keys, end, rest, locked, strict, out);
        }
    }
}

/// Đoạn gõ tắt có phím dấu ở đuôi (`six`, `suwx`): dấu kết thúc chữ, nên ghép Telex phải ra trọn âm tiết
/// (`has` là há, không khớp hán), dấu phụ đã gõ phải đúng (`namw` không khớp nam) và thanh đã gõ phải đúng
/// thanh của âm tiết.
fn marked_prefix(seg: &[char], syl: &[char], tone: Tone) -> bool {
    let is_mark = |c: char| c == 'w' || c == 'q' || Tone::from_telex(c).is_some();
    if !seg.last().is_some_and(|&c| is_mark(c)) {
        return false;
    }
    // Chuỗi gõ tắt đã đổi `dd` thành đ: trả lại phím để ghép Telex.
    let keys: String = seg.iter().flat_map(|&c| if c == 'đ' { vec!['d', 'd'] } else { vec![c] }).collect();
    let (composed, _) = telex::compose_marked(&keys, ToneStyle::Old, false);
    let typed: Vec<(char, Tone)> = composed.chars().map(|c| text::split_tone(text::lower(c))).collect();
    let typed_tone = typed.iter().map(|&(_, t)| t).find(|&t| t != Tone::Level).unwrap_or(Tone::Level);
    let letters: Vec<char> = typed.iter().map(|&(c, _)| c).collect();
    letters.iter().any(|&c| text::is_vowel(c))
        && letters.len() == syl.len()
        && prefix_matches(&letters, syl)
        && (typed_tone == Tone::Level || typed_tone == tone)
}

/// So một âm tiết đã gõ với một âm đọc trong từ điển:
/// chữ gõ không dấu phụ khớp mọi dấu phụ; không gõ thanh khớp mọi thanh, trừ khi đã ấn phím cách (`level`).
/// Riêng `d` chỉ khớp d: đ phải gõ ra đ (`dd`, hoặc `d` khi bật tuỳ chọn `dz`), để tắt tuỳ chọn
/// thì `d` không trông như đã ra đ.
fn syllable_matches(typed: &str, reading: &str, level: bool) -> bool {
    let (Some((mut tl, tt)), Some((mut rl, rt))) = (text::split_syllable(typed), text::split_syllable(reading)) else {
        return false;
    };
    // sỹ = sĩ, kỹ = kĩ.
    text::y_as_i(&mut tl);
    text::y_as_i(&mut rl);
    tl.len() == rl.len() && tl.iter().zip(&rl).all(|(&a, &b)| letter_matches(a, b)) && ((tt == Tone::Level && !level) || tt == rt)
}

/// Chữ gõ không dấu phụ khớp mọi dấu phụ, trừ `d` không khớp đ (xem [`syllable_matches`]).
fn letter_matches(typed: char, reading: char) -> bool {
    typed == reading || (typed == text::base(reading) && reading != 'đ')
}

/// Chữ gõ dở `typed` là phần đầu của âm đọc `reading` (cả hai đã bỏ thanh), theo [`letter_matches`].
fn prefix_matches(typed: &[char], reading: &[char]) -> bool {
    // Gõ đủ âm tiết: y cuối sau phụ âm như i (sỹ = sĩ).
    if typed.len() == reading.len() {
        let (mut t, mut r) = (typed.to_vec(), reading.to_vec());
        text::y_as_i(&mut t);
        text::y_as_i(&mut r);
        return t.iter().zip(&r).all(|(&a, &b)| letter_matches(a, b));
    }
    typed.len() <= reading.len() && typed.iter().zip(reading).all(|(&a, &b)| letter_matches(a, b))
}

/// Các chữ cái thường, đã bỏ thanh (giữ dấu phụ): `Việt` → `viêt`.
fn letters_of(s: &str) -> Vec<char> {
    s.chars().map(|c| text::split_tone(text::lower(c)).0).collect()
}


#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str =
        "喃;nôm\n𡨸;chữ\n南;nam\n𢆥;năm\n𠄼;năm\n𡨸喃;chữ nôm\n𠊛;người\n越;việt\n越南;việt nam\n卒;tốt\n";

    /// Từ điển thử viết gọn `chữ;âm1,âm2`, độ phổ biến 0.
    fn from_text(content: &str) -> (Dictionary, dict::ImportReport) {
        Dictionary::from_text(&dict::compact(content))
    }

    fn engine() -> Engine {
        let (dict, report) = from_text(SAMPLE);
        assert!(report.errors.is_empty());
        Engine::new(dict)
    }

    fn texts(cands: &[Candidate]) -> Vec<&str> {
        cands.iter().map(|c| c.text.as_str()).collect()
    }

    #[test]
    fn breaks_between_syllables() {
        let cands = engine().candidates("chunom");
        assert_eq!(cands[0].text, "𡨸喃");
        assert_eq!(cands[0].breaks, [3]);
        assert!(engine().candidates("nom")[0].breaks.is_empty());
    }

    #[test]
    fn completes_last_syllable() {
        // Chữ trước đủ, chữ cuối gõ dở: vẫn gợi ý cả cụm, xếp trên chữ đơn.
        for keys in ["chuwxn", "chuwxno", "chun", "vietqjn"] {
            let cands = engine().candidates(keys);
            let want = if keys.starts_with('v') { "越南" } else { "𡨸喃" };
            assert_eq!(cands[0].text, want, "{keys}");
            assert_eq!(cands[0].consumed, keys.chars().count(), "{keys}");
        }
        assert_eq!(engine().candidates("chuwxno")[0].breaks, [5]);
    }

    #[test]
    fn edited_readings_replace_old() {
        let (dict, _) = from_text("固;cố,có\n南;nam\n");
        let mut e = Engine::new(dict);
        assert_eq!(texts(&e.candidates("co")), ["固"]);
        // Xoá cặp 固 – cố: gõ `coqs` (cố) không ra 固 nữa, `cos` (có) vẫn ra.
        assert!(e.dict.remove(&dict::Entry::new("固", "cố").unwrap()));
        assert!(e.candidates("coqs").is_empty());
        assert_eq!(texts(&e.candidates("cos")), ["固"]);
        e.dict.bump("固", "có");
        // Mỗi cặp một dòng, xếp a → z, kèm độ phổ biến.
        let rows = e.rows("");
        assert_eq!(rows[0], Row { text: "固".into(), reading: "có".into(), freq: 1 });
        assert_eq!(rows[1].text, "南");
        assert_eq!(e.rows("u+56fa")[0].text, "固");
        assert_eq!(e.rows("co")[0].text, "固");
        assert!(e.rows("viet").is_empty());
        assert_eq!(unicode_codes("越南"), "U+8D8A U+5357");
    }

    #[test]
    fn dictionary_guides_split() {
        // Hiện tự tách là `an hem`, nhưng từ điển có anh thì vẫn gợi ý 英 cho `anh`.
        let (dict, _) = from_text("英;anh\n");
        let e = Engine::new(dict);
        let cands = e.candidates("anhem");
        assert_eq!(texts(&cands).first(), Some(&"英"));
        assert_eq!(cands[0].consumed, 3);
    }

    #[test]
    fn telex_keyboard() {
        let mut e = Engine::new(from_text("漢;hán\n喃;nôm\n漢喃;hán nôm\n呵;há\n𡨸;chữ\n𡨸喃;chữ nôm\n卒;tốt\n多;đa\n").0);
        e.options.telex = true;
        // Dấu gõ đâu trong chữ cũng được: `hasn` là hán, như `hans`.
        for keys in ["hasn", "hans"] {
            let cands = e.candidates(keys);
            assert_eq!(cands[0].text, "漢", "{keys}");
            assert_eq!(cands[0].consumed, 4, "{keys}");
        }
        // aa ee oo, dd là dấu Telex.
        for keys in ["toots", "tosot", "tot"] {
            assert_eq!(texts(&e.candidates(keys)).first(), Some(&"卒"), "{keys}");
        }
        assert_eq!(texts(&e.candidates("dda")), ["多"]);
        // Chữ chỉ tách ở phím cách (`'`): gõ liền không tự tách.
        for keys in ["chuwx'noom", "chu'nom", "chuwx'nom", "hasn'noom"] {
            let cands = e.candidates(keys);
            assert_eq!(cands[0].consumed, keys.len(), "{keys}");
            assert!(["𡨸喃", "漢喃"].contains(&cands[0].text.as_str()), "{keys}: {:?}", texts(&cands));
        }
        for keys in ["chuwxnoom", "chunom", "hasnnoom", "chuwxn"] {
            let cands = e.candidates(keys);
            assert!(cands.iter().all(|c| !c.text.contains('喃')), "{keys}: {:?}", texts(&cands));
        }
        // Chữ cuối gõ dở sau phím cách vẫn ra cả cụm.
        assert_eq!(texts(&e.candidates("chuwx'n"))[0], "𡨸喃");
        // Toàn phụ âm là gõ tắt, mỗi chữ cái một phụ âm đơn, đúng phụ âm đó: không tự gộp sang phụ âm dài hơn
        // (`cn` không phải chữ nôm), không gộp hai chữ cái thành phụ âm đôi (`chn` là c + h + n).
        // Riêng chữ một âm thì như khi chỉ gõ `c` (có cả 𡨸 chữ), nên câu ghép tự động có thể ra 𡨸喃 (người dùng
        // muốn 2026-11-14); cụm có sẵn trong từ điển thì không khớp.
        for keys in ["cn", "chn"] {
            assert!(e.candidates(keys).iter().all(|c| c.text != "𡨸喃" || c.joined), "{keys}: {:?}", texts(&e.candidates(keys)));
        }
        assert!(texts(&e.candidates("cn")).contains(&"𡨸"));
        // Đã chọn 漢: 喃 của 漢喃 phải lấy trọn chữ tới phím cách, không chỉ mỗi `n` (ra 喃om).
        let after = |keys: &str| e.after("漢", keys).into_iter().map(|(c, ..)| (c.text, c.consumed)).collect::<Vec<_>>();
        assert_eq!(after("noom'laf"), [("喃".to_string(), 5)]);
        assert_eq!(after("noom"), [("喃".to_string(), 4)]);
        assert_eq!(after("n"), [("喃".to_string(), 1)]);
        assert!(after("nom'la").iter().all(|&(_, n)| n == 4), "{:?}", after("nom'la"));
        // Kiểu gõ Hán Nôm riêng không đổi.
        e.options.telex = false;
        assert!(!texts(&e.candidates("hasn")).contains(&"漢"));
    }

    #[test]
    fn telex_words_split() {
        let w = |k: &str| telex::telex_words(k);
        assert_eq!(w("hasn"), ["hasn"]);
        assert_eq!(w("chunom"), ["chu", "nom"]);
        assert_eq!(w("chuwxnoom"), ["chuwx", "noom"]);
        assert_eq!(w("hoasanh"), ["hoas", "anh"]);
        assert_eq!(w("chu'nom"), ["chu", "'", "nom"]);
        assert_eq!(telex::display_telex("chuwxnoom", ToneStyle::Old), "chữ nôm");
        assert_eq!(telex::display_telex("vieetj'nam", ToneStyle::Old), "việt'nam");
    }

    #[test]
    fn telex_vowel_after_consonant_adds_hat() {
        // Chữ chỉ tách ở phím cách: `a` gõ lại sau phụ âm là dấu mũ như Unikey (thana → thân, hafna → hần).
        assert_eq!(telex::display_telex("thana", ToneStyle::Old), "thân");
        assert_eq!(telex::display_telex("hafna", ToneStyle::Old), "hần");
        assert_eq!(telex::display_telex("haan", ToneStyle::Old), "hân");
        // Phím dấu chen giữa vẫn là một chữ.
        assert_eq!(telex::display_telex("tosot", ToneStyle::Old), "tốt");
    }

    #[test]
    fn telex_backspace_clears_tone() {
        let b = |k: &str| telex::backspace_telex(k, ToneStyle::Old);
        // Phím dấu đã thay dấu cũ: xoá hẳn dấu (à → a), không lùi về á.
        assert_eq!(b("asf"), "a");
        assert_eq!(b("lawng'loanfs"), "lawng'loan");
        assert_eq!(b("lawngloanfs"), "lawngloan");
        assert_eq!(b("tosf"), "to");
        // Còn lại như thường: bỏ phím cuối.
        assert_eq!(b("as"), "a");
        assert_eq!(b("hasn"), "has");
        assert_eq!(b("loan"), "loa");
        // `s` là phụ âm đầu chữ mới thì chỉ bỏ nó.
        assert_eq!(b("tot's"), "tot'");
        assert_eq!(b(""), "");
    }

    #[test]
    fn diacritics_optional() {
        let e = engine();
        assert_eq!(texts(&e.candidates("nam")), ["南", "𢆥", "𠄼"]);
        assert_eq!(texts(&e.candidates("nguoi")), ["𠊛"]);
        // Không dấu, chỉ dấu thanh, hay gõ đủ Telex đều ra cùng chữ.
        for keys in ["tot", "tots", "totqs"] {
            let cands = e.candidates(keys);
            assert_eq!(texts(&cands).first(), Some(&"卒"), "{keys}");
            assert_eq!(cands[0].consumed, keys.len());
        }
        for keys in ["nguoif", "nguoiwf"] {
            assert_eq!(texts(&e.candidates(keys)).first(), Some(&"𠊛"), "{keys}");
        }
        // Dấu đã gõ thì dùng để lọc.
        assert_eq!(texts(&e.candidates("namw")), ["𢆥", "𠄼"]);
        // Dấu chỉ nhận ở cuối chữ: `nawm` là nă + m, `aqn` là â + n.
        assert!(e.candidates("nawm").iter().all(|c| c.consumed < 4));
        assert!(e.candidates("aqn").iter().all(|c| c.consumed < 3));
        assert!(e.candidates("totj").iter().all(|c| c.consumed < 4));
    }

    #[test]
    fn trailing_markers_filter() {
        let (dict, _) = from_text("卒;tốt\n南;nam\n𢆥;năm\n𡗶;nắm\n多;đa\n移;di\n");
        let mut e = Engine::new(dict);
        // Chỉ các gợi ý dùng hết chuỗi phím.
        let full = |e: &Engine, keys: &str| -> Vec<String> {
            e.candidates(keys).into_iter().filter(|c| c.consumed == keys.len()).map(|c| c.text).collect()
        };
        for keys in ["totq", "totqs", "totsq"] {
            assert_eq!(full(&e, keys), ["卒"], "{keys}");
        }
        assert_eq!(full(&e, "namw"), ["𢆥", "𡗶"]);
        assert_eq!(full(&e, "nam "), ["南", "𢆥"]);
        assert!(full(&e, "namz").is_empty());
        assert_eq!(full(&e, "namws"), ["𡗶"]);
        // Không bật dz: `d` chỉ khớp d, đ phải gõ `dd`.
        assert!(full(&e, "da").is_empty());
        assert_eq!(full(&e, "dda"), ["多"]);
        assert_eq!(full(&e, "di"), ["移"]);
        assert!(full(&e, "zi").is_empty());
        e.options.dz = true;
        assert_eq!(full(&e, "da"), ["多"]);
        assert!(full(&e, "di").is_empty());
        assert_eq!(full(&e, "zi"), ["移"]);
    }

    #[test]
    fn marked_word_is_whole_syllable() {
        // Dấu kết thúc chữ: `has` là há trọn, không là phần đầu của hán (`hasn` không ra 漢喃).
        let e = Engine::new(from_text("漢;hán\n喃;nôm\n漢喃;hán nôm\n呵;há\n").0);
        assert!(!texts(&e.candidates("hasn")).contains(&"漢喃"));
        assert_eq!(texts(&e.candidates("hans"))[0], "漢");
        assert_eq!(texts(&e.candidates("hn"))[0], "漢喃");
    }

    #[test]
    fn exact_reading_first() {
        let (dict, _) = from_text("媽;má\n麻;ma\n");
        let e = Engine::new(dict);
        assert_eq!(texts(&e.candidates("ma")), ["麻", "媽"]);
        assert_eq!(texts(&e.candidates("Ma")), ["麻", "媽"]);
        assert_eq!(texts(&e.candidates("mas")).first(), Some(&"媽"));
        assert_eq!(texts(&e.readings("ma")), ["ma", "má"]);
    }

    #[test]
    fn phrase_first_then_partial() {
        let e = engine();
        let cands = e.candidates("chunom");
        assert_eq!(texts(&cands), ["𡨸喃", "𡨸"]);
        assert_eq!(cands[1].remaining("chunom"), "nom");
        assert_eq!(texts(&e.candidates("chuwxnomq")), ["𡨸喃", "𡨸"]);
    }

    #[test]
    fn partial_first_syllable_shows_phrases() {
        let e = engine();
        // Gõ phần đầu âm tiết đầu: có cả chữ đơn lẫn cụm từ điển (越南), cụm thay cả chuỗi phím.
        for keys in ["v", "vi", "viet"] {
            let cands = e.candidates(keys);
            let phrase = cands.iter().find(|c| c.text == "越南").unwrap_or_else(|| panic!("{keys}: {:?}", texts(&cands)));
            assert_eq!(phrase.consumed, keys.len());
            assert!(cands.iter().any(|c| c.text == "越"), "{keys}");
        }
        assert!(texts(&e.candidates("c")).contains(&"𡨸喃"));
        // Còn phím phía sau thì không lấy cụm theo phần đầu (越南 ở đây là khớp đủ hai âm tiết).
        let cands = e.candidates("vietnam");
        assert_eq!(cands[0].text, "越南");
        assert_eq!(cands[0].consumed, 7);
        assert!(!texts(&e.candidates("viec")).contains(&"越南"));
        // Mã gõ tắt: gõ phần đầu của mã đã thấy cụm, xếp sau chữ khớp thật; gõ đủ mã thì lên đầu.
        let mut e = Engine::new(from_text("越;việt\n文明澱學士;vmđhs\n").0);
        // d và đ là hai chữ khác nhau: tắt d/z thì đ gõ `dd`.
        for keys in ["v", "vm", "vmdd", "vmddh", "vmddhs"] {
            assert!(texts(&e.candidates(keys)).contains(&"文明澱學士"), "{keys}");
        }
        for keys in ["vmd", "vmdh", "vmdhs"] {
            assert!(!texts(&e.candidates(keys)).contains(&"文明澱學士"), "{keys}");
        }
        // Bật d/z: `d` là đ, `z` là d.
        e.options.dz = true;
        for keys in ["vmd", "vmdh", "vmdhs"] {
            assert!(texts(&e.candidates(keys)).contains(&"文明澱學士"), "dz {keys}");
        }
        assert!(!texts(&e.candidates("vmz")).contains(&"文明澱學士"));
        e.options.dz = false;
        assert_eq!(texts(&e.candidates("v")), ["越", "文明澱學士"]);
        assert_eq!(texts(&e.candidates("vmddhs"))[0], "文明澱學士");
        assert!(!texts(&e.candidates("vx")).contains(&"文明澱學士"));
    }

    #[test]
    fn joined_sentence_capped() {
        let e = Engine::new(from_text("喃;nôm
𡨸;chữ
文明殿學士越南人;văn minh điện học sĩ việt nam nhân
").0);
        // 15 lần nom: ghép đủ 15 chữ, không cắt.
        let cands = e.candidates(&"nom".repeat(15));
        assert_eq!(cands[0].text, "喃".repeat(15));
        assert_eq!(cands[0].consumed, 45);
        assert_eq!(cands[0].breaks.len(), 14);
        // Cụm thường cũng không cắt.
        let keys = "chunom".repeat(6);
        let cands = e.candidates(&keys);
        assert_eq!(cands[0].text, "𡨸喃".repeat(6));
        assert_eq!(cands[0].consumed, keys.len());
        // Mục có sẵn trong từ điển dài hơn 10 chữ cũng không bị cắt.
        let e = Engine::new(from_text("喃;nôm\n文明殿學士越南人民國家;vmhsvnndqg\n").0);
        assert_eq!(e.candidates("vmhsvnndqg")[0].text, "文明殿學士越南人民國家");
        // Chữ không phải tiếng Việt: vẫn gợi ý theo phần đầu để chọn dần (`greenwich` ra 家 gia của `g`).
        let mut e2 = Engine::new(from_text("家;gia\n").0);
        for telex in [false, true] {
            e2.options.telex = telex;
            assert_eq!(texts(&e2.candidates("greenwich")), ["家"], "telex={telex}");
            assert_eq!(e2.candidates("greenwich")[0].consumed, 1);
            assert_eq!(texts(&e2.candidates("g")), ["家"]);
        }
        // Chuỗi phím quá dài: không ghép câu, không treo.
        let long = "nom".repeat(200);
        assert!(!e.candidates(&long).is_empty());
    }

    #[test]
    fn joins_pieces_into_sentence() {
        let (dict, _) = from_text("國;quốc\n越;việt\n越南;việt nam\n");
        let mut e = Engine::new(dict);
        // Từ điển không có cụm quốc việt: ghép 國 + 越 lên đầu, các phần đơn vẫn còn sau.
        let cands = e.candidates("quocqsvietqj");
        assert_eq!(texts(&cands), ["國越", "國"]);
        assert_eq!((cands[0].reading.as_str(), cands[0].consumed), ("quốc việt", 12));
        assert_eq!(cands[0].breaks, [6]);
        assert!(cands[0].joined && !cands[1].joined);
        assert!(!e.candidates("vietnam")[0].joined);
        // Phần sau là cụm có sẵn: ghép nguyên cụm, giữ vị trí tách của nó.
        assert_eq!(e.candidates("quocvietnam")[0].text, "國越南");
        assert_eq!(e.candidates("quocvietnam")[0].breaks, [4, 8]);
        // Chữ cuối gõ dở: lấy chữ có âm đọc bắt đầu bằng nó.
        for keys in ["quocv", "quocqsv", "quocqsvie"] {
            let cands = e.candidates(keys);
            assert_eq!(texts(&cands)[..2], ["國越", "國"], "{keys}");
            assert_eq!(cands[0].consumed, keys.chars().count(), "{keys}");
        }
        assert_eq!(texts(&e.candidates("q")), ["國"]);
        assert_eq!(texts(&e.candidates("v")), ["越", "越南"]);
        // Chữ đã gõ dấu mà không khớp thì không ghép.
        for keys in ["quocx", "quocview", "quocvies"] {
            assert_eq!(texts(&e.candidates(keys)), ["國"], "{keys}");
        }
        // Gợi ý ghép không có trong từ điển nên không học.
        assert!(!e.select(&cands[0]));
        assert_eq!(e.dict.freq_of("國越", "quốc việt"), None);
    }

    #[test]
    fn sentence_for_any_words() {
        let (dict, _) = from_text(
            "𠬠;một\n𠄩;hai\n𠀧;ba\n𣈜;ngày\n𡗶;trời\n地;địa\n𠫾;đi\n移;di\n𡨸喃;chữ nôm\n共;cùng\n漢越;hán việt\n南;nam\n",
        );
        let mut e = Engine::new(dict);
        let top = |e: &Engine, keys: &str| e.candidates(keys).first().map(|c| c.text.clone()).unwrap_or_default();
        assert_eq!(top(&e, "motjhaiba"), "𠬠𠄩𠀧");
        assert_eq!(top(&e, "ngayftroiwf"), "𣈜𡗶");
        assert_eq!(top(&e, "ngayftr"), "𣈜𡗶");
        assert_eq!(top(&e, "chuwxnomqcungf"), "𡨸喃共");
        assert_eq!(top(&e, "hanvietnam"), "漢越南");
        // Không bật dz: `d` gõ dở chỉ khớp d, đ phải gõ `dd`.
        assert_eq!(top(&e, "namd"), "南移");
        // địa và đi đều bắt đầu bằng đ: lấy chữ đứng trước trong từ điển.
        assert_eq!(top(&e, "namdd"), "南地");
        assert_eq!(top(&e, "namddi"), "南𠫾");
        e.options.dz = true;
        assert_eq!(top(&e, "namd"), "南地");
        // Mỗi chữ cái là phần đầu một âm tiết (`mh` không là phần đầu âm tiết nào).
        assert_eq!(top(&e, "mhb"), "𠬠𠄩𠀧");
        assert_eq!(top(&e, "ngtr"), "𣈜𡗶");
        // Mới gõ một chữ cái đã gợi ý các chữ và cụm bắt đầu bằng nó, chữ gõ đủ xếp trước.
        assert_eq!(texts(&e.candidates("h")), ["𠄩", "漢越"]);
        assert_eq!(texts(&e.candidates("ng")), ["𣈜"]);
        let e2 = Engine::new(from_text("𣈜;ngày\n我;ngã\n").0);
        assert_eq!(texts(&e2.candidates("nga")), ["我", "𣈜"]);
        // Chữ hay chọn được ưu tiên khi ghép chữ gõ dở.
        let mut e = Engine::new(from_text("南;nam\n𠄩;hai\n係;hệ\n").0);
        assert_eq!(top(&e, "namh"), "南𠄩");
        e.dict.bump("係", "hệ");
        assert_eq!(top(&e, "namh"), "南係");
    }

    #[test]
    fn learning_and_dictionary_reorder() {
        let mut e = engine();
        let pick = e.candidates("nam").into_iter().find(|c| c.text == "𠄼").unwrap();
        assert!(e.select(&pick));
        assert_eq!(texts(&e.candidates("nam")), ["𠄼", "南", "𢆥"]);
        // Độ phổ biến nằm luôn trong từ điển, lưu cùng từ điển.
        assert!(e.dict.to_text().contains("𠄼;năm;1\n"));

        // Chữ mới thêm (độ phổ biến 0) xếp theo thứ tự trong từ điển, sau chữ hay chọn.
        e.dict.import("男;nam;0\n");
        assert_eq!(texts(&e.candidates("nam")), ["𠄼", "南", "男", "𢆥"]);
        // Độ phổ biến trong tệp quyết định thứ tự, không cần học.
        e.dict.import("楠;nam;5\n");
        assert_eq!(texts(&e.candidates("nam"))[0], "楠");

        // Tắt học thói quen: chọn không đổi gì.
        e.options.learning = false;
        assert!(!e.select(&pick));
    }

    #[test]
    fn quoc_ngu_readings() {
        let e = engine();
        let r = e.readings("nam");
        assert_eq!(texts(&r), ["nam", "năm"]);
        assert_eq!(texts(&e.readings("vn")), ["việt nam", "việt"]);
    }

    #[test]
    fn continues_after_chosen() {
        let mut e = engine();
        e.dict.import("函;hàm;0
數;số;0
函數;hàm số;0
線性;tuyến tính;0
函數線性;hàm số tuyến tính;0
");
        // Đã chọn 函, còn `stt`: cụm dài nhất khớp phần đầu phím trước (函數線性 → 數線性 phủ cả `stt`).
        let after = e.after("函", "stt");
        assert_eq!(after[0].0.text, "數線性");
        assert_eq!(after[0].0.consumed, 3);
        assert_eq!((after[0].1.as_str(), after[0].2.as_str()), ("函數線性", "hàm số tuyến tính"));
        assert!(after.iter().any(|(c, t, _)| c.text == "數" && c.consumed == 1 && t == "函數"));
        // Gõ đủ kèm thanh: thanh phải khớp.
        assert_eq!(e.after("函", "sos")[0].0.text, "數");
        assert_eq!(e.after("函", "sos")[0].0.consumed, 3);
        assert!(e.after("函", "sof").is_empty());
        // Dùng phần cuối của các chữ đã chọn: 函數 rồi `tt` → 線性 của 函數線性.
        assert_eq!(e.after("函數", "tt")[0].0.text, "線性");
        assert!(e.after("", "tt").is_empty());
    }

    #[test]
    fn narrows_first_syllable_by_parts() {
        // 極 có hai âm, cọc đứng trước: thu hẹp theo cực vẫn phải thấy 極.
        let e = Engine::new(
            Dictionary::from_text("極;cọc;0
極;cực;0
局;cục;0
居;cư;0
周;châu;0
朱;chu;0
州;châu;0
咒;chú;0
瓜;qua;0
果;của;0
求;cầu;0
").0,
        );
        let pick = |p: Part, v: &str| (p, v.to_string());
        let narrow = |k: &str, p: &[(Part, String)]| e.narrow(k, p);
        // Chỉ gõ phụ âm đầu: hỏi phụ âm đầu (c hay ch), rồi nguyên âm đầu, vần, phụ âm cuối, thanh.
        // Nhóm c/k/qu/g/gh và ch/tr: chỉ hiện phụ âm có chữ trong từ điển (qu của qua; k, g, gh, tr không có).
        assert_eq!(narrow("c", &[]), Some((Part::Initial, strs(&["c", "ch", "qu"]))));
        let ch = [pick(Part::Initial, "ch")];
        assert_eq!(narrow("c", &ch), Some((Part::Vowel, vec!["â".into(), "u".into()])));
        // châu: nguyên âm â chỉ có vần âu (vẫn hỏi); vần âu không có phụ âm cuối nên khỏi hỏi, vào thẳng thanh;
        // lưới là các chữ châu.
        let chau = [pick(Part::Initial, "ch"), pick(Part::Vowel, "â")];
        assert_eq!(narrow("c", &chau), Some((Part::Nucleus, vec!["âu".into()])));
        let chau = [chau[0].clone(), chau[1].clone(), pick(Part::Nucleus, "âu")];
        assert_eq!(narrow("c", &chau), Some((Part::Syllable, strs(&["châu"]))));
        let chau = [chau[0].clone(), chau[1].clone(), chau[2].clone(), pick(Part::Final, "")];
        assert_eq!(narrow("c", &chau), Some((Part::Syllable, vec!["châu".into()])));
        assert_eq!(texts(&e.candidates_narrowed("c", &chau)), ["周", "州"]);
        let chau = [chau[0].clone(), chau[1].clone(), chau[2].clone(), chau[3].clone(), pick(Part::Syllable, "châu")];
        assert_eq!(narrow("c", &chau), Some((Part::Syllable, strs(&["châu"]))));
        // c: nguyên âm đầu â, o, u, ư; chọn u thì mới hỏi vần u hay ua (của).
        let c = [pick(Part::Initial, "c")];
        assert_eq!(narrow("c", &c), Some((Part::Vowel, vec!["â".into(), "o".into(), "u".into(), "ư".into()])));
        let cu = [pick(Part::Initial, "c"), pick(Part::Vowel, "u")];
        assert_eq!(narrow("c", &cu), Some((Part::Nucleus, vec!["u".into(), "ua".into()])));
        // c + ư: chỉ có vần ư (vẫn hỏi), rồi phụ âm cuối rỗng (cư) hoặc c (cực).
        let cư = [pick(Part::Initial, "c"), pick(Part::Vowel, "ư")];
        assert_eq!(narrow("c", &cư), Some((Part::Nucleus, vec!["ư".into()])));
        let cư = [cư[0].clone(), cư[1].clone(), pick(Part::Nucleus, "ư")];
        assert_eq!(narrow("c", &cư), Some((Part::Final, vec!["".into(), "c".into()])));
        let cuc = [pick(Part::Initial, "c"), pick(Part::Vowel, "ư"), pick(Part::Final, "c")];
        assert_eq!(texts(&e.candidates_narrowed("c", &cuc)), ["極"]);
        // Đã gõ có nguyên âm: phụ âm đầu đã rõ (tr không ra chữ nào) thì khỏi hỏi; nguyên âm vẫn hỏi vì `chu` có
        // thể là gõ tắt ch… + u… (châu); chọn u thì vần đã rõ, vào thẳng cấp còn khác nhau (ở đây là thanh).
        assert_eq!(narrow("chu", &[]), Some((Part::Vowel, vec!["â".into(), "u".into()])));
        let chu = [pick(Part::Initial, "ch"), pick(Part::Vowel, "u")];
        assert_eq!(narrow("chu", &chu), Some((Part::Syllable, vec!["chu".into(), "chú".into()])));
        // Chữ cái vừa gõ không tự là phụ âm đầu (mọi chữ q đều là qu-): vẫn hỏi q hay qu; q là mọi chữ qu-.
        // Chữ đã gõ đứng đầu, ngay sau là phụ âm dài hơn bắt đầu bằng nó (qu), rồi phụ âm cùng nhóm có chữ (c).
        assert_eq!(narrow("q", &[]), Some((Part::Initial, strs(&["q", "qu", "c"]))));
        assert_eq!(narrow("c", &[]), Some((Part::Initial, strs(&["c", "ch", "qu"]))));
        assert_eq!(texts(&e.candidates_narrowed("q", &[pick(Part::Initial, "q")])), ["瓜"]);
        assert_eq!(texts(&e.candidates_narrowed("q", &[pick(Part::Initial, "qu")])), ["瓜"]);
        // `qua`: đổi qu sang c (bỏ u) thì kiểu gõ không Telex còn hiểu `ca` là gõ tắt c… (cọc, cực…) nên có chữ;
        // k / g / gh không ra chữ nào nên ẩn.
        assert_eq!(narrow("qua", &[]), Some((Part::Initial, strs(&["qu", "c"]))));
    }

    fn strs(v: &[&str]) -> Vec<String> {
        v.iter().map(|s| s.to_string()).collect()
    }

    #[test]
    fn narrowing_asks_sound_alike_initials() {
        let mut e = Engine::new(
            Dictionary::from_text("羅;là;0
那;nà;0
𧵑;của;0
諸;chưa;0
渚;trưa;0
咦;gì;0
𠫾;đi;0
姨;dì;0
").0,
        );
        e.options.telex = true;
        let pick = |p: Part, v: &str| [(p, v.to_string())];
        // Ngọng l/n: gõ `laf` mà chữ đọc nà vẫn tìm được ở cột trái; chọn n thì thay đúng 3 phím đã gõ.
        // Chỉ hiện phụ âm cùng nhóm ghép ra chữ (nà), ẩn phụ âm không có chữ nào (mà, bà… không có trong từ điển).
        assert_eq!(e.narrow("laf", &[]), Some((Part::Initial, strs(&["l", "n"]))));
        assert_eq!(texts(&e.candidates_narrowed("laf", &[])), ["羅"]);
        let na = e.candidates_narrowed("laf", &pick(Part::Initial, "n"));
        assert_eq!(texts(&na), ["那"]);
        assert_eq!(na[0].consumed, 3);
        assert_eq!(e.narrow("laf'", &pick(Part::Initial, "n")), Some((Part::Syllable, vec!["nà".into()])));
        // ch / tr dài bằng nhau; gi ↔ d: `gif` gì là g + i, đổi sang d giữ i (`dif` dì).
        assert_eq!(texts(&e.candidates_narrowed("truwa", &pick(Part::Initial, "ch"))), ["諸"]);
        assert_eq!(texts(&e.candidates_narrowed("gif", &pick(Part::Initial, "d"))), ["姨"]);
        // d ở nhóm b/m/v/l/n/p/ph/d/gi/r/đ/t/th: `dif` dì → gì (g + i), đi.
        assert_eq!(e.narrow("dif", &[]), Some((Part::Initial, strs(&["d", "đ", "g"]))));
        assert_eq!(texts(&e.candidates_narrowed("dif", &pick(Part::Initial, "g"))), ["咦"]);
        assert_eq!(texts(&e.candidates_narrowed("dif", &pick(Part::Initial, "đ"))), ["𠫾"]);
        // `ddi` đi ↔ `di` dì, thay đúng 3 phím đã gõ.
        assert_eq!(texts(&e.candidates_narrowed("ddi", &pick(Part::Initial, "d"))), ["姨"]);
        assert_eq!(e.candidates_narrowed("ddi", &pick(Part::Initial, "d"))[0].consumed, 3);
        // Phụ âm cùng nhóm không ra chữ nào thì ẩn: `cura` chỉ có của, không hỏi phụ âm đầu.
        assert!(e.narrow("cura", &[]).is_none_or(|(p, _)| p != Part::Initial));
    }

    #[test]
    fn narrowing_initials_run_asks_first_letter() {
        // Telex gõ tắt toàn phụ âm `ndtphn`: âm tiết đầu là n (chữ cái đầu), cột trái hỏi n, rồi ng, nh, ngh như khi
        // chỉ gõ `n`, rồi phụ âm cùng nhóm; chọn nh / b ra chữ của phụ âm đó, thay đúng phím n.
        let mut e = Engine::new(Dictionary::from_text("南;nam;0\n𰌽;nhà;0\n婆;bà;0\n民;dân;0\n").0);
        e.options.telex = true;
        assert_eq!(e.narrow("ndtphn", &[]), Some((Part::Initial, strs(&["n", "nh", "b", "d"]))));
        let nh = e.candidates_narrowed("ndtphn", &[(Part::Initial, "nh".to_string())]);
        assert_eq!(texts(&nh), ["𰌽"]);
        assert_eq!(nh[0].consumed, 1);
        assert_eq!(texts(&e.candidates_narrowed("ndtphn", &[(Part::Initial, "b".to_string())])), ["婆"]);
        // Chưa chọn gì: lưới là chữ của âm đầu như khi chỉ gõ `n` (cả n và nh), không chỉ chữ đúng n.
        assert_eq!(texts(&e.candidates_narrowed("ndtphn", &[])), ["南", "𰌽"]);
        // `dd` là đ: thay cả 2 phím.
        let d = e.candidates_narrowed("ddn", &[(Part::Initial, "d".to_string())]);
        assert_eq!(texts(&d), ["民"]);
        assert_eq!(d[0].consumed, 2);
    }

    #[test]
    fn initials_run_joins_common_words_and_learns() {
        // `phn`: câu ghép lấy chữ dùng nhiều của p (cả ph: phố) chứ không chỉ chữ đúng p (pi).
        let mut e = Engine::new(Dictionary::from_text("𪪈;pi;0\n舖;phố;9\n城;thành;9\n河內;hà nội;9\n漢喃;hán nôm;1\n").0);
        e.options.telex = true;
        let cands = e.candidates("phn");
        let joined: Vec<&str> = cands.iter().filter(|c| c.joined).map(|c| c.text.as_str()).collect();
        assert_eq!(joined, ["舖河內"]);
        // Chọn 城舖河內 (từng phần hay cả cụm) cho `tphn`: lần sau cụm đó đứng đầu.
        assert!(e.learn("tphn", "城舖河內", "thành phố hà nội"));
        assert_eq!(e.candidates("tphn")[0].text, "城舖河內");
        assert!(e.dict.freq_of("城舖河內", "thành phố hà nội").is_some());
    }

    #[test]
    fn lone_w_is_u_horn() {
        // `w` một mình là âm bắt đầu bằng ư: `w` → 央 (ương); `tw` chọn tr ở cột trái, chọn 中 rồi `w` → 央.
        let mut e = Engine::new(Dictionary::from_text("中;trung;0\n央;ương;0\n中央;trung ương;0\n相應;tương ứng;0\n").0);
        e.options.telex = true;
        assert_eq!(texts(&e.candidates("w")), ["央"]);
        assert_eq!(texts(&e.candidates("tw"))[0], "相應");
        assert_eq!(texts(&e.candidates_narrowed("tw", &[(Part::Initial, "tr".to_string())])), ["中"]);
        let after: Vec<String> = e.after("中", "w").into_iter().map(|(c, ..)| c.text).collect();
        assert_eq!(after, ["央"]);
    }

    #[test]
    fn narrowing_from_qu_drops_u() {
        // q luôn đi với u: gõ `quas` (quá) rồi chọn c / k / g / gh ở cột trái thì bỏ u (cá, ká…), không thành cuá.
        let mut e = Engine::new(Dictionary::from_text("過;quá;0\n哥;cá;0\n𩵜;cá;0\n家;gá;0\n").0);
        e.options.telex = true;
        let pick = |v: &str| [(Part::Initial, v.to_string())];
        assert_eq!(e.narrow("quas", &[]).map(|(p, o)| (p, o[0].clone())), Some((Part::Initial, "qu".to_string())));
        assert_eq!(texts(&e.candidates_narrowed("quas", &pick("c"))), ["哥", "𩵜"]);
        assert_eq!(e.candidates_narrowed("quas", &pick("c"))[0].consumed, 4);
        assert_eq!(texts(&e.candidates_narrowed("quas", &pick("g"))), ["家"]);
        // Ngược lại: `cas` chọn qu thì thêm u (quá).
        assert_eq!(texts(&e.candidates_narrowed("cas", &pick("qu"))), ["過"]);
    }

    #[test]
    fn narrowing_other_initial_keeps_shorter_syllables() {
        // Gõ `rich` mà ý là di chuyển, hay gõ tắt d… + ích: chọn d (cùng nhóm với r) ở cột trái vẫn hỏi nguyên
        // âm, phụ âm cuối để tới được di, dân, không chỉ còn dịch khớp trọn chữ.
        let e = Engine::new(
            Dictionary::from_text("易;dịch;0
移;di;0
民;dân;0
移轉;di chuyển;0
").0,
        );
        let d = [(Part::Initial, "d".to_string())];
        assert_eq!(e.narrow("rich", &d), Some((Part::Vowel, vec!["â".into(), "i".into()])));
        let dan = [d[0].clone(), (Part::Vowel, "â".to_string())];
        let cands = e.candidates_narrowed("rich", &dan);
        assert_eq!(texts(&cands), ["民"]);
        assert_eq!(cands[0].consumed, 1);
        let i = [d[0].clone(), (Part::Vowel, "i".to_string())];
        assert_eq!(e.narrow("rich", &i), Some((Part::Final, vec!["".into(), "ch".into()])));
        let di = [i[0].clone(), i[1].clone(), (Part::Final, String::new())];
        let cands = e.candidates_narrowed("rich", &di);
        assert_eq!(texts(&cands), ["移"]);
        assert_eq!(cands[0].consumed, 2);
    }

    #[test]
    fn narrowing_offers_other_marks() {
        let mut e = Engine::new(
            Dictionary::from_text("妬;đó;0
度;đo;0
𧹻;đỏ;0
圖;đồ;0
團;đoàn;0
𣈜;ngày;0
").0,
        );
        e.options.telex = true;
        // Đã gõ thanh (`ddos` đó) vẫn chọn được chữ khác thanh, khác dấu mũ / móc (đỏ, đồ), thay đúng chữ đã gõ;
        // không lẫn vần khác của chữ gõ dở (đoàn).
        assert_eq!(e.narrow("ddos", &[]), Some((Part::Vowel, vec!["o".into(), "ô".into()])));
        let o = [(Part::Vowel, "o".to_string())];
        // Thanh theo thứ tự cố định: ngang, huyền, sắc, hỏi, ngã, nặng.
        assert_eq!(e.narrow("ddos", &o), Some((Part::Syllable, strs(&["đo", "đó", "đỏ"]))));
        // Đã chọn đỏ (hay chọn nhanh âm đã gõ) vẫn đổi được sang thanh khác của chính âm đó.
        assert_eq!(e.narrow("ddos", &[(Part::Syllable, "đó".to_string())]), Some((Part::Syllable, strs(&["đo", "đó", "đỏ"]))));
        let red = [o[0].clone(), (Part::Syllable, "đỏ".to_string())];
        let cands = e.candidates_narrowed("ddos'ngayf", &red);
        assert_eq!(texts(&cands), ["𧹻"]);
        assert_eq!(cands[0].consumed, 5);
        // Lưới mặc định vẫn chỉ là chữ đã gõ.
        assert_eq!(texts(&e.candidates("ddos")), ["妬"]);
    }

    #[test]
    fn narrowing_picks_first_syllable_character() {
        // Gõ tắt `qv`: thu hẹp chỉ chọn chữ của âm đầu (quốc → 國), không nhảy sang cả cụm 國務, 國王; chọn
        // 國 xong thì `v` mới gợi ý tiếp (như pinyin).
        let mut e = Engine::new(
            Dictionary::from_text("國;quốc;0
官;quan;0
國務;quốc vụ;0
國王;quốc vương;0
官員;quan viên;0
").0,
        );
        e.options.telex = true;
        let qu = [(Part::Initial, "qu".to_string())];
        assert_eq!(e.narrow("qv", &qu), Some((Part::Vowel, vec!["a".into(), "ô".into()])));
        let quoc = [qu[0].clone(), (Part::Vowel, "ô".to_string())];
        assert_eq!(texts(&e.candidates_narrowed("qv", &quoc)), ["國"]);
        assert_eq!(e.candidates_narrowed("qv", &quoc)[0].consumed, 1);
        // Một lựa chọn vẫn hỏi đủ cấp: q → qu → ô → vần ô → phụ âm cuối c → các thanh (quốc).
        assert_eq!(e.narrow("qv", &quoc), Some((Part::Nucleus, vec!["ô".into()])));
        let quoc = [quoc[0].clone(), quoc[1].clone(), (Part::Nucleus, "ô".to_string())];
        assert_eq!(e.narrow("qv", &quoc), Some((Part::Final, vec!["c".into()])));
        let quoc = [quoc[0].clone(), quoc[1].clone(), quoc[2].clone(), (Part::Final, "c".to_string())];
        assert_eq!(e.narrow("qv", &quoc), Some((Part::Syllable, vec!["quốc".into()])));
    }

    #[test]
    fn abbreviated_phrases() {
        let e = engine();
        for keys in ["vn", "vnam", "vietn", "vna", "VN", "v'n", "vnam "] {
            let cands = e.candidates(keys);
            assert_eq!(texts(&cands).first(), Some(&"越南"), "{keys}");
            // `vietn`: viet đủ âm tiết, `n` là phần đầu của nam: khớp cụm gõ dở, không phải gõ tắt.
            assert_eq!(cands[0].abbreviated, keys != "vietn", "{keys}");
            assert_eq!(cands[0].consumed, keys.chars().count());
        }
        assert_eq!(texts(&e.candidates("cn")), ["𡨸喃", "𡨸"]);
        assert!(e.candidates("vx").iter().all(|c| c.consumed < 2));
        assert!(e.candidates("vi'etn").iter().all(|c| c.consumed < 6));
        // Một đoạn gõ kèm thanh ở đuôi: thanh phải khớp.
        assert_eq!(texts(&e.candidates("vietqjn")).first(), Some(&"越南"));
        assert_eq!(texts(&e.candidates("v'nam")).first(), Some(&"越南"));
        assert!(!texts(&e.candidates("v'nams")).contains(&"越南"));
        assert!(!texts(&e.candidates("v'namw")).contains(&"越南"));
        // Phím thanh của chữ trước không phải chữ đầu âm tiết sau: `vietqj` là một chữ trọn.
        assert!(e.candidates("vieqtj").iter().all(|c| !c.abbreviated));
        // y cuối sau phụ âm như i: sỹ = sĩ.
        let mut e = engine();
        e.dict.import("士;sĩ;0
學士;học sĩ;0
");
        assert_eq!(texts(&e.candidates("syx")).first(), Some(&"士"));
        assert_eq!(texts(&e.candidates("hocjsyx")).first(), Some(&"學士"));
        assert!(texts(&e.candidates("h'syx")).contains(&"學士"));
        // Mã gõ tắt người dùng đặt.
        let mut e = engine();
        e.dict.import("空;không;0\n空;k;0\n大學;đại học;0\n大學;đh;0\n");
        assert_eq!(texts(&e.candidates("k")), ["空"]);
        // d và đ khác nhau: mã `đh` phải gõ `ddh` (bật d/z thì `dh`).
        assert!(!texts(&e.candidates("dh")).contains(&"大學"));
        assert_eq!(texts(&e.candidates("ddh")), ["大學"]);
        e.options.dz = true;
        assert_eq!(texts(&e.candidates("dh")), ["大學"]);
        assert!(!texts(&e.candidates("zh")).contains(&"大學"));
        e.options.dz = false;
        assert!(e.candidates("k")[0].abbreviated);
        assert_eq!(texts(&e.readings("k")), ["không"]);

        // Gõ đủ âm tiết vẫn đứng trước gõ tắt.
        let e = engine();
        assert!(!e.candidates("chunom")[0].abbreviated);
    }

    #[test]
    fn narrowing_skips_final_after_closed_rhyme() {
        // Gõ `tooi`, chọn t, ô: vẫn hỏi vần (ôi); vần ôi không thể có phụ âm cuối nên khỏi hỏi, vào thẳng thanh.
        // Chỉ gõ `t`: hỏi vần ô hay ôi; chọn ô thì mới hỏi phụ âm cuối (rỗng hay n).
        let mut e = Engine::new(
            Dictionary::from_text("碎;tôi;0\n最;tối;0\n罪;tội;0\n蘇;tô;0\n損;tốn;0\n細;tơi;0\n").0,
        );
        e.options.telex = true;
        let o = [(Part::Initial, "t".to_string()), (Part::Vowel, "ô".to_string())];
        assert_eq!(e.narrow("tooi", &o), Some((Part::Nucleus, strs(&["ôi"]))));
        let oi = [o[0].clone(), o[1].clone(), (Part::Nucleus, "ôi".to_string())];
        assert_eq!(e.narrow("tooi", &oi), Some((Part::Syllable, strs(&["tôi", "tối", "tội"]))));
        assert_eq!(e.narrow("t", &o), Some((Part::Nucleus, strs(&["ô", "ôi"]))));
        assert_eq!(e.narrow("t", &oi), Some((Part::Syllable, strs(&["tôi", "tối", "tội"]))));
        let ô = [o[0].clone(), o[1].clone(), (Part::Nucleus, "ô".to_string())];
        assert_eq!(e.narrow("t", &ô), Some((Part::Final, strs(&["", "n"]))));
    }
}

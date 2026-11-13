//! Bộ máy Telex (clone Unikey): chuỗi phím của một từ → âm tiết có dấu.
//!
//! Mỗi lần gõ, kết quả được tính lại từ toàn bộ chuỗi phím của từ, nên bỏ dấu tự do
//! (gõ phím thanh ở đâu cũng được) và khôi phục phím khi gõ sai chính tả đều tự nhiên.
//!
//! [`compose`] là Telex tự do của Unikey (chế độ Quốc ngữ). Kiểu gõ Hán Nôm ([`compose_with`],
//! [`display`]) thêm: `q` thêm dấu mũ (`totq` → tôt), phím cách gán thanh ngang, và tuỳ chọn `dz`
//! cho `d` đầu âm tiết ra đ, `z` đầu âm tiết ra d.

use crate::syllable::{self, Check, ToneStyle};
use crate::text::{self, Tone};

#[derive(Clone, Copy)]
struct Letter {
    ch: char,
    upper: bool,
}

#[derive(Clone, Default)]
struct State {
    letters: Vec<Letter>,
    tone: Tone,
    /// Kiểu gõ Hán Nôm: có phím `q`, phím cách gán thanh ngang và tuỳ chọn [`dz`](Self::dz).
    hannom: bool,
    /// Đã ấn phím cách: thanh ngang là chủ ý, không phải chưa gõ thanh.
    level_marked: bool,
    /// `d` đầu âm tiết ra đ, `z` đầu âm tiết ra d.
    dz: bool,
    /// Tuỳ chọn `dz`: đã nhận `d` thứ hai của `dd` (vẫn là đ).
    /// Kiểu Hán Nôm: đã gõ dấu phụ (`w`, `q`) sau nguyên âm; như dấu thanh, chữ kết thúc ở đây.
    diacritic: bool,
    /// Người dùng vừa gõ lặp phím để huỷ dấu: các phím sau giữ nguyên.
    stopped: bool,
    /// Chuỗi đã không còn là tiếng Việt ở phím vừa nhận.
    invalid: bool,
}

impl State {
    fn lower(&self) -> Vec<char> {
        self.letters.iter().map(|l| l.ch).collect()
    }

    fn push(&mut self, key: char) {
        self.push_as(text::lower(key), key);
    }

    fn push_as(&mut self, ch: char, key: char) {
        self.letters.push(Letter { ch, upper: key.is_uppercase() });
    }

    fn has_vowel(&self) -> bool {
        self.letters.iter().any(|l| text::is_vowel(l.ch))
    }

    /// `aa` → â, `ee` → ê, `oo` → ô (cả gõ cách phụ âm: `congo` → công); gõ lần nữa để huỷ (công + o → congo).
    /// Riêng o:
    /// - vần oo (chữ hiện coong) gõ thêm `o` là ôô (`cooongo` → côông);
    /// - ôô gõ thêm `o` thì huỷ, ra phím thô (côông + o → coongo);
    /// - `ooo` huỷ mũ thành vần oo mà vẫn gõ tiếp được (`cooongs` → coóng).
    fn apply_circumflex(&mut self, k: char, key: char) {
        let l = self.lower();
        let (ns, ne) = syllable::nucleus_range(&l);
        let hat = match k {
            'a' => 'â',
            'e' => 'ê',
            _ => 'ô',
        };
        if k == 'o' && l[ns..ne] == ['ô', 'ô'] {
            self.letters[ns].ch = 'o';
            self.letters[ns + 1].ch = 'o';
            self.push(key);
            self.stopped = true;
            return;
        }
        if k == 'o' && l[ns..ne] == ['o', 'o'] {
            self.letters[ns].ch = 'ô';
            self.letters[ns + 1].ch = 'ô';
            return;
        }
        match (ns..ne).rev().find(|&i| text::base(l[i]) == k) {
            Some(i) if l[i] == hat => {
                self.letters[i].ch = k;
                self.push(key);
                // Vần oo (xoong, coong) là chữ thật: gõ tiếp phụ âm cuối, dấu thanh được.
                let oo = k == 'o' && syllable::nucleus_range(&self.lower()) == (i, i + 2);
                self.stopped = !(oo && syllable::check(&self.lower(), self.tone, Check::Prefix));
            }
            Some(i) => self.letters[i].ch = hat,
            None => self.push(key),
        }
    }

    /// `q`: thêm dấu mũ cho a / e / o trong vần (`totq` → tôt, `muonq` → muôn); gõ lần nữa để huỷ.
    fn apply_hat(&mut self, key: char) {
        let l = self.lower();
        let (ns, ne) = syllable::nucleus_range(&l);
        // Vần oo: mũ cả hai chữ (`coongq` → côông).
        if l[ns..ne] == ['o', 'o'] {
            self.letters[ns].ch = 'ô';
            self.letters[ns + 1].ch = 'ô';
            return;
        }
        for i in (ns..ne).rev() {
            let target = match l[i] {
                'a' | 'ă' => 'â',
                'e' => 'ê',
                'o' | 'ơ' => 'ô',
                _ => continue,
            };
            let mut trial = l.clone();
            trial[i] = target;
            if syllable::check(&trial, self.tone, Check::Prefix) {
                self.letters[i].ch = target;
                return;
            }
        }
        if l[ns..ne].iter().any(|c| matches!(c, 'â' | 'ê' | 'ô')) {
            for i in ns..ne {
                self.letters[i].ch = match l[i] {
                    'â' => 'a',
                    'ê' => 'e',
                    'ô' => 'o',
                    c => c,
                };
            }
            self.push(key);
            self.stopped = true;
        } else {
            self.push(key);
        }
    }

    /// `w`: uo → ươ, a → ă, o → ơ, u → ư; đứng riêng thì thành ư; gõ lần nữa để huỷ.
    fn apply_w(&mut self, key: char) {
        let l = self.lower();
        let (ns, ne) = syllable::nucleus_range(&l);
        // Chưa có nguyên âm: `w` là chữ w, không tự ra ư (muốn ư thì gõ `uw`).
        if ns == ne {
            self.push(key);
            return;
        }
        for i in ns..ne - 1 {
            // `w` lần ba trên uơ thì huỷ dấu, ra phím thô (`uowww` → uow), không lật lại ươ: gõ `w` liên tục
            // không lật ươ ↔ uơ mãi (phím xoá cũng lật theo).
            if !self.hannom && l[i] == 'u' && l[i + 1] == 'ơ' {
                self.letters[i + 1].ch = 'o';
                self.push(key);
                self.stopped = true;
                return;
            }
            // `w` lần hai trên ươ thành uơ (`uoww` → uơ, `thuowwr` → thuở). Telex tự do: chỉ khi uơ còn là vần
            // được (`nguowiww` không thành nguơi mà huỷ dấu như Unikey).
            if l[i] == 'ư' && l[i + 1] == 'ơ' {
                let mut trial = l.clone();
                trial[i] = 'u';
                if self.hannom || syllable::check(&trial, self.tone, Check::Prefix) {
                    self.letters[i].ch = 'u';
                    return;
                }
            }
            if text::base(l[i]) == 'u' && text::base(l[i + 1]) == 'o' && !(l[i] == 'ư' && l[i + 1] == 'ơ') {
                self.letters[i].ch = 'ư';
                self.letters[i + 1].ch = 'ơ';
                return;
            }
        }
        for i in (ns..ne).rev() {
            let target = match l[i] {
                'a' | 'â' => 'ă',
                'o' | 'ô' => 'ơ',
                'u' => 'ư',
                _ => continue,
            };
            let mut trial = l.clone();
            trial[i] = target;
            if syllable::check(&trial, self.tone, Check::Prefix) {
                self.letters[i].ch = target;
                return;
            }
        }
        if l[ns..ne].iter().any(|c| matches!(c, 'ă' | 'ơ' | 'ư')) {
            for i in ns..ne {
                self.letters[i].ch = match l[i] {
                    'ă' => 'a',
                    'ơ' => 'o',
                    'ư' => 'u',
                    c => c,
                };
            }
            self.push(key);
            self.stopped = true;
        } else {
            self.push(key);
        }
    }

    /// `dd` → đ; gõ lần nữa để huỷ.
    fn apply_stroke(&mut self, key: char) {
        match self.letters.first().map(|l| l.ch) {
            Some('d') => self.letters[0].ch = 'đ',
            Some('đ') => {
                self.letters[0].ch = 'd';
                self.push(key);
                self.stopped = true;
            }
            _ => self.push(key),
        }
    }

    fn feed(&mut self, key: char) {
        if key == RAW {
            self.stopped = true;
            return;
        }
        if self.stopped {
            self.push(key);
            return;
        }
        let k = key.to_ascii_lowercase();
        // Kiểu Hán Nôm: dấu thanh / dấu phụ kết thúc chữ, sau đó chỉ còn nhận dấu
        // (`hosa` không phải hóa, `oqm` không phải ôm).
        let toned = self.tone != Tone::Level || self.level_marked || self.diacritic;
        if self.hannom && toned && Tone::from_telex(k).is_none() && !matches!(k, 'w' | 'q' | ' ') {
            self.invalid = true;
            return;
        }
        let has_vowel = self.has_vowel();
        if self.hannom && has_vowel && matches!(k, 'w' | 'q') {
            self.diacritic = true;
        }
        if let Some(tone) = Tone::from_telex(k).filter(|_| has_vowel) {
            self.level_marked = false;
            if self.tone == tone {
                self.tone = Tone::Level;
                self.push(key);
                self.stopped = true;
            } else {
                self.tone = tone;
            }
        } else {
            match k {
                // Kiểu Hán Nôm: phím cách gán thanh ngang (`nam ` → nam¯).
                ' ' if has_vowel && self.hannom => {
                    self.tone = Tone::Level;
                    self.level_marked = true;
                }
                'z' if has_vowel && !self.hannom && self.tone != Tone::Level => self.tone = Tone::Level,
                'd' if self.dz && self.letters.is_empty() => self.push_as('đ', key),
                // Bật d/z thì mỗi phím `d` là một chữ: `dd` không gộp thành đ (xoá đ chỉ cần một lần).
                'd' if self.dz => self.push(key),
                'z' if self.dz && self.letters.is_empty() => self.push_as('d', key),
                LITERAL_D | 'Ｄ' => self.push_as('d', key),
                // Kiểu Hán Nôm: aa / ee / oo giữ nguyên hai chữ (xoong), â ê ô chỉ qua `q`.
                'a' | 'e' | 'o' if !self.hannom => self.apply_circumflex(k, key),
                'q' if has_vowel && self.hannom => self.apply_hat(key),
                // Kiểu Hán Nôm: dấu chỉ là dấu khi đứng sau nguyên âm; `w` trước đó là chữ w (không ra ư).
                'w' if !has_vowel && self.hannom => self.push(key),
                'w' => self.apply_w(key),
                'd' => self.apply_stroke(key),
                _ => self.push(key),
            }
        }
        if !self.stopped && !syllable::check(&self.lower(), self.tone, Check::Prefix) {
            self.invalid = true;
        }
    }

    /// Chữ chưa đặt dấu thanh, ký hiệu thanh ở cuối (`totˊ`); chưa gõ thanh thì không có ký hiệu.
    fn render_marked(&self) -> String {
        let mut out: String =
            self.letters.iter().map(|l| if l.upper { text::upper(l.ch) } else { l.ch }).collect();
        if self.tone != Tone::Level || self.level_marked {
            out.push(self.tone.mark());
        }
        out
    }

    fn render(&self, style: ToneStyle) -> String {
        let l = self.lower();
        let (ns, ne) = syllable::nucleus_range(&l);
        let pos = if self.tone == Tone::Level {
            None
        } else if ne > ns {
            Some(ns + syllable::tone_position(&l[ns..ne], ne < l.len(), style))
        } else {
            l.iter().rposition(|&c| text::is_vowel(c))
        };
        self.letters
            .iter()
            .enumerate()
            .map(|(i, letter)| {
                let c = if pos == Some(i) { text::with_tone(letter.ch, self.tone) } else { letter.ch };
                if letter.upper {
                    text::upper(c)
                } else {
                    c
                }
            })
            .collect()
    }
}

/// Biến chuỗi phím Telex của một từ thành chữ: `vieetj` → `việt`.
/// Chữ hết là tiếng Việt thì giữ phần đã ghép, các phím sau nối thêm nguyên như gõ: `soovanh` → sôvanh,
/// `ddcm` → đcm, `windows` → windows.
pub fn compose(keys: &str, style: ToneStyle) -> String {
    let mut st = State::default();
    let mut before = State::default();
    let chars: Vec<char> = keys.chars().collect();
    for (i, &key) in chars.iter().enumerate() {
        st.feed(key);
        if st.invalid {
            // Chữ hết là tiếng Việt: giữ nguyên chữ đã gõ tới phím trước, phím này và các phím sau nối thêm như gõ
            // (`soovanh` → sôvanh, `exchange` → ẽchange); không trả về phím gốc.
            let tail: String = chars[i..].iter().collect();
            return before.render(style) + &tail;
        }
        before = st.clone();
    }
    st.render(style)
}

/// Như [`compose`] theo kiểu gõ Hán Nôm (`totqs` → tốt); `dz`: `d` đầu âm tiết ra đ, `z` ra d.
pub fn compose_with(keys: &str, style: ToneStyle, dz: bool) -> String {
    compose_marked(keys, style, dz).0
}

/// Như [`compose_with`] cho cả chuỗi phím nhiều chữ, tách chữ như [`display`] và cách nhau một khoảng
/// (`hosa` → hó a, `chunom` → chu nom). Có chữ không phải tiếng Việt thì trả nguyên phím như [`compose_with`].
pub fn compose_words(keys: &str, style: ToneStyle, dz: bool) -> String {
    let mut parts: Vec<(String, bool)> = Vec::new();
    for w in shown_words(keys, dz) {
        if w.keys == ['\''] {
            parts.push(("'".into(), true));
            continue;
        }
        let raw: String = w.keys.iter().collect();
        let mut st = State { hannom: true, dz, ..Default::default() };
        for key in raw.chars() {
            st.feed(key);
            if st.invalid {
                return compose_with(keys, style, dz);
            }
        }
        parts.push((st.render(style), false));
    }
    let mut out = String::new();
    for (i, (s, apostrophe)) in parts.iter().enumerate() {
        if i > 0 && !parts[i - 1].1 && !apostrophe {
            out.push(' ');
        }
        out.push_str(s);
    }
    out
}

/// Như [`compose_words`] nhưng nối các chữ liền nhau, không chèn dấu cách, và ghép từng chữ riêng cả khi
/// có chữ không phải tiếng Việt, nên tuỳ chọn d/z áp đúng ở đầu mỗi chữ như khung gõ hiện
/// (`vmdhs` → vmđhs khi bật d/z, `chunom` → chunom). Dùng làm âm đọc khi thêm từ vào từ điển.
/// Riêng chữ đã gõ dấu (thanh hay dấu phụ) là đã kết thúc, nên cách chữ sau ra (`nhaqn` → nhâ n, không phải nhân).
pub fn compose_joined(keys: &str, style: ToneStyle, dz: bool) -> String {
    let mut out = String::new();
    let mut ended = false;
    for w in shown_words(keys, dz).into_iter().filter(|w| w.keys != ['\'']) {
        if ended {
            out.push(' ');
        }
        out.push_str(&compose_with(&w.keys.iter().collect::<String>(), style, dz));
        ended = w.tone_at.is_some() || !w.marks.is_empty();
    }
    out
}

/// Chuỗi phím là (phần đầu của) một chữ tiếng Việt theo Telex tự do của [`compose`]. Nguyên âm gõ lại cách
/// phụ âm vẫn ghép dấu mũ như Unikey (`thana` → thân, `coongo` → côông): chữ chỉ tách ở phím cách.
fn telex_valid(keys: &str) -> bool {
    let mut st = State::default();
    keys.chars().all(|key| {
        st.feed(key);
        !st.invalid
    })
}

/// Các chữ của chuỗi phím gõ liền theo Telex tự do (bàn phím Hán Nôm kiểu Telex): mỗi phím ghép tiếp vào
/// chữ đang gõ nếu chữ vẫn là tiếng Việt (dấu gõ đâu cũng được: `hasn` là một chữ hán), không thì mở chữ mới.
/// Chữ sau bắt đầu bằng nguyên âm thì lấy phụ âm cuối của chữ trước làm phụ âm đầu (`chunom` → chu + nom).
/// Dấu `'` là một chữ riêng. Mỗi chữ là đoạn phím gốc.
pub fn telex_words(keys: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    let mut word = String::new();
    for c in keys.chars() {
        if c == '\'' {
            out.extend((!word.is_empty()).then(|| std::mem::take(&mut word)));
            out.push("'".into());
            continue;
        }
        let trial = format!("{word}{c}");
        // `w` đứng một mình là chữ ư của gõ tắt (`wdd` → w đ, ưu đãi): phụ âm sau nó mở chữ mới; phím dấu
        // (`ws` ứ) thì vẫn ghép.
        let lone_w = word.eq_ignore_ascii_case("w")
            && c.is_ascii_alphabetic()
            && !"aeiouywsfrxjzAEIOUYWSFRXJZ".contains(c)
            && !telex_valid(&trial);
        // [`RAW`] (phím xoá đặt ở đầu chữ thô) luôn mở chữ mới.
        if word.is_empty() || c != RAW && !lone_w && (telex_valid(&trial) || !telex_valid(&word)) {
            word = trial;
        } else {
            out.push(std::mem::replace(&mut word, c.to_string()));
        }
    }
    out.extend((!word.is_empty()).then_some(word));
    let is_vowel = |c: char| text::is_vowel(text::lower(c));
    for i in 1..out.len() {
        let (a, b) = (&out[i - 1], &out[i]);
        if a == "'" || b == "'" || !b.chars().next().is_some_and(is_vowel) {
            continue;
        }
        let shown = compose(a, ToneStyle::Old);
        for n in 1..=2.min(a.chars().count().saturating_sub(1)) {
            let cut = a.char_indices().rev().nth(n - 1).map_or(0, |(i, _)| i);
            let (head, coda) = a.split_at(cut);
            // Chỉ phụ âm cuối thật của chữ (`hoas` là hóa, `s` là dấu thanh, không phải phụ âm cuối).
            if coda.chars().any(is_vowel) || !shown.to_lowercase().ends_with(&coda.to_lowercase()) {
                break;
            }
            let moved = format!("{coda}{b}");
            if head.chars().any(is_vowel) && telex_valid(head) && telex_valid(&moved) {
                let head = head.to_string();
                out[i - 1] = head;
                out[i] = moved;
                break;
            }
        }
    }
    out
}

/// Chữ hiện trong khung gõ của bàn phím Hán Nôm kiểu Telex: các chữ của [`telex_words`] đã ghép dấu,
/// cách nhau một khoảng (trừ cạnh dấu `'`): `chuwxnoom` → `chữ nôm`. Chữ không phải tiếng Việt giữ nguyên phím.
pub fn display_telex(keys: &str, style: ToneStyle) -> String {
    let words = telex_words(keys);
    let mut out = String::new();
    for (i, w) in words.iter().enumerate() {
        if i > 0 && w != "'" && words[i - 1] != "'" {
            out.push(' ');
        }
        out.push_str(&if w == "'" { w.clone() } else { compose(w, style) });
    }
    out
}

/// Phím xoá của bàn phím Hán Nôm kiểu Telex: bỏ phím cuối, nhưng phím cuối là phím dấu thanh đã thay một dấu
/// trước đó thì xoá hẳn dấu thanh của chữ, không lùi về dấu cũ (`asf` à → `a`, không phải `as` á).
pub fn backspace_telex(keys: &str, style: ToneStyle) -> String {
    // Chữ cuối đang thô (đã huỷ dấu): bỏ chữ cái cuối của chữ đang hiện. Phím mới phải hiện đúng chữ đó khi tách
    // chữ kiểu Telex (`exxchanging` xoá ra `exchangin` thì tách thành ẽ chan gin), không thì giữ thô ([`RAW`]).
    if let Some(last) = telex_words(keys).last().filter(|w| *w != "'") {
        if let Some(raw) = backspace_raw(last, style) {
            let head: String = keys.chars().take(keys.chars().count() - last.chars().count()).collect();
            let mut want = display_telex(keys, style);
            want.pop();
            let out = head.clone() + &raw;
            if raw.is_empty() || display_telex(&out, style) == want {
                return out;
            }
            let mut shown = compose(last, style);
            shown.pop();
            return format!("{head}{RAW}{shown}");
        }
    }
    if let Some(last) = telex_words(keys).last().filter(|w| *w != "'") {
        if let Some(rebuilt) = backspace_flipped_mark(last, style) {
            let head: String = keys.chars().take(keys.chars().count() - last.chars().count()).collect();
            return head + &rebuilt;
        }
    }
    let mut chars: Vec<char> = keys.chars().collect();
    let Some(last) = chars.pop() else {
        return String::new();
    };
    if Tone::from_telex(last).is_none() {
        return chars.into_iter().collect();
    }
    let split = |w: &[char]| text::split_syllable(&compose(&w.iter().collect::<String>(), style).to_lowercase());
    // Chữ cuối theo [`telex_words`] (gồm cả phím vừa xoá); các chữ trước không bị ảnh hưởng.
    let start = keys.chars().count() - telex_words(keys).last().map_or(0, |w| w.chars().count());
    if start > chars.len() {
        return chars.into_iter().collect();
    }
    let Some((letters, _)) = split(&chars[start..]) else {
        return chars.into_iter().collect();
    };
    // Còn dấu thanh thì bỏ dần các phím dấu thanh (từ cuối) mà chữ vẫn giữ nguyên chữ cái.
    while let Some((l, tone)) = split(&chars[start..]) {
        if tone == Tone::Level || l != letters {
            break;
        }
        let Some(i) = (start..chars.len()).rev().find(|&i| {
            Tone::from_telex(chars[i]).is_some() && {
                let mut w = chars[start..].to_vec();
                w.remove(i - start);
                split(&w).is_some_and(|(l2, _)| l2 == letters)
            }
        }) else {
            break;
        };
        chars.remove(i);
    }
    chars.into_iter().collect()
}

/// Phím cuối của chữ `word` chỉ đổi dấu phụ này sang dấu phụ khác (`coow`: cô → cơ, gõ xen kẽ `w` `o` lật qua lại):
/// xoá thì bỏ hẳn dấu phụ đó, trả phím mới của chữ không còn dấu (`co`), không lùi về dấu cũ, để xoá liên tục không
/// lật cô ↔ cơ mãi. Không phải trường hợp này thì `None` (xoá theo phím như thường).
fn backspace_flipped_mark(word: &str, style: ToneStyle) -> Option<String> {
    let mut before: Vec<char> = word.chars().collect();
    let last = before.pop()?;
    if Tone::from_telex(last).is_some() {
        return None;
    }
    let now: Vec<char> = compose(word, style).chars().collect();
    let before: Vec<char> = compose(&before.iter().collect::<String>(), style).chars().collect();
    if now.len() != before.len() || now == before {
        return None;
    }
    let mark = |c: char| text::split_tone(text::lower(c)).0;
    let mut stripped = now.clone();
    for i in (0..now.len()).filter(|&i| now[i] != before[i]) {
        let (a, b) = (mark(now[i]), mark(before[i]));
        if text::base(a) != text::base(b) || a == text::base(a) || b == text::base(b) {
            return None;
        }
        let plain = text::with_tone(text::base(a), text::split_tone(text::lower(now[i])).1);
        stripped[i] = if now[i].is_uppercase() { text::upper(plain) } else { plain };
    }
    if stripped == now {
        return None;
    }
    let stripped: String = stripped.into_iter().collect();
    let keys = decompose(&stripped);
    (compose(&keys, style) == stripped).then_some(keys)
}

/// Mọi chữ của chuỗi phím (theo [`telex_words`]) đều là tiếng Việt.
pub(crate) fn is_vietnamese_telex(keys: &str) -> bool {
    telex_words(keys).iter().all(|w| w == "'" || telex_valid(w))
}

/// Chữ, kèm cờ người dùng đã ấn phím cách để gán thanh ngang.
pub(crate) fn compose_marked(keys: &str, style: ToneStyle, dz: bool) -> (String, bool) {
    let mut st = State { hannom: true, dz, ..Default::default() };
    for key in keys.chars() {
        st.feed(key);
        if st.invalid {
            return (keys.to_string(), false);
        }
    }
    (st.render(style), st.level_marked)
}

/// Chuẩn hoá chuỗi phím Hán Nôm, đọc từ trái qua phải. Telex xử lý trước (`aa` → â, `nhw` → như);
/// khi chữ đã có nguyên âm, mỗi chữ chỉ nhận **một dấu thanh** (s f r x j, phím cách là thanh ngang)
/// và **một dấu phụ** (`w` ư ơ ă, `q` â ê ô) gõ ở cuối:
/// - gõ dấu cùng loại lần nữa thì thay dấu cũ (`tasf` → `taf`, `namwq` → `namq`);
/// - riêng `ww` trên uo là uơ (`nguoiww`);
/// - dấu không áp được thì bỏ (`tass` → `tas`, `namww` → `namw`, `f` khi chưa có nguyên âm).
///
/// Cách hiện trong khung gõ xem [`display`].
///
/// Sau dấu thanh, chữ cái tiếp theo bắt đầu chữ mới (`chuxnom` = chux + nom).
/// s / x / r đầu chữ (và `tr`), `q` đầu chữ (`qu`) là phụ âm.
pub fn normalize(keys: &str, dz: bool) -> String {
    words(keys, dz).into_iter().flat_map(|w| w.keys).collect()
}

/// Chữ hiện trong khung gõ Hán Nôm: chuỗi phím đã [`normalize`]; dấu thanh và dấu phụ không đặt lên
/// chữ mà hiện thành ký hiệu ở đuôi, theo thứ tự gõ: thanh ˊ ˋ ˀ ˜ ˙ ¯, `w` ˘, `q` ˆ
/// (`tots` → `totˊ`, `namw` → `nam˘`, `totqs` → `totˆˊ`, `nguoiww` → `nguoi˘˘`). Không bao giờ hiện
/// chữ thô của phím dấu. Chữ chọn xong vẫn dùng [`compose_with`].
///
/// Các chữ tự cách nhau một khoảng (`homnay` → `hom nay`), trừ cạnh dấu tách `'` và khi có chữ không
/// phải tiếng Việt (hiện liền như đã gõ).
pub fn display(keys: &str, dz: bool) -> String {
    // (chữ, là dấu `'`, đã gõ dấu nên đã kết thúc)
    let mut shown: Vec<(String, bool, bool)> = Vec::new();
    let mut all_valid = true;
    for w in shown_words(keys, dz) {
        if w.keys == ['\''] {
            shown.push(("'".into(), true, false));
            continue;
        }
        let mut tail: Vec<usize> = w.marks.iter().copied().chain(w.tone_at).collect();
        tail.sort_unstable();
        let letters: String = w.keys.iter().enumerate().filter(|(i, _)| !tail.contains(i)).map(|(_, c)| c).collect();
        let word = display_word(&letters, dz);
        all_valid &= word.is_some();
        // Chữ không phải tiếng Việt giữ nguyên phím, trừ `d` đầu chữ khi bật d/z vẫn là đ (như khi chốt).
        let mut out = word.unwrap_or_else(|| match letters.chars().next() {
            Some(c @ ('d' | 'D')) if dz => format!("{}{}", if c == 'd' { 'đ' } else { 'Đ' }, &letters[1..]),
            _ => letters,
        });
        let ended = !tail.is_empty();
        for i in tail {
            let c = w.keys[i];
            out.push(match c.to_ascii_lowercase() {
                'w' => '˘',
                'q' => 'ˆ',
                ' ' => Tone::Level.mark(),
                _ => Tone::from_telex(c).map_or(c, Tone::mark),
            });
        }
        shown.push((out, false, ended));
    }
    let mut out = String::new();
    for (i, (s, apostrophe, _)) in shown.iter().enumerate() {
        let after_word = i > 0 && !shown[i - 1].1;
        // Có chữ không phải tiếng Việt thì hiện liền như đã gõ, nhưng chữ trước đã gõ dấu (đã kết thúc)
        // thì vẫn cách ra: gõ `nhaqdj` không làm `nhâ` dính vào phần sau.
        if after_word && !apostrophe && (all_valid || shown[i - 1].2) {
            out.push(' ');
        }
        out.push_str(s);
    }
    out
}

/// Mọi chữ của chuỗi phím đều là tiếng Việt (âm tiết hay phần đầu âm tiết, như [`display`] tách):
/// `vmdhs`, `chunom` thì có, `greenwich`, `facebook` thì không.
pub(crate) fn is_vietnamese(keys: &str, dz: bool) -> bool {
    shown_words(keys, dz).iter().filter(|w| w.keys != ['\'']).all(|w| {
        let tail: Vec<usize> = w.marks.iter().copied().chain(w.tone_at).collect();
        let letters: String = w.keys.iter().enumerate().filter(|(i, _)| !tail.contains(i)).map(|(_, c)| c).collect();
        display_word(&letters, dz).is_some()
    })
}

/// Các chữ của chuỗi phím đã [`normalize`]: đoạn `[đầu, cuối)` tính theo ký tự, và chữ đã gõ dấu ở
/// đuôi chưa. `None` nếu chuỗi chưa chuẩn hoá.
pub(crate) fn word_spans(keys: &str, dz: bool) -> Option<Vec<(usize, usize, bool)>> {
    let mut spans = Vec::new();
    let mut pos = 0;
    let mut joined = String::new();
    for w in words(keys, dz) {
        let len = w.keys.len();
        spans.push((pos, pos + len, w.tone_at.is_some() || !w.marks.is_empty()));
        joined.extend(&w.keys);
        pos += len;
    }
    (joined == keys).then_some(spans)
}

/// Phím chữ z thật khi bật tuỳ chọn `dz` (lúc đó phím `z` là d): bàn phím đưa vào khi giữ phím z.
/// Không phải chữ tiếng Việt nên chữ chứa nó giữ nguyên phím; bàn phím hiện và chốt nó thành z.
pub const LITERAL_Z: char = 'ｚ';

/// Phím chữ d thật khi bật `dz` (lúc đó phím `d` là đ): bàn phím đưa vào khi giữ phím d. Là chữ d như
/// thường (ghép dấu được: `ｄi` → di), nhưng khác phím `z` ở chỗ đứng sau [`LITERAL_Z`] vẫn hiện là d.
pub const LITERAL_D: char = 'ｄ';

/// Đầu chữ thô (Telex tự do): các phím sau giữ nguyên, không ghép dấu. Phím xoá đặt nó khi chữ đã huỷ dấu
/// (`coongoo` hiện coongo, xoá thì còn coong chứ không ghép lại thành côông), như Unikey.
pub const RAW: char = '\u{E000}';

/// Chữ Telex tự do `word` đang thô (gõ lặp phím để huỷ dấu, hay đã có [`RAW`]): xoá thì bỏ chữ cái cuối của
/// chữ đang hiện; không thì `None` (xoá theo phím như thường). Phím mới là phím gõ thường ra đúng chữ còn lại
/// để gõ tiếp vẫn ghép dấu như Telex (congo → xoá → cong → gõ `o` lại là công; coongo → coong là `cooong`);
/// không có thì [`RAW`] + chữ còn lại.
pub fn backspace_raw(word: &str, style: ToneStyle) -> Option<String> {
    let mut st = State::default();
    for key in word.chars() {
        st.feed(key);
        if st.invalid {
            return None;
        }
    }
    if !st.stopped {
        return None;
    }
    let mut shown = st.render(style);
    shown.pop();
    if shown.is_empty() {
        return Some(String::new());
    }
    // Phím Telex của chữ còn lại (lòng → `lofng`, để gõ `o` tiếp là lồng); vần oo phải gõ `ooo` (`oo` là ô).
    let keys = decompose(&shown);
    let candidates = [keys.clone(), keys.replace("oo", "ooo").replace("OO", "OOO")];
    Some(candidates.into_iter().find(|k| compose(k, style) == shown).unwrap_or_else(|| format!("{RAW}{shown}")))
}

/// Phím xoá của bàn phím Quốc ngữ (Telex tự do như Unikey): bỏ chữ cái cuối của chữ đang hiện, trả lại chuỗi phím
/// mới. Chữ còn lại ghép lại từ phím Telex nếu ra đúng chữ đó (`việt` → `vieej` là việ); không thì giữ thô
/// ([`RAW`]): chữ đã trả về phím gốc (`exchanging`) xoá lùi vẫn là chữ thô, không tự ghép dấu (xoá tới `ex` vẫn là
/// ex, không thành ẽ).
pub fn backspace(keys: &str, style: ToneStyle) -> String {
    if let Some(raw) = backspace_raw(keys, style) {
        return raw;
    }
    let mut shown = compose(keys, style);
    shown.pop();
    if shown.is_empty() {
        return String::new();
    }
    let rebuilt = decompose(&shown);
    if compose(&rebuilt, style) == shown {
        rebuilt
    } else {
        format!("{RAW}{shown}")
    }
}

fn is_literal_z(c: char) -> bool {
    c == LITERAL_Z || c == 'Ｚ'
}

/// Một chữ của [`words`]: phím đã nhận, vị trí phím dấu thanh và các phím dấu phụ trong đó.
#[derive(Default)]
struct Word {
    keys: Vec<char>,
    tone_at: Option<usize>,
    marks: Vec<usize>,
}

/// Các chữ của chuỗi phím theo quy tắc của [`normalize`]; dấu `'` là một chữ riêng.
fn words(keys: &str, dz: bool) -> Vec<Word> {
    let shown = |w: &[char]| display_word(&w.iter().collect::<String>(), dz);
    let count = |s: &str, k: char| s.chars().filter(|c| c.to_ascii_lowercase() == k).count();
    let mut out = Vec::new();
    let mut word: Vec<char> = Vec::new();
    // Vị trí phím thanh và các phím dấu phụ đuôi chữ trong `word`.
    let mut tone_at: Option<usize> = None;
    let mut marks: Vec<usize> = Vec::new();
    let finish = |out: &mut Vec<Word>, word: &mut Vec<char>, tone_at: &mut Option<usize>, marks: &mut Vec<usize>| {
        if !word.is_empty() {
            out.push(Word { keys: std::mem::take(word), tone_at: tone_at.take(), marks: std::mem::take(marks) });
        }
        *tone_at = None;
        marks.clear();
    };
    let chars: Vec<char> = keys.chars().collect();
    for (at, &c) in chars.iter().enumerate() {
        let k = c.to_ascii_lowercase();
        if c == '\'' {
            finish(&mut out, &mut word, &mut tone_at, &mut marks);
            out.push(Word { keys: vec![c], ..Default::default() });
            continue;
        }
        let current = shown(&word);
        // Chữ z thật (zalo): `z` đầu chữ khi tắt tuỳ chọn `dz`, hoặc [`LITERAL_Z`] (giữ phím z khi bật):
        // không phải tiếng Việt, giữ nguyên mọi phím sau nó.
        let literal_z = (!dz && word.first().is_some_and(|f| f.to_ascii_lowercase() == 'z'))
            || word.iter().any(|&ch| is_literal_z(ch));
        if literal_z && current.is_none() && c != ' ' {
            word.push(c);
            continue;
        }
        let vowel = current
            .as_deref()
            .is_some_and(|s| s.chars().any(|ch| text::is_vowel(text::split_tone(text::lower(ch)).0)));
        let is_tone = c == ' ' || Tone::from_telex(c).is_some();
        // `q` rồi `u` là phụ âm đầu `qu` của chữ sau, không phải dấu mũ: `tuongwsquanq` = tướng + quân
        // (không thì `q` thay dấu `w` thành tuống).
        let onset_q = k == 'q' && !word.is_empty() && chars.get(at + 1).is_some_and(|n| n.to_ascii_lowercase() == 'u');
        let is_mark = (k == 'w' || k == 'q') && !onset_q;
        // Phím dấu: chỉ nhận nếu đổi được chữ mà không sinh chữ thô.
        let accepts = |trial: &[char]| {
            matches!((shown(trial), &current), (Some(t), Some(cur)) if t != *cur && count(&t, k) <= count(cur, k))
        };
        // Mỗi chữ nhận một thanh và một loại dấu phụ (tối đa hai phím giống nhau, `ww`); thanh hay dấu phụ
        // đã gõ thì không đổi được. Phím bị từ chối mà làm phụ âm đầu được (`s x r q`) thì mở chữ mới
        // (`tuongwsqu` = tướng + qu…), không thì bỏ qua.
        let rejected = vowel
            && ((is_tone && tone_at.is_some())
                || (is_mark && marks.first().is_some_and(|&i| word[i].to_ascii_lowercase() != k || marks.len() == 2)));
        if rejected {
            if matches!(k, 's' | 'x' | 'r' | 'q') {
                finish(&mut out, &mut word, &mut tone_at, &mut marks);
                word.push(c);
            }
            continue;
        }
        if vowel && is_tone {
            let mut trial = word.clone();
            trial.push(c);
            if accepts(&trial) {
                tone_at = Some(word.len());
                word = trial;
            }
            continue;
        }
        if vowel && is_mark {
            // Dấu phụ đầu tiên, hoặc phím thứ hai giống nó (`ww`: ươ → uơ).
            let mut trial = word.clone();
            trial.push(c);
            if accepts(&trial) {
                marks.push(word.len());
                word = trial;
            }
            continue;
        }
        let initial = (word.is_empty() && matches!(k, 's' | 'x' | 'r' | 'q'))
            || (k == 'r' && word.len() == 1 && word[0].to_ascii_lowercase() == 't');
        // Phím dấu chưa đứng sau nguyên âm thì không phải dấu: là chữ cái thường (chữ thành không phải
        // tiếng Việt, giữ nguyên phím). Riêng phím cách thì bỏ.
        // Phụ âm đầu s / x không ghép được vào chữ trước (`hs` không là phụ âm) thì mở chữ mới:
        // `hsix` = h + six. Không áp cho `r`: gr, br, pr… là dấu hiệu chữ nước ngoài (`greenwich`).
        let new_onset = matches!(k, 's' | 'x')
            && !word.is_empty()
            && current.is_some()
            && shown(&[word.as_slice(), &[c]].concat()).is_none();
        if new_onset {
            finish(&mut out, &mut word, &mut tone_at, &mut marks);
            word.push(c);
            continue;
        }
        if (is_tone || is_mark) && !initial {
            if c != ' ' {
                word.push(c);
            }
            continue;
        }
        // Chữ cái: sau dấu thanh, hoặc không ghép tiếp được, thì bắt đầu chữ mới.
        let mut trial = word.clone();
        trial.push(c);
        // Dấu thanh hay dấu phụ kết thúc chữ (`hosa` = ho + a, `oqm` = o + m).
        if onset_q || tone_at.is_some() || !marks.is_empty() || (shown(&trial).is_none() && !word.is_empty() && current.is_some()) {
            finish(&mut out, &mut word, &mut tone_at, &mut marks);
            word.push(c);
        } else {
            word = trial;
        }
    }
    finish(&mut out, &mut word, &mut tone_at, &mut marks);

    out
}

/// Các chữ để hiện và chốt: như [`words`], thêm [`prefer_consonant_onset`]. Gợi ý thì dùng cách tách gốc
/// của [`words`], để từ điển quyết định (`anhem` có 英 thì là anh + em).
fn shown_words(keys: &str, dz: bool) -> Vec<Word> {
    let mut out = words(keys, dz);
    prefer_consonant_onset(&mut out, dz);
    out
}

/// Ưu tiên chữ dạng phụ âm + nguyên âm (+ phụ âm): chữ sau bắt đầu bằng nguyên âm thì lấy phụ âm cuối
/// của chữ trước (chưa gõ dấu) làm phụ âm đầu, nếu cả hai vẫn là tiếng Việt (`chun` + `om` → `chu` + `nom`).
fn prefer_consonant_onset(out: &mut [Word], dz: bool) {
    let letters = |w: &Word, extra: &[char]| -> String {
        let tail: Vec<usize> = w.marks.iter().copied().chain(w.tone_at).collect();
        extra.iter().copied().chain(w.keys.iter().enumerate().filter(|(i, _)| !tail.contains(i)).map(|(_, &c)| c)).collect()
    };
    let is_vowel = |c: char| text::is_vowel(text::lower(c));
    for i in 1..out.len() {
        let (left, right) = out.split_at_mut(i);
        let (a, b) = (&mut left[i - 1], &mut right[0]);
        if a.keys == ['\''] || b.keys == ['\''] || a.tone_at.is_some() || !a.marks.is_empty() {
            continue;
        }
        // Chỉ khi chữ sau là nguyên âm + phụ âm (`om` → nom); chữ sau chỉ có nguyên âm thì giữ (`nam a`).
        let b_letters = letters(b, &[]);
        if !b.keys.first().is_some_and(|&c| is_vowel(c)) || b_letters.chars().last().is_none_or(is_vowel) {
            continue;
        }
        for n in 1..=2.min(a.keys.len().saturating_sub(1)) {
            let cut = a.keys.len() - n;
            let (head, coda) = a.keys.split_at(cut);
            if coda.iter().any(|&c| is_vowel(c)) {
                break;
            }
            let head_ok = head.iter().any(|&c| is_vowel(c)) && display_word(&head.iter().collect::<String>(), dz).is_some();
            if head_ok && display_word(&letters(b, coda), dz).is_some() {
                let coda = coda.to_vec();
                a.keys.truncate(cut);
                b.keys.splice(0..0, coda);
                b.tone_at = b.tone_at.map(|t| t + n);
                b.marks.iter_mut().for_each(|m| *m += n);
                break;
            }
        }
    }
}

/// Một âm tiết (chưa có phím thanh) theo kiểu của [`display`]; `None` nếu không phải tiếng Việt.
fn display_word(keys: &str, dz: bool) -> Option<String> {
    let mut st = State { hannom: true, dz, ..Default::default() };
    for key in keys.chars() {
        st.feed(key);
        if st.invalid {
            return None;
        }
    }
    Some(st.render_marked())
}

/// Chuỗi phím Telex sinh ra một từ: `việt` → `vieetj`.
pub fn decompose(word: &str) -> String {
    let mut keys = String::new();
    let mut tone = Tone::Level;
    let mut prev = None;
    for c in word.chars() {
        let (b, t) = text::split_tone(text::lower(c));
        if t != Tone::Level {
            tone = t;
        }
        // uơ: `uow` là ươ, phải gõ `uoww` (xoá chữ sau uơ ra đúng uơ, không lùi về ươ).
        if b == 'ơ' && prev == Some('u') {
            keys.push(if c.is_uppercase() { 'O' } else { 'o' });
            keys.push_str("ww");
            prev = Some(b);
            continue;
        }
        prev = Some(b);
        let (first, second) = match b {
            'â' => ('a', Some('a')),
            'ă' => ('a', Some('w')),
            'ê' => ('e', Some('e')),
            'ô' => ('o', Some('o')),
            'ơ' => ('o', Some('w')),
            'ư' => ('u', Some('w')),
            'đ' => ('d', Some('d')),
            _ => (b, None),
        };
        keys.push(if c.is_uppercase() { text::upper(first) } else { first });
        keys.extend(second);
    }
    keys.extend(tone.telex_key());
    keys
}

/// Như [`decompose`] theo kiểu gõ Hán Nôm của [`compose_with`]: chữ gốc, rồi dấu phụ (`q` cho
/// â ê ô, `w` cho ă ơ ư), rồi dấu thanh: `tốt` → `totqs`, `người` → `nguoiwf`, `uơ` → `uoww`.
/// Tuỳ chọn `dz`: `đi` → `di`, `di` → `zi`.
pub fn decompose_with(word: &str, dz: bool) -> String {
    let bases: Vec<char> = word.chars().map(|c| text::split_tone(text::lower(c)).0).collect();
    let mut keys = String::new();
    let mut tone = Tone::Level;
    let (mut hat, mut horn) = (false, false);
    for c in word.chars() {
        let (b, t) = text::split_tone(text::lower(c));
        if t != Tone::Level {
            tone = t;
        }
        let letter = match b {
            'â' | 'ê' | 'ô' => {
                hat = true;
                text::base(b)
            }
            'ă' | 'ơ' | 'ư' => {
                horn = true;
                text::base(b)
            }
            'đ' => 'd',
            'd' if dz => 'z',
            _ => b,
        };
        keys.push(if c.is_uppercase() { text::upper(letter) } else { letter });
        if b == 'đ' && !dz {
            keys.push('d');
        }
    }
    if hat {
        keys.push('q');
    }
    // ươ là một `w`, uơ là `ww`.
    let uo = bases.windows(2).any(|p| p == ['u', 'ơ']);
    keys.extend(std::iter::repeat_n('w', if uo { 2 } else { usize::from(horn) }));
    keys.extend(tone.telex_key());
    keys
}

/// Bộ đệm gõ một từ, dùng cho frontend: thêm phím, xoá lùi, lấy kết quả.
#[derive(Clone, Debug, Default)]
pub struct Composer {
    pub style: ToneStyle,
    keys: String,
    /// Sau khi xoá lùi mà không dựng lại được chuỗi phím: giữ chữ nguyên văn.
    literal: Option<String>,
}

impl Composer {
    pub fn new(style: ToneStyle) -> Self {
        Composer { style, ..Default::default() }
    }

    pub fn push(&mut self, key: char) {
        match &mut self.literal {
            Some(s) => s.push(key),
            None => self.keys.push(key),
        }
    }

    /// Xoá ký tự cuối của chữ hiển thị (như Unikey), không phải phím cuối.
    pub fn backspace(&mut self) {
        let mut out = self.output();
        out.pop();
        let keys = decompose(&out);
        if compose(&keys, self.style) == out {
            self.keys = keys;
            self.literal = None;
        } else {
            self.keys.clear();
            self.literal = Some(out);
        }
    }

    pub fn output(&self) -> String {
        match &self.literal {
            Some(s) => s.clone(),
            None => compose(&self.keys, self.style),
        }
    }

    /// Chuỗi phím dùng để tra gợi ý.
    pub fn keys(&self) -> &str {
        self.literal.as_deref().unwrap_or(&self.keys)
    }

    pub fn is_empty(&self) -> bool {
        self.keys().is_empty()
    }

    pub fn clear(&mut self) {
        self.keys.clear();
        self.literal = None;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn c(keys: &str) -> String {
        compose(keys, ToneStyle::Old)
    }

    #[test]
    fn lone_w_then_consonant_starts_new_word() {
        // Gõ tắt `wdd` (ưu đãi): `w` đứng riêng, `dd` vẫn là đ; phím dấu sau w (`ws`) vẫn ghép.
        assert_eq!(display_telex("wdd", ToneStyle::Old), "w đ");
        assert_eq!(display_telex("twdd", ToneStyle::Old), "t w đ");
        assert_eq!(telex_words("ws"), ["ws"]);
    }

    #[test]
    fn basic_words() {
        let cases = [
            ("vieetj", "việt"),
            ("nguoiwf", "người"),
            ("ddaay", "đây"),
            ("tieengs", "tiếng"),
            ("Vieetj", "Việt"),
            ("VIEETJ", "VIỆT"),
            ("quas", "quá"),
            ("gif", "gì"),
            ("giaf", "già"),
            ("muaw", "mưa"),
            ("nawm", "năm"),
            ("hoawcj", "hoặc"),
            ("truwowngf", "trường"),
            ("dduwowngf", "đường"),
            ("khuyeens", "khuyến"),
            // `w` khi chưa có nguyên âm là chữ w, không tự ra ư.
            ("w", "w"),
            ("nhw", "nhw"),
            ("uw", "ư"),
            ("nhuw", "như"),
        ];
        for (keys, want) in cases {
            assert_eq!(c(keys), want, "{keys}");
        }
    }

    #[test]
    fn invalid_words_keep_marks() {
        // Chữ hết là tiếng Việt: giữ phần đã ghép dấu, phím sau nối thêm nguyên như gõ, không trả về phím gốc.
        let cases = [
            ("leeni", "lêni"),
            ("leenin", "lênin"),
            ("Leenin", "Lênin"),
            ("soov", "sôv"),
            ("soovanh", "sôvanh"),
            ("exc", "ẽc"),
            ("exchange", "ẽchange"),
            ("windows", "windows"),
        ];
        for (keys, want) in cases {
            assert_eq!(c(keys), want, "{keys}");
        }
        // Xoá lùi rồi gõ lại vẫn đúng.
        assert_eq!(c(&backspace("leenin", ToneStyle::Old)), "lêni");
        assert_eq!(c(&backspace("soovanh", ToneStyle::Old)), "sôvan");
    }

    #[test]
    fn stroke_kept_in_non_words() {
        // Chữ không phải tiếng Việt trả nguyên phím, nhưng `dd` đầu chữ vẫn là đ (như Unikey).
        assert_eq!(c("ddcm"), "đcm");
        assert_eq!(c("DDcm"), "Đcm");
        // `ddd` huỷ đ: ra dd như Unikey.
        assert_eq!(c("dddcm"), "ddcm");
        assert_eq!(c("windows"), "windows");
    }

    #[test]
    fn double_marks() {
        // `ww` trên ươ ra uơ khi uơ là vần được (thuở); gõ nữa thì huỷ dấu (không lật lại ươ mãi).
        assert_eq!(c("uoww"), "uơ");
        assert_eq!(c("thuowwr"), "thuở");
        assert_eq!(c("uowww"), "uow");
        assert_eq!(c("nguownww"), "nguonw");
        assert_eq!(c("nguownwwww"), "nguonwww");
        // Xoá chữ cái cuối của nguơn ra nguơ (phím `nguoww`), không lùi về ngươn.
        assert_eq!(decompose("nguơ"), "nguoww");
        assert_eq!(c(&decompose("nguơ")), "nguơ");
        assert_eq!(c(&decompose("thuở")), "thuở");
        assert_eq!(c(&backspace_raw("nguownww", ToneStyle::Old).unwrap()), "nguon");
    }

    #[test]
    fn backspace_keeps_raw_words_raw() {
        // Xoá lùi chữ không phải tiếng Việt: từng chữ cái một của chữ đang hiện.
        let mut keys = "exchanging".to_string();
        for want in ["ẽchangin", "ẽchangi", "ẽchang", "ẽchan", "ẽcha", "ẽch", "ẽc", "ẽ", ""] {
            keys = backspace(&keys, ToneStyle::Old);
            assert_eq!(c(&keys), want);
        }
        // Chữ tiếng Việt vẫn xoá như cũ: bỏ chữ cái cuối, gõ tiếp vẫn ghép dấu.
        let back = backspace("vieetj", ToneStyle::Old);
        assert_eq!(c(&back), "việ");
        assert_eq!(c(&format!("{back}t")), "việt");
        assert_eq!(c(&backspace("nguownw", ToneStyle::Old)), "nguơ");
        // Bàn phím Hán Nôm kiểu Telex: `exxchanging` (exchanging) xoá lùi không tách lại thành ẽ chan gin.
        let mut keys = "chuwxexxchanging".to_string();
        assert_eq!(display_telex(&keys, ToneStyle::Old), "chữ exchanging");
        for want in ["chữ exchangin", "chữ exchangi", "chữ exchang", "chữ ex", "chữ e", "chữ"] {
            while display_telex(&keys, ToneStyle::Old).chars().count() > want.chars().count() {
                keys = backspace_telex(&keys, ToneStyle::Old);
            }
            assert_eq!(display_telex(&keys, ToneStyle::Old), want);
        }
        assert_eq!(display_telex(&format!("{}g", backspace_telex("exxchanging", ToneStyle::Old)), ToneStyle::Old), "exchanging");
        // công (gõ cách hay gõ liền `oo`) thêm `o` thì huỷ dấu, ra congo.
        assert_eq!(c("congo"), "công");
        assert_eq!(c("congoo"), "congo");
        assert_eq!(c("coongo"), "congo");
        // Vần oo (chữ hiện coong, gõ `ooo`) thêm `o` mới là ôô; thêm nữa thì huỷ, ra coongo.
        assert_eq!(c("cooong"), "coong");
        assert_eq!(c("cooongo"), "côông");
        assert_eq!(c("Cooongo"), "Côông");
        assert_eq!(c("cooongoo"), "coongo");
        assert_eq!(display_telex("cooongo", ToneStyle::Old), "côông");
        // Dấu thanh đặt ở ô / o sau.
        assert_eq!(c("cooongos"), "côống");
        assert_eq!(c("cooongs"), "coóng");
        assert_eq!(c("xooongs"), "xoóng");
        assert_eq!(c("sooocs"), "soóc");
        assert_eq!(c("cooo"), "coo");
        // Chữ đã huỷ dấu: xoá bỏ chữ cái cuối của chữ đang hiện (coongo → coong), không ghép lại thành dấu;
        // gõ tiếp thì ghép dấu như Telex thường (cong + o lại là công, coong + o lại là côông).
        let back = backspace_telex("cooongoo", ToneStyle::Old);
        assert_eq!(c(&back), "coong");
        assert_eq!(c(&format!("{back}o")), "côông");
        assert_eq!(c(&backspace_telex(&back, ToneStyle::Old)), "coon");
        let back = backspace_raw("congoo", ToneStyle::Old).unwrap();
        assert_eq!(c(&back), "cong");
        assert_eq!(c(&format!("{back}o")), "công");
        // Có dấu thanh: lồng + o → lòngo, xoá → lòng (phím `lofng`), gõ `o` một lần là lồng.
        assert_eq!(c("lofngo"), "lồng");
        assert_eq!(c("lofngoo"), "lòngo");
        let back = backspace_telex("lofngoo", ToneStyle::Old);
        assert_eq!(c(&back), "lòng");
        assert_eq!(c(&format!("{back}o")), "lồng");
        assert!(back.chars().all(|ch| ch.is_ascii_alphabetic()), "{back:?}");
        // Chữ chưa huỷ dấu thì xoá theo phím như thường.
        assert_eq!(backspace_raw("cooongo", ToneStyle::Old), None);
        // Không ảnh hưởng chữ thường.
        assert_eq!(c("coongs"), "cống");
        assert_eq!(c("caanga"), "canga");
    }

    #[test]
    fn backspace_does_not_flip_marks() {
        let style = ToneStyle::Old;
        let t = |k: &str| display_telex(k, style);
        // Gõ xen kẽ w và o (cô ↔ cơ): xoá là mất dấu, không lật qua lại.
        for keys in ["coow", "coowo", "coowowowowowo", "coowowowowow"] {
            let back = backspace_telex(keys, style);
            assert_eq!(t(&back), "co", "{keys}");
            assert_eq!(t(&backspace_telex(&back, style)), "c", "{keys}");
        }
        // Xoá liên tục luôn hết chữ, không lặp.
        let mut keys = "chuwxcoowowowowowo".to_string();
        let mut steps = 0;
        while !keys.is_empty() {
            keys = backspace_telex(&keys, style);
            steps += 1;
            assert!(steps < 12, "{keys}");
        }
        assert_eq!(t(&backspace_telex("aawawa", style)), "a");
        // Giữ dấu thanh và chữ hoa.
        assert_eq!(t(&backspace_telex("coowfo", style)), "cò");
        assert_eq!(t(&backspace_telex("Coowo", style)), "Co");
        // Chữ thường xoá theo phím như cũ.
        assert_eq!(t(&backspace_telex("cow", style)), "co");
        assert_eq!(t(&backspace_telex("coo", style)), "co");
        assert_eq!(t(&backspace_telex("chuwxn", style)), "chữ");
        // Vần uơ: xoá lần lượt, không kẹt.
        assert_eq!(t(&backspace_telex("uoww", style)), "ươ");
        assert_eq!(t(&backspace_telex("uow", style)), "uo");
        // Quốc ngữ xoá theo chữ đang hiện, không lật.
        assert_eq!(c(&backspace("coowowo", style)), "c");
    }

    #[test]
    fn free_tone_placement() {
        assert_eq!(c("viejet"), "việt");
        assert_eq!(c("tosi"), "tói");
        assert_eq!(c("nguofiw"), "người");
    }

    #[test]
    fn tone_style() {
        assert_eq!(compose("hoaf", ToneStyle::Old), "hòa");
        assert_eq!(compose("hoaf", ToneStyle::New), "hoà");
        assert_eq!(compose("thuyr", ToneStyle::Old), "thủy");
        assert_eq!(compose("thuyr", ToneStyle::New), "thuỷ");
        assert_eq!(compose("hoanf", ToneStyle::New), "hoàn");
    }

    #[test]
    fn undo_by_repeating() {
        // `ooo` hiện oo; gõ tiếp ra chữ không phải tiếng Việt thì giữ đúng chữ đang hiện, không thừa `o`.
        assert_eq!(c("booo"), "boo");
        assert_eq!(c("boook"), "book");
        assert_eq!(c("Boook"), "Book");
        assert_eq!(c("boooks"), "books");
        // `oo` là ô; chữ hết là tiếng Việt thì giữ ô, không trả về phím gốc.
        assert_eq!(c("book"), "bôk");
        assert_eq!(c("ass"), "as");
        assert_eq!(c("aaa"), "aa");
        assert_eq!(c("ddd"), "dd");
        assert_eq!(c("asz"), "a");
        assert_eq!(h("totqq"), "totq");
    }

    fn h(keys: &str) -> String {
        compose_with(keys, ToneStyle::Old, false)
    }

    #[test]
    fn quoc_ngu_is_plain_unikey() {
        assert_eq!(c("totq"), "totq");
        assert_eq!(c("az"), "az");
        assert_eq!(c("di"), "di");
    }

    #[test]
    fn trailing_markers() {
        let cases = [
            ("totq", "tôt"),
            ("totqs", "tốt"),
            ("totsq", "tốt"),
            ("tienq", "tiên"),
            ("muonqs", "muốn"),
            ("quocqs", "quốc"),
            ("tanq", "tân"),
            ("namw", "năm"),
            ("tuw", "tư"),
            ("nguoiwf", "người"),
            ("nam ", "nam"),
            // `z` không còn là dấu ngang.
            ("namz", "namz"),
        ];
        for (keys, want) in cases {
            assert_eq!(h(keys), want, "{keys}");
        }
        assert_eq!(compose_marked("nam ", ToneStyle::Old, false), ("nam".into(), true));
        assert_eq!(compose_marked("nam", ToneStyle::Old, false), ("nam".into(), false));
    }

    #[test]
    fn display_marks_tone_after_word() {
        let cases = [
            ("tot", "tot"),
            ("tots", "totˊ"),
            // Dấu phụ cũng là ký hiệu ở đuôi, theo thứ tự gõ.
            ("totq", "totˆ"),
            ("totqs", "totˆˊ"),
            ("totsq", "totˊˆ"),
            ("quaw", "qua˘"),
            ("nguoiw", "nguoi˘"),
            ("nguoiwf", "nguoi˘ˋ"),
            ("nawm", "na˘ m"),
            ("aqn", "aˆ n"),
            ("nhaqn", "nhaˆ n"),
            // Phím thanh kết thúc âm tiết: đọc từ trái qua phải.
            ("tosi", "toˊ i"),
            // Một dấu thanh, một dấu phụ: không thay dấu đã gõ; phím thừa làm phụ âm đầu được (s x r q)
            // thì mở chữ mới, không thì bỏ.
            ("tass", "taˊ s"),
            ("tasf", "taˊ"),
            ("namww", "nam˘"),
            ("totqq", "totˆ"),
            ("namwq", "nam˘ q"),
            ("totqw", "totˆ"),
            ("tiw", "ti"),
            ("fja", "fja"),
            // ww trên uo là uơ.
            ("nguoiww", "nguoi˘˘"),
            ("nguoiwwf", "nguoi˘˘ˋ"),
            ("nguoiwww", "nguoi˘˘"),
            ("nhuw", "nhu˘"),
            ("quocs", "quocˊ"),
            ("Vietqj", "Vietˆ˙"),
            // aa / ee / oo giữ nguyên, â ê ô chỉ qua một `q`; `q` đầu chữ (qu) là phụ âm.
            ("coong", "coong"),
            ("congq", "congˆ"),
            ("quocqs", "quocˆˊ"),
            ("quocq", "quocˆ"),
            // ww trên uo: dấu thanh nằm đâu cũng giữ đủ hai w.
            ("nguoiwsw", "nguoi˘ˊ˘"),
            ("nguoiwws", "nguoi˘˘ˊ"),
            ("nguoisww", "nguoiˊ˘˘"),
            ("nam ", "nam¯"),
            // Phím dấu chưa đứng sau nguyên âm là chữ cái thường: `w` không ra ư.
            ("tw", "tw"),
            ("nhwngx", "nhwngx"),
            ("fnam", "fnam"),
            ("cuar", "cuaˀ"),
            // Phụ âm đầu s / x / r không phải phím thanh.
            ("xas", "xaˊ"),
            ("chuxnom", "chu˜ nom"),
            ("chuwx'nomq ", "chu˘˜'nomˆ¯"),
            // Các chữ tự cách nhau, ưu tiên chữ bắt đầu bằng phụ âm.
            ("chunom", "chu nom"),
            ("homnay", "hom nay"),
            ("homq'nay", "homˆ'nay"),
            ("nguoivietqj", "nguoi vietˆ˙"),
            // Dấu thanh, dấu phụ kết thúc chữ.
            ("hosa", "hoˊ a"),
            ("oqm", "oˆ m"),
            ("nama", "nam a"),
            // Chữ sau không phải tiếng Việt: chữ trước đã gõ dấu vẫn đứng riêng.
            ("nhaqdj", "nhaˆ dj"),
            // `q` rồi `u` mở chữ `qu…` mới, không đổi dấu chữ trước.
            ("tuongwsquanq", "tuong˘ˊ quanˆ"),
            ("tuongwsqu", "tuong˘ˊ qu"),
            ("tuongquanq", "tuong quanˆ"),
            ("tuongwq", "tuong˘ q"),
            ("hocjsinh", "hoc˙ sinh"),
            ("nguoiwwws", "nguoi˘˘ˊ"),
            ("tuongsw", "tuongˊ˘"),
            // Phụ âm đầu s / x không ghép được vào chữ trước thì là chữ mới.
            ("hsix", "h si˜"),
            ("vxa", "v xa"),
        ];
        for (keys, want) in cases {
            assert_eq!(display(keys, false), want, "{keys}");
        }
        assert_eq!(normalize("tasf", false), "tas");
        assert_eq!(normalize("namwwq", false), "namwq");
        assert_eq!(normalize("nguoiwwws", false), "nguoiwws");
        assert_eq!(normalize("fjtots", false), "fjtots");
        // Chuẩn hoá lại không đổi gì.
        for keys in ["taf", "namq", "nguoiwws", "chuxnom", "nawm", "tôtsq"] {
            assert_eq!(normalize(&normalize(keys, false), false), normalize(keys, false), "{keys}");
        }
        // Mỗi chữ nhận tối đa một thanh và một loại dấu phụ (hai phím `ww`): phím dấu thừa không đổi chữ.
        for (keys, first) in [("afsrxjqw", "aˋ"), ("aswqfrj", "aˊ˘"), ("nguoiwwqqss", "nguoi˘˘")] {
            let shown = display(keys, false);
            assert_eq!(shown.split(' ').next(), Some(first), "{keys} -> {shown}");
        }
        // Trước nguyên âm thì là chữ cái thường: chữ không phải tiếng Việt, giữ nguyên phím.
        for keys in ["windows", "tqs", "bsf", "w"] {
            assert_eq!(display(keys, false), keys);
            assert_eq!(compose_with(keys, ToneStyle::Old, false), keys);
        }
        // Trừ chữ bắt đầu bằng z khi tắt tuỳ chọn dz: là chữ z thật, giữ nguyên mọi phím.
        for keys in ["zas", "zaxxjj", "zalo", "pizza"] {
            assert_eq!(display(keys, false), keys);
            assert_eq!(normalize(keys, false), keys);
            assert_eq!(compose_with(keys, ToneStyle::Old, false), keys);
        }
        // Trừ phụ âm đầu: s / x / r, tr, qu.
        assert_eq!(display("sa", false), "sa");
        assert_eq!(display("trar", false), "traˀ");
        assert_eq!(display("quas", false), "quaˊ");
        assert_eq!(display("dis", true), "điˊ");
    }

    #[test]
    fn tone_ends_word() {
        assert_eq!(h("hosa"), "hosa");
        assert_eq!(h("hoas"), "hóa");
        assert_eq!(h("totsq"), "tốt");
        let w = |k: &str| compose_words(k, ToneStyle::Old, false);
        assert_eq!(w("hosa"), "hó a");
        assert_eq!(w("hoas"), "hóa");
        assert_eq!(w("chunom"), "chu nom");
        assert_eq!(w("homq'nay"), "hôm'nay");
        assert_eq!(w("windows"), "windows");
    }

    #[test]
    fn joined_keeps_dz_per_word() {
        let j = |k: &str, dz: bool| compose_joined(k, ToneStyle::Old, dz);
        // Bật d/z: d đầu mỗi chữ là đ, z là d, kể cả khi cả cụm không phải tiếng Việt (gõ tắt).
        assert_eq!(j("vmdhs", true), display("vmdhs", true).replace(' ', ""));
        assert_eq!(j("vmdhs", true), "vmđhs");
        assert_eq!(j("zaf", true), "dà");
        assert_eq!(j("dif", true), "đì");
        // Tắt d/z: dd là đ, z giữ nguyên.
        assert_eq!(j("ddaij", false), "đại");
        assert_eq!(j("vmddh", false), "vmđh");
        assert_eq!(j("chunom", false), "chunom");
        assert_eq!(j("chuwx", false), "chữ");
        // Dấu kết thúc chữ: chữ sau tách riêng.
        assert_eq!(j("nhaqn", false), "nhâ n");
        assert_eq!(j("hosa", false), "hó a");
        assert_eq!(j("nhanq", false), "nhân");
    }

    #[test]
    fn dz_option() {
        let d = |k: &str| compose_with(k, ToneStyle::Old, true);
        assert_eq!(d("di"), "đi");
        // `dd` không gộp thành một đ: phím d thứ hai là chữ mới.
        assert_eq!(display("dd", true), "đ đ");
        assert_eq!(display("ddi", true), "đ đi");
        // Chữ z thật (giữ phím z): ghép tiếp vào chữ đang gõ, giữ nguyên phím sau nó.
        assert_eq!(display("diｚ", true), "điｚ");
        assert_eq!(normalize("ｚas", true), "ｚas");
        assert_eq!(display("ｚas", true), "ｚas");
        assert_eq!(d("zi"), "di");
        assert_eq!(d("duongwf"), "đường");
        assert_eq!(d("zayqf"), "dầy");
        // Không bật thì như Telex thường.
        assert_eq!(c("ddi"), "đi");
        for word in ["đi", "dầy", "Đường", "dịch"] {
            assert_eq!(d(&decompose_with(word, true)), word, "{word}");
        }
        // Chữ d thật (giữ phím d): là d như thường, đứng sau chữ z thật vẫn là d (không thành z như phím z).
        assert_eq!(d("ｄi"), "di");
        assert_eq!(d("Ｄi"), "Di");
        assert_eq!(display("ｄi", true), "di");
        assert_eq!(display("ｚｄ", true), "ｚｄ");
        assert_ne!(display("ｚｄ", true), display("ｚz", true));
    }

    #[test]
    fn hannom_marks_compose_and_decompose() {
        let cases = [
            ("congq", "công"),
            ("coong", "coong"),
            ("quocqs", "quốc"),
            ("nguoiwf", "người"),
            ("nguoiwwf", "nguời"),
            ("nguoiwsw", "nguới"),
            ("nguoisww", "nguới"),
            ("huouw", "hươu"),
            ("coongq", "côông"),
        ];
        for (keys, want) in cases {
            assert_eq!(h(keys), want, "{keys}");
        }
        assert_eq!(decompose_with("tốt", false), "totqs");
        assert_eq!(decompose_with("người", false), "nguoiwf");
        assert_eq!(decompose_with("ệ", false), "eqj");
        for word in ["tốt", "người", "nguơi", "Quốc", "hươu", "đường", "chữ", "nôm", "năm", "côông"] {
            assert_eq!(h(&decompose_with(word, false)), word, "{word}");
        }
    }

    #[test]
    fn non_vietnamese_keeps_typed_marks() {
        // Không ghép được dấu nào thì như gõ; đã ghép rồi mới hết là tiếng Việt thì giữ dấu đã ghép.
        for word in ["windows", "fix", "chrome", "Windows"] {
            assert_eq!(c(word), word);
        }
        assert_eq!(c("text"), "tẽt");
    }

    #[test]
    fn decompose_roundtrip() {
        for word in ["việt", "người", "Đường", "khuyến", "hoặc", "chữ", "nôm"] {
            let keys = decompose(word);
            assert_eq!(c(&keys), word, "{word} -> {keys}");
        }
    }

    #[test]
    fn composer_backspace_removes_last_char() {
        let mut comp = Composer::new(ToneStyle::Old);
        "vieetj".chars().for_each(|k| comp.push(k));
        assert_eq!(comp.output(), "việt");
        comp.backspace();
        assert_eq!(comp.output(), "việ");
        comp.push('t');
        assert_eq!(comp.output(), "việt");

        let mut comp = Composer::new(ToneStyle::Old);
        "ass".chars().for_each(|k| comp.push(k));
        comp.backspace();
        assert_eq!(comp.output(), "a");
    }
}

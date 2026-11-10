//! Cấu trúc âm tiết tiếng Việt: phụ âm đầu + âm chính (nguyên âm) + âm cuối.
//!
//! Mọi hàm ở đây nhận chữ thường, không dấu thanh (dấu thanh truyền riêng).

use crate::text::{self, Tone};

/// Kiểu đặt dấu thanh (tuỳ chọn như Unikey).
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum ToneStyle {
    /// hòa, thủy
    #[default]
    Old,
    /// hoà, thuỷ
    New,
}

/// Mức độ kiểm tra âm tiết.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Check {
    /// Đang gõ dở: chấp nhận tiền tố hợp lệ, cho phép chưa gõ dấu phụ.
    Prefix,
    /// Âm tiết trọn vẹn, cho phép thiếu dấu phụ (Telex rút gọn).
    Complete,
    /// Âm tiết đúng chính tả, đủ dấu.
    Strict,
}

const INITIALS: &[&str] = &[
    "ngh", "ch", "gh", "gi", "kh", "ng", "nh", "ph", "qu", "th", "tr", "b", "c", "d", "đ", "g",
    "h", "k", "l", "m", "n", "p", "q", "r", "s", "t", "v", "x",
];

const FINALS: &[&str] = &["ch", "ng", "nh", "c", "m", "n", "p", "t"];

#[derive(PartialEq)]
enum Final {
    Never,
    Optional,
    Required,
}

use Final::*;

const NUCLEI: &[(&str, Final)] = &[
    ("a", Optional), ("ă", Required), ("â", Required), ("e", Optional), ("ê", Optional),
    ("i", Optional), ("o", Optional), ("ô", Optional), ("ơ", Optional), ("u", Optional),
    ("ư", Optional), ("y", Never),
    ("ai", Never), ("ao", Never), ("au", Never), ("ay", Never), ("âu", Never), ("ây", Never),
    ("eo", Never), ("êu", Never), ("ia", Never), ("iê", Required), ("iu", Never),
    ("oa", Optional), ("oă", Required), ("oe", Optional), ("oi", Never), ("ôi", Never),
    ("ơi", Never), ("oo", Required), ("ôô", Required), ("ua", Never), ("uâ", Required), ("uê", Optional),
    ("ui", Never), ("uô", Required), ("uơ", Never), ("uy", Optional), ("ưa", Never),
    ("ưi", Never), ("ươ", Required), ("ưu", Never), ("yê", Required),
    ("iêu", Never), ("oai", Never), ("oao", Never), ("oay", Never), ("oeo", Never),
    ("uây", Never), ("uôi", Never), ("uya", Never), ("uyê", Required), ("uyu", Never),
    ("ươi", Never), ("ươu", Never), ("yêu", Never),
];

/// Vị trí âm chính `(đầu, cuối)` trong âm tiết; xử lý `qu` và `gi` là phụ âm đầu.
pub fn nucleus_range(l: &[char]) -> (usize, usize) {
    let mut ns = l.iter().position(|&c| text::is_vowel(c)).unwrap_or(l.len());
    let second = l.get(1).copied();
    if ns == 1 && l[0] == 'q' && second == Some('u') {
        ns = 2;
    } else if ns == 1 && l[0] == 'g' && second == Some('i') && l.get(2).is_some_and(|&c| text::is_vowel(c)) {
        ns = 2;
    }
    let ne = ns + l[ns..].iter().take_while(|&&c| text::is_vowel(c)).count();
    (ns, ne)
}

/// Kiểm tra chuỗi chữ (thường, không dấu thanh) với thanh `tone` có phải âm tiết hợp lệ.
pub fn check(l: &[char], tone: Tone, mode: Check) -> bool {
    let (ns, ne) = nucleus_range(l);
    if l[ne..].iter().any(|&c| text::is_vowel(c)) {
        return false;
    }
    let initial: String = l[..ns].iter().collect();
    let fin: String = l[ne..].iter().collect();
    let nucleus = &l[ns..ne];

    if !initial.is_empty() && !INITIALS.contains(&initial.as_str()) {
        return false;
    }
    if !fin.is_empty() && !FINALS.contains(&fin.as_str()) {
        return false;
    }
    if nucleus.is_empty() {
        return mode == Check::Prefix && fin.is_empty();
    }
    if initial == "q" {
        return false;
    }

    let compat = |typed: char, want: char| typed == want || (mode != Check::Strict && typed == text::base(want));
    let nucleus_ok = NUCLEI.iter().any(|(n, rule)| {
        // Sau qu, `y` là vần `uy` nên có âm cuối được (quýt, quỳnh như huýt, huỳnh).
        let rule = if initial == "qu" && *n == "y" { &Optional } else { rule };
        let n: Vec<char> = n.chars().collect();
        if nucleus.len() > n.len() || !nucleus.iter().zip(&n).all(|(&a, &b)| compat(a, b)) {
            return false;
        }
        let full = nucleus.len() == n.len();
        match (mode, fin.is_empty()) {
            (Check::Prefix, true) => true,
            (Check::Prefix, false) => full && *rule != Never,
            (_, true) => full && *rule != Required,
            (_, false) => full && *rule != Never,
        }
    });
    if !nucleus_ok {
        return false;
    }

    // Chính tả phụ âm đầu: k/gh/ngh đứng trước e ê i y; c/g/ng thì không.
    let first = text::base(nucleus[0]);
    let front = matches!(first, 'e' | 'i' | 'y');
    let spelling_ok = match initial.as_str() {
        "k" | "gh" | "ngh" => front,
        "c" | "ng" => !front,
        "g" => first != 'e' && first != 'y',
        "qu" => first != 'u',
        _ => true,
    };
    if !spelling_ok {
        return false;
    }

    if fin == "ch" || fin == "nh" {
        let last = nucleus[nucleus.len() - 1];
        let ok = if mode == Check::Strict {
            matches!(last, 'a' | 'ê' | 'i' | 'y')
        } else {
            matches!(text::base(last), 'a' | 'e' | 'i' | 'y')
        };
        if !ok {
            return false;
        }
    }

    // Âm cuối tắc (c ch p t) chỉ đi với thanh sắc, nặng.
    if matches!(fin.as_str(), "c" | "ch" | "p" | "t") {
        match tone {
            Tone::Grave | Tone::Hook | Tone::Tilde => return false,
            Tone::Level if mode == Check::Strict => return false,
            _ => {}
        }
    }
    true
}

/// Chỉ số (trong âm chính) của nguyên âm mang dấu thanh.
pub fn tone_position(nucleus: &[char], has_final: bool, style: ToneStyle) -> usize {
    if let Some(i) = nucleus.iter().rposition(|&c| text::base(c) != c) {
        return i;
    }
    match nucleus.len() {
        0 | 1 => 0,
        n if has_final => n - 1,
        3 => 1,
        _ => {
            let shifts = matches!(nucleus, ['o', 'a'] | ['o', 'e'] | ['u', 'y']);
            usize::from(style == ToneStyle::New && shifts)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ok(s: &str, mode: Check) -> bool {
        let (l, t) = text::split_syllable(s).unwrap();
        check(&l, t, mode)
    }

    #[test]
    fn strict_syllables() {
        for s in ["việt", "nguyệt", "quốc", "gì", "già", "người", "khuya", "ếch", "các", "nghìn", "thủy", "quýt", "quỳnh", "huỳnh"] {
            assert!(ok(s, Check::Strict), "{s}");
        }
        for s in ["tien", "cac", "kà", "gha", "ech", "bă", "q", "ce", "fa", "tẽt", "ưin", "ýt", "kýt"] {
            assert!(!ok(s, Check::Strict), "{s}");
        }
    }

    #[test]
    fn loose_syllables() {
        assert!(ok("tien", Check::Complete));
        assert!(ok("nguoi", Check::Complete));
        assert!(!ok("tie", Check::Complete));
        assert!(ok("tie", Check::Prefix));
        assert!(ok("ngh", Check::Prefix));
        assert!(!ok("ngh", Check::Complete));
    }

    #[test]
    fn tone_positions() {
        let p = |s: &str, f, st| tone_position(&s.chars().collect::<Vec<_>>(), f, st);
        assert_eq!(p("oa", false, ToneStyle::Old), 0);
        assert_eq!(p("oa", false, ToneStyle::New), 1);
        assert_eq!(p("oa", true, ToneStyle::Old), 1);
        assert_eq!(p("ươi", false, ToneStyle::Old), 1);
        assert_eq!(p("uyê", true, ToneStyle::Old), 2);
        assert_eq!(p("oai", false, ToneStyle::Old), 1);
    }
}

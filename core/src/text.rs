//! Bảng chữ cái tiếng Việt: dấu thanh, dấu phụ, chuẩn hoá.

/// Sáu thanh điệu tiếng Việt.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Hash)]
pub enum Tone {
    /// Thanh ngang
    #[default]
    Level,
    /// Sắc
    Acute,
    /// Huyền
    Grave,
    /// Hỏi
    Hook,
    /// Ngã
    Tilde,
    /// Nặng
    Dot,
}

const TONES: [Tone; 6] = [Tone::Level, Tone::Acute, Tone::Grave, Tone::Hook, Tone::Tilde, Tone::Dot];

impl Tone {
    /// Phím Telex của thanh: s f r x j.
    pub fn from_telex(key: char) -> Option<Tone> {
        match key.to_ascii_lowercase() {
            's' => Some(Tone::Acute),
            'f' => Some(Tone::Grave),
            'r' => Some(Tone::Hook),
            'x' => Some(Tone::Tilde),
            'j' => Some(Tone::Dot),
            _ => None,
        }
    }

    /// Ký hiệu hiện sau chữ đang gõ thay cho dấu thanh: ˊ ˋ ˀ ˜ ˙, thanh ngang đã chốt là ¯.
    pub fn mark(self) -> char {
        match self {
            Tone::Level => '¯',
            Tone::Acute => 'ˊ',
            Tone::Grave => 'ˋ',
            Tone::Hook => 'ˀ',
            Tone::Tilde => '˜',
            Tone::Dot => '˙',
        }
    }

    pub fn telex_key(self) -> Option<char> {
        match self {
            Tone::Level => None,
            Tone::Acute => Some('s'),
            Tone::Grave => Some('f'),
            Tone::Hook => Some('r'),
            Tone::Tilde => Some('x'),
            Tone::Dot => Some('j'),
        }
    }
}

const TONE_TABLE: [[char; 6]; 12] = [
    ['a', 'á', 'à', 'ả', 'ã', 'ạ'],
    ['ă', 'ắ', 'ằ', 'ẳ', 'ẵ', 'ặ'],
    ['â', 'ấ', 'ầ', 'ẩ', 'ẫ', 'ậ'],
    ['e', 'é', 'è', 'ẻ', 'ẽ', 'ẹ'],
    ['ê', 'ế', 'ề', 'ể', 'ễ', 'ệ'],
    ['i', 'í', 'ì', 'ỉ', 'ĩ', 'ị'],
    ['o', 'ó', 'ò', 'ỏ', 'õ', 'ọ'],
    ['ô', 'ố', 'ồ', 'ổ', 'ỗ', 'ộ'],
    ['ơ', 'ớ', 'ờ', 'ở', 'ỡ', 'ợ'],
    ['u', 'ú', 'ù', 'ủ', 'ũ', 'ụ'],
    ['ư', 'ứ', 'ừ', 'ử', 'ữ', 'ự'],
    ['y', 'ý', 'ỳ', 'ỷ', 'ỹ', 'ỵ'],
];

pub fn lower(c: char) -> char {
    c.to_lowercase().next().unwrap_or(c)
}

pub fn upper(c: char) -> char {
    c.to_uppercase().next().unwrap_or(c)
}

/// Tách dấu thanh khỏi một ký tự chữ thường: `ệ` → (`ê`, Nặng).
pub fn split_tone(c: char) -> (char, Tone) {
    for row in &TONE_TABLE {
        if let Some(i) = row.iter().position(|&x| x == c) {
            return (row[0], TONES[i]);
        }
    }
    (c, Tone::Level)
}

/// Đặt dấu thanh lên một nguyên âm chữ thường không dấu thanh.
pub fn with_tone(c: char, tone: Tone) -> char {
    TONE_TABLE.iter().find(|row| row[0] == c).map_or(c, |row| row[tone as usize])
}

/// Bỏ dấu phụ: â ă → a, ê → e, ô ơ → o, ư → u, đ → d.
pub fn base(c: char) -> char {
    match c {
        'ă' | 'â' => 'a',
        'ê' => 'e',
        'ô' | 'ơ' => 'o',
        'ư' => 'u',
        'đ' => 'd',
        _ => c,
    }
}

pub fn is_vowel(c: char) -> bool {
    matches!(c, 'a' | 'ă' | 'â' | 'e' | 'ê' | 'i' | 'o' | 'ô' | 'ơ' | 'u' | 'ư' | 'y')
}

/// Tách một âm tiết thành (chữ thường không dấu thanh, thanh).
/// Trả `None` nếu âm tiết có hơn một dấu thanh.
pub fn split_syllable(s: &str) -> Option<(Vec<char>, Tone)> {
    let mut tone = Tone::Level;
    let mut letters = Vec::with_capacity(s.len());
    for c in s.chars() {
        let (b, t) = split_tone(lower(c));
        if t != Tone::Level {
            if tone != Tone::Level {
                return None;
            }
            tone = t;
        }
        letters.push(b);
    }
    Some((letters, tone))
}

/// Chuẩn hoá để so khớp Telex rút gọn: chữ thường, bỏ dấu thanh và dấu phụ.
pub fn fold(s: &str) -> String {
    s.chars().map(|c| base(split_tone(lower(c)).0)).collect()
}

/// i và y cuối âm tiết, ngay sau phụ âm, là một (sỹ = sĩ, kỹ = kĩ, lý = lí): đổi y đó thành i để tra
/// cứu không phân biệt. `letters`: chữ cái một âm tiết, chữ thường, đã bỏ thanh. Không đụng tới y sau
/// nguyên âm (quý, tuy, may) hay y đứng một mình.
pub fn y_as_i(letters: &mut [char]) {
    if let [.., prev, last] = letters {
        if *last == 'y' && !is_vowel(*prev) {
            *last = 'i';
        }
    }
}

/// Khoá tra từ điển: [`fold`] rồi [`y_as_i`] từng âm tiết (`Sỹ` → `si`).
pub fn fold_key(s: &str) -> String {
    fold(s)
        .split(' ')
        .map(|w| {
            let mut letters: Vec<char> = w.chars().collect();
            y_as_i(&mut letters);
            letters.into_iter().collect::<String>()
        })
        .collect::<Vec<_>>()
        .join(" ")
}

/// Một âm tiết gõ ở ô tìm có đúng là âm tiết `reading` không. Chữ cái phải đúng như đã gõ, a khác â, d khác đ (ta không
/// ra tâm). Đã gõ thanh thì phải đúng thanh, không kể bỏ dấu kiểu cũ hay mới (hoà = hòa); không gõ dấu gì thì được mọi
/// thanh (ta → tá, tà). i/y như [`y_as_i`].
/// `partial`: âm tiết đang gõ dở, chỉ cần khớp từ đầu âm tiết (t, tâ → tâm); chưa gõ thanh thì âm dài hơn có thanh
/// gì cũng được (tâ → tấn), vì thanh thường gõ sau cùng.
fn same_syllable(query: &str, reading: &str, partial: bool) -> bool {
    let sound = |s: &str| {
        split_syllable(s).map(|(mut letters, tone)| {
            y_as_i(&mut letters);
            (letters, tone)
        })
    };
    let (Some((q, q_tone)), Some((r, r_tone))) = (sound(query), sound(reading)) else {
        return false;
    };
    let whole = q.len() == r.len();
    if !whole && !partial {
        return false;
    }
    let marked = q.iter().any(|&c| base(c) != c);
    r.starts_with(&q) && (q_tone == r_tone || (q_tone == Tone::Level && (!whole || !marked)))
}

/// Âm đọc `reading` có chứa các âm tiết liền nhau `query` không ([`same_syllable`]): các âm tiết đã gõ xong phải khớp
/// nguyên âm tiết, âm tiết cuối đang gõ khớp từ đầu âm tiết, nên `hoà` ra hoàng nhưng không ra thoại.
pub fn reading_has(reading: &str, query: &str) -> bool {
    let q: Vec<&str> = query.split_whitespace().collect();
    let r: Vec<&str> = reading.split_whitespace().collect();
    !q.is_empty()
        && r.windows(q.len())
            .any(|w| w.iter().zip(&q).enumerate().all(|(i, (r, s))| same_syllable(s, r, i + 1 == q.len())))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reading_search_matches_whole_syllables() {
        // Có dấu: đúng âm, không kể chỗ đặt dấu, hoa thường.
        assert!(reading_has("hoà bình", "hoà"));
        assert!(reading_has("điều hoà", "hòa"));
        assert!(reading_has("hoà bình", "HOÀ"));
        for other in ["danh hoạ", "đàm thoại", "đào hoa", "đào thoát"] {
            assert!(!reading_has(other, "hoà"), "{other}");
        }
        // Không gõ dấu: được mọi thanh, nhưng chữ cái đúng như đã gõ và từ đầu âm tiết.
        assert!(reading_has("đào hoa", "hoa"));
        assert!(reading_has("danh hoạ", "hoa"));
        assert!(!reading_has("đàm thoại", "hoa"));
        assert!(!reading_has("đa", "da"));
        assert!(!reading_has("đào hoa", "dao hoa"));
        // Âm tiết cuối đang gõ dở khớp từ đầu âm tiết, giữ đúng dấu đã gõ.
        for typing in ["t", "tâ", "tâm"] {
            assert!(reading_has("tâm", typing), "{typing}");
        }
        for found in ["ta", "tá", "tam", "tám", "tan"] {
            assert!(reading_has(found, "ta"), "{found}");
        }
        for other in ["tâm", "tấn", "tầm", "tăng"] {
            assert!(!reading_has(other, "ta"), "{other}");
        }
        assert!(reading_has("tấn", "tâ"));
        assert!(!reading_has("tấn", "tân"));
        assert!(!reading_has("tám", "tâ"));
        assert!(!reading_has("tâm", "tá"));
        assert!(!reading_has("tâm", "tâmm"));
        assert!(!reading_has("nhân tâm", "â"));
        assert!(reading_has("đàng hoàng", "hoà"));
        assert!(reading_has("đàng hoàng", "ho"));
        assert!(reading_has("đào hoa", "đào ho"));
        assert!(!reading_has("đào hoa", "đà hoa"));
        assert!(!reading_has("da", "đa"));
        // Nhiều âm tiết liền nhau; i/y như nhau.
        assert!(reading_has("đào hoa", "đào hoa"));
        assert!(reading_has("đào hoa", "đào  hoa"));
        assert!(!reading_has("hoa đào", "đào hoa"));
        assert!(reading_has("kỹ sư", "kĩ"));
    }

    #[test]
    fn tone_roundtrip() {
        assert_eq!(split_tone('ệ'), ('ê', Tone::Dot));
        assert_eq!(with_tone('ư', Tone::Tilde), 'ữ');
        assert_eq!(split_tone('b'), ('b', Tone::Level));
    }

    #[test]
    fn folding() {
        assert_eq!(fold("Người Việt"), "nguoi viet");
        assert_eq!(fold("đường"), "duong");
    }

    #[test]
    fn rejects_double_tone() {
        assert!(split_syllable("áà").is_none());
        assert_eq!(split_syllable("Nôm"), Some((vec!['n', 'ô', 'm'], Tone::Level)));
    }
}

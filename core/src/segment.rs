//! Tách chuỗi phím gõ liền thành các âm tiết (`chunom` → `chu` + `nom`).
//!
//! Một chuỗi có thể tách nhiều cách (`chun` + `om`); từ điển sẽ quyết định cách nào có nghĩa.
//! Dấu `'` ép ranh giới âm tiết.

use crate::syllable::{self, Check, ToneStyle};
use crate::telex;
use crate::text;

/// Số phím tối đa của một âm tiết (vd. `nghieengx` là 9).
const MAX_SYLLABLE_KEYS: usize = 12;
/// Giới hạn số cách tách để giữ thời gian tra cứu ổn định.
const MAX_PATHS: usize = 256;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Segment {
    /// Âm tiết sau khi áp Telex, chữ thường (có thể thiếu dấu ở chế độ rút gọn).
    pub syllable: String,
    /// Vị trí (tính theo ký tự) ngay sau âm tiết trong chuỗi phím, đã bỏ qua dấu `'`.
    pub end: usize,
    /// Đã gõ `z`: chỉ khớp thanh ngang.
    pub level: bool,
}

/// Mọi cách tách một **phần đầu** của chuỗi phím thành các âm tiết trọn vẹn.
/// `dz`: tuỳ chọn `d` ra đ, `z` ra d của [`telex::compose_with`].
pub fn segment(keys: &str, style: ToneStyle, dz: bool) -> Vec<Vec<Segment>> {
    let keys: Vec<char> = keys.chars().collect();
    let start = keys.iter().take_while(|&&c| c == '\'').count();
    let spans = telex::word_spans(&keys.iter().collect::<String>(), dz).unwrap_or_default();
    // Dấu gõ ở đuôi thuộc về cả chữ: không tách ngang chữ đó (`nguonww` không là `ngu` + …).
    let mut locked: Vec<(usize, usize)> = spans.iter().filter(|s| s.2).map(|&(s, e, _)| (s, e)).collect();
    // Chữ cuối đang gõ cũng không gợi ý theo phần đầu: gõ `quan` thì thôi gợi ý `qua`.
    if let Some(&(s, e, _)) = spans.last() {
        locked.push((s, e));
    }
    let mut paths = Vec::new();
    walk(&keys, start, (style, dz, &locked, false), &mut Vec::new(), &mut paths);
    paths
}

/// Như [`segment`] cho bàn phím Hán Nôm kiểu Telex: mỗi âm tiết ghép theo Telex tự do của
/// [`telex::compose`] (`aa` → â, dấu gõ đâu trong chữ cũng được: `hasn` là hán), không có quy tắc dấu kết thúc chữ.
/// Chữ của [`telex::telex_words`] đã là âm tiết trọn có dấu thanh thì giữ trọn như khung gõ hiện: gõ `loanfs`
/// là đổi dấu thành loán (như Unikey), không tách thành `loanf` + `s…` hay `lo` + `anfs`. Riêng chữ cuối có thể
/// là chữ trước cộng chữ sau gõ dở (`chuwxn`: chữ + n…), nên còn được tách ngay sau phím dấu thanh, trừ khi
/// phím kế cũng là phím dấu (đổi dấu).
pub fn segment_telex(keys: &str, style: ToneStyle) -> Vec<Vec<Segment>> {
    let chars: Vec<char> = keys.chars().collect();
    let is_tone = |c: char| text::Tone::from_telex(c).is_some() || c.eq_ignore_ascii_case(&'z');
    let words = telex::telex_words(keys);
    let mut locked = Vec::new();
    let mut pos = 0;
    for (i, w) in words.iter().enumerate() {
        let len = w.chars().count();
        let syl: String = telex::compose(w, style).chars().map(text::lower).collect();
        let toned = w != "'" &&
            text::split_syllable(&syl).is_some_and(|(l, t)| t != text::Tone::Level && syllable::check(&l, t, Check::Complete));
        if toned {
            let mut from = pos;
            if i == words.len() - 1 {
                for cut in pos + 1..pos + len {
                    if is_tone(chars[cut - 1]) && !is_tone(chars[cut]) {
                        locked.push((from, cut));
                        from = cut;
                    }
                }
            }
            locked.push((from, pos + len));
        }
        pos += len;
    }
    let keys = chars;
    let start = keys.iter().take_while(|&&c| c == '\'').count();
    let mut paths = Vec::new();
    walk(&keys, start, (style, false, &locked, true), &mut Vec::new(), &mut paths);
    paths
}

/// Kiểu đặt dấu, tuỳ chọn `dz`, các đoạn chữ không được tách ngang, và kiểu Telex tự do.
type Opts<'a> = (ToneStyle, bool, &'a [(usize, usize)], bool);

fn walk(keys: &[char], pos: usize, opts: Opts, path: &mut Vec<Segment>, out: &mut Vec<Vec<Segment>>) {
    for len in 1..=MAX_SYLLABLE_KEYS.min(keys.len() - pos) {
        if out.len() >= MAX_PATHS {
            return;
        }
        let raw = &keys[pos..pos + len];
        if raw.contains(&'\'') {
            break;
        }
        // Dấu gõ ở đuôi thuộc về cả chữ: không dừng giữa chữ đó.
        if opts.2.iter().any(|&(s, e)| s < pos + len && pos + len < e) {
            continue;
        }
        // Phím dấu bị từ chối ngay sau chữ đã gõ dấu là chữ mới, không đổi dấu chữ trước: không ghép qua
        // (`hocjs` là học + s…, không là hóc). Chữ cái thường thì vẫn ghép được (`nawm` → năm).
        let is_mark = |c: char| matches!(c.to_ascii_lowercase(), 'w' | 'q') || text::Tone::from_telex(c).is_some();
        if opts.2.iter().any(|&(_, e)| pos < e && e < pos + len && is_mark(keys[e])) {
            break;
        }
        let raw: String = raw.iter().collect();
        let (syl, level) = if opts.3 { (telex::compose(&raw, opts.0), false) } else { telex::compose_marked(&raw, opts.0, opts.1) };
        let syl: String = syl.chars().map(text::lower).collect();
        let valid = text::split_syllable(&syl).is_some_and(|(l, t)| syllable::check(&l, t, Check::Complete));
        if !valid {
            continue;
        }
        let mut end = pos + len;
        while keys.get(end) == Some(&'\'') {
            end += 1;
        }
        path.push(Segment { syllable: syl, end, level });
        // Không dừng giữa một đoạn người dùng đã chốt bằng `'` (`chun'om` không gợi ý `chu`).
        let at_boundary = keys[end - 1] == '\'' || !keys[end..].contains(&'\'');
        if at_boundary {
            out.push(path.clone());
        }
        walk(keys, end, opts, path, out);
        path.pop();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn syllables(keys: &str) -> Vec<Vec<String>> {
        segment(keys, ToneStyle::Old, false)
            .into_iter()
            .map(|p| p.into_iter().map(|s| s.syllable).collect())
            .collect()
    }

    #[test]
    fn splits_running_syllables() {
        let paths = syllables("chunom");
        assert!(paths.contains(&vec!["chu".into(), "nom".into()]));
        assert!(paths.contains(&vec!["chu".into()]));
    }

    #[test]
    fn telex_inside_segments() {
        let paths = syllables("chuwxnomq");
        assert!(paths.contains(&vec!["chữ".into(), "nôm".into()]));
    }

    #[test]
    fn apostrophe_forces_boundary() {
        let paths = syllables("chun'om");
        assert!(paths.iter().all(|p| p[0] != "chu"));
        let p = segment("chun'om", ToneStyle::Old, false);
        let full = p.iter().find(|p| p.last().unwrap().end == 7).unwrap();
        assert_eq!(full[0].end, 5);
        assert_eq!(full[1].syllable, "om");
    }

    #[test]
    fn marked_word_not_split() {
        // Dấu ở đuôi thuộc về cả chữ: `nguonww` không tách thành `ngu` + …
        assert!(syllables("nguonww").iter().all(|p| p[0] != "ngu"));
        assert!(syllables("nguonww").contains(&vec!["nguơn".into()]));
    }

    #[test]
    fn telex_retone_not_split() {
        // Telex: gõ dấu khác sau chữ đã có dấu là đổi dấu (loàn → loán), không tách chữ đó.
        let paths: Vec<Vec<String>> = segment_telex("lawngloanfs", ToneStyle::Old)
            .into_iter()
            .map(|p| p.into_iter().map(|s| s.syllable).collect())
            .collect();
        assert!(paths.contains(&vec!["lăng".into(), "loán".into()]));
        assert!(paths.iter().all(|p| !p.contains(&"loàn".to_string()) && !p.contains(&"lo".to_string())));
    }

    #[test]
    fn last_word_not_split() {
        // Gõ tiếp thành chữ dài hơn thì thôi gợi ý phần đầu.
        assert!(syllables("quan").iter().all(|p| p[0] != "qua"));
        assert!(syllables("quan").contains(&vec!["quan".into()]));
        // Các chữ trước vẫn tách được.
        assert!(syllables("chunom").contains(&vec!["chu".into(), "nom".into()]));
    }
}

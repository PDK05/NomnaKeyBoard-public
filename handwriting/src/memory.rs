//! Chữ viết tay người dùng đã dạy: mỗi mẫu là nét viết + chữ đúng + đặc trưng của mô hình.
//!
//! Lúc nhận dạng, chữ có mẫu giống nét đang viết được cộng điểm, kể cả chữ mô hình không có
//! (dạy 腀 khi mô hình chưa biết). Đặc trưng của hai lần viết cùng chữ thường có cosine ≥ 0.82,
//! khác chữ ≤ 0.5 (đo trên chữ tổng hợp), nên điểm cộng tăng dần từ [`SIM_LO`] tới [`SIM_HI`].
//!
//! Lưu dạng văn bản (mỗi mẫu một dòng `chữ<TAB>đặc trưng hex<TAB>nét`), nét theo toạ độ ô
//! [`CELL`]×[`CELL`] như `handwriting/examples/cases.txt`, nên tệp xuất dùng được để huấn luyện lại.
//! Đổi mô hình thì đặc trưng được tính lại từ nét.

use std::collections::HashMap;
use std::fmt::Write;

use crate::{Analysis, Recognizer, Stroke};

/// Cạnh ô viết khi lưu nét.
pub const CELL: f32 = 300.0;
/// Độ giống bắt đầu được cộng điểm.
pub const SIM_LO: f32 = 0.6;
/// Độ giống được cộng đủ 1 điểm (hơn mọi xác suất của mô hình).
pub const SIM_HI: f32 = 0.85;
/// Số mẫu tối đa cho một chữ; thêm nữa thì bỏ mẫu cũ nhất.
pub const MAX_PER_TEXT: usize = 5;
pub const MAX_SAMPLES: usize = 1000;

const HEADER: &str = "# hannom-handwriting 1";

struct Sample {
    text: String,
    strokes: Vec<Stroke>,
    features: Vec<f32>,
}

#[derive(Default)]
pub struct Memory {
    samples: Vec<Sample>,
}

impl Memory {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn len(&self) -> usize {
        self.samples.len()
    }

    pub fn is_empty(&self) -> bool {
        self.samples.is_empty()
    }

    pub fn clear(&mut self) {
        self.samples.clear();
    }

    /// Dạy chữ `text` cho nét `strokes` (ô viết cạnh `canvas`). Trả `false` nếu không có nét hoặc chữ rỗng.
    pub fn add(&mut self, r: &Recognizer, text: &str, strokes: &[Stroke], canvas: f32) -> bool {
        let text = text.trim();
        if text.is_empty() {
            return false;
        }
        let k = CELL / canvas.max(1e-6);
        let strokes: Vec<Stroke> =
            strokes.iter().filter(|s| !s.is_empty()).map(|s| s.iter().map(|&(x, y)| (x * k, y * k)).collect()).collect();
        let Some(a) = r.analyze(&strokes, CELL, 0) else { return false };
        self.samples.push(Sample { text: text.to_string(), strokes, features: a.features });
        let same: Vec<usize> = (0..self.samples.len()).filter(|&i| self.samples[i].text == text).collect();
        if same.len() > MAX_PER_TEXT {
            self.samples.remove(same[0]);
        }
        if self.samples.len() > MAX_SAMPLES {
            self.samples.remove(0);
        }
        true
    }

    /// Xếp lại gợi ý của mô hình theo các mẫu đã dạy: điểm = xác suất + điểm cộng độ giống.
    pub fn rank(&self, a: &Analysis, limit: usize) -> Vec<String> {
        let mut order: Vec<String> = Vec::new();
        let mut score: HashMap<String, f32> = HashMap::new();
        for &(c, p) in &a.top {
            order.push(c.to_string());
            score.insert(c.to_string(), p);
        }
        let mut bonus: HashMap<&str, f32> = HashMap::new();
        for s in &self.samples {
            let sim: f32 = s.features.iter().zip(&a.features).map(|(x, y)| x * y).sum();
            let b = ((sim - SIM_LO) / (SIM_HI - SIM_LO)).clamp(0.0, 1.0);
            if b > 0.0 {
                let e = bonus.entry(&s.text).or_default();
                *e = e.max(b);
            }
        }
        for (text, b) in bonus {
            if !score.contains_key(text) {
                order.push(text.to_string());
            }
            *score.entry(text.to_string()).or_default() += b;
        }
        // Sắp ổn định: cùng điểm thì giữ thứ tự của mô hình.
        order.sort_by(|x, y| score[y].total_cmp(&score[x]));
        order.truncate(limit);
        order
    }

    pub fn to_text(&self, r: &Recognizer) -> String {
        let mut out = format!("{HEADER} model={:016x}\n", r.id());
        for s in &self.samples {
            out.push_str(&s.text);
            out.push('\t');
            for v in &s.features {
                write!(out, "{:02x}", (v.clamp(0.0, 1.0) * 255.0).round() as u8).unwrap();
            }
            out.push('\t');
            let strokes: Vec<String> = s
                .strokes
                .iter()
                .map(|st| st.iter().map(|(x, y)| format!("{x:.1},{y:.1}")).collect::<Vec<_>>().join(" "))
                .collect();
            out.push_str(&strokes.join(";"));
            out.push('\n');
        }
        out
    }

    /// Đọc tệp do `to_text` ghi; dòng hỏng bị bỏ. Tệp của mô hình khác thì tính lại đặc trưng từ nét.
    pub fn from_text(r: &Recognizer, content: &str) -> Memory {
        let mut lines = content.lines();
        let same_model = lines
            .next()
            .and_then(|h| h.strip_prefix(HEADER))
            .and_then(|h| h.trim().strip_prefix("model="))
            .and_then(|id| u64::from_str_radix(id, 16).ok())
            == Some(r.id());
        let mut m = Memory::new();
        for line in lines {
            let mut f = line.split('\t');
            let (Some(text), Some(hex), Some(raw)) = (f.next(), f.next(), f.next()) else { continue };
            let Some(strokes) = parse_strokes(raw) else { continue };
            let features = if same_model { parse_features(hex) } else { None };
            match features {
                Some(features) => m.samples.push(Sample { text: text.to_string(), strokes, features }),
                None => {
                    m.add(r, text, &strokes, CELL);
                }
            }
        }
        m
    }
}

fn parse_strokes(raw: &str) -> Option<Vec<Stroke>> {
    raw.split(';')
        .map(|s| {
            s.split(' ')
                .map(|p| {
                    let (x, y) = p.split_once(',')?;
                    Some((x.parse().ok()?, y.parse().ok()?))
                })
                .collect()
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn recognizer() -> Recognizer {
        let fixture: &[u8] = include_bytes!("../tests/fixture.bin");
        let len = u32::from_le_bytes(fixture[..4].try_into().unwrap()) as usize;
        Recognizer::from_bytes(&fixture[4..4 + len]).unwrap()
    }

    fn box_strokes(cell: f32) -> Vec<Stroke> {
        let k = cell / 100.0;
        vec![
            vec![(10.0 * k, 10.0 * k), (90.0 * k, 10.0 * k), (90.0 * k, 90.0 * k)],
            vec![(10.0 * k, 10.0 * k), (10.0 * k, 90.0 * k), (90.0 * k, 90.0 * k)],
            vec![(50.0 * k, 20.0 * k), (50.0 * k, 80.0 * k)],
        ]
    }

    #[test]
    fn taught_char_ranks_first_even_if_unknown_to_model() {
        let r = recognizer();
        let mut m = Memory::new();
        assert!(m.add(&r, "腀", &box_strokes(200.0), 200.0));
        assert!(!m.add(&r, " ", &box_strokes(200.0), 200.0));
        assert!(!m.add(&r, "腀", &[vec![]], 200.0));
        // Cùng hình, ô viết khác cỡ: vẫn khớp.
        let a = r.analyze(&box_strokes(300.0), 300.0, 5).unwrap();
        let ranked = m.rank(&a, 5);
        assert_eq!(ranked[0], "腀");
        assert_eq!(ranked.len(), 5);
        // Chưa dạy gì: đúng thứ tự của mô hình.
        let plain: Vec<String> = a.top.iter().map(|p| p.0.to_string()).collect();
        assert_eq!(Memory::new().rank(&a, 5), plain);
    }

    #[test]
    fn keeps_newest_samples_per_char() {
        let r = recognizer();
        let mut m = Memory::new();
        for _ in 0..MAX_PER_TEXT + 2 {
            m.add(&r, "腀", &box_strokes(300.0), 300.0);
        }
        m.add(&r, "喃", &box_strokes(300.0), 300.0);
        assert_eq!(m.len(), MAX_PER_TEXT + 1);
    }

    #[test]
    fn text_round_trip_and_model_change() {
        let r = recognizer();
        let mut m = Memory::new();
        m.add(&r, "腀", &box_strokes(150.0), 150.0);
        let text = m.to_text(&r);
        assert!(text.starts_with(HEADER));
        let line = text.lines().nth(1).unwrap();
        assert!(line.starts_with("腀\t"));
        // Nét quy về ô 300: (15, 15) trong ô 150 thành (30, 30).
        assert!(line.ends_with("\t30.0,30.0 270.0,30.0 270.0,270.0;30.0,30.0 30.0,270.0 270.0,270.0;150.0,60.0 150.0,240.0"));

        let a = r.analyze(&box_strokes(300.0), 300.0, 5).unwrap();
        let back = Memory::from_text(&r, &format!("{text}hỏng\n"));
        assert_eq!(back.len(), 1);
        assert_eq!(back.rank(&a, 1), ["腀"]);
        // Tệp của mô hình khác: đặc trưng tính lại từ nét.
        let other = text.replacen("model=", "model=f", 1);
        assert_eq!(Memory::from_text(&r, &other).rank(&a, 1), ["腀"]);
        assert!(Memory::from_text(&r, "").is_empty());
    }
}

fn parse_features(hex: &str) -> Option<Vec<f32>> {
    if hex.is_empty() || hex.len() % 2 != 0 {
        return None;
    }
    let mut v: Vec<f32> = (0..hex.len())
        .step_by(2)
        .map(|i| u8::from_str_radix(hex.get(i..i + 2)?, 16).ok().map(|b| b as f32 / 255.0))
        .collect::<Option<_>>()?;
    let norm = v.iter().map(|x| x * x).sum::<f32>().sqrt().max(1e-12);
    v.iter_mut().for_each(|x| *x /= norm);
    Some(v)
}

//! Viết nhiều chữ liền một dòng (trái sang phải): tách thành từng chữ rồi nhận dạng từng chữ.
//!
//! Nét chồng nhau theo chiều ngang gộp thành một **cột** (không tách được giữa chừng). Một chữ là một
//! hoặc vài cột liền nhau (川 là ba cột). Cách tách chọn bằng quy hoạch động: mỗi chữ tốn
//! `−ln p` (xác suất chữ đầu của mô hình) + một khoản cố định cho mỗi chữ (để không tách vụn chữ
//! nhiều cột) + phạt khi chữ rộng quá chiều cao dòng hoặc có khe hở lớn bên trong.

use std::collections::HashMap;
use std::sync::Mutex;

use crate::{Analysis, Recognizer, Stroke};

/// Số cột tối đa của một chữ.
const MAX_COLUMNS: usize = 4;
/// Chữ rộng quá chừng này lần chiều cao dòng thì không xét.
const MAX_WIDTH: f32 = 1.8;
/// Khoản tốn cố định của mỗi chữ.
const SPLIT_COST: f32 = 0.8;
/// Rộng quá chừng này lần chiều cao dòng bắt đầu bị phạt.
const WIDE_FROM: f32 = 1.2;
const WIDE_COST: f32 = 4.0;
/// Khe hở bên trong chữ rộng quá chừng này lần chiều cao dòng bắt đầu bị phạt.
const GAP_FROM: f32 = 0.3;
const GAP_COST: f32 = 3.0;
/// Số kết quả nhớ sẵn (mỗi lần nhấc bút chỉ chữ cuối thay đổi, các chữ trước dùng lại).
const CACHE: usize = 256;

/// Một chữ trong dòng: chỉ số các nét (theo thứ tự đưa vào) và kết quả nhận dạng.
pub struct Segment {
    pub strokes: Vec<usize>,
    pub analysis: Analysis,
}

#[derive(Default)]
pub(crate) struct Cache(Mutex<HashMap<u64, (Vec<f32>, Vec<(char, f32)>)>>);

struct Column {
    strokes: Vec<usize>,
    x0: f32,
    x1: f32,
}

fn bounds(s: &Stroke) -> (f32, f32, f32, f32) {
    s.iter().fold((f32::MAX, f32::MAX, f32::MIN, f32::MIN), |(a, b, c, d), &(x, y)| (a.min(x), b.min(y), c.max(x), d.max(y)))
}

/// Gộp các nét chồng nhau theo chiều ngang thành cột, từ trái sang phải.
fn columns(strokes: &[Stroke]) -> Vec<Column> {
    let mut order: Vec<(usize, f32, f32)> = strokes
        .iter()
        .enumerate()
        .filter(|(_, s)| !s.is_empty())
        .map(|(i, s)| {
            let (x0, _, x1, _) = bounds(s);
            (i, x0, x1)
        })
        .collect();
    order.sort_by(|a, b| a.1.total_cmp(&b.1));
    let mut out: Vec<Column> = Vec::new();
    for (i, x0, x1) in order {
        match out.last_mut() {
            Some(c) if x0 <= c.x1 => {
                c.strokes.push(i);
                c.x1 = c.x1.max(x1);
            }
            _ => out.push(Column { strokes: vec![i], x0, x1 }),
        }
    }
    out
}

impl Recognizer {
    /// Nhận dạng một dòng chữ viết liền: các chữ từ trái sang phải, mỗi chữ tối đa `limit` gợi ý.
    /// `canvas` là chiều cao vùng viết. Không có nét thì trả rỗng.
    pub fn recognize_line(&self, strokes: &[Stroke], canvas: f32, limit: usize) -> Vec<Segment> {
        self.recognize_line_alternatives(strokes, canvas, limit).0
    }

    /// Như [`recognize_line`](Self::recognize_line), kèm cách tách tốt nhất **có số chữ khác** cách tách chính
    /// (viết 𠊛 mà nhận là một chữ thì cách kia là 人 + 㝵, và ngược lại), nếu tách khác được.
    pub fn recognize_line_alternatives(
        &self,
        strokes: &[Stroke],
        canvas: f32,
        limit: usize,
    ) -> (Vec<Segment>, Option<Vec<Segment>>) {
        let cols = columns(strokes);
        if cols.is_empty() {
            return (Vec::new(), None);
        }
        let (y0, y1) = strokes.iter().filter(|s| !s.is_empty()).map(bounds).fold((f32::MAX, f32::MIN), |(a, b), s| (a.min(s.1), b.max(s.3)));
        // Chiều cao dòng: chữ thấp (一, 二) không làm mọi thứ trông "rộng".
        let height = (y1 - y0).max(0.25 * canvas).max(1e-3);
        let n = cols.len();
        // best[j][k]: (tốn ít nhất để tách cột 0..j thành k chữ, chỗ bắt đầu chữ cuối).
        let mut best: Vec<Vec<(f32, usize)>> = vec![vec![(f32::MAX, 0); n + 1]; n + 1];
        best[0][0].0 = 0.0;
        let mut found: HashMap<(usize, usize), (Vec<usize>, Analysis)> = HashMap::new();
        for j in 1..=n {
            for i in j.saturating_sub(MAX_COLUMNS)..j {
                if best[i].iter().all(|b| b.0 == f32::MAX) {
                    continue;
                }
                let width = (cols[j - 1].x1 - cols[i].x0) / height;
                if j - i > 1 && width > MAX_WIDTH {
                    continue;
                }
                let gap = (i + 1..j).map(|k| cols[k].x0 - cols[k - 1].x1).fold(0f32, f32::max) / height;
                let mut ids: Vec<usize> = cols[i..j].iter().flat_map(|c| c.strokes.iter().copied()).collect();
                ids.sort_unstable();
                let part: Vec<Stroke> = ids.iter().map(|&k| strokes[k].clone()).collect();
                let Some(a) = self.analyze_cached(&part, canvas, limit) else { continue };
                let p = a.top.first().map_or(0.0, |t| t.1).max(1e-6);
                let cost = -p.ln()
                    + SPLIT_COST
                    + WIDE_COST * (width - WIDE_FROM).max(0.0)
                    + GAP_COST * (gap - GAP_FROM).max(0.0);
                for k in 0..n {
                    if best[i][k].0 != f32::MAX && best[i][k].0 + cost < best[j][k + 1].0 {
                        best[j][k + 1] = (best[i][k].0 + cost, i);
                    }
                }
                found.insert((i, j), (ids, a));
            }
        }
        let by_cost = |a: &usize, b: &usize| best[n][*a].0.total_cmp(&best[n][*b].0);
        let reachable: Vec<usize> = (1..=n).filter(|&k| best[n][k].0 != f32::MAX).collect();
        let Some(main) = reachable.iter().copied().min_by(by_cost) else { return (Vec::new(), None) };
        let alt = reachable.iter().copied().filter(|&k| k != main).min_by(by_cost);
        let path = |mut k: usize| {
            let mut out = Vec::new();
            let mut j = n;
            while j > 0 {
                let i = best[j][k].1;
                let (ids, analysis) = found.get(&(i, j)).expect("đoạn đã tính");
                out.push(Segment { strokes: ids.clone(), analysis: Analysis { features: analysis.features.clone(), top: analysis.top.clone() } });
                j = i;
                k -= 1;
            }
            out.reverse();
            out
        };
        (path(main), alt.map(path))
    }

    /// Như `analyze`, nhớ kết quả theo nét (cùng nét, cùng `canvas`, cùng `limit` thì không chạy lại mô hình).
    fn analyze_cached(&self, strokes: &[Stroke], canvas: f32, limit: usize) -> Option<Analysis> {
        let mut h = 0xcbf29ce484222325u64;
        let mut mix = |v: u32| h = (h ^ v as u64).wrapping_mul(0x100000001b3);
        mix(canvas.to_bits());
        mix(limit as u32);
        for s in strokes {
            mix(u32::MAX);
            for &(x, y) in s {
                mix(x.to_bits());
                mix(y.to_bits());
            }
        }
        if let Some((features, top)) = self.cache.0.lock().unwrap().get(&h) {
            return Some(Analysis { features: features.clone(), top: top.clone() });
        }
        let a = self.analyze(strokes, canvas, limit)?;
        let mut cache = self.cache.0.lock().unwrap();
        if cache.len() >= CACHE {
            cache.clear();
        }
        cache.insert(h, (a.features.clone(), a.top.clone()));
        Some(a)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn line(xs: &[(f32, f32)]) -> Vec<Stroke> {
        xs.iter().map(|&(a, b)| vec![(a, 0.0), (b, 100.0)]).collect()
    }

    #[test]
    fn columns_merge_overlaps() {
        // Hai nét chồng nhau (0–40, 30–60) thành một cột; nét 100–120 riêng; thứ tự đưa vào không quan trọng.
        let c = columns(&line(&[(100.0, 120.0), (0.0, 40.0), (30.0, 60.0)]));
        assert_eq!(c.len(), 2);
        assert_eq!(c[0].strokes, vec![1, 2]);
        assert_eq!(c[1].strokes, vec![0]);
    }

    #[test]
    fn empty_strokes_ignored() {
        assert!(columns(&[Vec::new()]).is_empty());
    }

    #[test]
    fn alternative_has_other_count() {
        // Hai cột: cách tách chính và cách kia khác số chữ (một chữ ↔ hai chữ), cùng phủ mọi nét.
        // fixture.bin: độ dài mô hình (u32) + mô hình nhỏ ngẫu nhiên + dữ liệu thử (xem lib.rs).
        let fixture: &[u8] = include_bytes!("../tests/fixture.bin");
        let len = u32::from_le_bytes(fixture[..4].try_into().unwrap()) as usize;
        let r = Recognizer::from_bytes(&fixture[4..4 + len]).unwrap();
        let strokes = line(&[(0.0, 10.0), (60.0, 70.0)]);
        let (main, alt) = r.recognize_line_alternatives(&strokes, 100.0, 3);
        let alt = alt.expect("tách khác được");
        assert_ne!(main.len(), alt.len());
        for segs in [&main, &alt] {
            let mut ids: Vec<usize> = segs.iter().flat_map(|s| s.strokes.clone()).collect();
            ids.sort_unstable();
            assert_eq!(ids, vec![0, 1]);
        }
        // Một cột thì không có cách tách khác.
        assert!(r.recognize_line_alternatives(&line(&[(0.0, 10.0)]), 100.0, 3).1.is_none());
    }
}

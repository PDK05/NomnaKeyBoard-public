//! Nhận dạng chữ Hán Nôm viết tay.
//!
//! Nhận dạng theo **ảnh** của cả chữ chứ không theo chuỗi nét, nên không phụ thuộc thứ tự nét,
//! chịu được thêm nét sau (viết 目 rồi thêm 氵 → 泪), thiếu/gộp chấm và nét nối kiểu hành thư.
//! Mỗi lần nhấc bút, giao diện gọi lại `recognize` với toàn bộ nét của chữ đang viết, hoặc
//! `recognize_line` khi viết nhiều chữ liền một dòng.
//!
//! Mô hình (`hw.bin`) được huấn luyện bằng `tools/handwriting/`. Chữ người dùng tự dạy
//! (kể cả chữ mô hình không có) nằm trong [`Memory`].

mod line;
mod memory;
mod net;
pub mod render;

pub use line::Segment;
pub use memory::Memory;
pub use net::FormatError;
pub use render::Stroke;

pub struct Recognizer {
    net: net::Net,
    id: u64,
    cache: line::Cache,
}

/// Kết quả nhận dạng kèm đặc trưng (đã chuẩn hoá độ dài 1) để so với chữ đã dạy.
pub struct Analysis {
    pub features: Vec<f32>,
    pub top: Vec<(char, f32)>,
}

impl Recognizer {
    pub fn from_bytes(data: &[u8]) -> Result<Recognizer, FormatError> {
        let net = net::Net::from_bytes(data)?;
        if net.size != render::SIZE {
            return Err(FormatError("cỡ ảnh vào không khớp"));
        }
        // FNV-1a: đổi mô hình thì đặc trưng của chữ đã dạy phải tính lại.
        let id = data.iter().fold(0xcbf29ce484222325u64, |h, &b| (h ^ b as u64).wrapping_mul(0x100000001b3));
        Ok(Recognizer { net, id, cache: Default::default() })
    }

    /// Mã nhận diện mô hình (băm của tệp).
    pub fn id(&self) -> u64 {
        self.id
    }

    /// Như `recognize` nhưng kèm đặc trưng; `None` nếu chưa có nét nào.
    pub fn analyze(&self, strokes: &[Stroke], canvas: f32, limit: usize) -> Option<Analysis> {
        if strokes.iter().all(|s| s.is_empty()) {
            return None;
        }
        let (mut features, logits) = self.net.run(&render::render(strokes, canvas));
        let norm = features.iter().map(|v| v * v).sum::<f32>().sqrt().max(1e-12);
        features.iter_mut().for_each(|v| *v /= norm);
        Some(Analysis { features, top: self.top_k(&logits, limit) })
    }

    pub fn len(&self) -> usize {
        self.net.chars.len()
    }

    pub fn is_empty(&self) -> bool {
        self.net.chars.is_empty()
    }

    /// Tối đa `limit` chữ khả dĩ nhất kèm xác suất, giảm dần. `canvas` là cạnh ô viết.
    pub fn recognize(&self, strokes: &[Stroke], canvas: f32, limit: usize) -> Vec<(char, f32)> {
        if strokes.iter().all(|s| s.is_empty()) {
            return Vec::new();
        }
        self.classify(&render::render(strokes, canvas), limit)
    }

    /// Như `recognize` nhưng nhận thẳng ảnh SIZE×SIZE đã vẽ.
    pub fn classify(&self, img: &[f32], limit: usize) -> Vec<(char, f32)> {
        self.top_k(&self.net.forward(img), limit)
    }

    fn top_k(&self, logits: &[f32], limit: usize) -> Vec<(char, f32)> {
        let max = logits.iter().copied().fold(f32::MIN, f32::max);
        let exp: Vec<f32> = logits.iter().map(|&v| (v - max).exp()).collect();
        let sum: f32 = exp.iter().sum();
        let mut idx: Vec<usize> = (0..exp.len()).collect();
        let k = limit.min(idx.len());
        if k == 0 {
            return Vec::new();
        }
        idx.select_nth_unstable_by(k - 1, |&a, &b| exp[b].total_cmp(&exp[a]));
        idx.truncate(k);
        idx.sort_by(|&a, &b| exp[b].total_cmp(&exp[a]));
        idx.into_iter().map(|i| (self.net.chars[i], exp[i] / sum)).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Mô hình nhỏ ngẫu nhiên + ảnh + logit mong đợi do `export.py --fixture` sinh từ PyTorch.
    const FIXTURE: &[u8] = include_bytes!("../tests/fixture.bin");

    fn fixture() -> (net::Net, Vec<f32>, Vec<f32>) {
        let model_len = u32::from_le_bytes(FIXTURE[..4].try_into().unwrap()) as usize;
        let net = net::Net::from_bytes(&FIXTURE[4..4 + model_len]).unwrap();
        let rest: Vec<f32> = FIXTURE[4 + model_len..].chunks_exact(4).map(|c| f32::from_le_bytes(c.try_into().unwrap())).collect();
        let (img, logits) = rest.split_at(net.size * net.size);
        (net, img.to_vec(), logits.to_vec())
    }

    #[test]
    fn matches_pytorch() {
        let (net, img, want) = fixture();
        let got = net.forward(&img);
        assert_eq!(got.len(), want.len());
        for (g, w) in got.iter().zip(&want) {
            assert!((g - w).abs() < 1e-3 * (1.0 + w.abs()), "{got:?} vs {want:?}");
        }
    }

    #[test]
    fn top_k_sorted_probabilities() {
        let (net, img, want) = fixture();
        let r = Recognizer { net, id: 0, cache: Default::default() };
        let top = r.classify(&img, 3);
        assert_eq!(top.len(), 3);
        assert!(top.windows(2).all(|w| w[0].1 >= w[1].1));
        let best = want.iter().enumerate().max_by(|a, b| a.1.total_cmp(b.1)).unwrap().0;
        assert_eq!(top[0].0, r.net.chars[best]);
        assert!(r.classify(&img, 100).iter().map(|p| p.1).sum::<f32>() > 0.999);
    }

    #[test]
    fn rejects_garbage() {
        assert!(Recognizer::from_bytes(b"nope").is_err());
        let (_, _, _) = fixture();
        let model_len = u32::from_le_bytes(FIXTURE[..4].try_into().unwrap()) as usize;
        assert!(net::Net::from_bytes(&FIXTURE[4..4 + model_len - 1]).is_err());
    }
}

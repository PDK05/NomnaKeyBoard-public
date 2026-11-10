//! Đọc và chạy CNN do `tools/handwriting/export.py` xuất ra.
//!
//! Định dạng (little-endian): `HNHW`, u32 phiên bản = 1, u32 cạnh ảnh vào, u32 số chữ, mỗi chữ một u32
//! mã Unicode, u32 số lớp mạng, rồi từng lớp mở đầu bằng một byte loại:
//! - 1 conv 3×3 (đệm 1) + ReLU: u32 vào, u32 ra, i8[ra·vào·9], f32 tỉ lệ[ra], f32 bias[ra]
//! - 2 max-pool 2×2
//! - 3 average-pool 2×2
//! - 4 kết nối đầy đủ: u32 vào, u32 ra, u8 có ReLU, i8[ra·vào], f32 tỉ lệ[ra], f32 bias[ra]
//!
//! BatchNorm đã gộp vào trọng số. Trọng số lượng tử hoá int8 theo từng kênh ra; conv được giải nén
//! sang f32 lúc nạp (nhỏ), lớp kết nối đầy đủ giữ int8 (lớp cuối chiếm phần lớn mô hình).

use std::fmt;

#[derive(Debug, PartialEq)]
pub struct FormatError(pub &'static str);

impl fmt::Display for FormatError {
    fn fmt(&self, f: &mut fmt::Formatter) -> fmt::Result {
        write!(f, "mô hình viết tay hỏng: {}", self.0)
    }
}

impl std::error::Error for FormatError {}

enum Layer {
    Conv { cin: usize, cout: usize, w: Vec<f32>, b: Vec<f32> },
    MaxPool,
    AvgPool,
    Dense { cin: usize, cout: usize, relu: bool, w: Vec<i8>, scale: Vec<f32>, b: Vec<f32> },
}

pub struct Net {
    pub size: usize,
    pub chars: Vec<char>,
    layers: Vec<Layer>,
}

struct Reader<'a>(&'a [u8]);

impl<'a> Reader<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8], FormatError> {
        if self.0.len() < n {
            return Err(FormatError("thiếu dữ liệu"));
        }
        let (a, b) = self.0.split_at(n);
        self.0 = b;
        Ok(a)
    }
    fn u8(&mut self) -> Result<u8, FormatError> {
        Ok(self.take(1)?[0])
    }
    fn u32(&mut self) -> Result<usize, FormatError> {
        Ok(u32::from_le_bytes(self.take(4)?.try_into().unwrap()) as usize)
    }
    fn i8s(&mut self, n: usize) -> Result<Vec<i8>, FormatError> {
        Ok(self.take(n)?.iter().map(|&b| b as i8).collect())
    }
    fn f32s(&mut self, n: usize) -> Result<Vec<f32>, FormatError> {
        Ok(self.take(n * 4)?.chunks_exact(4).map(|c| f32::from_le_bytes(c.try_into().unwrap())).collect())
    }
}

impl Net {
    pub fn from_bytes(data: &[u8]) -> Result<Net, FormatError> {
        let mut r = Reader(data);
        if r.take(4)? != b"HNHW" {
            return Err(FormatError("sai mã nhận dạng tệp"));
        }
        if r.u32()? != 1 {
            return Err(FormatError("phiên bản không hỗ trợ"));
        }
        let size = r.u32()?;
        let n = r.u32()?;
        let mut chars = Vec::with_capacity(n);
        for _ in 0..n {
            chars.push(char::from_u32(r.u32()? as u32).ok_or(FormatError("mã chữ sai"))?);
        }
        let mut layers = Vec::new();
        for _ in 0..r.u32()? {
            layers.push(match r.u8()? {
                1 => {
                    let (cin, cout) = (r.u32()?, r.u32()?);
                    let q = r.i8s(cout * cin * 9)?;
                    let scale = r.f32s(cout)?;
                    let w = q.iter().enumerate().map(|(i, &v)| v as f32 * scale[i / (cin * 9)]).collect();
                    Layer::Conv { cin, cout, w, b: r.f32s(cout)? }
                }
                2 => Layer::MaxPool,
                3 => Layer::AvgPool,
                4 => {
                    let (cin, cout, relu) = (r.u32()?, r.u32()?, r.u8()? != 0);
                    let w = r.i8s(cout * cin)?;
                    Layer::Dense { cin, cout, relu, w, scale: r.f32s(cout)?, b: r.f32s(cout)? }
                }
                _ => return Err(FormatError("loại lớp lạ")),
            });
        }
        if !r.0.is_empty() {
            return Err(FormatError("thừa dữ liệu cuối tệp"));
        }
        let net = Net { size, chars, layers };
        if net.out_len() != Some(net.chars.len()) {
            return Err(FormatError("kích thước các lớp không khớp"));
        }
        Ok(net)
    }

    /// Kiểm tra hình dạng các lớp; trả về độ dài đầu ra.
    fn out_len(&self) -> Option<usize> {
        let (mut c, mut s, mut flat) = (1usize, self.size, false);
        for l in &self.layers {
            match l {
                Layer::Conv { cin, cout, .. } if !flat && *cin == c => c = *cout,
                Layer::MaxPool | Layer::AvgPool if !flat && s >= 2 => s /= 2,
                Layer::Dense { cin, cout, .. } if *cin == if flat { c } else { c * s * s } => {
                    c = *cout;
                    flat = true;
                }
                _ => return None,
            }
        }
        flat.then_some(c)
    }

    /// Điểm (logit) của từng chữ cho ảnh `size`×`size` (mực = 1).
    pub fn forward(&self, img: &[f32]) -> Vec<f32> {
        self.run(img).1
    }

    /// (đặc trưng: đầu vào của lớp cuối, logit). Hai ảnh giống nhau thì đặc trưng gần nhau,
    /// dùng để so với chữ người dùng đã dạy (`Memory`).
    pub fn run(&self, img: &[f32]) -> (Vec<f32>, Vec<f32>) {
        assert_eq!(img.len(), self.size * self.size);
        let mut x = img.to_vec();
        let mut s = self.size;
        let mut features = Vec::new();
        for (i, l) in self.layers.iter().enumerate() {
            if i + 1 == self.layers.len() {
                features = x.clone();
            }
            x = match l {
                Layer::Conv { cin, cout, w, b } => conv3x3(&x, s, *cin, *cout, w, b),
                Layer::MaxPool => {
                    let y = pool(&x, s, |a, b, c, d| a.max(b).max(c).max(d));
                    s /= 2;
                    y
                }
                Layer::AvgPool => {
                    let y = pool(&x, s, |a, b, c, d| (a + b + c + d) * 0.25);
                    s /= 2;
                    y
                }
                Layer::Dense { cin, cout, relu, w, scale, b } => {
                    let mut y = Vec::with_capacity(*cout);
                    for o in 0..*cout {
                        let row = &w[o * cin..(o + 1) * cin];
                        let dot: f32 = row.iter().zip(&x).map(|(&q, &v)| q as f32 * v).sum();
                        let v = dot * scale[o] + b[o];
                        y.push(if *relu { v.max(0.0) } else { v });
                    }
                    y
                }
            };
        }
        (features, x)
    }
}

/// Conv 3×3 đệm 0, bước 1, rồi ReLU. Bố cục CHW. Vòng trong chạy trên một hàng liền nhau để
/// trình biên dịch tự vector hoá (NEON/SSE).
fn conv3x3(x: &[f32], s: usize, cin: usize, cout: usize, w: &[f32], b: &[f32]) -> Vec<f32> {
    let plane = s * s;
    let mut out = vec![0f32; cout * plane];
    for o in 0..cout {
        let dst = &mut out[o * plane..(o + 1) * plane];
        dst.fill(b[o]);
        for i in 0..cin {
            let src = &x[i * plane..(i + 1) * plane];
            let k = &w[(o * cin + i) * 9..(o * cin + i) * 9 + 9];
            for ky in 0..3 {
                for kx in 0..3 {
                    let wv = k[ky * 3 + kx];
                    if wv == 0.0 {
                        continue;
                    }
                    // Hàng ra y đọc hàng vào y + ky − 1; cột ra x đọc cột vào x + kx − 1.
                    let (y_lo, y_hi) = (if ky == 0 { 1 } else { 0 }, if ky == 2 { s - 1 } else { s });
                    let (x_lo, x_hi) = (if kx == 0 { 1 } else { 0 }, if kx == 2 { s - 1 } else { s });
                    for y in y_lo..y_hi {
                        let sy = y + ky - 1;
                        let d = &mut dst[y * s + x_lo..y * s + x_hi];
                        let r = &src[sy * s + x_lo + kx - 1..sy * s + x_hi + kx - 1];
                        for (a, &v) in d.iter_mut().zip(r) {
                            *a += wv * v;
                        }
                    }
                }
            }
        }
        dst.iter_mut().for_each(|v| *v = v.max(0.0));
    }
    out
}

fn pool(x: &[f32], s: usize, f: impl Fn(f32, f32, f32, f32) -> f32) -> Vec<f32> {
    let h = s / 2;
    let c = x.len() / (s * s);
    let mut out = Vec::with_capacity(c * h * h);
    for ch in 0..c {
        let p = &x[ch * s * s..];
        for y in 0..h {
            for xx in 0..h {
                let i = 2 * y * s + 2 * xx;
                out.push(f(p[i], p[i + 1], p[i + s], p[i + s + 1]));
            }
        }
    }
    out
}

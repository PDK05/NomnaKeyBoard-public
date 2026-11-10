//! Nét viết → ảnh 64×64 (mực = 1). Giống `tools/handwriting/synth.py`: co khung bao mực vào
//! ảnh với lề `MARGIN`, giữ tỉ lệ, đặt giữa; nét dày `PEN` px, đầu tròn, khử răng cưa.

pub const SIZE: usize = 64;
pub const MARGIN: f32 = 4.0;
pub const PEN: f32 = 2.5;

/// Một nét: dãy điểm (x, y), y hướng xuống.
pub type Stroke = Vec<(f32, f32)>;

/// Vẽ các nét. `canvas` là cạnh ô viết (cùng đơn vị với toạ độ): nét nhỏ hơn 1/4 ô
/// không bị phóng to quá mức (một chấm không thành vết mực to cả ảnh).
pub fn render(strokes: &[Stroke], canvas: f32) -> Vec<f32> {
    let mut img = vec![0f32; SIZE * SIZE];
    let pts = strokes.iter().flatten();
    let (mut x0, mut y0, mut x1, mut y1) = (f32::MAX, f32::MAX, f32::MIN, f32::MIN);
    for &(x, y) in pts {
        x0 = x0.min(x);
        y0 = y0.min(y);
        x1 = x1.max(x);
        y1 = y1.max(y);
    }
    if x0 > x1 {
        return img;
    }
    let inner = SIZE as f32 - 2.0 * MARGIN;
    let scale = (inner / (x1 - x0).max(y1 - y0).max(1e-6)).min(inner / (0.25 * canvas.max(1e-6)));
    let (cx, cy) = ((x0 + x1) / 2.0, (y0 + y1) / 2.0);
    let half = SIZE as f32 / 2.0;
    let map = |(x, y): (f32, f32)| ((x - cx) * scale + half, (y - cy) * scale + half);

    for s in strokes {
        let p: Vec<(f32, f32)> = s.iter().copied().map(map).collect();
        match p.len() {
            0 => {}
            1 => segment(&mut img, p[0], p[0]),
            _ => p.windows(2).for_each(|w| segment(&mut img, w[0], w[1])),
        }
    }
    img
}

/// Đoạn thẳng dày PEN: độ phủ điểm ảnh = r + 0.5 − khoảng cách từ tâm điểm ảnh tới đoạn.
fn segment(img: &mut [f32], a: (f32, f32), b: (f32, f32)) {
    let r = PEN / 2.0;
    let reach = r + 1.0;
    let lo_x = (a.0.min(b.0) - reach).floor().max(0.0) as usize;
    let hi_x = ((a.0.max(b.0) + reach).ceil() as isize).clamp(0, SIZE as isize) as usize;
    let lo_y = (a.1.min(b.1) - reach).floor().max(0.0) as usize;
    let hi_y = ((a.1.max(b.1) + reach).ceil() as isize).clamp(0, SIZE as isize) as usize;
    let (dx, dy) = (b.0 - a.0, b.1 - a.1);
    let len2 = (dx * dx + dy * dy).max(1e-9);
    for y in lo_y..hi_y {
        for x in lo_x..hi_x {
            let (px, py) = (x as f32 + 0.5, y as f32 + 0.5);
            let t = (((px - a.0) * dx + (py - a.1) * dy) / len2).clamp(0.0, 1.0);
            let (ex, ey) = (px - a.0 - dx * t, py - a.1 - dy * t);
            let v = (r + 0.5 - (ex * ex + ey * ey).sqrt()).clamp(0.0, 1.0);
            let cell = &mut img[y * SIZE + x];
            *cell = cell.max(v);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ink_box(img: &[f32]) -> (usize, usize, usize, usize) {
        let (mut x0, mut y0, mut x1, mut y1) = (SIZE, SIZE, 0, 0);
        for y in 0..SIZE {
            for x in 0..SIZE {
                if img[y * SIZE + x] > 0.5 {
                    x0 = x0.min(x);
                    y0 = y0.min(y);
                    x1 = x1.max(x);
                    y1 = y1.max(y);
                }
            }
        }
        (x0, y0, x1, y1)
    }

    #[test]
    fn fits_bbox_keeping_aspect() {
        // Nét ngang dài ở góc ô: được kéo ra hết chiều ngang, nằm giữa theo chiều dọc.
        let img = render(&[vec![(10.0, 10.0), (90.0, 10.0)]], 300.0);
        let (x0, y0, x1, y1) = ink_box(&img);
        assert!(x0 <= 4 && x1 >= 59, "{x0} {x1}");
        assert!((30..=33).contains(&y0) && (31..=34).contains(&y1), "{y0} {y1}");
    }

    #[test]
    fn position_independent() {
        let a = render(&[vec![(0.0, 0.0), (0.0, 50.0)], vec![(-20.0, 25.0), (20.0, 25.0)]], 200.0);
        let b = render(&[vec![(100.0, 70.0), (100.0, 120.0)], vec![(80.0, 95.0), (120.0, 95.0)]], 200.0);
        assert!(a.iter().zip(&b).all(|(p, q)| (p - q).abs() < 1e-4));
    }

    #[test]
    fn tiny_dot_not_blown_up() {
        let img = render(&[vec![(50.0, 50.0), (52.0, 51.0)]], 400.0);
        let (x0, _, x1, _) = ink_box(&img);
        assert!(x1 - x0 < 6, "{x0} {x1}");
    }

    #[test]
    fn empty() {
        assert!(render(&[], 100.0).iter().all(|&v| v == 0.0));
    }
}

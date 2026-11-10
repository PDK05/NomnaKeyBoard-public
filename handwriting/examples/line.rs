//! Ghép các chữ trong `examples/cases.txt` thành dòng (đặt cạnh nhau, khe hở khác nhau) rồi tách lại
//! bằng `recognize_line`:
//!
//!     cargo run --release --target wasm32-wasip1 --example line < ../models/hw.bin

use std::io::Read;
use std::time::Instant;

use hannom_handwriting::{Recognizer, Stroke};

fn main() {
    let mut model = Vec::new();
    std::io::stdin().read_to_end(&mut model).unwrap();
    let r = Recognizer::from_bytes(&model).expect("mô hình");
    let cases = std::fs::read_to_string("examples/cases.txt").expect("examples/cases.txt");
    // Mỗi chữ một mẫu (bỏ các cách viết khác của cùng chữ).
    let mut all: Vec<(char, Vec<Stroke>)> = Vec::new();
    for (c, s) in cases.lines().filter(|l| !l.is_empty()).map(parse) {
        if !all.iter().any(|a| a.0 == c) {
            all.push((c, s));
        }
    }
    let (mut ok, mut n) = (0, 0);
    let start = Instant::now();
    for gap in [8.0f32, 40.0] {
        for len in 2..=3 {
            for first in 0..all.len() {
                let picked: Vec<&(char, Vec<Stroke>)> = (0..len).map(|k| &all[(first + k * 3) % all.len()]).collect();
                let want: String = picked.iter().map(|p| p.0).collect();
                let mut strokes: Vec<Stroke> = Vec::new();
                let mut x = 0.0;
                for (_, s) in &picked {
                    let x0 = s.iter().flatten().map(|p| p.0).fold(f32::MAX, f32::min);
                    let x1 = s.iter().flatten().map(|p| p.0).fold(f32::MIN, f32::max);
                    strokes.extend(s.iter().map(|st| st.iter().map(|&(px, py)| (px - x0 + x, py)).collect::<Stroke>()));
                    x += x1 - x0 + gap;
                }
                let got: String = r.recognize_line(&strokes, 300.0, 5).iter().map(|s| s.analysis.top[0].0).collect();
                n += 1;
                if got == want {
                    ok += 1;
                } else {
                    println!("✗ khe {gap}: {want} → {got}");
                }
            }
        }
    }
    let ms = start.elapsed().as_secs_f64() * 1000.0 / n as f64;
    println!("đúng: {ok}/{n}, {ms:.0} ms/dòng");
}

fn parse(line: &str) -> (char, Vec<Stroke>) {
    let mut f = line.split('\t');
    let want = f.next().unwrap().chars().next().unwrap();
    let raw = f.nth(1).unwrap();
    let strokes = raw
        .split(';')
        .map(|s| {
            s.split(' ')
                .map(|p| {
                    let (x, y) = p.split_once(',').unwrap();
                    (x.parse().unwrap(), y.parse().unwrap())
                })
                .collect()
        })
        .collect();
    (want, strokes)
}

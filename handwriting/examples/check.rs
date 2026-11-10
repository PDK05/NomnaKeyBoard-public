//! Chạy mô hình thật trên các ca trong `examples/cases.txt` (sinh bằng tools/handwriting/gen_cases.py).
//! Mô hình đọc từ stdin để không phụ thuộc thư mục WASI được mở:
//!
//!     cargo run --release --target wasm32-wasip1 --example check < ../models/hw.bin

use std::io::Read;
use std::time::Instant;

use hannom_handwriting::{Memory, Recognizer, Stroke};

fn main() {
    let mut model = Vec::new();
    std::io::stdin().read_to_end(&mut model).unwrap();
    let r = Recognizer::from_bytes(&model).expect("mô hình");
    println!("{} chữ", r.len());
    let cases = std::fs::read_to_string("examples/cases.txt").expect("examples/cases.txt");
    let (mut hit1, mut hit5, mut n) = (0, 0, 0);
    let start = Instant::now();
    for line in cases.lines().filter(|l| !l.is_empty()) {
        let desc = line.split('\t').nth(1).unwrap();
        let (want, strokes) = parse(line);
        let top = r.recognize(&strokes, 300.0, 5);
        let rank = top.iter().position(|p| p.0 == want);
        n += 1;
        hit1 += (rank == Some(0)) as usize;
        hit5 += rank.is_some() as usize;
        let shown: Vec<String> = top.iter().map(|(c, p)| format!("{c} {:.0}%", p * 100.0)).collect();
        let mark = match rank {
            Some(0) => "✓",
            Some(_) => "~",
            None => "✗",
        };
        println!("{mark} {want} {desc}: {}", shown.join("  "));
    }
    let ms = start.elapsed().as_secs_f64() * 1000.0 / n as f64;
    println!("đúng ngay: {hit1}/{n}, trong top 5: {hit5}/{n}, {ms:.0} ms/chữ");

    // Dạy chữ cuối cùng trong các ca, rồi viết lại hơi lệch: chữ đó phải lên đầu,
    // các chữ khác giữ nguyên chữ đứng đầu như mô hình đoán.
    let all: Vec<(char, Vec<Stroke>)> = cases.lines().filter(|l| !l.is_empty()).map(parse).collect();
    let (taught, strokes) = all.last().unwrap();
    let mut memory = Memory::new();
    memory.add(&r, &taught.to_string(), strokes, 300.0);
    let warped: Vec<Stroke> =
        strokes.iter().map(|s| s.iter().map(|&(x, y)| (x * 0.9 + y * 0.05 + 12.0, y * 1.05 - 8.0)).collect()).collect();
    let a = r.analyze(&warped, 300.0, 5).unwrap();
    println!("dạy {taught}, viết lại lệch: {:?} (mô hình: {})", memory.rank(&a, 5), a.top[0].0);
    let mut kept = 0;
    for (_, s) in &all[..all.len() - 1] {
        let a = r.analyze(s, 300.0, 5).unwrap();
        kept += (memory.rank(&a, 1)[0] == a.top[0].0.to_string()) as usize;
    }
    println!("chữ khác giữ nguyên gợi ý đầu: {kept}/{}", all.len() - 1);
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

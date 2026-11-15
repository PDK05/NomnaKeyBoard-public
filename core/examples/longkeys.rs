//! TẠM THỜI: thời gian gợi ý khi chuỗi phím rất dài (cụm test kinh điển lặp lại), gõ lần lượt từng phím như trên
//! điện thoại: mỗi mốc độ dài in thời gian trung bình / chậm nhất của 50 phím quanh đó và độ dài câu ghép.
use std::io::Read;
use std::time::Instant;

use hannom_core::{Dictionary, Engine};

fn main() {
    let mut content = String::new();
    std::io::stdin().read_to_string(&mut content).unwrap();
    let (dict, _) = Dictionary::from_text(&content);
    let mut engine = Engine::new(dict);
    engine.options.telex = true;
    let phrase = "khoong'mootj'hai'ba'boons'nawm'saus'bayr'tams'chins'muwowif'muwowif'mootj'muwowif'hai'muwowif'ba";
    let max: usize = std::env::args().nth(1).and_then(|a| a.parse().ok()).unwrap_or(1000);
    let step: usize = std::env::args().nth(2).and_then(|a| a.parse().ok()).unwrap_or(100);
    let mut full = String::new();
    // Tham số thứ ba: tệp phím Telex (trong thư mục chạy) thay cho cụm test lặp lại.
    if let Some(path) = std::env::args().nth(3) {
        // Chỉ giữ chữ mà gợi ý đầu phủ hết (tiếng Việt có trong từ điển), để cả chuỗi vẫn ghép câu.
        let text = std::fs::read_to_string(path).unwrap();
        let words: Vec<&str> = text
            .trim()
            .split('\'')
            .filter(|w| !w.is_empty() && engine.candidates(w).first().is_some_and(|c| c.consumed == w.len()))
            .collect();
        full = words.join("'");
        engine = Engine::new(engine.dict.clone());
        engine.options.telex = true;
    }
    while full.len() < max {
        if !full.is_empty() {
            full.push('\'');
        }
        full.push_str(phrase);
    }
    let (mut sum, mut worst, mut count) = (0.0f64, 0.0f64, 0);
    for k in 1..=max.min(full.len()) {
        let t = Instant::now();
        let c = engine.candidates(&full[..k]);
        let ms = t.elapsed().as_secs_f64() * 1000.0;
        sum += ms;
        worst = worst.max(ms);
        count += 1;
        if k % step == 0 {
            let first = c.first().map_or(0, |c| c.text.chars().count());
            println!("{k:>5} phím: tb {:>6.1} ms, chậm nhất {worst:>6.1} ms (gợi ý đầu {first} chữ)", sum / count as f64);
            (sum, worst, count) = (0.0, 0.0, 0);
        }
    }
}

//! TẠM THỜI: ghép câu tính tiếp (gõ thêm, xoá lùi, xen chuỗi phím khác) phải ra đúng như tính lại từ đầu.
use std::io::Read;

use hannom_core::{Dictionary, Engine};

fn main() {
    let mut content = String::new();
    std::io::stdin().read_to_string(&mut content).unwrap();
    let (dict, _) = Dictionary::from_text(&content);
    let mut warm = Engine::new(dict.clone());
    warm.options.telex = true;
    let fresh = |keys: &str| {
        let mut e = Engine::new(dict.clone());
        e.options.telex = true;
        e.candidates(keys)
    };
    let text = std::fs::read_to_string(std::env::args().nth(1).unwrap()).unwrap();
    let max: usize = std::env::args().nth(2).and_then(|a| a.parse().ok()).unwrap_or(800);
    let full: String = text.trim().chars().take(max).collect();
    let mut checked = 0;
    let mut bad = 0;
    let mut check = |keys: &str, warm: &Engine| {
        let a = warm.candidates(keys);
        if checked % 23 == 0 || keys.len() == full.len() {
            let b = fresh(keys);
            if a.first().map(|c| (&c.text, c.consumed)) != b.first().map(|c| (&c.text, c.consumed)) {
                bad += 1;
                println!("KHÁC ở {} phím:\n  tiếp: {:?}\n  mới : {:?}", keys.len(), a.first().map(|c| &c.text), b.first().map(|c| &c.text));
            }
        }
        checked += 1;
    };
    // Gõ thêm từng phím.
    for k in 1..=full.len() {
        check(&full[..k], &warm);
        // Xen chuỗi phím khác (như bộ gõ tính trước sau khi chọn chữ): phần sau.
        if k % 50 == 0 {
            let _ = warm.candidates(&full[k / 2..k]);
        }
    }
    // Xoá lùi.
    for k in (full.len() - 200..full.len()).rev() {
        check(&full[..k], &warm);
    }
    // Sửa giữa chuỗi: đổi một chữ ở giữa.
    let mid = full.len() / 2;
    let edited = format!("{}nam{}", &full[..mid], &full[mid..]);
    check(&edited, &warm);
    println!("xong: {bad} chỗ khác");
}

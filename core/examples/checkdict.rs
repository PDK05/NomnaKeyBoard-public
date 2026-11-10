//! Kiểm tra một tệp từ điển theo đúng luật của lõi: số mục hợp lệ, các dòng lỗi, và (nếu truyền
//! chuỗi phím) các gợi ý đầu như bộ gõ hiện. Tệp đọc từ stdin để không phụ thuộc thư mục WASI được mở:
//!
//!     cargo run --target wasm32-wasip1 --example checkdict < ../dict/nomna-dict.txt
//!     cargo run --target wasm32-wasip1 --example checkdict -- nam nguoi < ../dict/nomna-dict.txt

use std::io::Read;

use hannom_core::{normalize, Dictionary, Engine};

fn main() {
    let mut content = String::new();
    std::io::stdin().read_to_string(&mut content).expect("tệp UTF-8");
    let start = std::time::Instant::now();
    let (dict, report) = Dictionary::from_text(&content);
    let ms = start.elapsed().as_millis();
    let phrases = dict.entries().iter().filter(|e| e.reading.contains(' ')).count();
    println!("{} mục ({} cụm nhiều chữ), {} trùng, {} dòng lỗi, nạp {ms} ms", dict.len(), phrases, report.duplicates, report.errors.len());
    for err in report.errors.iter().take(30) {
        println!("  {err}");
    }
    match Dictionary::first_unsorted(&content) {
        None => println!("đã xếp a → z"),
        Some(line) => println!("chưa xếp a → z, lệch từ dòng {line}"),
    }
    // Lưu lại (xếp a → z) phải ra đúng các dòng dữ liệu của tệp đã xếp sẵn.
    let data: String = content.lines().filter(|l| !l.trim().is_empty() && !l.starts_with('#')).map(|l| format!("{l}\n")).collect();
    let saved = dict.to_text();
    match saved.lines().zip(data.lines()).enumerate().find(|(_, (a, b))| a != b) {
        None if saved == data => println!("lưu lại giống hệt tệp gốc"),
        None => println!("lưu lại khác số dòng: {} / {}", saved.lines().count(), data.lines().count()),
        Some((i, (a, b))) => println!("lưu lại khác ở dòng dữ liệu {}: {b:?} → {a:?}", i + 1),
    }
    let engine = Engine::new(dict);
    for keys in std::env::args().skip(1) {
        // Như bàn phím: chuẩn hoá chuỗi phím Telex trước khi tra.
        let list: Vec<String> = engine.candidates(&normalize(&keys, false)).iter().take(8).map(|c| c.text.clone()).collect();
        println!("{keys:>10}: {}", list.join(" "));
    }
}

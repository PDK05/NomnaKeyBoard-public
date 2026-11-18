//! Đo thời gian tách số gốc theo từ điển mặc định: cách cũ (dựng từ điển thứ hai) và cách mới (đọc thẳng dòng).
//! Chạy (trong `core/`): `cargo run --release --example rebasetime --target wasm32-wasip1 < ../dict/nomna-dict.txt`
use std::io::Read;
use std::time::Instant;

use hannom_core::Dictionary;

fn main() {
    let mut content = String::new();
    std::io::stdin().read_to_string(&mut content).unwrap();
    for _ in 0..3 {
        let (mut a, _) = Dictionary::from_text(&content);
        let t = Instant::now();
        a.rebase(&Dictionary::from_text(&content).0);
        let old = t.elapsed().as_secs_f64() * 1000.0;
        let (mut b, _) = Dictionary::from_text(&content);
        let t = Instant::now();
        b.rebase_text(&content);
        let new = t.elapsed().as_secs_f64() * 1000.0;
        assert_eq!(a.to_text(), b.to_text());
        println!("cũ {old:.1} ms, mới {new:.1} ms");
    }
}

//! In 8 gợi ý đầu cho từng chuỗi phím Telex (đối số).
use std::io::Read;
use hannom_core::{Dictionary, Engine};
fn main() {
    let mut content = String::new();
    std::io::stdin().read_to_string(&mut content).unwrap();
    let (dict, _) = Dictionary::from_text(&content);
    let mut engine = Engine::new(dict);
    engine.options.telex = true;
    for k in std::env::args().skip(1) {
        let c: Vec<String> = engine.candidates(&k).iter().take(8).map(|c| format!("{}({})", c.text, c.reading)).collect();
        println!("{k}: {}", c.join(" "));
    }
}

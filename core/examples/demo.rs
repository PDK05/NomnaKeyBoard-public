//! Thử bộ gõ trong terminal.
//!
//!   cargo run --example demo                 # gõ tương tác
//!   cargo run --example demo -- chunom nawm  # thử nhanh vài chuỗi
//!
//! Lệnh: `:moi` / `:cu` kiểu đặt dấu.

use std::io::{self, BufRead, Write};

use hannom_core::{compose, Dictionary, Engine, ToneStyle};

const DICT: &str = include_str!("../../dict/nomna-dict.txt");

fn main() {
    let (dict, report) = Dictionary::from_text(DICT);
    for err in &report.errors {
        eprintln!("dict/nomna-dict.txt: {err}");
    }
    let mut engine = Engine::new(dict);

    let args: Vec<String> = std::env::args().skip(1).collect();
    if !args.is_empty() {
        args.iter().for_each(|a| handle(&mut engine, a));
        return;
    }

    println!("Bàn phím Hán Nôm –{} mục từ điển. Gõ chuỗi phím rồi Enter (:moi, :cu).", engine.dict.len());
    let stdin = io::stdin();
    loop {
        print!("> ");
        io::stdout().flush().ok();
        let mut line = String::new();
        if stdin.lock().read_line(&mut line).unwrap_or(0) == 0 {
            break;
        }
        handle(&mut engine, line.trim());
    }
}

fn handle(engine: &mut Engine, input: &str) {
    match input {
        "" => {}
        ":moi" => engine.options.tone_style = ToneStyle::New,
        ":cu" => engine.options.tone_style = ToneStyle::Old,
        keys => {
            let words: Vec<String> = keys.split_whitespace().map(|w| compose(w, engine.options.tone_style)).collect();
            println!("  Quốc ngữ : {}", words.join(" "));
            let compact: String = keys.split_whitespace().collect::<Vec<_>>().join("'");
            let cands = engine.candidates(&compact);
            if cands.is_empty() {
                println!("  Hán Nôm  : (không tìm thấy – thêm vào từ điển?)");
            } else {
                let list: Vec<String> = cands
                    .iter()
                    .take(9)
                    .enumerate()
                    .map(|(i, c)| format!("{}.{}({})", i + 1, c.text, c.reading))
                    .collect();
                println!("  Hán Nôm  : {}", list.join("  "));
            }
        }
    }
}

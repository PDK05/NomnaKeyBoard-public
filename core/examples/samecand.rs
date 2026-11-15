//! So gợi ý từng phím (để kiểm chứng tăng tốc không đổi kết quả): in mỗi phím một dòng các gợi ý (chữ, số phím).
use std::io::Read;

use hannom_core::{Dictionary, Engine};

fn main() {
    let mut content = String::new();
    std::io::stdin().read_to_string(&mut content).unwrap();
    let (dict, _) = Dictionary::from_text(&content);
    let mut engine = Engine::new(dict);
    engine.options.telex = true;
    let unit = ["khoong", "mootj", "hai", "ba", "boons", "nawm", "saus", "bayr", "tams", "chins"];
    let mut w: Vec<String> = unit.iter().map(|s| s.to_string()).collect();
    w.push("muwowif".into());
    for n in 1..10 {
        w.push("muwowif".into());
        w.push(if n == 5 { "lawm" } else { unit[n] }.into());
    }
    for n in 0..10 {
        w.push("hai".into());
        w.push("muwowi".into());
        match n {
            0 => {}
            1 => w.push("moots".into()),
            5 => w.push("lawm".into()),
            _ => w.push(unit[n].into()),
        }
    }
    w.push("ba".into());
    w.push("muwowi".into());
    let texts = [
        w.join("'"),
        "tooi'ddi'hocj'owr'truwowngf'ddaij'hocj'quoocs'gia'haf'nooij'vieetj'nam'laf'mootj'nuwowcs'ddepj'vaf'giaux'cos'nhieeuf'nguwowif'tooi'yeeu".to_string(),
        "tooi'ddi'chowj'tinhr'veef'mieenf'nam'thamaw'langw'bacs'hoof'chuooix'chuooix'mieenf'trung'quoocs'vieetj'nam'chuwx'hanf'chuwx'noom".to_string(),
        "conf'dduwowngf'xuwa'bes'mang'chungs'ta'ddi'qua'nhuwngx'nawm'thangs'cuar'tuooir'thow'vaf'nhuwngx'giaacs'mow'ddepj'nhaats".to_string(),
    ];
    for t in &texts {
        for n in 1..=t.len() {
            let c = engine.candidates(&t[..n]);
            let line: Vec<String> = c.iter().take(20).map(|c| format!("{}:{}", c.text, c.consumed)).collect();
            println!("{} | {}", &t[..n], line.join(" "));
        }
    }
}

//! Chất lượng: gợi ý đầu ở cuối cụm 0 → 30 phủ bao nhiêu phím / âm.
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
    let full = w.join("'");
    let c = engine.candidates(&full);
    let top = c.first();
    println!(
        "RESULT phu consumed={} of={} chars={}",
        top.map(|c| c.consumed).unwrap_or(0),
        full.len(),
        top.map(|c| c.text.chars().count()).unwrap_or(0)
    );
}

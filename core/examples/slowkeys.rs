use std::io::Read;
use hannom_core::{Dictionary, Engine};
fn keys() -> String {
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
    w.join("'")
}

fn main() {
    let mut content = String::new();
    std::io::stdin().read_to_string(&mut content).unwrap();
    let (dict, _) = Dictionary::from_text(&content);
    let mut engine = Engine::new(dict);
    engine.options.telex = true;
    let full = keys();
    for i in 1..=full.len() {
        let t = std::time::Instant::now();
        let _ = engine.candidates(&full[..i]);
        let d = t.elapsed().as_secs_f64() * 1000.0;
        if d > 20.0 { println!("{i:>4} {d:>7.1} ...{}", &full[i.saturating_sub(25)..i]); }
    }
}

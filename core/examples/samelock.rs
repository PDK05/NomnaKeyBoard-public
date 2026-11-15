//! So cột trái và gợi ý khi chốt / bỏ chốt / chọn chữ (kiểm chứng tăng tốc không đổi kết quả).
use std::io::Read;
use hannom_core::{Dictionary, Engine, Part};
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
    let none: Vec<(Part, String)> = Vec::new();
    for full in [keys(), "tooi'ddi'chowj'tinhr'veef'mieenf'nam'thamaw'langw'bacs'hoof'chuooix'chuooix'mieenf'trung'quoocs'vieetj'nam".to_string(), "twcmn'vieetj'nam'chuwx'hanf".to_string()] {
        let n = engine.syllable_chunks(&full).len();
        let mut order: Vec<usize> = (0..=n).collect();
        order.extend((0..n).rev());
        for k in order {
            let locks = vec![Vec::new(); k];
            let a = engine.narrow_locked(&full, "", &locks, &none);
            let c: Vec<String> = engine.candidates_locked(&full, "", &locks, &none).iter().take(30).map(|c| format!("{}:{}", c.text, c.consumed)).collect();
            println!("{k} {a:?} | {}", c.join(" "));
            if k > 0 {
                let pick = vec![(Part::Initial, String::new())];
                let b = engine.narrow_locked(&full, "", &locks[..k - 1], &pick);
                println!("  pick {b:?}");
            }
        }
    }
}

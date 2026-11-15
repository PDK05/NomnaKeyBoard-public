//! Đo lịch sử: cụm 0 → 30, chỉ dùng API có từ bản đầu (candidates, narrow).
use std::io::Read;
use std::time::Instant;

use hannom_core::{Dictionary, Engine};

fn ms(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1000.0
}

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
    let t = Instant::now();
    let (dict, _) = Dictionary::from_text(&content);
    let load = ms(t);
    let mut engine = Engine::new(dict);
    engine.options.telex = true;
    let full = keys();
    let n = full.len();
    let (mut tot, mut max) = (0.0, 0.0f64);
    for i in 1..=n {
        let t = Instant::now();
        let _ = engine.candidates(&full[..i]);
        let d = ms(t);
        tot += d;
        max = max.max(d);
    }
    println!("RESULT go total={tot:.1} avg={:.2} max={max:.1}", tot / n as f64);
    let (mut tot, mut max) = (0.0, 0.0f64);
    for i in (1..n).rev() {
        let t = Instant::now();
        let _ = engine.candidates(&full[..i]);
        let d = ms(t);
        tot += d;
        max = max.max(d);
    }
    println!("RESULT xoa total={tot:.1} avg={:.2} max={max:.1}", tot / (n - 1) as f64);
    // Cột trái (mở bảng thả) ở cuối mỗi âm.
    let ends: Vec<usize> = full.match_indices('\'').map(|(i, _)| i).chain(std::iter::once(n)).collect();
    let (mut tot, mut max) = (0.0, 0.0f64);
    for &e in &ends {
        let t = Instant::now();
        let _ = engine.narrow(&full[..e], &[]);
        let d = ms(t);
        tot += d;
        max = max.max(d);
    }
    println!("RESULT cot total={tot:.1} avg={:.2} max={max:.1}", tot / ends.len() as f64);
    println!("RESULT nap total={load:.1} avg={load:.1} max={load:.1}");
}

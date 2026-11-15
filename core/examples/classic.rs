//! Cụm test kinh điển: gõ từng phím "không một hai … hai mươi chín ba mươi" (đếm 0 → 30, 60 âm), đo thời gian gợi ý
//! mỗi phím (như mỗi lần bấm trên điện thoại); rồi trong bảng thả ấn cách chốt lần lượt hết các âm; rồi ấn xoá: bỏ
//! chốt từng âm một, hết âm chốt thì xoá từng phím.
//!
//! Chạy (trong `core/`): `cargo run --release --example classic --target wasm32-wasip1 [-- typing] < ../dict/nomna-dict.txt`
use std::io::Read;
use std::time::Instant;

use hannom_core::{Dictionary, Engine, Part};

fn ms(t: Instant) -> f64 {
    t.elapsed().as_secs_f64() * 1000.0
}

/// Mã Telex của các số 0 → 30, cách âm bằng `'`.
fn keys() -> String {
    let unit = ["khoong", "mootj", "hai", "ba", "boons", "nawm", "saus", "bayr", "tams", "chins"];
    let mut words: Vec<String> = unit.iter().map(|s| s.to_string()).collect();
    words.push("muwowif".into());
    for n in 1..10 {
        words.push("muwowif".into());
        words.push(if n == 5 { "lawm" } else { unit[n] }.into());
    }
    for n in 0..10 {
        words.push("hai".into());
        words.push("muwowi".into());
        match n {
            0 => {}
            1 => words.push("moots".into()),
            5 => words.push("lawm".into()),
            _ => words.push(unit[n].into()),
        }
    }
    words.push("ba".into());
    words.push("muwowi".into());
    words.join("'")
}

fn main() {
    let mut content = String::new();
    std::io::stdin().read_to_string(&mut content).unwrap();
    let (dict, _) = Dictionary::from_text(&content);
    let mut engine = Engine::new(dict);
    engine.options.telex = true;
    let full = keys();
    let syllables = full.split('\'').count();
    let typing = std::env::args().any(|a| a == "typing");
    let none: Vec<(Part, String)> = Vec::new();

    if typing {
        let (mut total, mut worst) = (0.0, (0.0, 0));
        for n in 1..=full.len() {
            let t = Instant::now();
            let _ = engine.candidates(&full[..n]);
            let d = ms(t);
            total += d;
            if d > worst.0 {
                worst = (d, n);
            }
        }
        println!("gõ {} phím: tổng {total:.1} ms, chậm nhất {:.1} ms ở phím {}", full.len(), worst.0, worst.1);
    }

    // Phím cách trong bảng thả (không chọn gì ở cột trái): chốt thêm một âm, bảng hỏi lại cột trái và gợi ý.
    let step = |engine: &mut Engine, locks: &[Vec<(Part, String)>]| {
        let t = Instant::now();
        let n = engine.narrow_locked(&full, "", locks, &none);
        let t1 = ms(t);
        let t = Instant::now();
        let c = engine.candidates_locked(&full, "", locks, &none);
        (t1, ms(t), c.len(), n.map(|x| x.0))
    };
    let mut locks: Vec<Vec<(Part, String)>> = Vec::new();
    let mut total = 0.0;
    for k in 0..=syllables {
        let (t1, t2, c, n) = step(&mut engine, &locks);
        total += t1 + t2;
        println!("cách  {k:>2}: narrow {t1:>6.1} ms, gợi ý {t2:>6.1} ms ({c}) {n:?}");
        if k < syllables {
            locks.push(none.clone());
        }
    }
    println!("cách: tổng {total:.1} ms\n");

    // Chọn chữ: đã chốt hết, chọn lần lượt chữ Hán của từng âm từ đầu (như chọn 空 trên thanh gợi ý); phần còn lại vẫn
    // chốt. Rồi xoá chữ: gỡ từng chữ đã chọn từ chữ cuối.
    let segs: Vec<&str> = full.split('\'').collect();
    let hans: Vec<String> = segs
        .iter()
        .map(|s| engine.candidates(s).first().and_then(|c| c.text.chars().next()).unwrap_or('?').to_string())
        .collect();
    let pick_step = |engine: &mut Engine, i: usize| {
        let rest = segs[i..].join("'");
        let before: String = hans[..i].concat();
        let locks = vec![Vec::<(Part, String)>::new(); segs.len() - i];
        let t = Instant::now();
        let _ = engine.narrow_locked(&rest, &before, &locks, &none);
        let t1 = ms(t);
        let t = Instant::now();
        let c = engine.candidates_locked(&rest, &before, &locks, &none);
        (t1, ms(t), c.len())
    };
    let (mut total, mut worst) = (0.0, 0.0f64);
    for i in 1..segs.len() {
        let (t1, t2, _) = pick_step(&mut engine, i);
        total += t1 + t2;
        worst = worst.max(t1 + t2);
    }
    println!("chọn chữ {} lần: tổng {total:.1} ms, chậm nhất {worst:.1} ms", segs.len() - 1);
    let (mut total, mut worst) = (0.0, 0.0f64);
    for i in (0..segs.len() - 1).rev() {
        let (t1, t2, _) = pick_step(&mut engine, i);
        total += t1 + t2;
        worst = worst.max(t1 + t2);
    }
    println!("xoá chữ {} lần: tổng {total:.1} ms, chậm nhất {worst:.1} ms\n", segs.len() - 1);

    // Phím xoá: bỏ chốt âm cuối, bảng hỏi lại.
    let mut total = 0.0;
    while !locks.is_empty() {
        locks.pop();
        let (t1, t2, c, n) = step(&mut engine, &locks);
        total += t1 + t2;
        println!("xoá   {:>2}: narrow {t1:>6.1} ms, gợi ý {t2:>6.1} ms ({c}) {n:?}", locks.len());
    }
    println!("xoá bỏ chốt: tổng {total:.1} ms");

    // Hết âm chốt: xoá từng phím, mỗi lần hỏi lại gợi ý.
    let (mut total, mut worst) = (0.0, (0.0, 0));
    for n in (1..full.len()).rev() {
        let t = Instant::now();
        let _ = engine.candidates(&full[..n]);
        let d = ms(t);
        total += d;
        if d > worst.0 {
            worst = (d, n);
        }
    }
    println!("xoá {} phím: tổng {total:.1} ms, chậm nhất {:.1} ms còn {} phím", full.len() - 1, worst.0, worst.1);
}

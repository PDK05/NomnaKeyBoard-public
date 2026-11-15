//! TẠM THỜI: so phụ âm đầu cột trái hỏi ở âm đầu (chưa chốt) và ở âm kế tiếp sau khi chốt một âm.
use std::io::Read;

use hannom_core::{Dictionary, Engine, Part};

fn main() {
    let only_picks = std::env::var("PICKS").is_ok();
    let mut content = String::new();
    std::io::stdin().read_to_string(&mut content).unwrap();
    let (dict, _) = Dictionary::from_text(&content);
    let mut engine = Engine::new(dict);
    engine.options.telex = true;
    let words = [
        "b", "ba", "m", "mas", "v", "vi", "p", "ph", "phos", "d", "di", "gi", "gias", "r", "ra", "l", "laf", "n", "nam",
        "dd", "ddi", "t", "tooi", "th", "thi", "c", "co", "k", "ki", "qu", "qua", "g", "ga", "gh", "ghe", "ch", "cho",
        "tr", "tre", "h", "ha", "kh", "khi", "ng", "nga", "ngh", "nghe", "nh", "nha", "s", "sao", "x", "xa",
    ];
    if only_picks { picks(&engine); return; }
    for w in words {
        let first = engine.narrow(w, &[]);
        let keys = format!("tooi'{w}");
        let locks = vec![Vec::<(Part, String)>::new()];
        let locked = engine.narrow_locked(&keys, "", &locks, &[]);
        let show = |o: &Option<(Part, Vec<String>)>| match o {
            Some((Part::Initial, v)) => v.join("/"),
            Some((p, v)) => format!("[{p:?}] {}", v.iter().take(6).cloned().collect::<Vec<_>>().join("/")),
            None => "-".into(),
        };
        println!("{w:<6} âm đầu: {:<28} sau chốt: {}", show(&first), show(&locked));
    }
}

#[allow(dead_code)]
pub fn picks(engine: &Engine) {
    let locks = vec![Vec::<(Part, String)>::new()];
    for (keys, v) in [("tooi'laf", "n"), ("tooi'l", "n"), ("tooi'ba", "m"), ("tooi'tooi", "đ"), ("tooi'ddi", "t"), ("tooi'co", "g"), ("tooi'qua", "c")] {
        let picked = vec![(Part::Initial, v.to_string())];
        let t = std::time::Instant::now();
        let c = engine.candidates_locked(keys, "", &locks, &picked);
        let n = engine.narrow_locked(keys, "", &locks, &picked);
        let ms = t.elapsed().as_secs_f64() * 1000.0;
        let list: Vec<String> = c.iter().take(5).map(|c| format!("{}({})", c.text, c.reading)).collect();
        println!("{keys} chọn {v}: {ms:.1} ms {:?} | {}", n.map(|x| (x.0, x.1.into_iter().take(6).collect::<Vec<_>>())), list.join(" "));
    }
}

//! Đo tốc độ lõi theo từng phím, như bàn phím gọi: mỗi phím gõ thêm thì tra lại cả chuỗi phím.
//! Từ điển đọc từ stdin (như checkdict):
//!
//!     cargo run --release --target wasm32-wasip1 --example bench < ../dict/nomna-dict.txt
//!
//! Số đo là thời gian chạy WebAssembly trên máy tính; trên điện thoại (ARM, bản gốc) khác, nhưng chỗ nào
//! chậm tương đối thì vẫn chậm.

use std::io::Read;
use std::time::{Duration, Instant};

use hannom_core::{Dictionary, Engine, Part};

/// Thời gian nhỏ nhất qua vài lần chạy (bớt nhiễu), kèm kết quả lần cuối.
fn time<T>(runs: usize, mut f: impl FnMut() -> T) -> (Duration, T) {
    let mut best = Duration::MAX;
    let mut out = None;
    for _ in 0..runs {
        let t = Instant::now();
        let r = f();
        best = best.min(t.elapsed());
        out = Some(r);
    }
    (best, out.unwrap())
}

fn ms(d: Duration) -> f64 {
    d.as_secs_f64() * 1000.0
}

struct Stat {
    name: String,
    samples: Vec<(f64, String)>,
}

impl Stat {
    fn new(name: &str) -> Self {
        Stat { name: name.to_string(), samples: Vec::new() }
    }
    fn report(&mut self) {
        if self.samples.is_empty() {
            return;
        }
        self.samples.sort_by(|a, b| b.0.partial_cmp(&a.0).unwrap());
        let n = self.samples.len();
        let mean = self.samples.iter().map(|s| s.0).sum::<f64>() / n as f64;
        let p95 = self.samples[n / 20].0;
        let worst: Vec<String> = self.samples.iter().take(5).map(|(t, k)| format!("{k:?} {t:.1}")).collect();
        println!("{:<26} n={n:<4} tb {mean:>6.2} ms  p95 {p95:>6.2}  max {:>6.2}   chậm nhất: {}", self.name, self.samples[0].0, worst.join(", "));
    }
}

fn prefixes(s: &str) -> Vec<String> {
    let chars: Vec<char> = s.chars().collect();
    (1..=chars.len()).map(|i| chars[..i].iter().collect()).collect()
}

fn main() {
    let runs: usize = std::env::var("RUNS").ok().and_then(|v| v.parse().ok()).unwrap_or(3);
    let mut content = String::new();
    std::io::stdin().read_to_string(&mut content).expect("tệp UTF-8");

    let (load, (dict, _)) = time(runs, || Dictionary::from_text(&content));
    println!("nạp từ điển: {:.1} ms ({} mục)", ms(load), dict.len());
    let (parse_t, _) = time(runs, || hannom_core::dict::parse(&content));
    println!("  trong đó đọc dòng + kiểm tra âm (parse): {:.1} ms", ms(parse_t));
    if std::env::var("ONLY_LOAD").is_ok() {
        return;
    }
    // LOCK_RUN=1: chỉ đo ấn cách liên tục trong bảng thả (chốt lần lượt từng âm của câu dài, không chọn gì), như đo
    // trên điện thoại 2026-11-15 (narrow 99 → 398 ms ở âm thứ 10).
    if std::env::var("LOCK_RUN").is_ok() {
        let mut engine = Engine::new(dict);
        engine.options.telex = true;
        let keys = "tooi'ddi'chowj'tinhr'veef'mieenf'nam'thamaw'langw'bacs'hoof";
        let mut total = 0.0;
        for k in 0..11 {
            let locks = vec![Vec::new(); k];
            let (t, _) = time(1, || engine.narrow_locked(keys, "", &locks, &[]));
            let (c, _) = time(1, || engine.candidates_locked(keys, "", &locks, &[]));
            total += ms(t) + ms(c);
            println!("chốt {k:>2} âm: narrow_locked {:>7.1} ms, candidates_locked {:>6.1} ms", ms(t), ms(c));
        }
        println!("tổng: {total:.1} ms");
        return;
    }
    let (newt, mut engine) = time(1, || Engine::new(dict.clone()));
    println!("Engine::new: {:.1} ms", ms(newt));
    let (text_t, _) = time(1, || engine.dict.to_text());
    println!("dict.to_text (lưu): {:.1} ms", ms(text_t));
    let (rows_t, _) = time(1, || engine.rows("ng"));
    println!("dict rows(\"ng\") (màn từ điển): {:.1} ms", ms(rows_t));

    // Gõ kiểu Hán Nôm (mặc định): không dấu, gõ liền, gõ tắt.
    let words = ["t", "ng", "ngh", "nguoi", "truong", "nghieng", "thuong", "khuyen"];
    let joined = ["chunom", "nguoivietnam", "toilanguoivietnam", "hocsinhdihoc", "conduongxuabemangchungtadiquanhungnamthang"];
    let shortcuts = ["vn", "hstt", "nvn", "ktgdtt"];
    let mut by_len: Vec<(usize, f64)> = Vec::new();

    for (label, telex) in [("Hán Nôm", false), ("Telex", true)] {
        engine.options.telex = telex;
        println!("\n== Bàn phím {label} ==");
        let mut cand = Stat::new("candidates (từ)");
        let mut cand_j = Stat::new("candidates (gõ liền)");
        let mut cand_s = Stat::new("candidates (gõ tắt)");
        let mut read = Stat::new("readings (Quốc ngữ)");
        let mut nar = Stat::new("narrow cấp 1 (cột trái)");
        let mut nar2 = Stat::new("narrow cấp 2 + lưới");
        let mut after = Stat::new("after (chọn từng phần)");
        let sets: [(&[&str], &mut Stat); 3] = [(&words, &mut cand), (&joined, &mut cand_j), (&shortcuts, &mut cand_s)];
        for (list, stat) in sets {
            for w in list {
                for p in prefixes(w) {
                    let (t, _) = time(runs, || engine.candidates(&p));
                    stat.samples.push((ms(t), p.clone()));
                    if !telex && list.as_ptr() == joined.as_ptr() {
                        by_len.push((p.chars().count(), ms(t)));
                    }
                }
            }
        }
        for w in words.iter().chain(joined.iter().take(2)) {
            for p in prefixes(w) {
                let (t, _) = time(runs, || engine.readings(&p));
                read.samples.push((ms(t), p.clone()));
            }
        }
        // Cột trái: lần đầu hỏi một chuỗi phím (chưa có đệm), rồi chọn phụ âm đầu và dựng lưới.
        for w in words.iter().chain(joined.iter().take(3)) {
            for p in prefixes(w) {
                let fresh = Engine::new(engine.dict.clone());
                let mut fresh = fresh;
                fresh.options = engine.options;
                let (t, first) = time(1, || fresh.narrow(&p, &[]));
                nar.samples.push((ms(t), p.clone()));
                if let Some((Part::Initial, opts)) = first {
                    if let Some(o) = opts.first() {
                        let picked = vec![(Part::Initial, o.clone())];
                        let (t2, _) = time(1, || (fresh.narrow(&p, &picked), fresh.candidates_narrowed(&p, &picked)));
                        nar2.samples.push((ms(t2), format!("{p}/{o}")));
                    }
                }
            }
        }
        for (before, keys) in [("函", "stt"), ("越", "n"), ("𡨸", "nom"), ("人", "dan")] {
            for p in prefixes(keys) {
                let (t, _) = time(runs, || engine.after(before, &p));
                after.samples.push((ms(t), format!("{before}+{p}")));
            }
        }
        // Cách gõ thật (bàn phím Telex: phím cách là dấu tách `'`): đủ chữ, gõ tắt, trộn.
        let mut real = Stat::new("candidates (gõ thật)");
        let mut real_n = Stat::new("narrow (gõ thật)");
        if telex {
            for s in ["toi'la'nguoi'viet'nam", "tlnv", "toi'la'ng'v", "t'l'ng'v", "t'l'n'v", "chung'ta'cung'nhau'di'hoc'tieng'viet"] {
                for p in prefixes(s) {
                    let (t, _) = time(runs, || engine.candidates(&p));
                    real.samples.push((ms(t), p.clone()));
                    let mut fresh = Engine::new(engine.dict.clone());
                    fresh.options = engine.options;
                    let (t, _) = time(1, || fresh.narrow(&p, &[]));
                    real_n.samples.push((ms(t), p.clone()));
                }
            }
        }
        for s in [&mut cand, &mut cand_j, &mut cand_s, &mut real, &mut real_n, &mut read, &mut nar, &mut nar2, &mut after] {
            s.report();
        }
    }

    println!("\n== Gõ liền (Hán Nôm): thời gian theo số phím ==");
    let mut buckets: std::collections::BTreeMap<usize, Vec<f64>> = Default::default();
    for (len, t) in by_len {
        buckets.entry(len.div_ceil(5) * 5).or_default().push(t);
    }
    for (len, ts) in buckets {
        let max = ts.iter().cloned().fold(0.0, f64::max);
        println!("  ≤{len:>2} phím: tb {:>7.2} ms, max {max:>7.2}", ts.iter().sum::<f64>() / ts.len() as f64);
    }

    engine.options.telex = true;
    let ph = vec![vec![(Part::Initial, "ph".to_string())]];
    for keys in ["pt", "pcp", "pt'"] {
        let (t, opts) = time(1, || engine.narrow_locked(keys, "", &ph, &[]));
        let cands: Vec<String> = engine.candidates_locked(keys, "", &ph, &[]).iter().take(8).map(|c| c.text.clone()).collect();
        println!("chốt ph… + {keys}: {opts:?} {:.1} ms; {}", ms(t), cands.join(" "));
        let tr = [(Part::Initial, "tr".to_string())];
        let cands: Vec<String> = engine.candidates_locked(keys, "", &ph, &tr).iter().take(8).map(|c| format!("{}({})", c.text, c.reading)).collect();
        println!("   + tr…: {}", cands.join(" "));
    }
    // Đã chọn 政 cho `c` của `ctqgst`, chốt tr… cho `t`.
    let tr = vec![vec![(Part::Initial, "tr".to_string())]];
    let (t, cands) = time(1, || engine.candidates_locked("tqgst", "政", &tr, &[]));
    let list: Vec<String> = cands.iter().take(8).map(|c| format!("{}({})", c.text, c.reading)).collect();
    println!("政 + chốt tr… + tqgst: {:.1} ms; {}; cột: {:?}", ms(t), list.join(" "), engine.narrow_locked("tqgst", "政", &tr, &[]));
    // `twcmn` (trung ương cục miền nam): chọn tr… cho `t`, chốt → `w`; chọn ương, chốt → `c`.
    let p = |v: &[(Part, &str)]| v.iter().map(|(p, s)| (*p, s.to_string())).collect::<Vec<_>>();
    println!("twcmn đoạn âm: {:?}", engine.syllable_chunks("twcmn"));
    let mut locks = vec![p(&[(Part::Initial, "tr")])];
    for step in ["w", "c"] {
        let list: Vec<String> = engine.candidates_locked("twcmn", "", &locks, &[]).iter().take(6).map(|c| format!("{}({})", c.text, c.reading)).collect();
        println!("  chốt {} âm → cột ({step}): {:?}; lưới: {}", locks.len(), engine.narrow_locked("twcmn", "", &locks, &[]), list.join(" "));
        locks.push(p(&[(Part::Syllable, "ương")]));
    }
    engine.options.telex = false;
    for keys in ["t", "n", "ng", "nguoi", "hstt", "ktgdtt"] {
        println!("số gợi ý {keys}: {}", engine.candidates(keys).len());
    }
    for keys in ["ktct", "tct", "ct"] {
        let list: Vec<String> = engine.candidates(keys).iter().take(6).map(|c| format!("{}[{}]", c.text, c.consumed)).collect();
        println!("candidates {keys}: {}", list.join(" "));
    }
    for (before, keys) in [("人", "d"), ("越", "n"), ("函", "s"), ("經", "tct"), ("經濟", "ct"), ("經", "t")] {
        let list: Vec<String> = engine.after(before, keys).iter().take(6).map(|(c, t, _)| format!("{}({t})", c.text)).collect();
        println!("after {before}+{keys}: {}", list.join(" "));
    }

    // Chọn chữ (học thói quen): tăng độ phổ biến rồi tra lại.
    let c = engine.candidates("nguoi").into_iter().next().unwrap();
    let (t, _) = time(1, || engine.select(&c));
    println!("\nselect (học): {:.2} ms", ms(t));
}

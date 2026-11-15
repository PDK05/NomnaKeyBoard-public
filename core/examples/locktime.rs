//! TẠM THỜI: đo chốt âm có chọn phụ âm đầu ở cột trái, và gợi ý của các chuỗi phím đo trên điện thoại.
use std::io::Read;
use std::time::Instant;

use hannom_core::{Dictionary, Engine, Part};

fn main() {
    let mut content = String::new();
    std::io::stdin().read_to_string(&mut content).unwrap();
    let (dict, _) = Dictionary::from_text(&content);
    let mut engine = Engine::new(dict);
    engine.options.telex = true;
    let keys = "tooi'ddi'chowj'tinhr'veef'mieenf'nam'thamaw'langw'bacs'hoof";
    let inits = ["t", "đ", "ch", "t", "v", "m", "n", "th", "l", "b", "h"];
    let mut total = 0.0;
    for k in 0..inits.len() {
        // Mỗi âm đã chốt có chọn phụ âm đầu; âm kế tiếp cũng chọn phụ âm đầu (như bấm cột trái rồi ấn cách).
        let locks: Vec<Vec<(Part, String)>> = inits[..k].iter().map(|i| vec![(Part::Initial, i.to_string())]).collect();
        let picked = vec![(Part::Initial, inits[k].to_string())];
        let t = Instant::now();
        let a = engine.narrow_locked(keys, "", &locks, &[]);
        let t1 = t.elapsed().as_secs_f64() * 1000.0;
        let t = Instant::now();
        let b = engine.narrow_locked(keys, "", &locks, &picked);
        let c = engine.candidates_locked(keys, "", &locks, &picked);
        let t2 = t.elapsed().as_secs_f64() * 1000.0;
        total += t1 + t2;
        println!("chốt {k:>2}: narrow {t1:>6.1} ms, chọn {} + lưới {t2:>6.1} ms ({} gợi ý) {:?} {:?}", inits[k], c.len(), a.map(|x| x.0), b.map(|x| x.0));
    }
    println!("tổng: {total:.1} ms");
    for keys in ["tr", "sungs'vi't", "sungs'vi'tr", "sungs'vi'trungf'vaf't", "sungs'vi'trungf'vaf'th", "sungs'vi'trungf'vaf'thesp"] {
        let mut fresh = Engine::new(engine.dict.clone());
        fresh.options = engine.options;
        let t = Instant::now();
        let n = fresh.candidates(keys).len();
        println!("candidates {keys}: {:.1} ms ({n})", t.elapsed().as_secs_f64() * 1000.0);
    }
}

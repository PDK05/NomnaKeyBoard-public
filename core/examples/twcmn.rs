//! TẠM THỜI: thử chốt âm `twcmn` như trên điện thoại (trung ương cục + m n).
use std::io::Read;
use std::time::Instant;

use hannom_core::{Dictionary, Engine, Part};

fn main() {
    let mut content = String::new();
    std::io::stdin().read_to_string(&mut content).unwrap();
    let (dict, _) = Dictionary::from_text(&content);
    let mut engine = Engine::new(dict);
    engine.options.telex = true;
    let p = |v: &[(Part, &str)]| v.iter().map(|(p, s)| (*p, s.to_string())).collect::<Vec<_>>();
    let locks = vec![p(&[(Part::Initial, "tr")]), p(&[(Part::Syllable, "ương")]), p(&[(Part::Syllable, "cục")])];
    for keys in ["twcm", "twcmn", "twcmnb", "twc'mieenf'nam", "twcmieenf'n"] {
        for k in 2..=3 {
            let t = Instant::now();
            let c = engine.candidates_locked(keys, "", &locks[..k], &[]);
            let n = engine.narrow_locked(keys, "", &locks[..k], &[]);
            let ms = t.elapsed().as_secs_f64() * 1000.0;
            let list: Vec<String> = c.iter().take(10).map(|c| format!("{}({})", c.text, c.reading)).collect();
            println!("{keys} chốt {k}: {ms:.1} ms cột {:?}\n   {}", n.map(|x| x.0), list.join(" "));
        }
    }
    plain(&engine);
    let c = engine.candidates_locked("twcmn", "", &locks, &p(&[(Part::Initial, "m")]));
    println!("chọn m: {}", c.iter().take(6).map(|c| c.text.clone()).collect::<Vec<_>>().join(" "));
}

#[allow(dead_code)]
fn plain(engine: &Engine) {
    for keys in ["twcmieenf'nam", "twcmieenf", "twc'mieenf'nam", "hsmieenf", "exchanging", "telex'nhanh"] {
        let c: Vec<String> = engine.candidates(keys).iter().take(4).map(|c| format!("{}[{}]", c.text, c.consumed)).collect();
        println!("candidates {keys}: {}", c.join(" "));
    }
}

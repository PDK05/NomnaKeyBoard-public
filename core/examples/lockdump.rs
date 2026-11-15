//! TẠM THỜI: in kết quả chốt âm để so bản cũ / mới (đổi tốc độ không được đổi kết quả).
use std::io::Read;

use hannom_core::{Dictionary, Engine, Part};

fn main() {
    let mut content = String::new();
    std::io::stdin().read_to_string(&mut content).unwrap();
    let (dict, _) = Dictionary::from_text(&content);
    let mut engine = Engine::new(dict);
    engine.options.telex = true;
    let p = |v: &[(Part, &str)]| v.iter().map(|(p, s)| (*p, s.to_string())).collect::<Vec<_>>();
    let sentences = [
        "tooi'ddi'chowj'tinhr'veef'mieenf'nam'thamaw'langw'bacs'hoof",
        "sungs'vi'trungf'vaf'thesp",
        "vi'trungf'vaf",
        "twcmn",
        "ky'thuaatj'mowis",
        "ddaij'hocj'quoocs'gia",
        "nguoiwf'vieetj'nam",
        "ctqgst",
        "hsdh",
    ];
    for keys in sentences {
        let n = engine.syllable_chunks(keys).len();
        for k in 0..n {
            for picks in [vec![], p(&[(Part::Initial, "tr")]), p(&[(Part::Initial, "")]), p(&[(Part::Initial, "ch")]), p(&[(Part::Initial, "th")]), p(&[(Part::Initial, "đ")]), p(&[(Part::Initial, "ng")]), p(&[(Part::Initial, "gi")])] {
                let locks: Vec<Vec<(Part, String)>> = (0..k).map(|i| if i == 0 || i == k - 1 { picks.clone() } else { Vec::new() }).collect();
                for picked in [vec![], p(&[(Part::Initial, "t")]), p(&[(Part::Initial, "v")]), p(&[(Part::Initial, "tr")]), p(&[(Part::Initial, "ch")]), p(&[(Part::Initial, "th")]), p(&[(Part::Initial, "đ")]), p(&[(Part::Initial, "")])] {
                    let nar = engine.narrow_locked(keys, "", &locks, &picked);
                    let c: Vec<String> = engine.candidates_locked(keys, "", &locks, &picked).iter().map(|c| format!("{}|{}|{}", c.text, c.reading, c.consumed)).collect();
                    println!("{keys} k={k} {locks:?} {picked:?}\n  {nar:?}\n  {}", c.join(" "));
                }
            }
        }
    }
    for (before, keys) in [("政", "tqgst"), ("人", "dan")] {
        let locks = vec![p(&[(Part::Initial, "tr")])];
        let c: Vec<String> = engine.candidates_locked(keys, before, &locks, &[]).iter().map(|c| c.text.clone()).collect();
        println!("{before}+{keys} {:?}\n  {}", engine.narrow_locked(keys, before, &locks, &[]), c.join(" "));
    }
}

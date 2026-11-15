//! TẠM THỜI: cột trái khi gõ `a` (ý là anh).
use std::io::Read;
use hannom_core::{Dictionary, Engine, Part};
fn main() {
    let mut content = String::new();
    std::io::stdin().read_to_string(&mut content).unwrap();
    let (dict, _) = Dictionary::from_text(&content);
    let mut engine = Engine::new(dict);
    engine.options.telex = true;
    for keys in ["a", "a'k'chawcs", "e", "o", "ng", "t"] {
        println!("{keys}: {:?}", engine.narrow(keys, &[]));
        if let Some((p, opts)) = engine.narrow(keys, &[]) {
            let first = vec![(p, opts[0].clone())];
            println!("   chọn {:?}: {:?}", first, engine.narrow(keys, &first));
        }
        let c: Vec<String> = engine.candidates(keys).iter().take(8).map(|c| format!("{}({})", c.text, c.reading)).collect();
        println!("   {}", c.join(" "));
    }
    let _ = Part::Initial;
}

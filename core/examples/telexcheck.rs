//! In chữ Telex hiện ra cho từng chuỗi phím (đối số).
fn main() {
    for k in std::env::args().skip(1) {
        let mut out = Vec::new();
        for n in 1..=k.len() {
            out.push(format!("{}→{}", &k[..n], hannom_core::display_telex(&k[..n], hannom_core::ToneStyle::Old)));
        }
        println!("{}", out.join("  "));
    }
}

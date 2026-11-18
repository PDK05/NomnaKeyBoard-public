# PRD – Bàn phím Hán Nôm (Mobile: iOS, Android)

## 1. Tổng quan

### 1.1 Mục tiêu

Bàn phím ảo cho điện thoại với trải nghiệm giống Gboard (bố cục, thanh gợi ý, nhấn giữ, từ điển
cá nhân), dùng chung lõi gõ với bản desktop để gõ **Quốc ngữ** (Telex) và **Hán Nôm** (gõ âm đọc,
chọn chữ trên thanh gợi ý). Hỗ trợ **Telex đầy đủ** và **Telex rút gọn**, và nhập từ điển riêng
dạng `chữ;âm`.

Tên ứng dụng: **Bàn phím Hán Nôm**. Không có ngôn ngữ nào tên "Hán Nôm": trên giao diện (phím cách,
cài đặt, nhãn subtype), ngôn ngữ của chế độ này ghi là **㗂越 (漢喃)**.

### 1.2 Đối tượng sử dụng

- Người học, nghiên cứu, sáng tác chữ Hán Nôm trên điện thoại.
- Người dùng bản desktop muốn mang từ điển riêng sang điện thoại.

### 1.3 Phạm vi

- Trong phạm vi: bàn phím Android và iOS kiểu Gboard; chế độ Quốc ngữ / Hán Nôm / Tiếng Anh;
Telex đầy đủ &amp; rút gọn; thanh gợi ý; từ điển riêng; học thói quen; ứng dụng cài đặt đi kèm.
- Ngoài phạm vi: gõ vuốt (glide typing), nhập giọng nói, GIF/sticker, dịch, tìm kiếm web,
đồng bộ đám mây, bàn phím máy tính bảng tách đôi. Không dùng tên, logo, hình ảnh của Gboard.

### 1.4 Kiến trúc


| Thành phần        | Công nghệ                                                 | Ghi chú                                            |
| ----------------- | --------------------------------------------------------- | -------------------------------------------------- |
| `core/`           | Rust (dùng chung với desktop)                             | Android: JNI viết tay (`android-ffi/`); iOS: C FFI |
| `mobile/android/` | Kotlin, `InputMethodService`, View thuần (không AndroidX) | minSdk 26                                          |
| `mobile/ios/`     | Swift, Custom Keyboard Extension + ứng dụng chủ (SwiftUI) | iOS 15+; dùng App Group chia sẻ từ điển            |


Quy tắc Telex, Telex rút gọn, chế độ Hán Nôm, xếp hạng và định dạng từ điển **giống hệt**
`PRD-desktop.md` (F1, F2, F3, F5, F6) – mọi thay đổi quy tắc làm ở `core/` để hai bản luôn khớp.

## 2. Chức năng

### F1. Bố cục bàn phím (clone Gboard)

- QWERTY 4 hàng + hàng dưới: `?123`, nút chế độ (`V`/`喃`/`E`), phím cách, `.`, `Enter`.
- Nhấn giữ phím hiện ký tự phụ (vd. giữ `a` → â ă á à ả ã ạ; bàn phím Quốc ngữ dùng bảng như Gboard tiếng Việt: nguyên âm không có hàng `a A`, phụ âm không có bảng, giữ `d` chỉ ra đ);
giữ hàng trên → số 1–0.
- Bàn phím số &amp; ký hiệu, dấu câu Hán (，。、「」！？) khi ở chế độ Hán Nôm.
- Phím Shift: chạm một lần viết hoa 1 chữ, chạm đúp khoá viết hoa; tự viết hoa đầu câu (tắt được).
- Giữ phím xoá để xoá liên tục; vuốt trái trên phím xoá để xoá cả từ.
- Vuốt ngang trên phím cách để di chuyển con trỏ.
- Phản hồi rung và âm thanh khi gõ (bật/tắt), xem trước phím khi nhấn.
- Chủ đề sáng / tối / theo hệ thống; chiều cao bàn phím chỉnh được (3 mức).

### F2. Gõ Quốc ngữ &amp; Hán Nôm

**Flow:**

1. Người dùng chạm phím; chuỗi đang gõ hiện gạch chân trong ô nhập (composing text).
2. Lõi xử lý theo kiểu gõ đang bật (Telex đầy đủ hoặc rút gọn) và chế độ (Quốc ngữ / Hán Nôm).
3. Thanh gợi ý (F3) cập nhật; người dùng chạm gợi ý hoặc phím cách để chèn.

**Quy tắc:**

- Telex đầy đủ ở chế độ Quốc ngữ: biến đổi trực tiếp như Unikey (`vieejt` → việt).
- Telex rút gọn: gõ chữ gốc (`nom`), gợi ý hiện các dạng có dấu / chữ Hán Nôm khớp.
- Hán Nôm gõ âm đọc bằng Telex tự do như Quốc ngữ: phím cách một lần là tách chữ (khung gõ hiện dấu cách),
lần nữa mới chọn gợi ý đầu; không có gợi ý thì chốt chữ Quốc ngữ đang gõ. Quốc ngữ: phím cách chốt từ.
- Gõ tắt toàn phụ âm (Hán Nôm, Telex): nhiều phụ âm liền nhau không dấu cách thì mỗi chữ cái là một phụ âm
đơn, đúng phụ âm đó: không tự gộp sang phụ âm dài hơn (`bndtp`: n là n, không phải nh; p không phải ph) và
không gộp hai chữ cái thành phụ âm đôi (`nh` là n + h, `ch` là c + h). Riêng `q` (không tự là phụ âm đầu) là
qu; `dd` là đ. Một phụ âm đứng một mình (`n`) vẫn gợi ý mọi âm bắt đầu bằng nó.
- Ứng dụng đổi ô nhập hoặc con trỏ nhảy chỗ khác: chốt nguyên chuỗi đang gõ, không mất chữ.
- Ô nhập mật khẩu: tự chuyển chế độ Tiếng Anh, không lưu, không học.

### F3. Thanh gợi ý

- Dải nằm trên bàn phím, không cuộn; số ô và cỡ ô theo cụm chữ Hán dài nhất đang hiện:
toàn chữ đơn 6 ô, có từ kép 5 ô; 3–4 chữ 4 ô, tổng ≤ 11 chữ (4+4+2+1, 3+3+3+2); 5–6 chữ 3 ô,
tổng ≤ 11 chữ; cụm 7 chữ 3 ô (cụm 7 + một cụm ≤ 3 chữ + một chữ
đơn); 8–10 chữ 2 ô (cụm dài + một cụm ≤ 3 chữ); 11 chữ 2 ô (+ một chữ đơn); &gt; 11 chữ chỉ mình nó.
Ít gợi ý hơn số ngăn thì không giãn ra cả thanh: các ô rộng như khi đủ ngăn, xếp từ ngăn 1 bên
trái (một chữ đơn chiếm ngăn 1 trong 6), phần còn lại để trống. Mỗi lần chỉ một cụm dài (≥ 7). Lấy gợi ý theo thứ tự xếp hạng, cái nào không vừa khuôn thì xuống
bảng mở rộng; gợi ý đầu luôn trên thanh. Ô chữ Hán rộng theo số chữ; tràn thanh thì thu nhỏ đều
chữ cho vừa, tới 15sp; vẫn tràn thì gợi ý đầu đứng một mình, thu nhỏ tiếp cho vừa; từ 21 chữ trở
đi giữ nguyên cỡ chữ như lúc 21 chữ, thanh kéo ngang được. Chữ Latin không tính vào số
chữ, chỉ rộng vừa đủ cho chữ khỏi dính; đơn, kép, Latin hiện lẫn được. Nút hỏi thêm từ vào từ điển
không tính vào số ô (5 cụm 2 chữ vẫn đủ 5 ô), chỉ chiếm thêm bề rộng. Giữa các ô có vạch ngăn mảnh,
không chạm viền trên dưới.
- **Chữ Latin** (bàn phím Hán Nôm kiểu Telex; người dùng đặt 2026-11-18): mọi chữ gõ ra đều có lựa chọn giữ nguyên chữ
  Latin ở **vị trí thứ 2** (ngay sau gợi ý đầu), là chữ của âm đang được trỏ tới: chưa chốt âm thì chữ đầu (`khoong
  mootj hai` → thanh: 空沒𠄩 · không · …); đã chốt âm thì chữ Hán các âm đã chốt viết liền chữ Latin của chữ kế tiếp
  (空một). Gõ nhiều chữ thì **cả cụm Latin** (không một hai; đã chốt: 空một hai) không lên thanh mà đứng **ô đầu của
  bảng thả**. Chữ Latin luôn ở **ô 2 của thanh** (không xuống lưới). Chạm chọn ở cột trái chưa đổi chữ Latin; **chốt
  âm** rồi thì chữ Latin của âm đó chỉ là **các phần đã chọn**, thay cho cả âm đã gõ: gõ `thooi`, chạm t → vẫn `thôi`;
  chốt âm ở `t…` → `t` (không phải `tôi`); chốt khi đã chọn tới thanh → đúng âm đó; chốt mà chưa chọn gì → `thôi`
  (người dùng đổi 2026-11-18, thay quy tắc cũ `trung` chọn ch → chung). Bật 𡨸單
  cũng có chữ Latin ở vị trí thứ 2. Không còn phân biệt chữ phổ biến / chữ không phải tiếng Việt (`lag`, `alo`,
  `chunom` như nhau).
- Nút mũi tên ở cuối dải mở **bảng mở rộng** dạng lưới, chiếm chỗ bàn phím, cuộn dọc; chỉ hiện các
gợi ý chưa có trên thanh.
- **Cột chọn nhanh** (bên trái bảng mở rộng) thu hẹp âm tiết đầu từng cấp: phụ âm đầu → nguyên âm → vần →
phụ âm cuối → thanh. Đầu cột có sẵn mục đúng âm đã gõ (gõ `tôi` thì có `tôi`). Vần luôn được hỏi; vần không thể
có phụ âm cuối (vần đôi ôi, ai, âu…) thì khỏi hỏi phụ âm cuối (t → ô → ôi → thanh). Các thanh luôn xếp theo thứ tự cố định: ngang, huyền, sắc,
hỏi, ngã, nặng (ma, mà, má, mả, mã, mạ). Đã chọn một âm (kể cả mục chọn nhanh) thì cột vẫn hiện các thanh
khác của chính âm đó để đổi.
- **Nhóm phụ âm đầu** (do người dùng đặt): có một phụ âm trong nhóm xuất hiện ở cột chọn nhanh thì hiện cả
các phụ âm cùng nhóm, nhưng tự ẩn phụ âm nào ghép vào không ra chữ nào cho gọn (gõ `cũng`: có gũng nên hiện g,
không có kũng nên ẩn k; chỉ gõ phụ âm đầu thì ẩn phụ âm không có âm nào trong từ điển); phụ âm đã gõ đứng đầu.
Các nhóm:
  - b / m / v / p / ph / d / gi / r
  - l / n
  - đ / t / th
  - c / k / qu / g / gh
  - ch / tr
  - ng / ngh
  - s / x
  - h, kh, nh đứng riêng.

  Ngoại lệ một chiều: gõ d thì cột hỏi thêm đ (vẫn ẩn nếu không ra chữ); gõ đ thì không hỏi d.

  Đổi qu sang phụ âm khác thì bỏ u, vì chỉ qu mới có u (`quá` → cá, ká, gá, ghá); đổi sang qu thì thêm u.
  Gõ `q` (chưa có u, kể cả gõ tắt `qg`) thì cột mặc định là qu, không có lựa chọn q riêng: q → qu, c, g, k, gh.

  Gõ tắt toàn phụ âm (`ndtphn`): âm tiết đầu là chữ cái đầu (n), nên cột hỏi như khi chỉ gõ `n`: n, rồi các
  phụ âm dài hơn bắt đầu bằng nó (ng, nh, ngh), rồi phụ âm cùng nhóm; chọn phụ âm nào cũng ra chữ của phụ âm đó.
  Chưa chọn gì thì thanh gợi ý và lưới có chữ một âm như khi gõ `n` (cả chữ n, ng, nh, ngh; `tphn` → cả chữ t,
  th, tr; `ph` → cả chữ p và ph). Cụm gõ tắt và câu ghép tự động vẫn giữ mỗi chữ cái là một phụ âm (`cn` không
  thành 𡨸喃 chữ nôm). Thứ tự gợi ý: cụm có sẵn trong từ điển khớp cả chuỗi, rồi chữ của âm đầu (`phn` → 拜
  phải, 發 phát…); câu ghép tự động đứng sau 3 chữ đầu. Mỗi mảnh một chữ cái của câu ghép lấy chữ dùng nhiều
  nhất (cả phụ âm dài hơn: `phn` → 拜河內 phải hà nội, không phải pi).
- **Học cụm:** đưa ra cả cụm trong một lượt gõ (chọn từng phần `tphn` → 城, 舖, 河內, hay chọn câu ghép) thì cụm
  được thêm / tăng trong từ điển, gõ tắt toàn phụ âm còn nhớ cả mã (`城舖河內;tphn`): lần sau gõ `tphn` cụm đứng đầu.

  Mã: `INITIAL_GROUPS` trong `core/src/engine.rs`. Chỉ người dùng đổi nhóm, không tự thêm bớt.
- **`w` gõ một mình** là âm bắt đầu bằng ư (`w` → 央 ương, 約 ước…), cả trong gõ tắt: `tw` chọn tr ở cột,
chọn 中 thì `w` còn lại gợi ý 央 (trung ương).
- Gợi ý ghép tự động không giới hạn độ dài, kể cả cụm lặp một chữ (`nomnomnom…` → 喃喃喃…);
dài trên 11 chữ thì một mình nó trên thanh gợi ý.
- Khung gõ (pinyin) một dòng, dài quá thì ẩn phần đầu. Chuỗi phím đang gõ tối đa 80 phím, quá thì
bỏ phím và rung báo, để không treo bàn phím.
- Nhấn giữ một gợi ý: hiện âm đọc, "Xoá khỏi gợi ý" (với mục học được / từ điển riêng).
- Khi không gõ gì: thanh hiện nút nhanh – chuyển Telex đầy đủ ↔ rút gọn, mở từ điển, cài đặt,
dán từ bộ nhớ tạm.
- Chữ ngoài BMP (𡨸…) hiển thị bằng phông Nôm đóng gói trong ứng dụng; độ trễ cập nhật gợi ý
≤ 50 ms trên máy tầm trung.

### F4. Từ điển riêng (kiểu Gboard)

- Định dạng và validation giống `PRD-desktop.md` F6: mỗi dòng `chữ;âm đọc;độ phổ biến` (`喃;nôm;30`),
chữ nhiều âm thì mỗi âm một dòng, UTF-8, `#` chú thích. Học thói quen là tăng độ phổ biến ngay trong
từ điển như desktop F5; từ điển lưu khi đóng bàn phím.
- Từ điển đã lưu theo định dạng cũ (`chữ;âm1,âm2`) trong máy được đổi tên thành `dictionary-old.txt`,
bàn phím dùng từ điển mặc định mới.
- Một từ điển duy nhất như desktop F6: lần đầu là bản sao từ điển mặc định, mọi thay đổi ghi
thẳng vào đó.
- Ứng dụng cài đặt → "Từ điển": danh sách mỗi cặp chữ – âm một dòng kèm độ phổ biến, xếp a → z, có
tìm kiếm; nút `+` thêm mục gồm 3 ô "Chữ", "Âm đọc" (gõ được nhiều âm, ngăn cách bằng `,`), "Độ phổ
biến"; chạm một dòng để sửa (chữ, âm, độ phổ biến) / xoá. Quản lý, nhập (Gộp / Thay toàn bộ), tự gộp
từ điển mặc định khi cập nhật ứng dụng: như desktop F6.
- Nút "Từ điển mặc định": gộp lại mục mặc định còn thiếu, hoặc thay toàn bộ bằng từ điển mặc định.
- Nhập: chọn một hoặc nhiều tệp `.txt` từ bộ nhớ máy / Files / iCloud Drive / Google Drive; mở
hoặc chia sẻ một / nhiều tệp `.txt` vào ứng dụng (Share sheet / Intent). Các tệp được gộp tự
động vào từ điển; hiện xem trước từng tệp: số mục mới, trùng, lỗi (kèm số dòng) trước khi xác nhận.
- Xuất: tạo tệp `.txt` và mở hộp thoại chia sẻ.
- Thêm nhanh từ bàn phím: đang gõ kết thúc bằng `'`, từ đơn (`nom'`) hay cụm (`chu'nom'`), thì luôn
hỏi "Thêm … vào từ điển?" ở đầu thanh gợi ý, kể cả khi đã có gợi ý hay đã có trong từ điển (để thêm
chữ khác cùng âm đọc). Không có `'` cuối thì chỉ hỏi khi không có gợi ý và đang gõ một từ đơn
(không có `'` ở giữa) chưa có trong từ điển. Bấm vào mở ứng dụng với ô Âm đọc đã điền sẵn.
- Âm đọc điền sẵn chỉ tách âm tiết ở chỗ người dùng gõ `'` hoặc phím cách,
không tự tách: `chu'nom` → "chu nom", `chunom` → "chunom".
- Hán Nôm: Enter chèn đúng chữ Quốc ngữ đang hiện trong khung gõ; phím cách mới chọn chữ Hán.
- iOS: từ điển lưu trong App Group để keyboard extension đọc được; extension chỉ đọc, ứng dụng
chủ ghi.

### F5. Chuyển chế độ &amp; cài đặt

- Chạm nút chế độ ở hàng dưới: xoay vòng Quốc ngữ → Hán Nôm → Tiếng Anh; nhấn giữ hiện menu
chọn trực tiếp và chọn kiểu gõ đầy đủ / rút gọn.
- Phím quả địa cầu (iOS) / chuyển bàn phím (Android) giữ hành vi chuẩn của hệ điều hành.
- Ứng dụng cài đặt có: hướng dẫn bật bàn phím từng bước (iOS: bật "Cho phép truy cập đầy đủ"
chỉ cần cho rung/âm thanh, ghi rõ không gửi dữ liệu), kiểu đặt dấu cũ/mới, số gợi ý, học
thói quen (bật/tắt, xoá dữ liệu học), giao diện, rung/âm thanh.
- Cài đặt hỏng hoặc thiếu: dùng mặc định, bàn phím vẫn hoạt động.

### F6. Bảng emoji &amp; tìm emoji

- Hàng trên bảng emoji: nút quay lại (tròn), nút tìm, các nhóm (cuộn ngang), như Gboard.
- Tìm emoji theo tên và từ khoá CLDR (tiếng Việt, tiếng Anh; gõ không dấu được). Kết quả 2 hàng, cuộn
theo trang 7 cột; giữ ô để chọn màu da / giới tính, bản chọn chỉ được gõ ra, không đổi màu chung.
- Nhãn giao diện đổi theo bàn phím đang dùng (Tiếng Việt / Hán Nôm / Tiếng Anh). Chữ Hán Nôm do người
dùng quyết định; mục còn trống chờ bổ sung, tạm hiện tiếng Việt:


| Chỗ hiện                    | Tiếng Việt                     | Hán Nôm | Tiếng Anh                          |
| --------------------------- | ------------------------------ | ------- | ---------------------------------- |
| Nút tìm, chữ mờ trong ô tìm | Tìm kiếm                       | 尋檢      | Search                             |
| Tiêu đề ô tìm               | Tìm biểu tượng cảm xúc         | 尋表象感觸   | Search emoji                       |
| Tiêu đề nhóm gần đây        | Biểu tượng cảm xúc gần đây     | 表象感觸近低  | Recent emoji *(đề xuất)*           |
| Tiêu đề nhóm                | Mặt cười và biểu tượng cảm xúc | 𩈘唭表象感觸 | Smileys &amp; emotions *(đề xuất)* |
| Tiêu đề nhóm                | Người                          | 𠊛      | People *(đề xuất)*                 |
| Tiêu đề nhóm                | Động vật và thiên nhiên        | 動物吧天然   |                                    |
| Tiêu đề nhóm                | Thực phẩm và đồ uống           | 食品吧𣘊㕵  | Food &amp; drink *(đề xuất)*       |
| Tiêu đề nhóm                | Du lịch và địa danh            | 遊歷地名    | Travel &amp; places *(đề xuất)*    |
| Tiêu đề nhóm                | Hoạt động và sự kiện           | 活動吧事件   |                                    |
| Tiêu đề nhóm                | Đồ vật                         | 𣘊物     | Objects *(đề xuất)*                |
| Tiêu đề nhóm                | Biểu tượng                     | 表象      | Symbols *(đề xuất)*                |
| Tiêu đề nhóm                | Cờ                             | 旗       | Flags *(đề xuất)*                  |


- Trang biểu tượng chữ `:-)`, như Gboard: cao hơn trang emoji 136 dp (màn hình dọc), hàng trên là nút quay lại, nút
"gần đây" (icon đồng hồ) rồi tên các nhóm (cuộn ngang); mỗi nhóm một trang riêng, lưới 4 cột, không có tiêu đề.
Biểu tượng lấy từ Gboard (`tools/emoji/gen_emoticons_gboard.py`, 14 nhóm × 32). Tên nhóm tiếng Việt và tiếng Anh
theo Gboard; cột Hán Nôm chờ người dùng điền, còn trống thì tạm hiện tiếng Việt:


| Nhóm biểu tượng chữ | Tiếng Việt            | Hán Nôm | Tiếng Anh           |
| ------------------- | --------------------- | ------- | ------------------- |
| Classic             | Cổ điển               | 古典      | Classic             |
| Smiling             | Cười                  | 唭       | Smiling             |
| Love                | Tình yêu              | 情𢞅     | Love                |
| Hugging             | Ôm                    | 揞       | Hugging             |
| Flexing             | Gập cánh tay          | 岋𦑃𢬣   | Flexing             |
| Animals             | Động vật              | 動物      | Animals             |
| Surprise            | Ngạc nhiên            | 愕然      | Surprise            |
| Dancing             | Nhảy múa              | 𬦮𢷵    | Dancing             |
| Shruggie            | Nhún vai              | 撋𦠘     | Shruggie            |
| Table flip          | Lật bàn               | 搮盤      | Table flip          |
| Look of disapproval | Ánh nhìn không đồng ý | 映𥆾空同意  | Look of disapproval |
| Crying              | Khóc                  | 哭       | Crying              |
| Nervous             | Lo lắng               | 𢥈𢠯    | Nervous             |
| Pointers            | Con trỏ               | 𡥵𢸫    | Pointers            |


- Tên từng emoji (tiếng Việt, tiếng Anh theo CLDR, cột Hán Nôm tạm sinh tự động — **chưa thống nhất**):
[emoji-names.md](emoji-names.md).
- Gõ chữ Hán Nôm vào ô tìm: mỗi chữ được đổi ra mọi âm đọc của nó trong từ điển đang dùng (cả chữ người dùng tự
thêm), emoji khớp khi tên hay từ khoá có một chữ trùng một âm đọc đó (𩈘唭 tìm như "mặt cười"). Không dùng cột Hán
Nôm của emoji-names.md.

## 3. Yêu cầu phi chức năng

- Hiệu năng: bàn phím hiện lên ≤ 300 ms; mỗi phím ≤ 16 ms (không giật khung hình).
- Bộ nhớ: keyboard extension iOS ≤ 48 MB (giới hạn của hệ thống) – từ điển dùng định dạng
nhị phân ánh xạ bộ nhớ (mmap), không nạp toàn bộ vào RAM.
- Riêng tư: không yêu cầu quyền mạng; không gửi dữ liệu gõ; dữ liệu học chỉ lưu trên máy.
- Trợ năng: phím có nhãn cho TalkBack / VoiceOver; hỗ trợ cỡ chữ lớn trong ứng dụng cài đặt.
- Kiểm thử: unit test `core/` dùng chung; test UI bàn phím bằng Espresso (Android) và XCUITest
(iOS); kiểm thử thủ công trên Zalo, Messenger, Notes, Chrome/Safari.
- Phát hành: Google Play và App Store; ảnh, tên, biểu tượng là của dự án, không dùng thương hiệu
Google/Gboard.

## 4. Danh sách chức năng &amp; trạng thái


| STT | Chức năng                   | Mã  | Phụ thuộc    | Trạng thái                          |
| --- | --------------------------- | --- | ------------ | ----------------------------------- |
| 1   | Bindings `core/` (JNI)      | C1  | core desktop | \[x\] Xong                          |
| 2   | Bố cục bàn phím             | F1  | –            | \[\~\] Android                      |
| 3   | Gõ Quốc ngữ &amp; Hán Nôm   | F2  | C1, F1       | \[\~\] Android                      |
| 4   | Thanh gợi ý                 | F3  | F2           | \[\~\] Android                      |
| 5   | Từ điển riêng               | F4  | C1           | \[\~\] Android                      |
| 6   | Chuyển chế độ &amp; cài đặt | F5  | F2           | \[\~\] Android                      |
| 7   | Bảng emoji &amp; tìm emoji  | F6  | F1           | \[\~\] Android (thiếu nhãn Hán Nôm) |
| 8   | Bản Android                 | A1  | F1–F6        | \[\~\] Chạy được trên máy ảo        |
| 9   | Bản iOS                     | I1  | F1–F6        | \[ \] Chưa làm                      |

## 5. Nhật ký hiệu năng

### 5.1 Cách đo

- **Cụm test kinh điển** (từ 2026-11-16): bàn phím Hán Nôm kiểu Telex, đếm "không một hai … hai mươi chín ba mươi"
  (0 → 30; 15, 25 là "lăm", 21 là "mốt"): 60 âm, 341 phím. Thao tác đo: gõ từng phím, xoá từng phím, mở cột trái của
  bảng thả, chọn âm (phím cách trong bảng thả), xoá âm (bỏ chốt), chọn chữ Hán, xoá chữ (gỡ chữ đã chọn).
- **Lõi trên máy tính:** `core/examples/hist.rs` (gõ, xoá, cột trái, nạp từ điển; chỉ dùng hàm có từ bản đầu),
  `core/examples/cover.rs` (gợi ý đầu ở cuối cụm phủ bao nhiêu phím), `core/examples/classic.rs` (chọn âm, xoá âm,
  chọn chữ, xoá chữ; chỉ có từ bản ad9d10c). Chạy WebAssembly (Node), mỗi phiên bản build riêng, đo lại cùng lúc
  ngày 2026-11-16. Sai số giữa hai lần chạy khoảng ±10%: thay đổi dưới 10% coi như **ngang**.
- **Trên điện thoại**: nhật ký `HanNomPerf` (logcat). Điện thoại chậm hơn máy tính khoảng 2–3 lần.
- Phần trăm: **nhanh hơn X%** = (trước − sau) / trước; **chậm hơn X%** = (sau − trước) / trước. Mốc so là phiên bản
  liền trước.

### 5.2 Lõi qua các phiên bản (máy tính, cụm 0 → 30)

Thời gian trung bình mỗi lần (ms); trong ngoặc là lần chậm nhất.

| Phiên bản (ngày) | Nội dung chính | Gõ phím | Xoá phím | Cột trái | Nạp từ điển | Gợi ý đầu phủ |
| --- | --- | --- | --- | --- | --- | --- |
| ccd5b1a (10-30) | Bản đầu | 5,34 (15) | 5,16 (17) | 10,3 (26) | 161 | 7/341 phím (1 chữ) |
| 7c04db4 (11-01) | Một tay, xoá lùi Telex | 6,67 (22) | 6,41 (19) | 14,1 (35) | 194 | 7/341 |
| ae4d70e (11-09) | Tìm từ điển theo âm, bảng thả | 6,45 (19) | 5,98 (16) | 13,2 (28) | 178 | 7/341 |
| 81f6dcd (11-10) | Màn hình ngang (0.1.3) | 6,34 (18) | 6,03 (16) | 11,6 (24) | 253 | 7/341 |
| aa029d6 (11-10) | Trang biểu tượng (0.1.5) | 8,08 (45) | 8,04 (33) | 12,8 (25) | 211 | 7/341 |
| a55f5e5 (11-14) | Cột chọn nhanh theo nhóm phụ âm (0.1.6) | 8,07 (21) | 9,18 (31) | 5,34 (11) | 212 | 7/341 |
| cdb199e (11-14) | Câu ghép gõ tắt | 7,14 (22) | 6,55 (19) | 5,04 (12) | 157 | 7/341 |
| ad9d10c (11-15) | Chốt âm ở cột trái | 6,72 (16) | 6,30 (18) | 4,37 (10) | 122 | 7/341 |
| Hiện tại (11-16, chưa commit) | Ghép câu không giới hạn độ dài | 23,9 (111) | 19,5 (130) | 4,67 (12) | 112 | **341/341 phím (60 chữ)** |

So với phiên bản liền trước:

| Phiên bản | Gõ phím | Xoá phím | Cột trái | Nạp từ điển | Đánh giá |
| --- | --- | --- | --- | --- | --- |
| 7c04db4 | chậm hơn 25% | chậm hơn 24% | chậm hơn 37% | chậm hơn 20% | **Đi lùi** |
| ae4d70e | ngang (nhanh hơn 3%) | ngang (nhanh hơn 7%) | ngang (nhanh hơn 6%) | nhanh hơn 8% | Ngang |
| 81f6dcd | ngang | ngang | nhanh hơn 12% | chậm hơn 42% | Đi lùi (nạp từ điển) |
| aa029d6 | chậm hơn 27% (chậm nhất 18 → 45 ms) | chậm hơn 33% | chậm hơn 10% | nhanh hơn 17% | **Đi lùi** |
| a55f5e5 | ngang | chậm hơn 14% | **nhanh hơn 58%** | ngang | **Đột phá** (cột trái) |
| cdb199e | nhanh hơn 12% | nhanh hơn 29% | ngang (nhanh hơn 6%) | nhanh hơn 26% | Cải thiện |
| ad9d10c | ngang (nhanh hơn 6%) | ngang (nhanh hơn 4%) | nhanh hơn 13% | nhanh hơn 22% | Cải thiện |
| Hiện tại | chậm hơn 256% (×3,6) | chậm hơn 210% (×3,1) | ngang (chậm hơn 7%) | ngang (nhanh hơn 8%) | **Đột phá về chất lượng, đi lùi về tốc độ** |

Ghi chú:

- Mọi bản tới ad9d10c chỉ ghép câu cho chuỗi phím ngắn (80 phím, Telex 120): với cụm 0 → 30, gợi ý đầu chỉ là chữ
  "không" (7/341 phím). Bản hiện tại ghép cả câu dài bao nhiêu cũng được (gợi ý đầu là đủ 60 chữ). Gõ, xoá chậm hơn ×3
  là cái giá của việc này, nhưng vẫn chưa đạt được: chuỗi càng dài càng chậm (phím 324: 111 ms). Đây là việc cần sửa
  (5.5, bước 2).
- 81f6dcd, aa029d6: từ điển to thêm nên nạp chậm hơn; cdb199e, ad9d10c đọc từ điển nhanh hơn.

### 5.3 Bảng thả: chọn âm, xoá âm, chọn chữ, xoá chữ (máy tính, cụm 0 → 30)

Có từ bản ad9d10c. Tổng thời gian cả cụm (ms); trong ngoặc là lần chậm nhất.

| Thao tác | ad9d10c (11-15) | Hiện tại (11-16) | Thay đổi | Đánh giá |
| --- | --- | --- | --- | --- |
| Chọn âm (60 lần ấn cách) | 1973 | 1517 (60) | nhanh hơn 23% | Cải thiện |
| Xoá âm (bỏ chốt 60 âm) | 2186 | 1014 (~26) | **nhanh hơn 54%** | **Đột phá** |
| Chọn chữ (59 lần) | 1103 (60) | 1115 (53) | ngang | Ngang |
| Xoá chữ (59 lần) | 1093 (57) | 1090 (48) | ngang | Ngang |

### 5.4 Trên điện thoại

Các lần đo ghi lại trong lúc làm (đã ghi trong mã nguồn):

| Ngày | Thao tác | Trước | Sau | Thay đổi | Cách sửa |
| --- | --- | --- | --- | --- | --- |
| 2026-11-15 | Chốt âm thứ 10 (cột trái; âm 1: 99 ms) | ~400 ms | chưa đo lại trên điện thoại (máy tính: chọn âm cả cụm nhanh hơn 23%, 5.3) | — | Nhớ kết quả từng đoạn âm đã chốt |
| 2026-11-15 | Gõ phím, chuỗi ~1000 phím (lõi) | ~150 ms/phím (điện thoại) | ~10–25 ms (máy tính; chưa đo lại trên điện thoại) | **Đột phá** (khác máy đo, chưa ra %) | Ghép câu theo cửa sổ, tính tiếp từ lần trước |
| 2026-11-15 | Dựng khung gõ, chuỗi ~1000 phím (giao diện) | ~100 ms/phím | 5,6 ms trung bình (đo 11-16, chuỗi ngắn hơn) | **Đột phá** (chưa đo lại ở 1000 phím) | Nhớ chữ Quốc ngữ đã hiện của từng chữ |
| 2026-11-15 | Bảng thả nhận gợi ý mới | ~85 ms mỗi lần | ~0 ms | nhanh hơn ~100% | Không dựng lại khi chuỗi phím không đổi |
| 2026-11-15 | Ấn cách / xoá trong bảng thả (giao diện phải chờ) | 40–265 ms | không chờ (tính ở luồng nền) | giao diện hết khựng | Bảng gọi lõi ở luồng nền |
| 2026-11-15 | Chọn 空 khi đã chốt 17 âm | khựng ~100 ms (chờ tra gợi ý ~45 ms) | chưa đo lại | — | Tính trước cột trái; tra gợi ý sau khi dựng giao diện |
| 2026-11-16 | Thanh gợi ý khi chọn âm rồi xoá | chớp (đổi 2–3 lần) | đổi 1 lần | hết chớp | Không hiện lại đủ gợi ý khi lưới đang thu hẹp; không dựng bảng hai lần |

Đo bằng nhật ký `HanNomPerf` ngày 2026-11-16 (01:04–01:22, gõ thường trên Messenger):

| Việc | Luồng | Số lần | Trung bình | Chậm nhất | Số lần > 50 ms |
| --- | --- | --- | --- | --- | --- |
| Dựng khung gõ và thanh gợi ý mỗi phím | giao diện | 2337 | 5,6 ms | 56 ms | 1 |
| Tách âm (chỉ chuỗi phím chưa gặp; đã gặp thì lấy lại) | giao diện | 645 | 3,9 ms | 49 ms | 0 |
| Vẽ bảng thả | giao diện | 416 | 9,7 ms | 58 ms | 1 |
| Tra gợi ý khi gõ | nền | 374 | 38 ms | 174 ms | 108 |
| Tính bảng thả (chọn / xoá âm) | nền | 503 | 32 ms | 392 ms | 106 |
| Tính cột trái | nền | 2692 | 8 ms | 228 ms | 90 |

Luồng giao diện đã đạt (phím không khựng). Chỗ chậm là tính gợi ý ở luồng nền: gợi ý hiện trễ.

### 5.5 Kế hoạch cải thiện (2026-11-16)

Làm từng bước; mỗi bước người dùng thử và duyệt (giữ / bỏ) rồi mới làm bước sau.

| Bước | Việc | Mục tiêu | Trạng thái |
| --- | --- | --- | --- |
| 1 | Xoá phím lấy lại gợi ý đã tra lúc gõ (nhớ 400 trạng thái) | Xoá phím ~120 ms → ~0 ms | **Đã duyệt 2026-11-16.** Điện thoại: lấy lại 0,0 ms thay cho tra mới 6–14 ms (chậm nhất 60 ms); người dùng thấy không rõ rệt (cụm thử ngắn), không đổi quy tắc gõ |
| 4 | Tách âm một lần mỗi phím | Bớt ~10 ms giao diện mỗi phím | **Bỏ:** đã có sẵn (tách âm nhớ theo chuỗi phím); đề xuất ban đầu đọc nhầm nhật ký |
| 2 | Gõ phím không chậm dần theo độ dài chuỗi | ~20–30 ms mỗi phím ở mọi độ dài | **Đã duyệt 2026-11-16** (người dùng: gõ chữ đen và xoá chữ đen "đúng là đột phá"; chọn âm / chọn chữ / xoá âm / xoá chữ vẫn lag — bước 3). Máy tính, cụm 0 → 30: gõ phím trung bình 23,9 → 7,9 ms (nhanh hơn 67%), chậm nhất 111 → 15 ms (nhanh hơn 86%); xoá phím 19,5 → 7,2 ms (nhanh hơn 63%), chậm nhất 130 → 14 ms (nhanh hơn 89%). Gợi ý giống hệt bản trước (so 20 gợi ý đầu ở 723 phím, 4 câu: `core/examples/samecand.rs`). Điện thoại (01:47–01:54, người dùng gõ tự do, chuỗi tới 267 phím; so với 01:04–01:22 trước bước 1–2, khác nội dung gõ): tra gợi ý khi gõ trung bình 38 → 29 ms (nhanh hơn 24%), chậm nhất 174 → 77 ms (nhanh hơn 56%), số lần > 50 ms 29% → 8%; khoảng 27% lần cần gợi ý lấy lại từ bộ nhớ của bước 1 (0 ms; ước theo 768 lần hỏi, 559 lần tra mới). Chưa phẳng: chuỗi 100–199 phím vẫn ~40 ms trung bình (chữ không phải tiếng Việt / gõ tắt như `lamswz`, `moofsfst` không dùng được cách cắt cửa sổ) |
| 3 | Bảng thả: nhớ các âm đã chốt; âm nhiều chữ chỉ tính trang đầu | Chọn âm không tăng dần; chậm nhất < 100 ms trên điện thoại | **Tạm duyệt 2026-11-16** (người dùng: không ai chọn cụm 54 âm). Điện thoại, so lần thử 3a (02:09–02:12) với lần thử 3b (02:25–), khác nội dung gõ: tính bảng trung bình 58,8 → 23,9 ms (nhanh hơn 59%), chậm nhất 402 → 105 ms (nhanh hơn 74%), số lần > 50 ms 52% → 7%; tính cột trái 40,4 → 20,7 ms (nhanh hơn 49%); chữ đơn theo âm (mỗi 4 nhóm) 124 → 64 ms (nhanh hơn 48%). Còn lại: chọn âm tiến lên ~20–25 ms mỗi âm trên máy tính khi đã chốt ~40 âm (câu phủ phần phím còn lại tính lại mỗi lần). Chi tiết: **3a:** nhật ký điện thoại cho thấy chỗ lag chính là chờ, không phải tính: xoá âm khi đã chốt ~40 âm, bảng tự tính 2–8 ms mà chờ tới 402 ms sau các lần "tính trước" (33–128 ms mỗi lần) đang giữ lõi. Sửa: chỉ tính trước khi người dùng dừng tay 300 ms. Người dùng: "nhanh rõ rệt", nhưng bật 𡨸單 (chữ đơn) phải chờ lâu: điện thoại 1,1–1,5 giây khi đã chốt ~40 âm (tra lõi riêng cho từng âm, ~30 ms mỗi âm, đợi hết mới hiện). Sửa tiếp: tính 4 nhóm đầu rồi hiện ngay, các nhóm sau tính dần ở nền mỗi lần 4 nhóm, bỏ dở khi người dùng thao tác. **Tạm duyệt 2026-11-16** ("ổn hơn trước"), nhưng giữ phím xoá "siêu lag" và ấn nhanh ▲▼ cũng lag. Nhật ký: giữ xoá khi bật 𡨸單, phím lặp ~50 ms mà mỗi lần dựng lưới 10–150 ms, lưới luôn đuổi theo sau. Sửa (3a-2, chờ duyệt): thao tác dồn dập (hai lần dựng cách < 120 ms) thì khung gõ đổi ngay, lưới chỉ dựng lại trạng thái cuối khi dừng tay; ▲▼ tính từ trang đích của lần ấn trước, chỉ dựng đủ hàng cho trang đó. Người dùng **không chấp nhận** cách sửa ▲▼ (đã hoàn lại); chọn / xoá âm với nhiều âm vẫn "siêu lag". **3b (chờ duyệt):** đo trong lõi khi đã chốt 40 âm (máy tính ~36 ms mỗi lần): 30 ms là ghép câu cho các âm đã chốt — (1) tách đoạn thử mọi độ dài cụm cho mỗi âm (~800 lần, mỗi lần dựng khoá nhớ): thêm phép tra nhanh chữ cái đầu, không có cụm thì bỏ qua; (2) câu phủ phần phím còn lại tính lại mỗi lần chốt / bỏ chốt: nhớ 96 kết quả gợi ý gần nhất theo chuỗi phím. Máy tính, cụm 0 → 30, so ngay sau nhau với bản trước: chọn âm cả cụm nhanh hơn 22% (âm 54: 53 → 30 ms), xoá âm nhanh hơn 55% (mỗi âm 13–35 → 6–13 ms), chọn chữ nhanh hơn 22%, xoá chữ nhanh hơn 26%. Kết quả giống hệt (cột trái, gợi ý khi chốt / bỏ chốt / chọn phụ âm đầu trên 3 câu: `core/examples/samelock.rs`; gợi ý khi gõ: `samecand.rs`). |

Sửa lỗi phát hiện khi so với Gboard (5.6): chạm vào lưới lúc lưới đang chờ dựng lại thì bỏ qua (trước đó chọn trúng
chữ cũ, nuốt sai phím).

### 5.6 So với Gboard 拼音 (2026-11-16)

**Cách thử.** Điện thoại Android 16, Gboard 18.5.6, ô "Gõ thử" của ứng dụng. Chạm tự động qua adb
(`input tap`, mỗi lần ~0,09–0,1 giây khi chạm dồn dập), quay màn hình bằng trình ghi màn hình của máy (bật hiện điểm
chạm). Cùng nội dung đếm 0 → 30: Gboard gõ pinyin liền (153 phím, 61 âm), bàn phím Hán Nôm gõ Telex (341 phím, 60 âm).
Số lần chạm được nhận: bàn phím Hán Nôm đếm theo nhật ký `HanNomPerf`; Gboard đếm theo ảnh chụp trước / sau mỗi đợt.
Video quay màn hình 8 phút 45 giây. Máy quay không đều (37–55 khung/giây,
có chỗ hở tới 85 ms) nên độ trễ đo trên video chỉ là ước lượng.

| Thao tác | Gboard 拼音 | Hán Nôm | Bên tốt hơn |
| --- | --- | --- | --- |
| Gõ cả chuỗi 0 → 30 | Câu ghép sai nhiều chữ: 另一二三四五六七**八就是**十一十二**时**三十… (lần sau, đã học: …**把就是试一试而是**三十四**食物是**…) | Câu ghép đủ 60 chữ đúng âm (空𠬠𠄩𠀧𦊚…) | Hán Nôm (chất lượng) |
| Độ trễ khung gõ khi gõ nhanh (video, ước lượng) | Trung vị ~50 ms, 90% ~140 ms; chỉ ~33 lần vẽ lại cho 153 phím (gộp nhiều phím một lần vẽ) | Trung vị ~17 ms, 90% ~100 ms, chậm nhất ~130 ms; vẽ lại gần như mỗi phím | Hán Nôm (đo được trên một phần lần chạm) |
| Chốt âm dồn dập (~0,1 giây một lần) | **1/10** lần chạm được nhận | 9/10, rồi 50/60 (mất 17%) | Hán Nôm |
| Chốt âm nhịp ~0,3 giây | 7/20 | — | |
| Chốt âm nhịp ~1 giây | Nhận đủ; mỗi lần chốt ~0,9 giây mới thấy (video, ước lượng) | Mỗi lần tính bảng ~0,2 giây khi đã chốt 40–60 âm (nhật ký) | Hán Nôm |
| Chốt hết âm | Tự đưa cả câu vào ô ngay khi chốt âm cuối | Chờ chọn chữ (phím chốt âm mờ đi) | Tuỳ ý người dùng |
| Bỏ chốt / xoá dồn dập | Giữ xoá 4 giây vẫn chưa xoá hết đoạn pinyin 153 phím | Bỏ chốt 60 âm trong 8,7 giây, nhận đủ 60/60, về 0 ngay sau lần cuối | Hán Nôm |
| Chọn chữ dồn dập | Lần chạm không kịp thì **bỏ qua**, không hỏng gì | **Lỗi:** lưới đang chờ dựng lại vẫn nhận chạm, chọn trúng chữ cũ (空空 + `ai'…`, nuốt sai phím). Đã sửa: bỏ qua chạm khi lưới chưa dựng xong | Gboard (trước khi sửa) |

**Gboard làm tốt hơn ở đâu (suy ra từ video và ảnh chụp, chưa đọc mã của Gboard):**

1. **Không bao giờ áp thao tác lên kết quả cũ.** Đang bận thì bỏ qua lần chạm, thay vì xếp hàng hay chạm vào lưới cũ.
   Bỏ qua thì người dùng phải chạm lại, nhưng không bao giờ ra chữ sai.
2. **Gộp nhiều lần vẽ khung gõ khi gõ nhanh** (~1 lần vẽ cho 4–5 phím): bớt việc mỗi phím, phím không bao giờ khựng.
3. **Chốt âm không tính lại cả lưới.** Lưới vẫn hiện chữ của âm đầu (另令灵…) trong suốt lúc chốt các âm sau; chỉ cột trái
   (lựa chọn của âm kế tiếp) đổi. Việc mỗi lần chốt rất nhẹ.
4. **Chốt xong âm cuối thì đưa cả câu vào ô**: bớt một bước khi người dùng chỉ muốn sửa vài âm rồi lấy cả câu.

**Đề xuất cho các bản sau** (theo thứ tự lợi / công sức; mục 4 thay đổi cách dùng nên phải hỏi người dùng trước):

1. **Chốt âm nhận đủ mọi lần chạm** (hiện mất ~17% khi chạm 0,1 giây một lần): danh sách âm đã chốt chỉ là một dãy, cộng
   thêm ngay khi chạm; phần tính lại để sau (đã có hoãn dựng lưới khi thao tác dồn dập). Tìm chỗ đang bỏ lần chạm
   (`whenReady` / `canLock` khi bảng đang tính).
2. **Chốt âm chỉ tính cột trái**, như Gboard: lưới chữ của các âm đã chốt tính khi dừng tay (giảm ~0,2 giây mỗi lần
   chốt khi đã chốt 40–60 âm).
3. **Gộp vẽ khung gõ theo nhịp khung hình** (`Choreographer`) khi phím tới dồn dập.
4. (Hỏi người dùng) Có tự đưa cả câu vào ô khi đã chốt hết âm như Gboard không.
5. Giữ lợi thế hiện có: câu ghép dài đúng, xoá / bỏ chốt nhận đủ mọi lần chạm.

**Đã làm đề xuất 1 (2026-11-16, tạm duyệt, bản 0.1.8).** Đo lại thì không mất lần chạm nào: nhật ký ghi đủ 60 lần chạm xuống,
nhưng mỗi lần chốt phải chờ một lần dựng bảng (~0,2 giây), nên 60 lần chạm trong 3,5 giây xong 2 giây sau mới chốt hết
(lần đo trước đếm 50/60 khi hàng đợi chưa chạy hết). Sửa: phím chốt âm nhận lúc chạm xuống và chốt ngay, không chờ lần
dựng đang tra dở; bảng chỉ dựng lại khi dừng tay. Điện thoại: 60 lần chạm trong 3,5 giây chốt đủ 60/60, bảng chỉ tính 2
lần, xong **0,13 giây** sau lần chạm cuối (trước: ~2 giây). Chọn chữ dồn dập ở 𡨸單 sau khi sửa lỗi lưới cũ: chọn đúng
từng âm (空 → 𠬠 → 𠄩 → 𠀧), lần chạm lúc lưới chưa dựng xong bị bỏ qua như Gboard (30 lần chạm dồn dập nhận 4).

**Sửa thanh gợi ý nháy (2026-11-16, tạm duyệt, bản 0.1.8).** Mở / đóng bảng thả, chốt / bỏ chốt âm: thanh gợi ý vẽ hai
lần (đủ gợi ý, rồi bỏ các chữ của lưới), gợi ý dài và ngắn đổi chỗ liên tục. Sửa: bảng mở thì chỉ lưới vẽ thanh, một
lần khi dựng xong; gợi ý xuống lưới tính theo cách xếp thanh cho đủ gợi ý, không theo thanh đang hiện.

**Sửa mất lựa chọn chữ Latin (2026-11-16, thay APK của bản 0.1.8).** Gõ `lag`: lõi ghép được câu 痞咦 (la + g gõ tắt) phủ
hết phím, nên chữ Latin "lag" bị coi như đã có chữ Hán khớp trọn và xuống lưới. Sửa: chữ có đoạn toàn phụ âm đứng sau
một âm có nguyên âm (`lag` hiện la g) là chữ không phải tiếng Việt, chữ Latin lên thanh (vị trí 2); có nhiều chữ thì cả
cụm Latin (`không lag`) đứng trước chữ đầu (`không`). Gõ tắt dính liền âm gõ đủ (`twcmieenf`) và gõ liền (`chunom`) không
đổi. Đã chốt âm thì lựa chọn 空lag vẫn lên thanh khi thu bảng (bảng mở thì nằm ở lưới, như trước).

**Xoá trong bảng thả nhận lúc chạm, như chốt âm (2026-11-17, chờ duyệt).** Người dùng báo "mở bảng thả ra lại xoá chậm".
Đo cụm 0 → 30 trên điện thoại bằng chạm adb (bản đang sửa, chưa commit; gõ 341 phím, mở bảng, chốt 60 âm, bỏ chốt 60
âm, xoá phím): gõ, mở bảng, chốt âm đều ổn; bỏ chốt / xoá thì mỗi lần chạm phải chờ một lần dựng bảng (chờ 120 ms xem
còn chạm tiếp không, rồi tính lưới ~87 ms), chạm nhanh hơn nhịp đó thì xếp hàng, thả tay rồi vẫn xoá tiếp vài giây. Lõi
không phải nguyên nhân (máy tính: bỏ chốt 60 âm 691 → 705 ms, ngang). Sửa: nút xoá của bảng bỏ chốt / xoá phím ngay, lưới
dựng lại một lần khi dừng tay (đang lùi bước chọn ở cột trái thì vẫn chờ như cũ). Điện thoại, 60 lần chạm trong ~3,7 giây:

| Thao tác | Trước | Sau | Thay đổi |
| --- | --- | --- | --- |
| Bỏ chốt 60 âm: xong sau lần chạm cuối | 4,01 giây, còn 10 âm chưa bỏ | 0,17 giây, đủ 60/60 | **nhanh hơn 96%** |
| Bỏ chốt 60 âm: số lần tính / vẽ lưới | 47 / 47 | 2 / 2 | nhanh hơn 96% |
| Xoá 341 phím trong bảng: số lần vẽ lưới | 88 (đợt 320 lần chạm, còn ~65 phím chưa xoá khi đo) | 1 (xoá hết) | **Đột phá** |
| Chốt 60 âm (không đổi) | 0,13 giây | 0,17 giây | ngang |

Máy ảo: bỏ chốt 60 âm 4,08 → 0,18 giây; quay màn hình lúc bỏ chốt thấy thanh gợi ý chỉ đổi 2 lần, không lần nào quay
lại trạng thái cũ (không chớp). Máy ảo có lần báo "không phản hồi" lúc xoá: luồng vẽ kẹt trong trình đồ hoạ của máy ảo
(`qemu_pipe_read`), bản trước cũng có khung hình treo 4,95 giây như vậy, không do bàn phím.

Người dùng thử: giữ xoá lúc đã chốt âm "hơi nhanh quá" (không còn bị bảng kìm, giữ xoá nhanh dần tới 20 ms một âm). Sửa:
giữ xoá khi lần xoá kế tiếp là cả một âm đã chốt / chữ đã chọn thì đều 0,2 giây một lần (nhịp cũ), không nhanh dần; hết
âm chốt thì xoá phím nhanh dần như bàn phím. Máy ảo, giữ xoá 3 giây khi đã chốt 60 âm: bỏ chốt 13 âm, cách nhau ~0,22 giây.

Cùng đợt (2026-11-17, chờ duyệt):

- **Khung kính hiện sớm khi xoá lùi.** Phần bị "…" che ngắn (ít hơn 5 lần xoá) thì khung kính chỉ hiện khi chỗ xoá đã
  vào phần bị che, không kịp thấy đếm ngược. Sửa: còn "…" mà chỉ còn ≤ 5 lần xoá nữa là hết chữ chọn / âm chốt thì khung
  kính hiện từ lần đó (ở chữ chọn / âm chốt sau cùng), đếm 5, 4, 3, 2, 1. Máy ảo, 15 âm (2 âm bị che): hiện từ số 5.
- **Chữ Latin cho chữ gõ liền không có trong từ điển** (`alo`). Chỉ có câu lõi tự ghép (音嚧 = a + lo) phủ hết phím nên
  chữ Latin nằm trong lưới. Sửa: chữ gõ liền nhiều âm, âm nào cũng có nguyên âm, mà không mục từ điển nào khớp trọn (câu
  tự ghép không tính) là chữ không phải tiếng Việt: chữ Latin lên thanh (vị trí 2), như `lag`. Gõ tắt (có đoạn toàn phụ
  âm) không đổi. Hệ quả: `chunom` (không dấu) chỉ có câu tự ghép 眾掩 nên giờ cũng có chữ Latin "chunom" ở vị trí 2.
  Người dùng chọn giữ, rồi đặt luôn quy tắc chung (mục F3 "Chữ Latin"): mọi chữ có chữ Latin ở vị trí thứ 2, cả cụm Latin
  ở ô đầu bảng thả, chọn ở cột trái thì theo lựa chọn, 𡨸單 cũng có. Máy ảo: `khoong mootj hai` → thanh 空沒𠄩 · không,
  ô đầu bảng thả "không một hai", chốt không → 空một; `trung` chọn ch → chung (ô 2), bật 𡨸單 → 眾 · chung.
- **Trang từ điển:** (1) bỏ trống ô khi thêm / sửa mục thì báo "Chưa nhập chữ" / "Chưa nhập âm đọc" (trước: "thiếu âm
  đọc sau dấu ;", nói theo định dạng tệp); (2) dòng phụ dưới tiêu đề xuống dòng khi không đủ chỗ (trước: "A → Z" bị cắt ở
  mép nút tìm / thêm trên điện thoại); (3) bật / tắt từ điển tiếng Anh "siêu lag": mỗi lần gạt đếm lại số mục mọi từ điển,
  đọc lại cả tệp tiếng Anh (121.813 mục) trên luồng giao diện, **1,43 giây** trên máy ảo. Sửa: nhớ số mục theo tệp (không
  đổi thì không đếm lại, bật / tắt không đổi tệp), đếm lần đầu ở luồng nền ("Đang đếm số mục…" rồi hiện số).
  (4) mở từ điển tiếng Anh "siêu lag" (2026-11-18): trên luồng giao diện, đọc + tách tệp **1.437 ms** (mỗi dòng tách
  bằng regex, chuẩn hoá NFC), xếp a → z **797 ms** (mỗi lần so sánh bỏ dấu lại cả hai từ bằng regex + NFD); trang hiện
  sau 2,96 giây; mỗi chữ gõ vào ô tìm cũng xếp lại. Sửa: dòng đã đúng dạng thì không tách regex / chuẩn hoá lại; khoá bỏ
  dấu tính một lần mỗi mục, xếp một lần, giữ đến khi sửa từ điển; chữ ASCII bỏ dấu chỉ là chữ thường. Máy ảo: đọc 219 ms,
  xếp 53 ms, tổng **2.234 → 272 ms (nhanh hơn 88%)**, trang hiện sau 1,05 giây (trang nào cũng mất ~0,6 giây để hiện
  trên máy ảo); ô tìm 5–16 ms mỗi chữ. Từ điển tiếng Việt cũng nhờ: 121 ms.

- **Phím đầu tiên lag sau khi bàn phím khởi động** (cài bản mới, hay Android tắt bàn phím rồi bật lại; người dùng báo
  2026-11-18). Nhật ký điện thoại: nạp từ điển chặn luồng giao diện ~0,66 giây (đọc 31 ms, nạp vào lõi 270 ms, tách số gốc
  / phần đã học `rebase` **362 ms**, bước mới của đợt "học không làm mòn số gốc"). Các lần mở bàn phím sau, phím đầu
  16–18 ms như phím thường. Sửa: (1) `rebase` đọc thẳng từng dòng từ điển đóng gói, không dựng từ điển thứ hai (máy tính:
  150 → 100 ms, `core/examples/rebasetime.rs`, kết quả giống hệt); (2) chạy ở luồng nền ngay sau khi nạp, vì nó không đổi
  độ phổ biến dùng để xếp gợi ý (chỉ cần cho lúc chia đôi phần đã học); riêng lần gộp từ điển sau khi cài bản mới (lưu tệp
  ngay sau đó, một lần mỗi lần cài) vẫn làm ngay. Máy ảo: luồng giao diện chỉ còn đọc + nạp (~180–220 ms), `rebase` ở nền
  190–290 ms. Gõ ngay trong lúc `rebase` đang chạy thì phím đó chờ phần còn lại. Điện thoại (01:28, ngay sau khi cài):
  luồng giao diện nạp từ điển ~663 → ~318 ms (**nhanh hơn 52%**: đọc 31 + nạp 287 ms); `rebase` ở nền 238 ms, xong 1,4 giây
  trước phím đầu; phím đầu (`onChar`) 46 ms, không phải chờ.

**Bản 0.1.9 (2026-11-18, bản thử, release `v0.1.9-test`).** Gồm mọi sửa đổi ở trên từ 2026-11-17 (xoá trong bảng thả
nhận lúc chạm, khung kính, chữ Latin ở vị trí 2, trang từ điển, khởi động) và đợt giao diện theo số đo Gboard trên điện
thoại (2026-11-18):

- Màn hình dọc: hàng phím mức "Vừa" 46 dp (trước 50; các mức 40 / 46 / 52), thanh gợi ý / công cụ 46 dp (trước 48), khe
  giữa các hàng 8 dp; hàng số thấp hơn hàng chữ (0,932) và hàng phím cách cao hơn (1,068) như màn hình ngang; bàn phím số
  khe 6 dp, phím cao hơn; emoji hàng cao 1,15 × bề rộng ô, cỡ emoji 0,755 × ô. Cỡ chữ trên phím giữ như cũ.
- Thanh công cụ: icon ⊞ 28 dp, nút tròn góc phải 34 dp với icon 20 dp; các icon vẽ lại theo Gboard.
- Bảng sửa văn bản che cả thanh công cụ, đầu bảng có nút ← về bàn phím và tên bảng (như bảng nhớ tạm).
- Bảng tiện ích: thêm nút "Ngôn ngữ tiếp theo" (đổi bàn phím như chạm V / EN / 喃); nút ✎ là viên viền mảnh với bút chì
  nét viền như Gboard.
- Trang từ điển: màn hình ngang chừa tai thỏ và thanh điều hướng (nền thanh tiêu đề tràn qua); hộp thoại thêm / sửa nằm
  trên bàn phím, không nháy khi bàn phím đang mở; trang danh sách ngôn ngữ bỏ giữ để chọn và mục xoá.

Còn chậm (chưa sửa): tra gợi ý khi gõ ở luồng nền trên điện thoại trung bình 43 ms, 39% số lần > 50 ms (gợi ý hiện trễ,
phím không khựng); mở bảng tra cột trái 107–129 ms.

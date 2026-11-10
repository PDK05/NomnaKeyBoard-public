r"""Tạo mobile/android/app/src/main/assets/emoticons.txt: các nhóm biểu tượng chữ (trang `:-)`) đúng như Gboard.

    python -I tools/emoji/gen_emoticons_gboard.py <base.apk của Gboard> <aapt2> mobile/android/app/src/main/assets/emoticons.txt

<base.apk của Gboard>: lấy từ máy (`adb shell pm path com.google.android.inputmethod.latin`, rồi `adb pull`).
<aapt2>: trong Android SDK, `build-tools/<phiên bản>/aapt2`.

Mỗi nhóm là một tệp XML đã biên dịch trong `res/` (tên tệp bị đổi ngẫu nhiên mỗi phiên bản): `softkey_list` gồm các
`softkey`, mỗi cái có `param_data` là một biểu tượng chữ, hoặc tham chiếu `@0x7f…` tới một chuỗi (những biểu tượng có
dấu `\`). Nhận ra nhóm theo biểu tượng đầu tệp. Đo trên Gboard 18.5 (11/2026): 14 nhóm, mỗi nhóm 32 biểu tượng.

Định dạng emoticons.txt: mỗi nhóm một khối, các khối cách nhau một dòng trống; dòng đầu khối là tên nhóm (tiếng Việt
như Gboard), sau đó mỗi dòng một biểu tượng. Không dùng ký hiệu đầu dòng cho tên nhóm vì biểu tượng có thể bắt đầu bằng
bất kỳ ký tự nào (`=_=`, `#`…), và có thể chứa dấu cách nên không gộp nhiều cái một dòng.
"""

import re
import subprocess
import sys
import zipfile

# Biểu tượng đầu tệp → tên nhóm, theo thứ tự các nhóm trên hàng nhóm của Gboard.
GROUPS = [
    (":-)", "Cổ điển"),
    ("(•‿•)", "Cười"),
    ("(●♡∀♡)", "Tình yêu"),
    ("⊂((・▽・))⊃", "Ôm"),
    ("ᕙ(⇀‸↼‶)ᕗ", "Gập cánh tay"),
    ("V●ᴥ●V", "Động vật"),
    ("(･o･;)", "Ngạc nhiên"),
    ("♪～(´ε｀ )", "Nhảy múa"),
    ("¯\\_(ツ)_/¯", "Nhún vai"),
    ("(╯°□°）╯︵ ┻━┻", "Lật bàn"),
    ("ಠ_ಠ", "Ánh nhìn không đồng ý"),
    ("•́  ‿ ,•̀", "Khóc"),
    ("(๑•﹏•)", "Lo lắng"),
    ("┗(•ˇ_ˇ•)―→", "Con trỏ"),
]

PARAM = re.compile(r'A: param_data=(?:"(.*)" \(Raw: |(@0x[0-9a-f]+))')


def run(aapt2, *args):
    return subprocess.run([aapt2, *args], capture_output=True, encoding="utf-8", check=True).stdout


def strings(aapt2, apk):
    """Chuỗi mặc định (không theo ngôn ngữ) của mọi tài nguyên string: id → giá trị."""
    out = {}
    current = None
    for line in run(aapt2, "dump", "resources", apk).splitlines():
        m = re.match(r"\s*resource (0x[0-9a-f]+) string/", line)
        if m:
            current = m.group(1)
            continue
        m = re.match(r'\s*\(\) "(.*)"$', line)
        if current and m:
            out[current] = m.group(1)
            current = None
    return out


def main(apk, aapt2, out_path):
    with zipfile.ZipFile(apk) as z:
        names = [n for n in z.namelist() if n.startswith("res/") and n.endswith(".xml") and b"param_data" in z.read(n)]
    lookup = None
    found = {}
    for name in names:
        items = []
        for line in run(aapt2, "dump", "xmltree", "--file", name, apk).splitlines():
            m = PARAM.search(line)
            if not m:
                continue
            if m.group(2):
                lookup = lookup or strings(aapt2, apk)
                items.append(lookup[m.group(2)[1:]])
            else:
                items.append(m.group(1))
        for first, title in GROUPS:
            if items and items[0] == first:
                found[title] = items
    missing = [t for _, t in GROUPS if t not in found]
    if missing:
        sys.exit(f"Không tìm thấy nhóm: {', '.join(missing)}")
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n\n".join("\n".join([title, *found[title]]) for _, title in GROUPS) + "\n")
    sys.stdout.reconfigure(encoding="utf-8")
    print(f"{len(GROUPS)} nhóm, {sum(len(v) for v in found.values())} biểu tượng → {out_path}")


if __name__ == "__main__":
    main(*sys.argv[1:4])

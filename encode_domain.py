import base64
import re

def main():
    path = "android/app/src/main/java/com/sync/xxx/MainActivity.kt"

    with open(path, "r", encoding="utf-8") as f:
        code = f.read()

    # cari nilai RAW_URL (domain plain) di dalam tanda kutip
    m = re.search(r'private val RAW_URL\s*=\s*"([^"]*)"', code)
    if not m:
        raise SystemExit("RAW_URL tidak ditemukan di MainActivity.kt")

    plain = m.group(1).strip()
    if not plain:
        raise SystemExit("RAW_URL kosong")

    # jangan encode ulang kalau sudah base64
    if not (plain.startswith("http://") or plain.startswith("https://")):
        print("RAW_URL tampak sudah ter-encode, skip.")
        return

    encoded = base64.b64encode(plain.encode("utf-8")).decode("utf-8")

    new_code = code.replace(
        'private val RAW_URL = "%s"' % plain,
        'private val RAW_URL = "%s"' % encoded
    )

    with open(path, "w", encoding="utf-8") as f:
        f.write(new_code)

    print(f"Domain di MainActivity.kt ter-encode jadi Base64:")
    print(f"  plain   : {plain}")
    print(f"  encoded : {encoded}")

if __name__ == "__main__":
    main()

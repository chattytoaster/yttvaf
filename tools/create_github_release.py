import os
import sys
import json
import subprocess
import urllib.request
import urllib.parse

def get_git_token():
    cred_proc = subprocess.Popen(
        ['git', 'credential', 'fill'],
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True
    )
    out, _ = cred_proc.communicate("protocol=https\nhost=github.com\n")
    for line in out.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1].strip()
    raise RuntimeError("Could not retrieve token from git credential manager")

def main():
    token = get_git_token()
    repo = "chattytoaster/yttvaf"
    tag = sys.argv[1] if len(sys.argv) > 1 else "v1.0.2"
    title = sys.argv[2] if len(sys.argv) > 2 else f"YouTube TV Mod {tag} (Floating Skip Popup & Full EN/RU Localization)"
    body = f"""# YouTube TV Mod {tag}

Release of modified **YouTube for Android TV** client (`com.chatty.yttvaf`) based on Cobalt / Leanback engine.
[🇬🇧 Read in English](https://github.com/chattytoaster/yttvaf/blob/main/README.md) | [🇷🇺 Читать на русском](https://github.com/chattytoaster/yttvaf/blob/main/README_RU.md)

---

### 🌟 What's New in {tag} / Что нового:

#### 💬 Floating Disappearing Popup Window (OSD) / Всплывающее окно пропуска
- **Instant Visual Feedback**: Whenever a sponsor or promotional segment is skipped, a sleek floating notification pops up in the top-right corner of the TV screen:
  `⏩ Skipped sponsor (13s)` / `⏩ Пропущен спонсор (13 сек)`.
- **System Toast Bridge**: In addition to on-screen DOM rendering, skip events are bridged to native Android TV toasts for guaranteed visibility on any TV firmware.
- **HUD Indicator**: Also clearly displays playback speed adjustments (⚡ Speed: 1.25x), resolution locks (📺 Quality: 1080p), and SponsorBlock toggle status.

#### 🌍 Full Multilingual Localization (EN & RU) / Полная двуязычная локализация
- **Auto-Detection & Manual Switcher**: Automatically selects English or Russian based on your Android TV system language, with easy manual switching in both the TV Settings Menu and Web UI (`[EN] / [RU]`).
- **Complete In-App Translation**:
  - Leanback TV Settings Dialog (opened via MENU / SETTINGS / GUIDE / RED button).
  - SponsorBlock Category Selector dialog on TV screen.
  - SOCKS5 input dialog and setup instructions.
  - Embedded Web Management UI (`http://<TV_IP>:8888`).
- **Bilingual Documentation**:
  - Full project manuals: [README.md (EN)](https://github.com/chattytoaster/yttvaf/blob/main/README.md) & [README_RU.md (RU)](https://github.com/chattytoaster/yttvaf/blob/main/README_RU.md).
  - Step-by-step update guides: [UPDATING_EN.md (EN)](https://github.com/chattytoaster/yttvaf/blob/main/UPDATING_EN.md) & [UPDATING.md (RU)](https://github.com/chattytoaster/yttvaf/blob/main/UPDATING.md).

#### 🎯 SponsorBlock Category Selection / Выбор категорий
- Interactive multi-choice category selector dialog directly on the TV screen and checkboxes in Web UI (`:8888`).
- 8 customizable categories: Sponsor, Self-promotion, Interaction reminder, Intro, Outro, Preview, Filler/Tangent, Music off-topic.

#### ⚡ Built-in SOCKS5 Proxy Subsystem / Встроенный SOCKS5 Прокси
- Authenticated loopback tunnel on port `9876` for transparent routing of Cobalt, Cronet, and Chromium.
- Smart TV YouTube unblocking without third-party VPN apps or router alterations.

#### 📺 Quality Lock & Playback Speed / Фиксация качества и скорости
- Lock desired resolution up to 4K 2160p.
- Variable playback speed (1.0x - 2.0x).

---

### 📦 Installation / Установка:
```bash
adb connect <TV_IP>:5555
adb install -r YouTubeTV-Mod-{tag}.apk
adb shell monkey -p com.chatty.yttvaf -c android.intent.category.LEANBACK_LAUNCHER 1
```
"""

    headers = {
        "Authorization": f"token {token}",
        "Accept": "application/vnd.github.v3+json",
        "User-Agent": "yttvaf-release-uploader"
    }

    # 1. Create Release
    print(f"Creating release {tag} in {repo}...")
    release_data = json.dumps({
        "tag_name": tag,
        "target_commitish": "main",
        "name": title,
        "body": body,
        "draft": False,
        "prerelease": False
    }).encode("utf-8")

    req = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/releases",
        data=release_data,
        headers=headers,
        method="POST"
    )

    try:
        with urllib.request.urlopen(req) as resp:
            resp_data = json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        err_msg = e.read().decode("utf-8")
        print(f"HTTPError {e.code}: {err_msg}")
        # If release already exists, fetch it
        req_get = urllib.request.Request(
            f"https://api.github.com/repos/{repo}/releases/tags/{tag}",
            headers=headers
        )
        with urllib.request.urlopen(req_get) as resp:
            resp_data = json.loads(resp.read().decode("utf-8"))

    release_id = resp_data["id"]
    upload_url_template = resp_data["upload_url"]
    upload_base = upload_url_template.split("{")[0]
    print(f"Release created with ID {release_id}. Uploading asset...")

    # 2. Upload APK asset
    apk_path = os.path.join(os.path.dirname(__file__), "..", "MODIFIED_FILE.apk")
    apk_size = os.path.getsize(apk_path)
    asset_name = f"YouTubeTV-Mod-{tag}.apk"
    upload_url = f"{upload_base}?name={urllib.parse.quote(asset_name)}"

    print(f"Uploading {asset_name} ({apk_size} bytes) to {upload_url}...")
    upload_headers = {
        "Authorization": f"token {token}",
        "Content-Type": "application/vnd.android.package-archive",
        "Content-Length": str(apk_size),
        "User-Agent": "yttvaf-release-uploader"
    }

    with open(apk_path, "rb") as f:
        upload_req = urllib.request.Request(
            upload_url,
            data=f,
            headers=upload_headers,
            method="POST"
        )
        with urllib.request.urlopen(upload_req) as up_resp:
            up_data = json.loads(up_resp.read().decode("utf-8"))
            print(f"Asset uploaded successfully! Download URL: {up_data.get('browser_download_url')}")

    print(f"=== Release is published at: {resp_data.get('html_url')} ===")

if __name__ == "__main__":
    main()

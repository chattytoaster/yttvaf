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
    tag = "v1.0.0"
    title = "YouTube TV Mod v1.0.0 (SOCKS5, SponsorBlock, Web UI)"
    body = """# YouTube TV Mod v1.0.0

Модифицированный клиент **YouTube для Android TV** (пакет `com.chatty.yttvaf`) на базе движка Cobalt / Leanback.

### ✨ Ключевые возможности:
- ⚡ **Встроенный SOCKS5 Прокси**: локальный loopback-туннель (порт 9876) с поддержкой аутентификации для работы на Smart TV без сторонних VPN.
- 🌐 **Локальный Web-интерфейс (`:8888`)**: полноценное управление со смартфона или ПК (ввод прокси-строки, SponsorBlock, выбор категорий, качество, скорость).
- ⚙️ **Интерактивное меню настроек на ТВ**: нативный диалог по кнопке `MENU` / `SETTINGS` / `Красная` кнопка пульта.
- ⏩ **SponsorBlock с выбором категорий**:
  - Прямой защищённый HTTPS-клиент с зеркалом.
  - Настраиваемый пропуск всех 8 категорий (спонсорские блоки, самореклама, интеракции, интро, аутро, превью, филлеры, немузыкальные паузы) прямо на экране ТВ или через Web UI.
- 📺 **Фиксация качества видео**: Авто, 720p, 1080p, 1440p, 2160p (4K).
- ⚡ **Управление скоростью**: от 1.0x до 2.0x с пульта или через Web UI.
- 🛡️ **Блокировка рекламы**: вырезание рекламных структур из API и автоскип preroll/midroll.
- 🎮 **Горячие клавиши пульта**: Red — Настройки, Green — Скорость, Yellow — SponsorBlock, Blue — Качество.
- 🔄 **Инструкции по обновлению**: поддержка лёгкого переноса мода на новые версии оригинального APK (см. [UPDATING.md](https://github.com/chattytoaster/yttvaf/blob/main/UPDATING.md)).

### 📦 Установка:
```bash
adb connect <IP_ТВ>:5555
adb install -r YouTubeTV-Mod-v1.0.0.apk
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
    asset_name = "YouTubeTV-Mod-v1.0.0.apk"
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

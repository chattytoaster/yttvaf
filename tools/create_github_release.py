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
    tag = sys.argv[1] if len(sys.argv) > 1 else "v1.0.1"
    title = sys.argv[2] if len(sys.argv) > 2 else f"YouTube TV Mod {tag} (SponsorBlock Fix & Category Selector)"
    body = f"""# YouTube TV Mod {tag}

Обновление модифицированного клиента **YouTube для Android TV** (пакет `com.chatty.yttvaf`) на базе движка Cobalt / Leanback.

### 🌟 Что нового в {tag}:
- 🛠️ **Полное исправление работы SponsorBlock**:
  - Устранена циклическая повторная инъекция скрипта при обновлениях медиа-сессии.
  - Исключена перезапись активного Video ID фоновыми запросами рекомендаций (`JSON.parse`).
  - Добавлено изолированное кэширование сегментов (`segmentsCache`) для надёжного пропуска при любых переключениях видео.
  - Оптимизировано окно детекции таймкодов для мгновенного и бесшовного автоскипа вставок.
- 🎯 **Интерактивный выбор категорий SponsorBlock**:
  - Нативный диалог мульти-выбора на экране ТВ (меню по кнопке MENU / Red ➔ «Категории SponsorBlock»).
  - Удобные чекбоксы в Web UI (`:8888`) для всех 8 категорий (спонсорство, самореклама, интеракции, интро, аутро, превью, филлеры, немузыкальные паузы в клипах).
- 🔤 **Поддержка UTF-8 кодировки**: безупречное отображение русских текстов в Leanback-диалогах и OSD на телевизоре.
- ⚡ **Встроенный SOCKS5 Прокси**: локальный loopback-туннель на порту 9876 с поддержкой авторизации.
- 📺 **Фиксация качества видео**: до 4K 2160p с пульта и через Web UI.
- ⚡ **Регулировка скорости**: от 1.0x до 2.0x с горячими клавишами.
- 📖 **Подробные руководства**: инструкции по установке в [README.md](https://github.com/chattytoaster/yttvaf/blob/main/README.md) и руководство по переносу мода на новые версии YouTube в [UPDATING.md](https://github.com/chattytoaster/yttvaf/blob/main/UPDATING.md).

### 📦 Установка:
```bash
adb connect <IP_ТВ>:5555
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

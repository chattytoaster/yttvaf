# YouTube TV Mod (com.chatty.yttvaf)

Modified **YouTube for Android TV** client based on the Cobalt / Leanback engine with bypass capabilities, ad blocking, SponsorBlock integration with custom category selection, floating toast notifications, multilingual support (English & Russian), and big-screen UI enhancements.

[![Latest Release](https://img.shields.io/github/v/release/chattytoaster/yttvaf?style=for-the-badge&color=blue)](https://github.com/chattytoaster/yttvaf/releases/latest)
[![Download APK](https://img.shields.io/badge/Download-APK-success?style=for-the-badge&logo=android)](https://github.com/chattytoaster/yttvaf/releases/latest)

[🇬🇧 Read in English](README.md) | [🇷🇺 Читать на русском](README_RU.md)

---

## 🚀 Key Features

### ⚡ Built-in SOCKS5 Proxy (Bypass Subsystem)
- Full support for socks5://user:pass@host:port with user authentication.
- Local SOCKS loopback tunnel (127.0.0.1:9876) that transparently routes Chromium, Cobalt, and Cronet networking.
- Enables watching YouTube on Smart TV without needing external VPN apps or router configuration.

### 🌐 Local Web Management Interface (:8888)
- Lightweight embedded HTTP server listening on port 8888.
- On startup, the app displays an on-screen toast with the TV's local network IP:
  `	ext
  http://<TV_IP>:8888
  `
- Easily manage settings from any smartphone or PC connected to the same Wi-Fi:
  - Copy and paste long SOCKS5 proxy URLs.
  - Switch language between **English** and **Russian** ([EN] / [RU]).
  - Toggle SponsorBlock and select active categories via checkboxes.
  - Lock preferred video resolution and playback speed.
  - Check real-time connection and proxy status.

### ⚙️ Interactive On-Screen TV Settings Menu
- Opened directly with the TV remote: **MENU**, **SETTINGS**, **GUIDE**, **INFO**, or the **RED** color key.
- Native Leanback dialog displaying the live status of all mod features.
- In-menu **Language selector** (*Auto / English / Russian*).
- Multi-choice category selector dialog (🎯 SponsorBlock Categories: [X of 8]) designed specifically for remote D-pad navigation.

### ⏩ SponsorBlock (Skip Segments with Category Selector)
- Automatically skips sponsors, self-promotion, and tangents using the crowd-sourced SponsorBlock database.
- **On-Screen Disappearing Floating OSD Window**:
  - Whenever a segment is skipped, a crisp floating toast window appears in the top-right corner of the TV:
    `	ext
    ⏩ Skipped sponsor (13s)
    `
  - Also displays speed changes (⚡ Speed: 1.25x), quality adjustments (📺 Quality: 1080p), and SponsorBlock toggle status.
  - Mirrored via native Android system toasts for 100% visibility.
- **8 Selectable Segment Categories**:
  - 📢 **Sponsor integrations** (sponsor)
  - 🛍️ **Self-promotion / merch / socials** (selfpromo)
  - 🔔 **Subscribe / Like reminder** (interaction)
  - 🎬 **Intro animation** (intro)
  - 🏁 **End credits / Outro** (outro)
  - ⏱️ **Preview / Hook in start** (preview)
  - 💬 **Filler / Tangent** (iller)
  - 🎵 **Non-music section in music videos** (music_offtopic)
- Direct secure HTTPS client with fallback mirror (sponsor.ajay.app ➔ pi.sponsor.ajay.app), routed through the proxy tunnel when enabled.
- Segment caching (segmentsCache) prevents lost skips when switching between videos.

### 📺 Video Quality Lock
- Lock preferred resolution: **Auto**, **720p HD**, **1080p Full HD**, **1440p 2K**, **2160p 4K Ultra HD**.
- Automatically selects the closest available stream if the target resolution is unavailable.

### ⚡ Playback Speed Control
- Cycle playback speed: **1.0x**, **1.25x**, **1.5x**, **1.75x**, **2.0x**.
- Controlled via remote green button or Web UI.

### 🛡️ Built-in AdBlock
- Strips ad structures (dPlacements, dSlots, playerAds) from YouTube internal API responses.
- Auto-accelerates and skips any remaining video ads.
- Maintains 100% stable remote navigation and focus on Smart TVs.

---

## 🎮 Remote Hotkeys

| Remote Button | Action |
| :--- | :--- |
| **MENU** / **SETTINGS** / **GUIDE** / **INFO** | Open on-screen YouTube TV Mod Settings dialog |
| **RED (PROG RED)** | Open Settings dialog |
| **GREEN (PROG GREEN)** | Cycle speed: 1.0x ➔ 1.25x ➔ 1.5x ➔ 1.75x ➔ 2.0x |
| **YELLOW (PROG YELLOW)** | Toggle SponsorBlock (ON / OFF) |
| **BLUE (PROG BLUE)** | Cycle quality: Auto ➔ 1080p ➔ 1440p ➔ 2160p (4K) ➔ 720p |

*(Color keys can be toggled on/off in settings to prevent conflicts with your TV's native shortcuts).*

---

## 🛠️ Architecture

The modification utilizes a hybrid architecture of Java/Smali hooks and JavaScript injection into the Chromium/Cobalt engine:

`
                  ┌─────────────────────────────────────┐
                  │          Android TV Remote          │
                  │   (MENU / Red / Green / Blue / ...)  │
                  └──────────────────┬──────────────────┘
                                     │ KeyEvent
                                     ▼
┌──────────────────┐      ┌─────────────────────────────┐
│  Web Interface   │─────▶│      ProxyHelper.java       │◀──── Cronet / Network Hooks
│  (:8888 Web UI)  │ HTTP │   - Local SOCKS loopback    │      (ProxyChangeListener)
└──────────────────┘      │   - SponsorBlock Proxy & OSD│
                          │   - In-app Settings Dialog  │
                          └──────────────┬──────────────┘
                                         │ evaluateJavaScript
                                         ▼
                          ┌─────────────────────────────┐
                          │    Cobalt Web Engine        │
                          │   - AdBlock JSON & Video    │
                          │   - SponsorBlock HTTPS & OSD│
                          │   - Speed & Quality Hooks   │
                          └─────────────────────────────┘
`

1. **ProxyHelper.java (scratch/src/dev/cobalt/coat/ProxyHelper.java)**:
   - Core mod module containing the embedded HTTP server, local SOCKS5 tunnel, dialog runners, and remote key listeners.
2. **AdBlockHelper.smali Hook (onWebContentsAvailable)**:
   - Intercepts the native Chromium WebContentsImpl handle upon page initialization and injects uildModScript().
3. **CobaltActivity.smali Hook (dispatchKeyEvent)**:
   - Intercepts remote button presses prior to web engine handling.
4. **ProxyChangeListener.smali & rn.smali Hooks**:
   - Injects proxy configuration into the Cronet stack and Chromium CommandLine flags.
5. **uildModScript()**:
   - Manages direct SponsorBlock skipping, floating OSD popups, ad stripping, and playback parameters.

---

## 📦 Building from Source

### Prerequisites
- Python 3.8+
- Java Development Kit (JDK 8 or newer)
- Android SDK Build-Tools (d8, zipalign, pksigner)
- pktool (3.0+)

### Build Steps

1. Clone the repository:
   `ash
   git clone https://github.com/chattytoaster/yttvaf.git
   cd yttvaf
   `

2. Run the automated build script:
   `ash
   python tools/generate_proxy_helper.py
   `

The script automatically:
- Compiles ProxyHelper.java with javac (UTF-8 encoded)
- Converts classes to .dex using d8
- Disassembles dex to smali with pktool
- Copies generated smali files into the decompiled APK tree
- Verifies and applies smali hooks
- Builds APK with pktool b
- Runs zipalign and signs with pksigner using mod-debug.keystore
- Outputs MODIFIED_FILE.apk

---

## 📲 Installation on TV

Via ADB (USB or Wi-Fi):
`ash
adb connect <TV_IP>:5555
adb install -r MODIFIED_FILE.apk
`

Launch the application:
`ash
adb shell monkey -p com.chatty.yttvaf -c android.intent.category.LEANBACK_LAUNCHER 1
`

On first launch, the web interface URL (e.g. http://192.168.0.178:8888) will appear on screen. Open it on your phone or PC to configure proxy settings.

---

## 🔄 Updating to Newer YouTube TV Versions

When new versions of YouTube for Android TV are released, you can easily port all modifications, hooks, and Web UI to the new APK.

Refer to the complete step-by-step porting guide:
👉 **[UPDATING_EN.md](UPDATING_EN.md)** *(Russian version: [UPDATING.md](UPDATING.md))*

---

## 📄 License

This modification is provided for educational and personal use only. All rights to the original YouTube TV application belong to Google LLC.

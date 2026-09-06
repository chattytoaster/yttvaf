# 🔄 Porting Guide for New YouTube TV Releases

This guide describes the process of porting hooks, modules, and patches from **YouTube TV Mod (`com.chatty.yttvaf`)** to newer official versions of YouTube for Android TV (Cobalt / Leanback engine).

[🇬🇧 Read in English](UPDATING_EN.md) | [🇷🇺 Читать на русском](UPDATING.md)

---

## 📑 Table of Contents
1. [Mod Architecture Overview](#-mod-architecture-overview)
2. [Environment Setup](#-environment-setup)
3. [Step-by-Step Update Process](#-step-by-step-update-process)
   - [Step 1. Download Clean APK](#step-1-download-clean-apk)
   - [Step 2. Decompile with Apktool](#step-2-decompile-with-apktool)
   - [Step 3. Clone Package Name (for Parallel Installation)](#step-3-clone-package-name-for-parallel-installation)
   - [Step 4. Identify Obfuscated Classes (ProGuard / R8 Mapping)](#step-4-identify-obfuscated-classes-proguard--r8-mapping)
   - [Step 5. Apply Hooks and Build](#step-5-apply-hooks-and-build)
   - [Step 6. Testing and Verification on Device](#step-6-testing-and-verification-on-device)
4. [Integration Points (Hook Cheat Sheet)](#-integration-points-hook-cheat-sheet)

---

## 🏛 Mod Architecture Overview

The modification consists of two primary components:

1. **`ProxyHelper.java` (`scratch/src/dev/cobalt/coat/ProxyHelper.java`)**:
   - A standalone Java class that encapsulates all auxiliary business logic:
     - Local SOCKS5 loopback tunnel (`127.0.0.1:9876`) supporting authenticated upstream SOCKS5 proxies.
     - HTTP web server on port `8888` (Web UI for remote setup via phone/PC and REST API).
     - Persistent settings store (`SharedPreferences`: proxy URL, SponsorBlock toggle, category bitmask, resolution lock, playback speed, app language).
     - Direct HTTPS client for the SponsorBlock API (`sponsor.ajay.app` / `api.sponsor.ajay.app`).
     - Native Leanback dialogs on TV screen: main settings dialog, multi-choice SponsorBlock category picker, proxy input, and language switcher.
     - Chromium/Cobalt injection script generator (`buildModScript()`) featuring an on-screen floating toast (OSD) overlay and SponsorBlock engine.

2. **Smali Hooks in the App Codebase**:
   - **`AdBlockHelper.smali`**: Invokes `ProxyHelper.onWebContentsAvailable(J)` once Chromium `WebContents` is initialized to inject JavaScript.
   - **`CobaltActivity.smali`**: Intercepts `dispatchKeyEvent` for remote control shortcuts (`MENU`, `SETTINGS`, `GUIDE`, `PROG_RED`, `PROG_GREEN`, `PROG_YELLOW`, `PROG_BLUE`).
   - **`ProxyChangeListener.smali`**: Overrides system Chromium proxy settings and passes the local SOCKS5 tunnel to native Cronet/Chromium layers.
   - **`ern.smali` (Cronet options)**: Injects `--proxy-server` and `host-resolver-rules` flags into the network engine launch options.

---

## 🛠 Environment Setup

To work with the build tools, ensure you have:
- **Python 3.8+**
- **Java Development Kit (JDK 11, 17, or 21)** (`JAVA_HOME` configured or `javac` on `PATH`)
- **Android SDK Build-Tools** (`d8`, `zipalign`, `apksigner`)
- **Apktool** (provided in repository: `tools/apktool_3.0.3.jar`)

---

## 🚀 Step-by-Step Update Process

### Step 1. Download Clean APK
1. Visit [APKMirror](https://www.apkmirror.com/apk/google-inc/youtube-for-android-tv/) and find the latest stable release of **YouTube for Android TV**.
2. Select the target variant:
   - Architecture: **armeabi-v7a** (or **arm64-v8a** depending on target TV hardware)
   - DPI: **nodpi**
   - Minimum Android version: **Android 7.0+ (minAPI24)**

### Step 2. Decompile with Apktool
Extract the fresh APK into the working directory `work/decoded_modified`:
```bash
java -jar tools/apktool_3.0.3.jar d -f path/to/youtube_tv_new.apk -o work/decoded_modified
```

---

### Step 3. Clone Package Name (for Parallel Installation)
If you want the mod to install **side-by-side** with the official YouTube TV app:

1. Open `work/decoded_modified/AndroidManifest.xml`:
   - Replace `package="com.google.android.youtube.tv"` with `package="com.chatty.yttvaf"`.
   - Inside `<provider ... android:authorities="...">` tags, replace prefixes `com.google.android.youtube.tv` with `com.chatty.yttvaf`.
2. Open `work/decoded_modified/apktool.yml`:
   - Add or update the parameter:
     ```yaml
     renameManifestPackage: com.chatty.yttvaf
     ```

---

### Step 4. Identify Obfuscated Classes (ProGuard / R8 Mapping)

Google's builds use ProGuard/R8 obfuscation, meaning short class names (such as `auo`, `bnj`, `ern`) may change across releases. Follow these tips to locate them:

#### 1. Proxy Configuration Class (`ProxyConfig`) — `Lbnj;` in v7.12
- Open: `work/decoded_modified/smali_classes2/cobalt/org/chromium/net/ProxyChangeListener.smali`
- Find the proxy update method:
  ```smali
  .method public final b(L...;)V
  ```
- The argument type (e.g. `Lbnj;` or `Labc;`) is the `ProxyConfig` class.
- Verify its fields: it must have `host`, `port`, `pacUrl`, and `exclusionList`.
- **If the class name changed**:
  - Update the class name in `scratch/src/dev/cobalt/coat/bnj.java` (or rename the file).
  - In `tools/generate_proxy_helper.py`, update the replacement rule:
    ```python
    content = content.replace("Ldev/cobalt/coat/bnj;", "Lnew_name;")
    ```

#### 2. Chromium CommandLine Class (`CommandLine`) — `Lauo;` in v7.12
- Search for the command-line flag string in decoded smali:
  ```bash
  grep -rn "disable-web-security" work/decoded_modified/
  ```
  Or locate the class with a public static singleton and argument appending method:
  ```smali
  .field public static a:L...;
  .method public c(Ljava/lang/String;Ljava/lang/String;)V
  ```
- **If the class name changed**:
  - Update `scratch/src/dev/cobalt/coat/auo.java`.
  - In `tools/generate_proxy_helper.py`, update:
    ```python
    content = content.replace("Ldev/cobalt/coat/auo;", "Lnew_name;")
    ```

#### 3. Cronet Experimental Options Class — `ern.smali` in v7.12
- Search for the Cronet experimental options key:
  ```bash
  grep -rn "experimental_options" work/decoded_modified/
  ```
- Find the method reading the Cronet JSON options string (in v7.12 this method checks the result of interface `ers->a()Ljava/lang/String;`).
- Insert the intercept hook:
  ```smali
  invoke-static {v3}, Ldev/cobalt/coat/ProxyHelper;->getCronetOptions(Ljava/lang/String;)Ljava/lang/String;
  move-result-object v3
  ```
- Verify and update the search regex in section 4 of `tools/generate_proxy_helper.py` (`patch_smali_hooks`) if needed.

---

### Step 5. Apply Hooks and Build

The automated script `tools/generate_proxy_helper.py` performs the entire remaining build cycle:
```bash
python tools/generate_proxy_helper.py
```

What the script does automatically:
1. Compiles `ProxyHelper.java` using `javac` with `android.jar` on classpath (with `-encoding UTF-8`).
2. Converts compiled bytecode to `.dex` via `d8` (with desugaring for API 24+).
3. Disassembles `.dex` to smali using `apktool`.
4. Replaces stub references `auo` and `bnj` with real target references and copies `ProxyHelper*.smali` files into `work/decoded_modified/smali_classes2/dev/cobalt/coat/`.
5. Verifies and injects smali hooks into:
   - `AdBlockHelper.smali` (`onWebContentsAvailable`)
   - `CobaltActivity.smali` (`dispatchKeyEvent`)
   - `ProxyChangeListener.smali` (`getProperty`, `b`, `start`)
   - `ern.smali` (Cronet options)
6. Rebuilds APK via `apktool b`.
7. Aligns archive via `zipalign`.
8. Signs APK with `mod-debug.keystore` via `apksigner`.
9. The resulting installation package is saved to `MODIFIED_FILE.apk`.

---

### Step 6. Testing and Verification on Device

1. Connect to the TV via ADB and install the APK:
   ```bash
   adb connect <TV_IP>:5555
   adb install -r MODIFIED_FILE.apk
   ```

2. Launch the application:
   ```bash
   adb shell monkey -p com.chatty.yttvaf -c android.intent.category.LEANBACK_LAUNCHER 1
   ```

3. Check initialization logs:
   ```bash
   adb logcat -s YTTV_ProxyHelper cobalt
   ```
   Ensure the following messages appear:
   - `Starting WebServer on port 8888`
   - `WebContents available: ...`
   - `Injecting mod script (ProxyHelper) into WebContents...`

4. Test Web UI:
   Open `http://<TV_IP>:8888` in a browser. The page should load immediately, providing the SOCKS5 proxy configuration, language selector ([EN] / [RU]), SponsorBlock toggle, category checkboxes, and resolution lock.

5. Test SponsorBlock and Floating OSD:
   Play a video with known sponsor segments (e.g. Linus Tech Tips). Verify that:
   - Segment is skipped automatically.
   - A floating toast appears: `⏩ Skipped sponsor (13s)` (or `⏩ Пропущен спонсор (13 сек)`).

---

## 📌 Integration Points (Hook Cheat Sheet)

| Component | Smali File | Method | Purpose |
| :--- | :--- | :--- | :--- |
| **JS Injection** | `dev/cobalt/coat/AdBlockHelper.smali` | `onWebContentsAvailable(J)V` | Calls `ProxyHelper.onWebContentsAvailable(v0, v1)` to inject player scripts |
| **Remote Keys** | `dev/cobalt/coat/CobaltActivity.smali` | `dispatchKeyEvent(Landroid/view/KeyEvent;)Z` | Intercepts `MENU`, `SETTINGS`, and color buttons before web engine handles them |
| **Proxy Host/Port** | `cobalt/org/chromium/net/ProxyChangeListener.smali` | `getProperty(Ljava/lang/String;)Ljava/lang/String;` | Redirects Chromium networking to local port `9876` |
| **Proxy Broadcast** | `cobalt/org/chromium/net/ProxyChangeListener.smali` | `b(Lbnj;)V` | Replaces proxy config object with local loopback tunnel |
| **Cronet Options** | `smali_classes2/ern.smali` | Read JSON `experimental_options` | Injects `--proxy-server` and `host-resolver-rules` into Cronet |
| **Helper Module** | `dev/cobalt/coat/ProxyHelper*.smali` | — | All auxiliary features (Web UI, SponsorBlock, OSD, Settings, Localization) |

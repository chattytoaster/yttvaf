# 🔄 Руководство по переносу модификации на новые версии YouTube TV

Данный документ описывает процесс портирования хуков и модулей **YouTube TV Mod (com.chatty.yttvaf)** на новые официальные версии YouTube для Android TV (движок Cobalt / Leanback).

---

## 📑 Содержание
1. [Общая архитектура модификации](#-общая-архитектура-модификации)
2. [Подготовка рабочего окружения](#-подготовка-рабочего-окружения)
3. [Пошаговый процесс обновления](#-пошаговый-процесс-обновления)
   - [Шаг 1. Загрузка чистого APK](#шаг-1-загрузка-чистого-apk)
   - [Шаг 2. Декомпиляция с помощью Apktool](#шаг-2-декомпиляция-с-помощью-apktool)
   - [Шаг 3. Клонирование Package Name (для параллельной установки)](#шаг-3-клонирование-package-name-для-параллельной-установки)
   - [Шаг 4. Определение обфусцированных классов (ProGuard / R8 Mapping)](#шаг-4-определение-обфусцированных-классов-proguard--r8-mapping)
   - [Шаг 5. Применение хуков и сборка](#шаг-5-применение-хуков-и-сборка)
   - [Шаг 6. Тестирование и верификация на устройстве](#шаг-6-тестирование-и-верификация-на-устройстве)
4. [Точки интеграции (Cheat Sheet по хукам)](#-точки-интеграции-cheat-sheet-по-хукам)

---

## 🏛 Общая архитектура модификации

Модификация состоит из двух ключевых компонентов:

1. **ProxyHelper.java (scratch/src/dev/cobalt/coat/ProxyHelper.java)**:
   - Автономный Java-класс, содержащий в себе всю вспомогательную бизнес-логику:
     - Локальный SOCKS5 loopback туннель (127.0.0.1:9876) с поддержкой аутентификации upstream-прокси.
     - HTTP-сервер на порту 8888 (Web UI для настройки со смартфона / ПК и REST API).
     - Хранилище настроек (SharedPreferences: прокси, SponsorBlock, выбор категорий, фиксация качества, скорость).
     - Прямой HTTPS-клиент для SponsorBlock API (sponsor.ajay.app / pi.sponsor.ajay.app).
     - Нативный Leanback-диалог настроек на экране ТВ и диалог мульти-выбора категорий SponsorBlock.
     - Генератор инъекционного JavaScript (uildModScript()) для Chromium/Cobalt.

2. **Smali-хуки в кодовой базе приложения**:
   - **AdBlockHelper.smali**: вызов ProxyHelper.onWebContentsAvailable(J) при готовности Chromium WebContents для внедрения JavaScript.
   - **CobaltActivity.smali**: перехват dispatchKeyEvent для обработки кнопок пульта (MENU, Red, Green, Yellow, Blue).
   - **ProxyChangeListener.smali**: подмена настроек системного прокси Chromium и передача локального SOCKS5 туннеля в нативный слой Cronet/Chromium.
   - **rn.smali (Cronet options)**: инъекция флагов --proxy-server и host-resolver-rules в параметры запуска сетевого движка.

---

## 🛠 Подготовка рабочего окружения

Для работы со скриптами потребуются:
- **Python 3.8+**
- **Java Development Kit (JDK 11, 17 или 21)** (путь в JAVA_HOME или доступен javac)
- **Android SDK Build-Tools** (d8, zipalign, pksigner)
- Утилита **pktool** (поставляется в репозитории: 	ools/apktool_3.0.3.jar)

---

## 🚀 Пошаговый процесс обновления

### Шаг 1. Загрузка чистого APK
1. Зайдите на [APKMirror](https://www.apkmirror.com/apk/google-inc/youtube-for-android-tv/) и выберите последнюю стабильную версию **YouTube for Android TV**.
2. Скачайте вариант:
   - Архитектура: **rmeabi-v7a** (или rm64-v8a в зависимости от целевого ТВ)
   - DPI: **
odpi**
   - Минимальная версия Android: **Android 7.0+ (minAPI24)**

### Шаг 2. Декомпиляция с помощью Apktool
Распакуйте новый APK в рабочую папку work/decoded_modified:
`ash
java -jar tools/apktool_3.0.3.jar d -f path/to/youtube_tv_new.apk -o work/decoded_modified
`

---

### Шаг 3. Клонирование Package Name (для параллельной установки)
Если вы хотите, чтобы мод устанавливался **параллельно** с официальным YouTube TV, а не поверх него:

1. Откройте work/decoded_modified/AndroidManifest.xml:
   - Замените package="com.google.android.youtube.tv" на package="com.chatty.yttvaf".
   - Внутри тегов <provider ... android:authorities="..."> замените префиксы com.google.android.youtube.tv на com.chatty.yttvaf.
2. Откройте work/decoded_modified/apktool.yml:
   - Добавьте или обновите параметр:
     `yaml
     renameManifestPackage: com.chatty.yttvaf
     `

---

### Шаг 4. Определение обфусцированных классов (ProGuard / R8 Mapping)

При компиляции новых версий Google использует ProGuard/R8, поэтому короткие имена вспомогательных классов (например, uo, nj, rn) могут измениться. Ниже приведена инструкция, как их быстро найти:

#### 1. Класс конфигурации прокси (ProxyConfig) — в версии 7.12 это Lbnj;
- Откройте файл: work/decoded_modified/smali_classes2/cobalt/org/chromium/net/ProxyChangeListener.smali
- Найдите метод обновления прокси:
  `smali
  .method public final b(L...;)V
  `
- Тип передаваемого аргумента (например, Lbnj; или Labc;) — это и есть класс ProxyConfig.
- Проверьте его содержимое: в нём должны быть поля host, port, pacUrl, xclusionList.
- **Если имя изменилось**:
  - Обновите имя класса в scratch/src/dev/cobalt/coat/bnj.java (или переименуйте файл).
  - В 	ools/generate_proxy_helper.py обновите строковую замену:
    `python
    content = content.replace("Ldev/cobalt/coat/bnj;", "Lновое_имя;")
    `

#### 2. Класс флагов командной строки Chromium (CommandLine) — в версии 7.12 это Lauo;
- Поиск по строке флага в распакованных smali:
  `ash
  grep -rn "disable-web-security" work/decoded_modified/
  `
  или найдите класс, содержащий публичный статический синглтон и метод добавления параметров:
  `smali
  .field public static a:L...;
  .method public c(Ljava/lang/String;Ljava/lang/String;)V
  `
- **Если имя изменилось**:
  - Обновите scratch/src/dev/cobalt/coat/auo.java.
  - В 	ools/generate_proxy_helper.py обновите:
    `python
    content = content.replace("Ldev/cobalt/coat/auo;", "Lновое_имя;")
    `

#### 3. Класс Cronet Experimental Options — в версии 7.12 это rn.smali
- Выполните поиск по ключевому слову параметров Cronet:
  `ash
  grep -rn "experimental_options" work/decoded_modified/
  `
- Найдите метод, который получает JSON строку опций Cronet (в версии 7.12 метод проверяет результат интерфейса rs->a()Ljava/lang/String;).
- Вставьте вызов перехвата:
  `smali
  invoke-static {v3}, Ldev/cobalt/coat/ProxyHelper;->getCronetOptions(Ljava/lang/String;)Ljava/lang/String;
  move-result-object v3
  `
- Проверьте и при необходимости скорректируйте паттерн поиска в секции 4 скрипта 	ools/generate_proxy_helper.py (patch_smali_hooks).

---

### Шаг 5. Применение хуков и сборка

Скрипт 	ools/generate_proxy_helper.py автоматизирует весь оставшийся цикл:
`ash
python tools/generate_proxy_helper.py
`

Что делает скрипт автоматически:
1. Компилирует ProxyHelper.java при помощи javac с подключением ndroid.jar.
2. Преобразует скомпилированные классы в .dex через d8 (с дешугарингом для API 24+).
3. Дизассемблирует dex в smali с помощью pktool.
4. Заменяет временные заглушки uo и nj на реальные ссылки и копирует smali-файлы ProxyHelper*.smali в work/decoded_modified/smali_classes2/dev/cobalt/coat/.
5. Проверяет и внедряет хуки в:
   - AdBlockHelper.smali (onWebContentsAvailable)
   - CobaltActivity.smali (dispatchKeyEvent)
   - ProxyChangeListener.smali (getProperty, , start)
   - rn.smali (Cronet options)
6. Собирает APK через pktool b.
7. Выравнивает архив через zipalign.
8. Подписывает APK ключом mod-debug.keystore через pksigner.
9. Результат сохраняется в MODIFIED_FILE.apk.

---

### Шаг 6. Тестирование и верификация на устройстве

1. Подключитесь к телевизору по ADB и установите обновлённый APK:
   `ash
   adb connect <IP_ТВ>:5555
   adb install -r MODIFIED_FILE.apk
   `

2. Запустите приложение:
   `ash
   adb shell monkey -p com.chatty.yttvaf -c android.intent.category.LEANBACK_LAUNCHER 1
   `

3. Проверьте логи инициализации ProxyHelper:
   `ash
   adb logcat -s ProxyHelper:V
   `
   Убедитесь, что в логах присутствуют сообщения:
   - Starting WebServer on port 8888
   - WebContents available: ...
   - Injecting mod script (ProxyHelper) into WebContents...

4. Проверьте веб-интерфейс:
   Откройте в браузере http://<IP_ТВ>:8888. Страница должна открываться мгновенно, отображая форму ввода SOCKS5, переключатели SponsorBlock, чекбоксы категорий и выбор качества.

---

## 📌 Точки интеграции (Cheat Sheet по хукам)

| Компонент | Файл в smali | Метод | Назначение |
| :--- | :--- | :--- | :--- |
| **JS Injection** | dev/cobalt/coat/AdBlockHelper.smali | onWebContentsAvailable(J)V | Вызов ProxyHelper.onWebContentsAvailable(v0, v1) для инъекции скрипта плеера |
| **Кнопки пульта** | dev/cobalt/coat/CobaltActivity.smali | dispatchKeyEvent(Landroid/view/KeyEvent;)Z | Перехват MENU, SETTINGS, цветных кнопок до обработки веб-движком |
| **Proxy Host/Port** | cobalt/org/chromium/net/ProxyChangeListener.smali | getProperty(Ljava/lang/String;)Ljava/lang/String; | Перенаправление Chromium в локальный порт 9876 |
| **Proxy Broadcast** | cobalt/org/chromium/net/ProxyChangeListener.smali | (Lbnj;)V | Подмена структуры прокси на локальный loopback туннель |
| **Cronet Options** | smali_classes2/ern.smali | Чтение JSON xperimental_options | Добавление флагов --proxy-server и host-resolver-rules в Cronet |
| **Автономный модуль** | dev/cobalt/coat/ProxyHelper*.smali | — | Все вспомогательные функции (Web UI, SponsorBlock, OSD, Settings) |

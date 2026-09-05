# -*- coding: utf-8 -*-
import os
import sys
import shutil
import subprocess
import zipfile

BASE_DIR = r"C:\Users\ChattyNB\Documents\yttvaf"
SDK_DIR = r"C:\Users\ChattyNB\AppData\Local\Android\Sdk"
BUILD_TOOLS = os.path.join(SDK_DIR, "build-tools", "34.0.0")
ANDROID_JAR = os.path.join(SDK_DIR, "platforms", "android-34", "android.jar")
JAVAC = r"C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot\bin\javac.exe"
D8 = os.path.join(BUILD_TOOLS, "d8.bat")
ZIPALIGN = os.path.join(BUILD_TOOLS, "zipalign.exe")
APKSIGNER = os.path.join(BUILD_TOOLS, "apksigner.bat")
APKTOOL = os.path.join(BASE_DIR, "tools", "apktool_3.0.3.jar")

def compile_java():
    print("=== 1. Compiling ProxyHelper.java with javac ===")
    src_dir = os.path.join(BASE_DIR, "scratch", "src", "dev", "cobalt", "coat")
    jni_src_dir = os.path.join(BASE_DIR, "scratch", "src", "org", "jni_zero")
    bin_dir = os.path.join(BASE_DIR, "scratch", "bin")
    
    if os.path.exists(bin_dir):
        shutil.rmtree(bin_dir)
    os.makedirs(bin_dir, exist_ok=True)

    cmd_javac = [
        JAVAC,
        "-source", "8",
        "-target", "8",
        "-cp", ANDROID_JAR,
        "-d", bin_dir,
        os.path.join(src_dir, "auo.java"),
        os.path.join(src_dir, "bnj.java"),
        os.path.join(jni_src_dir, "GEN_JNI.java"),
        os.path.join(src_dir, "ProxyHelper.java")
    ]
    subprocess.check_call(cmd_javac)
    print("javac compilation successful.")

def dex_classes():
    print("=== 2. Running d8 on ProxyHelper classes ===")
    bin_dir = os.path.join(BASE_DIR, "scratch", "bin")
    dex_dir = os.path.join(BASE_DIR, "scratch", "dex")
    if os.path.exists(dex_dir):
        shutil.rmtree(dex_dir)
    os.makedirs(dex_dir, exist_ok=True)

    coat_bin = os.path.join(bin_dir, "dev", "cobalt", "coat")
    class_files = [
        os.path.join(coat_bin, f)
        for f in os.listdir(coat_bin)
        if f.startswith("ProxyHelper") and f.endswith(".class")
    ]
    if not class_files:
        raise RuntimeError("No ProxyHelper class files found to dex!")

    cmd_d8 = [D8, "--output", dex_dir] + class_files
    subprocess.check_call(cmd_d8, shell=True)
    print(f"d8 finished: {len(class_files)} classes processed into classes.dex.")

def apktool_disassemble():
    print("=== 3. Packaging dex and disassembling with apktool ===")
    dex_dir = os.path.join(BASE_DIR, "scratch", "dex")
    test_apk = os.path.join(BASE_DIR, "scratch", "test.apk")
    if os.path.exists(test_apk):
        os.remove(test_apk)

    with zipfile.ZipFile(test_apk, "w") as zf:
        zf.write(os.path.join(dex_dir, "classes.dex"), "classes.dex")

    test_out = os.path.join(BASE_DIR, "scratch", "test_out")
    if os.path.exists(test_out):
        shutil.rmtree(test_out)

    cmd_apktool_d = ["java", "-jar", APKTOOL, "d", "-f", test_apk, "-o", test_out]
    subprocess.check_call(cmd_apktool_d)
    print("apktool disassembled dex to smali.")

def install_smali():
    print("=== 4. Post-processing smali and copying to work/decoded_modified ===")
    test_out = os.path.join(BASE_DIR, "scratch", "test_out")
    generated_smali_dir = os.path.join(test_out, "smali", "dev", "cobalt", "coat")
    target_smali_dir = os.path.join(BASE_DIR, "work", "decoded_modified", "smali_classes2", "dev", "cobalt", "coat")
    os.makedirs(target_smali_dir, exist_ok=True)

    # Clean old ProxyHelper smali files
    for f in os.listdir(target_smali_dir):
        if f.startswith("ProxyHelper"):
            os.remove(os.path.join(target_smali_dir, f))

    # Copy new smali files with auo and bnj replacement
    copied_count = 0
    for f in os.listdir(generated_smali_dir):
        if f.startswith("ProxyHelper"):
            src_file = os.path.join(generated_smali_dir, f)
            dst_file = os.path.join(target_smali_dir, f)
            with open(src_file, "r", encoding="utf-8") as sf:
                content = sf.read()
            content = content.replace("Ldev/cobalt/coat/auo;", "Lauo;")
            content = content.replace("Ldev/cobalt/coat/bnj;", "Lbnj;")
            with open(dst_file, "w", encoding="utf-8") as df:
                df.write(content)
            copied_count += 1
    print(f"Installed {copied_count} ProxyHelper smali files.")

def patch_smali_hooks():
    print("=== 5. Verifying / patching smali hooks ===")

    # 1. AdBlockHelper.smali -> onWebContentsAvailable hook
    adblock_path = os.path.join(BASE_DIR, "work", "decoded_modified", "smali_classes2", "dev", "cobalt", "coat", "AdBlockHelper.smali")
    if os.path.exists(adblock_path):
        with open(adblock_path, "r", encoding="utf-8") as f:
            abh_content = f.read()
        if "Ldev/cobalt/coat/ProxyHelper;->onWebContentsAvailable(J)V" not in abh_content:
            target_check = "if-eqz v2, :cond_1\n"
            hook_call = "    invoke-static {v0, v1}, Ldev/cobalt/coat/ProxyHelper;->onWebContentsAvailable(J)V\n\n"
            idx = abh_content.find(target_check)
            if idx != -1:
                insert_pos = idx + len(target_check)
                abh_content = abh_content[:insert_pos] + "\n" + hook_call + abh_content[insert_pos:]
                with open(adblock_path, "w", encoding="utf-8") as f:
                    f.write(abh_content)
                print("AdBlockHelper.smali onWebContentsAvailable hook installed.")
            else:
                print("Warning: target_check not found in AdBlockHelper.smali")
        else:
            print("AdBlockHelper.smali onWebContentsAvailable hook already present.")

    # 2. CobaltActivity.smali -> dispatchKeyEvent hook
    cobalt_path = os.path.join(BASE_DIR, "work", "decoded_modified", "smali_classes2", "dev", "cobalt", "coat", "CobaltActivity.smali")
    if os.path.exists(cobalt_path):
        with open(cobalt_path, "r", encoding="utf-8") as f:
            cobalt_content = f.read()
        if "handleDispatchKeyEvent" not in cobalt_content:
            dispatch_method = """
.method public dispatchKeyEvent(Landroid/view/KeyEvent;)Z
    .locals 1

    invoke-static {p0, p1}, Ldev/cobalt/coat/ProxyHelper;->handleDispatchKeyEvent(Landroid/app/Activity;Landroid/view/KeyEvent;)Z

    move-result v0

    if-eqz v0, :cond_default_key

    const/4 v0, 0x1

    return v0

    :cond_default_key
    invoke-super {p0, p1}, Landroid/app/Activity;->dispatchKeyEvent(Landroid/view/KeyEvent;)Z

    move-result v0

    return v0
.end method
"""
            target_pos = cobalt_content.find(".method public final onKeyDown(ILandroid/view/KeyEvent;)Z")
            if target_pos != -1:
                cobalt_content = cobalt_content[:target_pos] + dispatch_method + "\n" + cobalt_content[target_pos:]
                with open(cobalt_path, "w", encoding="utf-8") as f:
                    f.write(cobalt_content)
                print("CobaltActivity.smali dispatchKeyEvent hook installed.")
            else:
                print("Warning: onKeyDown anchor not found in CobaltActivity.smali")
        else:
            print("CobaltActivity.smali dispatchKeyEvent hook already present.")

    # 3. ProxyChangeListener.smali
    pcl_path = os.path.join(BASE_DIR, "work", "decoded_modified", "smali_classes2", "cobalt", "org", "chromium", "net", "ProxyChangeListener.smali")
    if os.path.exists(pcl_path):
        with open(pcl_path, "r", encoding="utf-8") as f:
            pcl_content = f.read()

        old_getprop = """.method public static getProperty(Ljava/lang/String;)Ljava/lang/String;
    .locals 0

    invoke-static {p0}, Ljava/lang/System;->getProperty(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    return-object p0
.end method"""

        new_getprop = """.method public static getProperty(Ljava/lang/String;)Ljava/lang/String;
    .locals 1

    invoke-static {p0}, Ldev/cobalt/coat/ProxyHelper;->getProxyProperty(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    if-eqz v0, :cond_0

    return-object v0

    :cond_0
    invoke-static {p0}, Ljava/lang/System;->getProperty(Ljava/lang/String;)Ljava/lang/String;

    move-result-object p0

    return-object p0
.end method"""

        if old_getprop in pcl_content:
            pcl_content = pcl_content.replace(old_getprop, new_getprop)
            print("ProxyChangeListener.getProperty patched.")

        old_b = """.method public final b(Lbnj;)V
    .locals 7

    iget-wide v0, p0, Lcobalt/org/chromium/net/ProxyChangeListener;->c:J

    const-wide/16 v2, 0x0

    cmp-long v2, v0, v2

    if-nez v2, :cond_0

    return-void

    :cond_0
    if-eqz p1, :cond_1"""

        new_b = """.method public final b(Lbnj;)V
    .locals 7

    iget-wide v0, p0, Lcobalt/org/chromium/net/ProxyChangeListener;->c:J

    const-wide/16 v2, 0x0

    cmp-long v2, v0, v2

    if-nez v2, :cond_0

    return-void

    :cond_0
    invoke-static {p1}, Ldev/cobalt/coat/ProxyHelper;->getEffectiveProxyConfig(Lbnj;)Lbnj;

    move-result-object p1

    if-eqz p1, :cond_1"""

        if old_b in pcl_content:
            pcl_content = pcl_content.replace(old_b, new_b)
            print("ProxyChangeListener.b patched.")

        old_start_end = """    invoke-static {p2, p0, p1}, Laus;->c(Landroid/content/Context;Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    if-eqz v0, :cond_1"""

        new_start_end = """    invoke-static {p2, p0, p1}, Laus;->c(Landroid/content/Context;Landroid/content/BroadcastReceiver;Landroid/content/IntentFilter;)Landroid/content/Intent;

    const/4 p1, 0x0

    invoke-virtual {p0, p1}, Lcobalt/org/chromium/net/ProxyChangeListener;->b(Lbnj;)V
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    if-eqz v0, :cond_1"""

        if old_start_end in pcl_content:
            pcl_content = pcl_content.replace(old_start_end, new_start_end)
            print("ProxyChangeListener.start patched.")

        with open(pcl_path, "w", encoding="utf-8") as f:
            f.write(pcl_content)

    # 4. ern.smali -> Cronet hook
    ern_path = os.path.join(BASE_DIR, "work", "decoded_modified", "smali_classes2", "ern.smali")
    if os.path.exists(ern_path):
        with open(ern_path, "r", encoding="utf-8") as f:
            ern_content = f.read()

        if "Ldev/cobalt/coat/ProxyHelper;->getCronetOptions" not in ern_content:
            old_pattern = 'invoke-interface {v2}, Lers;->a()Ljava/lang/String;\n\n    move-result-object v3\n\n    invoke-static {v3}, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z'
            new_pattern = 'invoke-interface {v2}, Lers;->a()Ljava/lang/String;\n\n    move-result-object v3\n\n    invoke-static {v3}, Ldev/cobalt/coat/ProxyHelper;->getCronetOptions(Ljava/lang/String;)Ljava/lang/String;\n\n    move-result-object v3\n\n    invoke-static {v3}, Landroid/text/TextUtils;->isEmpty(Ljava/lang/CharSequence;)Z'
            if old_pattern in ern_content:
                ern_content = ern_content.replace(old_pattern, new_pattern)
                with open(ern_path, "w", encoding="utf-8") as f:
                    f.write(ern_content)
                print("ern.smali Cronet hook added.")
            else:
                print("Warning: old_pattern not found in ern.smali")
        else:
            print("ern.smali Cronet hook already present.")

def build_apk():
    print("=== 6. Building unsigned APK with apktool ===")
    unsigned_apk = os.path.join(BASE_DIR, "MODIFIED_FILE.unsigned.apk")
    if os.path.exists(unsigned_apk):
        os.remove(unsigned_apk)
    cmd_apktool_b = ["java", "-jar", APKTOOL, "b", os.path.join(BASE_DIR, "work", "decoded_modified"), "-o", unsigned_apk]
    subprocess.check_call(cmd_apktool_b)
    print("APK built successfully.")

    print("=== 7. Aligning APK with zipalign ===")
    aligned_apk = os.path.join(BASE_DIR, "MODIFIED_FILE.aligned.apk")
    if os.path.exists(aligned_apk):
        os.remove(aligned_apk)
    cmd_zipalign = [ZIPALIGN, "-p", "-f", "4", unsigned_apk, aligned_apk]
    subprocess.check_call(cmd_zipalign)
    print("APK aligned.")

    print("=== 8. Signing APK with apksigner ===")
    final_apk = os.path.join(BASE_DIR, "MODIFIED_FILE.apk")
    if os.path.exists(final_apk):
        os.remove(final_apk)
    keystore = os.path.join(BASE_DIR, "mod-debug.keystore")
    cmd_sign = [
        APKSIGNER, "sign",
        "--ks", keystore,
        "--ks-pass", "pass:android",
        "--ks-key-alias", "modkey",
        "--key-pass", "pass:android",
        "--out", final_apk,
        aligned_apk
    ]
    subprocess.check_call(cmd_sign, shell=True)
    print(f"=== Build Completed: {final_apk} ===")

def main():
    compile_java()
    dex_classes()
    apktool_disassemble()
    install_smali()
    patch_smali_hooks()
    build_apk()

if __name__ == "__main__":
    main()

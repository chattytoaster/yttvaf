$adb = "C:\Users\ChattyNB\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb connect 192.168.0.178:5555
Start-Sleep -Seconds 1
& $adb -s 192.168.0.178:5555 shell am force-stop com.google.android.youtube.tv.mod
& $adb -s 192.168.0.178:5555 shell am force-stop com.chatty.yttvaf
Write-Host "Pushing APK to /data/local/tmp/mod.apk..."
& $adb -s 192.168.0.178:5555 push "C:\Users\ChattyNB\Documents\yttvaf\MODIFIED_FILE.apk" /data/local/tmp/mod.apk
Write-Host "Installing from /data/local/tmp/mod.apk..."
& $adb -s 192.168.0.178:5555 shell pm install -r /data/local/tmp/mod.apk
Write-Host "Cleaning up temp file..."
& $adb -s 192.168.0.178:5555 shell rm -f /data/local/tmp/mod.apk
Write-Host "Launching com.chatty.yttvaf..."
& $adb -s 192.168.0.178:5555 shell monkey -p com.chatty.yttvaf -c android.intent.category.LEANBACK_LAUNCHER 1
Write-Host "Install finished successfully."


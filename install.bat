@echo off
cd /d "%~dp0"
node node_modules/vite/bin/vite.js build
node node_modules/@capacitor/cli/bin/capacitor copy android
node node_modules/@capacitor/cli/bin/capacitor sync android
echo === All done! ===
echo APK at: android/app/build/outputs/apk/debug/

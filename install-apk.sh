#! /usr/bin/env nix-shell
#!nix-shell -i bash -p androidenv.androidPkgs_9_0.platform-tools
echo "Devices"
adb devices
#adb -d uninstall finance.valet
adb -d install -r ./app/build/outputs/apk/debug/Valet-4.4.4.apk

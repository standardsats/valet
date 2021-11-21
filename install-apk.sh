#! /usr/bin/env nix-shell
#!nix-shell -i bash -p androidenv.androidPkgs_9_0.platform-tools

adb -d uninstall com.btcontract.wallet
adb install -r ./app/build/outputs/apk/debug/SBW-2.2.17.apk

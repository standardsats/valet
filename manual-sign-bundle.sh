#! /usr/bin/env nix-shell
#!nix-shell -i bash -p androidsdk_9_0 -p apksigner -p 
set -xe
#echo $PATH
#SDK_DERIVATION=/nix/store/w1259lpbs1wg39ji71hbyvvq7q8d3ac4-build-tools-31.0.0
#$SDK_DERIVATION/libexec/android-sdk/build-tools/31.0.0/zipalign -v 4 app-release.aab app-release-aligned.aab
apksigner sign --ks release.keystore --ks-key-alias release --v1-signing-enabled true --v2-signing-enabled true --min-sdk-version 33 app-release.aab

#! /usr/bin/env nix-shell
#!nix-shell -i bash -p androidsdk_9_0 -p apksigner
set -xe
echo $PATH
SDK_DERIVATION=/nix/store/w1259lpbs1wg39ji71hbyvvq7q8d3ac4-build-tools-31.0.0
VERSION=3.7.0
rm app/build/outputs/apk/release/StandardSats-SBW-$VERSION-aligned.apk || true
$SDK_DERIVATION/libexec/android-sdk/build-tools/31.0.0/zipalign -v 4 app/build/outputs/apk/release/StandardSats-SBW-$VERSION.apk app/build/outputs/apk/release/StandardSats-SBW-$VERSION-aligned.apk
apksigner sign --ks release.keystore --ks-key-alias release --v1-signing-enabled true --v2-signing-enabled true app/build/outputs/apk/release/StandardSats-SBW-$VERSION-aligned.apk

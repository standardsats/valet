#! /usr/bin/env nix-shell
#!nix-shell -i bash -p androidsdk_9_0 -p apksigner
set -xe
echo $PATH
zipalign=~/Android/Sdk/build-tools/30.0.3/zipalign
VERSION=4.2.0
rm app/build/outputs/apk/release/Valet-$VERSION-aligned.apk || true
$zipalign -v 4 app/build/outputs/apk/release/Valet-$VERSION.apk app/build/outputs/apk/release/Valet-$VERSION-aligned.apk
apksigner sign --ks release.keystore --ks-key-alias release --v1-signing-enabled true --v2-signing-enabled true app/build/outputs/apk/release/Valet-$VERSION-aligned.apk

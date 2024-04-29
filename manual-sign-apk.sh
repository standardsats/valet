#! /usr/bin/env nix-shell
#!nix-shell -i bash -p androidsdk_9_0 -p apksigner
set -xe
export NIXPKGS_ACCEPT_ANDROID_SDK_LICENSE=1
apksigner sign --ks release.keystore --ks-key-alias release --v1-signing-enabled true --v2-signing-enabled true ./Valet-aligned.apk

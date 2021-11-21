with import ./pkgs.nix {
  config = {
    android_sdk.accept_license = true;
  };
};

let
  androidComposition = androidenv.composeAndroidPackages {
    includeEmulator = true;
    platformVersions = [ "28" "29" ];
    includeSources = false;
    includeSystemImages = false;
    systemImageTypes = [ "google_apis_playstore" ];
    abiVersions = [ "armeabi-v7a" "arm64-v8a" ];
    includeNDK = true;
    useGoogleAPIs = false;
    useGoogleTVAddOns = false;
    includeExtras = [
      "extras;google;gcm"
    ];
  };
in
stdenv.mkDerivation rec {
  name = "rust-env";
  env = buildEnv { name = name; paths = buildInputs; };

  buildInputs = [
    androidComposition.androidsdk
  ];
  shellHook = ''
  export ANDROID_HOME="${androidComposition.androidsdk}"
  echo "Android SDK is located $ANDROID_HOME"
  '';
}

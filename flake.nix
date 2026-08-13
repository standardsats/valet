{
  description = "APK / binary inspection toolbox (nixpkgs 26.05)";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-26.05";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" ];

      # androidenv needs the Android SDK licence accepted, and the build-tools
      # come straight from Google, so they are unfree.
      pkgsFor = system: import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };

      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f (pkgsFor system));

      toolsFor = pkgs:
        let
          buildToolsVersion = "34.0.0";

          androidComposition = pkgs.androidenv.composeAndroidPackages {
            buildToolsVersions = [ buildToolsVersion ];
            platformVersions = [ ];
            includeEmulator = false;
            includeSystemImages = false;
            includeNDK = false;
            includeSources = false;
          };

          buildTools = builtins.head androidComposition.build-tools;

          # build-tools installs under libexec/, nothing lands on $PATH,
          # so expose just the dexdump binary.
          dexdump = pkgs.runCommandLocal "dexdump-${buildToolsVersion}" { } ''
            mkdir -p $out/bin
            ln -s ${buildTools}/libexec/android-sdk/build-tools/${buildToolsVersion}/dexdump \
              $out/bin/dexdump
          '';
        in
        [
          pkgs.aapt        # aapt/aapt2 (standalone, free build from AOSP)
          pkgs.apksigner   # sign / verify APKs
          pkgs.apksigcopier
          dexdump          # from Android build-tools ${buildToolsVersion}
          pkgs.bat
          pkgs.dos2unix
          pkgs.unzip
          pkgs.diffoscope
        ];
    in
    {
      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShellNoCC {
          packages = toolsFor pkgs;

          shellHook = ''
            echo "apk-tools: aapt apksigner dexdump bat dos2unix unzip diffoscope"
          '';
        };
      });

      # `nix build` / `nix profile install .` gives the same set as a single env
      packages = forAllSystems (pkgs: {
        default = pkgs.buildEnv {
          name = "apk-tools";
          paths = toolsFor pkgs;
        };
      });

      formatter = forAllSystems (pkgs: pkgs.nixfmt);
    };
}

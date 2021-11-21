# To update nix-prefetch-git https://github.com/NixOS/nixpkgs-channels
import ((import <nixpkgs> {}).fetchFromGitHub {
  owner = "NixOS";
  repo = "nixpkgs-channels";
  rev = "4762fba469e2baa82f983b262e2c06ac2fdaae67";
  sha256 = "1sidky93vc2bpnwb8avqlym1p70h2szhkfiam549377v9r5ld2r1";
})

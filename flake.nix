{
  description = "graalnix: a Nix language implementation on GraalVM/Truffle";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { self, nixpkgs }:
    let
      systems = [
        "x86_64-linux"
        "aarch64-linux"
      ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      packages = forAllSystems (pkgs: rec {
        graalnix = pkgs.callPackage ./nix/package.nix { };
        graalnix-native = graalnix.native;
        graalnix-lite = graalnix.lite;
        graalnix-lite-native = graalnix.lite-native;
        inherit (graalnix) docs playground;
        maven-repo = graalnix.mavenRepo;
        default = graalnix;
      });

      checks = forAllSystems (pkgs: {
        inherit (self.packages.${pkgs.stdenv.hostPlatform.system})
          graalnix
          graalnix-native
          graalnix-lite
          graalnix-lite-native
          ;
        inherit (self.packages.${pkgs.stdenv.hostPlatform.system}.graalnix)
          tests
          docs
          playground
          mavenRepo
          ;
        playground-tests = self.packages.${pkgs.stdenv.hostPlatform.system}.graalnix.playgroundTests;
        lint-builders = pkgs.callPackage ./nix/lint-builders.nix { };
        nixpkgs-formatted = pkgs.runCommand "nixpkgs-formatted" { } ''
          ${self.packages.${pkgs.stdenv.hostPlatform.system}.graalnix-lite-native}/bin/graalnix format --check ${nixpkgs}
          touch $out
        '';
      });

      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = [
            pkgs.graalvmPackages.graalvm-ce-musl
            pkgs.vendir
          ];
          JAVA_HOME = pkgs.graalvmPackages.graalvm-ce-musl;
        };
      });

      formatter = forAllSystems (pkgs: pkgs.nixfmt-rfc-style);
    };
}

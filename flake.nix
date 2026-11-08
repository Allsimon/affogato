{
  description = "affogato: a Nix language implementation on Java";

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
        affogato = pkgs.callPackage ./nix/package.nix { };
        affogato-native = affogato.native;
        inherit (affogato) docs playground site;
        maven-repo = affogato.mavenRepo;
        default = affogato;
      });

      checks = forAllSystems (pkgs: {
        inherit (self.packages.${pkgs.stdenv.hostPlatform.system})
          affogato
          affogato-native
          ;
        inherit (self.packages.${pkgs.stdenv.hostPlatform.system}.affogato)
          tests
          docs
          playground
          site
          mavenRepo
          ;
        playground-tests = self.packages.${pkgs.stdenv.hostPlatform.system}.affogato.playgroundTests;
        lint-builders = pkgs.callPackage ./nix/lint-builders.nix { };
        nixpkgs-formatted = pkgs.runCommand "nixpkgs-formatted" { } ''
          ${self.packages.${pkgs.stdenv.hostPlatform.system}.affogato-native}/bin/affogato format --check ${nixpkgs}
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

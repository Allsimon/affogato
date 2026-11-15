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
      # The Nix whose language tests vendir.yml vendors, and whose builtins BuiltinDocsTest documents; the vendir-nix
      # check keeps vendir in step.
      nixFor = pkgs: pkgs.nixVersions.nix_2_35;
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
        vendir-nix =
          let
            lock = builtins.readFile ./vendir.lock.yml;
            tag = builtins.match ".*tags:\n      - ([^\n]+)\n    path: \\.\n  path: language/src/test/java/lang\n.*" lock;
            locked = if tag == null then "no tag" else builtins.head tag;
            nix = (nixFor pkgs).version;
          in
          pkgs.runCommand "vendir-nix" { } ''
            if [ ${pkgs.lib.escapeShellArg locked} != ${pkgs.lib.escapeShellArg nix} ]; then
              echo "vendir.lock.yml locks NixOS/nix ${locked} for the language tests, the dev shell has Nix ${nix}" >&2
              exit 1
            fi
            touch $out
          '';
      });

      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          packages = [
            pkgs.jdk25_headless
            (nixFor pkgs)
            pkgs.vendir
          ];
          JAVA_HOME = pkgs.jdk25_headless;
          # Jenesis looks for native-image there before the running JDK.
          GRAALVM_HOME = pkgs.graalvmPackages.graalvm-ce-musl;
        };
      });

      formatter = forAllSystems (pkgs: pkgs.nixfmt-rfc-style);
    };
}

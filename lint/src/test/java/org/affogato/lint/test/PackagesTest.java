package org.affogato.lint.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.affogato.lint.Edit;
import org.affogato.lint.Packages;
import org.affogato.lint.Packages.Package;
import org.junit.jupiter.api.Test;

class PackagesTest {
    private static final String OLD = "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String NEW = "sha256-BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=";

    private static Package only(String src) {
        List<Package> found = Packages.find(src);
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private static String update(String src, String version) {
        Package p = only(src);
        assertThat(p.skipped()).isNull();
        return Edit.apply(
                src, p.update(version, p.sources().stream().map(s -> NEW).toList()));
    }

    @Test
    void recWithInterpolatedTag() {
        String src = """
            { stdenv, fetchFromGitHub }:
            stdenv.mkDerivation rec {
              pname = "hello";
              version = "1.2.0";
              src = fetchFromGitHub {
                owner = "acme";
                repo = pname;
                rev = "v${version}";
                hash = "%s";
              };
            }
            """.formatted(OLD);
        Package p = only(src);
        assertThat(p.name()).isEqualTo("hello");
        assertThat(p.version()).isEqualTo("1.2.0");
        assertThat(p.sources()).singleElement().satisfies(s -> {
            assertThat(s.owner()).isEqualTo("acme");
            assertThat(s.repo()).isEqualTo("hello");
            assertThat(s.tag("1.3.0")).isEqualTo("v1.3.0");
            assertThat(s.revStart()).isEqualTo(-1);
        });
        assertThat(update(src, "1.3.0")).isEqualTo(src.replace("1.2.0", "1.3.0").replace(OLD, NEW));
    }

    @Test
    void finalAttrsWithTag() {
        String src = """
            { stdenv, fetchFromGitHub }:
            stdenv.mkDerivation (finalAttrs: {
              pname = "hello";
              version = "1.2.0";
              src = fetchFromGitHub {
                owner = "acme";
                repo = "hello";
                tag = "release-${finalAttrs.version}";
                sha256 = "%s";
              };
              cargoHash = "sha256-x";
            })
            """.formatted(OLD);
        Package p = only(src);
        assertThat(p.sources().getFirst().tagPrefix()).isEqualTo("release-");
        assertThat(p.dependencies()).singleElement().satisfies(d -> {
            assertThat(d.name()).isEqualTo("cargoHash");
            assertThat(d.attr()).isEqualTo("cargoDeps");
            assertThat(Edit.apply(src, List.of(d.update(NEW)))).contains("cargoHash = \"" + NEW + "\";");
        });
        assertThat(update(src, "2.0")).isEqualTo(src.replace("1.2.0", "2.0").replace(OLD, NEW));
    }

    @Test
    void letBindings() {
        String src = """
            { buildGoModule, fetchFromGitHub }:
            let
              owner = "acme";
              version = "0.9";
            in
            buildGoModule {
              pname = "tool";
              inherit version;
              src = fetchFromGitHub {
                inherit owner;
                repo = "tool";
                rev = version;
                hash = "%s";
              };
              vendorHash = null;
            }
            """.formatted(OLD);
        Package p = only(src);
        assertThat(p.sources().getFirst().owner()).isEqualTo("acme");
        assertThat(p.dependencies()).isEmpty();
        assertThat(update(src, "1.0")).isEqualTo(src.replace("0.9", "1.0").replace(OLD, NEW));
    }

    @Test
    void literalRevContainingVersion() {
        String src = """
            { stdenv, fetchFromGitHub }:
            stdenv.mkDerivation {
              pname = "hello";
              version = "1.2";
              src = fetchFromGitHub {
                owner = "acme";
                repo = "hello";
                rev = "v1.2";
                hash = "%s";
              };
            }
            """.formatted(OLD);
        assertThat(update(src, "1.10")).isEqualTo(src.replace("1.2", "1.10").replace(OLD, NEW));
    }

    @Test
    void literalTagWithoutVersion() {
        String src = """
            { fetchFromGitHub }:
            fetchFromGitHub { owner = "acme"; repo = "data"; rev = "v3"; hash = lib.fakeHash; }
            """;
        Package p = only(src);
        assertThat(p.name()).isNull();
        assertThat(p.version()).isEqualTo("3");
        assertThat(update(src, "4")).isEqualTo(src.replace("v3", "v4").replace("lib.fakeHash", '"' + NEW + '"'));
    }

    @Test
    void twoSourcesShareTheVersion() {
        String src = """
            { stdenv, fetchFromGitHub }:
            stdenv.mkDerivation rec {
              pname = "app";
              version = "1.0";
              assets = fetchFromGitHub { owner = "acme"; repo = "assets"; rev = version; hash = "%s"; };
              src = fetchFromGitHub { owner = "acme"; repo = "app"; rev = "v${version}"; hash = "%s"; };
            }
            """.formatted(OLD, OLD);
        Package p = only(src);
        assertThat(p.sources()).extracting(Packages.Source::repo).containsExactly("app", "assets");
        assertThat(update(src, "1.1")).isEqualTo(src.replace("1.0", "1.1").replace(OLD, NEW));
    }

    @Test
    void skipped() {
        String src = """
            { stdenv, fetchFromGitHub }:
            [
              (fetchFromGitHub { owner = "a"; repo = "b"; rev = "v1"; fetchSubmodules = true; hash = ""; })
              (fetchFromGitHub { owner = "a"; repo = "b"; rev = "v1"; fetchSubmodules = false; hash = ""; })
              (fetchFromGitHub { owner = "a"; repo = "b"; rev = "0123456789abcdef0123456789abcdef01234567"; hash = ""; })
              (fetchFromGitHub { owner = x; repo = "b"; rev = "v1"; hash = ""; })
            ]
            """;
        assertThat(Packages.find(src))
                .extracting(Package::skipped)
                .containsExactly(
                        "fetchSubmodules: the source is not an attribute of the package, to build its hash",
                        null,
                        "pinned to a commit",
                        "the owner or repository is not a constant");
    }

    @Test
    void dynamicAttributeNames() {
        String src = """
            { stdenv, fetchFromGitHub, n }:
            stdenv.mkDerivation rec {
              pname = "hello";
              version = "1.0";
              ${n} = "x";
              src = fetchFromGitHub { owner = "a"; repo = "b"; rev = version; hash = x.${n}; };
            }
            """;
        assertThat(only(src).skipped()).isEqualTo("the hash is not a string");
    }

    @Test
    void dependencyFetchers() {
        String src = """
            { stdenv, rustPlatform, fetchFromGitHub, pnpm }:
            stdenv.mkDerivation (finalAttrs: {
              pname = "app";
              version = "1.0";
              src = fetchFromGitHub { owner = "a"; repo = "app"; rev = finalAttrs.version; hash = ""; };
              cargoDeps = rustPlatform.fetchCargoVendor { inherit (finalAttrs) src; hash = lib.fakeHash; };
              pnpmDeps = pnpm.fetchDeps { inherit (finalAttrs) pname src; hash = "sha256-x"; };
              vendorHash = if stdenv.isDarwin then "a" else "b";
              patches = [ (fetchpatch { url = "u"; hash = "h"; }) ];
            })
            """;
        assertThat(only(src).dependencies())
                .extracting(d -> d.name() + " " + d.attr() + " " + (d.start() >= 0))
                .containsExactly("cargoDeps cargoDeps true", "pnpmDeps pnpmDeps true", "vendorHash goModules false");
    }

    private static final String GO = """
        buildGoModule (finalAttrs: {
          pname = "%s";
          version = "1.0";
          src = fetchFromGitHub { owner = "a"; repo = "%s"; tag = "v${finalAttrs.version}"; hash = ""; };
          vendorHash = "";
        })\
        """;

    @Test
    void routesToTheFilesPackage() {
        String src = "{ buildGoModule, fetchFromGitHub }:\n" + GO.formatted("obi", "obi") + "\n";
        assertThat(only(src).route()).isEqualTo(new Packages.Route(List.of(), List.of()));
    }

    @Test
    void routesToAPackageInALetThroughItsName() {
        String src = """
            { buildGoModule, fetchFromGitHub }:
            let
              bpf2go = %s;
            in
            %s
            """.formatted(GO.formatted("bpf2go", "ebpf"), GO.formatted("obi", "obi"));
        List<Package> found = Packages.find(src);
        assertThat(found).extracting(Package::name).containsExactly("bpf2go", "obi");
        Packages.Route route = found.getFirst().route();
        assertThat(route.path()).isEmpty();
        assertThat(Edit.apply(src, route.edits())).endsWith("in\nbpf2go\n");
        assertThat(found.get(1).route()).isEqualTo(new Packages.Route(List.of(), List.of()));
    }

    @Test
    void routesThroughAttributeNames() {
        String src = "{ buildGoModule, fetchFromGitHub }:\n{ tools.\"a-b\" = rec { x = " + GO.formatted("x", "x")
                + "; }; }\n";
        assertThat(only(src).route()).isEqualTo(new Packages.Route(List.of("tools", "a-b", "x"), List.of()));
    }

    @Test
    void hasNoRouteThroughAListOrAFunctionCall() {
        String inList = "{ buildGoModule, fetchFromGitHub }:\n[ " + GO.formatted("x", "x") + " ]\n";
        String inCall = "{ buildGoModule, fetchFromGitHub, f }:\nf " + "(" + GO.formatted("x", "x") + ") 1\n";
        assertThat(only(inList).route()).isNull();
        assertThat(only(inCall).route()).isNull();
    }

    private static final String COMMIT = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void pinnedToACommitWithAnUnstableVersion() {
        String src = """
            { stdenv, fetchFromGitHub }:
            stdenv.mkDerivation {
              pname = "tool";
              version = "0-unstable-2026-01-02";
              src = fetchFromGitHub { owner = "acme"; repo = "tool"; rev = "%s"; hash = "%s"; };
            }
            """.formatted(COMMIT, OLD);
        Package p = only(src);
        assertThat(p.skipped()).isNull();
        assertThat(p.commit()).isEqualTo(COMMIT);
        String next = "fedcba9876543210fedcba9876543210fedcba98";
        String version = Packages.unstable(p.version(), "2026-11-20");
        assertThat(Edit.apply(src, p.update(version, next, List.of(NEW))))
                .isEqualTo(src.replace("2026-01-02", "2026-11-20")
                        .replace(COMMIT, next)
                        .replace(OLD, NEW));
    }

    @Test
    void pinnedToACommitWithARelease() {
        String src = """
            { stdenv, fetchFromGitHub }:
            stdenv.mkDerivation {
              pname = "tool";
              version = "1.2";
              src = fetchFromGitHub { owner = "acme"; repo = "tool"; rev = "%s"; hash = ""; };
            }
            """.formatted(COMMIT);
        Package p = only(src);
        assertThat(p.skipped()).isEqualTo(Packages.PINNED);
        assertThat(p.updatable(false)).isFalse();
        assertThat(p.updatable(true)).isTrue();
    }

    @Test
    void unstableVersions() {
        assertThat(Packages.unstable("0-unstable-2026-01-02", "2026-11-20")).isEqualTo("0-unstable-2026-11-20");
        assertThat(Packages.unstable("unstable-2020-01-02", "2026-11-20")).isEqualTo("unstable-2026-11-20");
        assertThat(Packages.unstable("1.2", "2026-11-20")).isEqualTo("1.2-unstable-2026-11-20");
        assertThat(Packages.unstable(null, "2026-11-20")).isEqualTo("0-unstable-2026-11-20");
    }

    @Test
    void gitOptionsAreBuilt() {
        String src = """
            { stdenv, fetchFromGitHub }:
            stdenv.mkDerivation rec {
              pname = "tool";
              version = "1.0";
              src = fetchFromGitHub {
                owner = "acme";
                repo = "tool";
                rev = "v${version}";
                fetchSubmodules = true;
                hash = "";
              };
            }
            """;
        Package p = only(src);
        assertThat(p.skipped()).isNull();
        assertThat(p.sources().getFirst().built()).isEqualTo("fetchSubmodules");
        assertThat(p.sources().getFirst().attr()).containsExactly("src");
    }

    @Test
    void otherForges() {
        String src = """
            { fetchFromGitLab, fetchFromGitea, fetchFromCodeberg, fetchFromSourcehut, fetchFromBitbucket }:
            {
              a = fetchFromGitLab { group = "g"; owner = "o"; repo = "a"; rev = "v1.0"; hash = ""; };
              b = fetchFromGitea { domain = "git.example.org"; owner = "o"; repo = "b"; rev = "v1.0"; hash = ""; };
              c = fetchFromCodeberg { owner = "o"; repo = "c"; rev = "v1.0"; hash = ""; };
              d = fetchFromSourcehut { owner = "~o"; repo = "d"; rev = "v1.0"; hash = ""; };
              e = fetchFromBitbucket { owner = "o"; repo = "e"; rev = "v1.0"; hash = ""; };
              f = fetchFromSourcehut { owner = "~o"; repo = "f"; vc = "hg"; rev = "v1.0"; hash = ""; };
            }
            """;
        assertThat(Packages.find(src))
                .extracting(p -> p.sources().isEmpty()
                        ? p.skipped()
                        : p.sources().getFirst().kind() + " "
                                + p.sources().getFirst().domain() + " "
                                + p.sources().getFirst().owner())
                .containsExactly(
                        "GITLAB gitlab.com g/o",
                        "GITEA git.example.org o",
                        "GITEA codeberg.org o",
                        "SOURCEHUT git.sr.ht ~o",
                        "BITBUCKET bitbucket.org o",
                        "the domain is not a constant, or not a git one");
    }

    @Test
    void registries() {
        String src = """
            { buildPythonPackage, fetchPypi, rustPlatform, fetchCrate }:
            {
              py = buildPythonPackage rec {
                pname = "requests";
                version = "2.31.0";
                src = fetchPypi { inherit pname version; hash = "%s"; };
              };
              rs = rustPlatform.buildRustPackage rec {
                pname = "ripgrep";
                version = "14.0.0";
                src = fetchCrate { inherit pname version; hash = "%s"; };
                cargoHash = "";
              };
            }
            """.formatted(OLD, OLD);
        List<Package> found = Packages.find(src);
        assertThat(found).extracting(Package::name).containsExactly("requests", "ripgrep");
        Packages.Source py = found.getFirst().sources().getFirst();
        assertThat(py.kind()).isEqualTo(Packages.Kind.PYPI);
        assertThat(py.unpack()).isFalse();
        assertThat(py.tag("2.32.0"))
                .isEqualTo("https://files.pythonhosted.org/packages/source/r/requests/requests-2.32.0.tar.gz");
        Packages.Source rs = found.get(1).sources().getFirst();
        assertThat(rs.tag("14.1.0")).isEqualTo("https://crates.io/api/v1/crates/ripgrep/14.1.0/download");
        assertThat(rs.unpack()).isTrue();
        assertThat(Edit.apply(src, found.getFirst().update("2.32.0", List.of(NEW))))
                .contains("version = \"2.32.0\";")
                .contains("hash = \"" + NEW + "\"; };\n  };\n  rs");
    }

    @Test
    void urlsWithTheVersion() {
        String src = """
            { stdenv, lib, fetchurl, fetchzip }:
            stdenv.mkDerivation (finalAttrs: {
              pname = "glib";
              version = "2.80.1";
              src = fetchurl {
                url = "mirror://gnome/sources/glib/${lib.versions.majorMinor finalAttrs.version}/glib-${finalAttrs.version}.tar.xz";
                hash = "%s";
              };
              patches = [ (fetchurl { url = "https://example.org/fix.patch"; hash = ""; }) ];
              docs = fetchzip { url = "https://example.org/docs-${finalAttrs.version}.zip"; hash = "%s"; };
            })
            """.formatted(OLD, OLD);
        Package p = only(src);
        assertThat(p.sources())
                .extracting(s -> s.tag("2.82.0") + " " + s.unpack())
                .containsExactly(
                        "mirror://gnome/sources/glib/2.82/glib-2.82.0.tar.xz false",
                        "https://example.org/docs-2.82.0.zip true");
        assertThat(update(src, "2.82.0"))
                .isEqualTo(src.replace("2.80.1", "2.82.0").replace(OLD, NEW));
    }

    @Test
    void aLiteralUrlWithTheVersion() {
        String src = """
            { stdenv, fetchurl }:
            stdenv.mkDerivation {
              pname = "hello";
              version = "2.12";
              src = fetchurl { url = "mirror://gnu/hello/hello-2.12.tar.gz"; hash = "%s"; };
            }
            """.formatted(OLD);
        assertThat(update(src, "2.12.1"))
                .isEqualTo(src.replace("2.12", "2.12.1").replace(OLD, NEW));
    }

    @Test
    void lockFilesAndUpdateScripts() {
        String src = """
            { rustPlatform, buildDotnetModule, stdenv, gradle, fetchFromGitHub, nix-update-script }:
            {
              rs = rustPlatform.buildRustPackage rec {
                pname = "rs";
                version = "1.0";
                src = fetchFromGitHub { owner = "a"; repo = "rs"; rev = version; hash = ""; };
                cargoLock = { lockFile = ./Cargo.lock; outputHashes."x-0.1" = ""; };
                passthru.updateScript = nix-update-script { };
              };
              net = buildDotnetModule rec {
                pname = "net";
                version = "1.0";
                src = fetchFromGitHub { owner = "a"; repo = "net"; rev = version; hash = ""; };
                nugetDeps = ./deps.json;
                passthru.updateScript = ./update.sh;
              };
              jvm = stdenv.mkDerivation rec {
                pname = "jvm";
                version = "1.0";
                src = fetchFromGitHub { owner = "a"; repo = "jvm"; rev = version; hash = ""; };
                mitmCache = gradle.fetchDeps { inherit pname; data = ./deps.json; };
              };
            }
            """;
        List<Package> found = Packages.find(src);
        assertThat(found)
                .extracting(Package::locks)
                .containsExactly(
                        List.of(new Packages.Lock(Packages.LockType.CARGO, "./Cargo.lock", true)),
                        List.of(new Packages.Lock(Packages.LockType.NUGET, "./deps.json", false)),
                        List.of(new Packages.Lock(Packages.LockType.GRADLE, "./deps.json", false)));
        assertThat(found).extracting(Package::updateScript).containsExactly(false, true, false);
    }

    @Test
    void inheritsFromFinalAttrs() {
        String src = """
            { rustPlatform, fetchCrate }:
            rustPlatform.buildRustPackage (finalAttrs: {
              pname = "wiremix";
              version = "0.11.0";
              src = fetchCrate { inherit (finalAttrs) pname version; hash = "%s"; };
            })
            """.formatted(OLD);
        assertThat(update(src, "0.12.0"))
                .isEqualTo(src.replace("0.11.0", "0.12.0").replace(OLD, NEW));
    }
}

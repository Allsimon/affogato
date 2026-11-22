package org.affogato.cli.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.affogato.cli.Main;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateTest {
    private static final String OLD = "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    /** What the fake prefetcher prints, in Nix base32, and its SRI form. */
    private static final String NIX32 = "0".repeat(51) + "1";

    private static final String SRI = "sha256-AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String FAKE = "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
    private static final String GOT = "sha256-CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=";
    private static final FileTime EPOCH = FileTime.fromMillis(0);

    @TempDir
    Path dir;

    private final StringWriter err = new StringWriter();
    private final Map<String, String> github = new ConcurrentHashMap<>();
    private HttpServer server;
    private Path prefetcher;
    private Path nix;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String body = github.get(exchange.getRequestURI().toString());
            byte[] bytes = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(body == null ? 404 : 200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        prefetcher = dir.resolve("bin/prefetch");
        Files.createDirectories(prefetcher.getParent());
        Files.writeString(prefetcher, """
            #!/bin/sh
            for a in "$@"; do case "$a" in http*|mirror*) echo "$a" >> "$0.log";; esac; done
            echo %s
            echo "$0.src"
            """.formatted(NIX32));
        Files.setPosixFilePermissions(prefetcher, PosixFilePermissions.fromString("rwx------"));
        nix = dir.resolve("bin/nix");
        nixReports("  got:    " + GOT);
    }

    /**
     * What {@code nix} and {@code nix-build} print. They log their arguments, and the {@code cargoHash} of the file
     * {@code nix-build} calls, or of the files of the flake {@code nix} builds.
     */
    private void nixReports(String output) throws IOException {
        nixReports(output, false);
    }

    /**
     * As {@link #nixReports(String)}; a build without the fake hash succeeds unless {@code buildsFail}, and that of
     * the derivations of an update script does.
     */
    private void nixReports(String output, boolean buildsFail) throws IOException {
        Files.writeString(nix, """
            #!/bin/sh
            echo "$@" >> "$0.log"
            fake=%s
            for a in "$@"; do
              case "$a" in
                *builtins.filter*) exit 0;;
                *callPackage*) f=$(echo "$a" | sed 's|.*callPackage (/[.] + "\\([^"]*\\)").*|\\1|')
                  grep -ho 'cargoHash = "[^"]*"' "$f" >> "$0.log"
                  grep -q AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA "$f" && fake=1;;
                path:*) d=${a#path:}; grep -rho 'cargoHash = "[^"]*"' "${d%%%%#*}/pkgs" >> "$0.log"; fake=1;;
              esac
            done
            if [ -n "$fake" ]; then
              echo 'error: hash mismatch in fixed-output derivation'
              echo '%s'
              exit 1
            fi
            echo /nix/store/00000000000000000000000000000000-built
            """.formatted(buildsFail ? "1" : "", output));
        Files.setPosixFilePermissions(nix, PosixFilePermissions.fromString("rwx------"));
    }

    private String built() throws IOException {
        Path log = Path.of(nix + ".log");
        return Files.exists(log) ? Files.readString(log) : "";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private int run(String... args) {
        List<String> all = new ArrayList<>(List.of(
                "update",
                "--github-api",
                "http://localhost:" + server.getAddress().getPort(),
                "--prefetcher",
                prefetcher.toString(),
                "--nix-build",
                nix.toString(),
                "--nix",
                nix.toString()));
        all.addAll(List.of(args));
        return Main.commandLine()
                .setOut(new PrintWriter(new StringWriter(), true))
                .setErr(new PrintWriter(err, true))
                .execute(all.toArray(String[]::new));
    }

    private Path file(String name, String content) throws IOException {
        Path p = dir.resolve(name);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
        Files.setLastModifiedTime(p, EPOCH);
        return p;
    }

    private static String pkg(String repo, String version) {
        return """
            { stdenv, fetchFromGitHub }:
            stdenv.mkDerivation rec {
              pname = "%s";
              version = "%s";
              src = fetchFromGitHub {
                owner = "acme";
                repo = pname;
                rev = "v${version}";
                hash = "%s";
              };
            }
            """.formatted(repo, version, OLD);
    }

    private void release(String repo, String tag) {
        github.put("/repos/acme/" + repo + "/releases/latest", "{\"tag_name\": \"" + tag + "\"}");
    }

    private String prefetched() throws IOException {
        Path log = Path.of(prefetcher + ".log");
        return Files.exists(log) ? Files.readString(log) : "";
    }

    @Test
    void updatesVersionAndHash() throws IOException {
        Path p = file("pkgs/hello/default.nix", pkg("hello", "1.2.0"));
        release("hello", "v1.3.0");

        assertThat(run(dir.toString())).isZero();

        assertThat(Files.readString(p)).isEqualTo(pkg("hello", "1.3.0").replace(OLD, SRI));
        assertThat(prefetched()).isEqualTo("https://github.com/acme/hello/archive/v1.3.0.tar.gz\n");
        assertThat(err.toString())
                .matches("hello  1\\.2\\.0  -> 1\\.3\\.0  \\Q" + p
                        + "\\E\n1 updated, 0 up to date, 0 skipped in \\d+ms\n");
    }

    @Test
    void checkWritesNothingAndFailsOnAnUpdate() throws IOException {
        Path old = file("a/default.nix", pkg("a", "1.0"));
        Path current = file("b/default.nix", pkg("b", "2.0"));
        release("a", "v1.1");
        release("b", "v2.0");

        assertThat(run("--check", "-v", dir.toString())).isEqualTo(1);

        assertThat(Files.getLastModifiedTime(old)).isEqualTo(EPOCH);
        assertThat(prefetched()).isEmpty();
        assertThat(err.toString())
                .contains("a  1.0  -> 1.1  " + old + "\n")
                .contains("b  2.0  up to date  " + current + "\n")
                .containsPattern("\n1 to update, 1 up to date, 0 skipped in \\d+ms\n");
        assertThat(run("--check", current.toString())).isZero();
    }

    @Test
    void fallsBackToTagsOfTheSameShapeWithoutPreReleases() throws IOException {
        Path p = file("tool.nix", pkg("tool", "1.9"));
        github.put(
                "/repos/acme/tool/tags?per_page=100",
                "[{\"name\": \"v2.0-rc1\"}, {\"name\": \"nightly\"}, {\"name\": \"other-3.0\"}, {\"name\": \"v1.10\"},"
                        + " {\"name\": \"v1.9\"}]");

        assertThat(run(p.toString())).isZero();

        assertThat(Files.readString(p)).contains("version = \"1.10\";");
    }

    @Test
    void reportsSkippedPackagesAndDependencyHashes() throws IOException {
        file("pinned.nix", """
            { fetchFromGitHub }:
            fetchFromGitHub { owner = "acme"; repo = "x"; rev = "0123456789abcdef0123456789abcdef01234567"; hash = ""; }
            """);
        Path rust = file("rust.nix", pkg("rust", "1.0").replace("  src =", "  cargoHash = \"\";\n  src ="));
        release("rust", "v1.1");

        assertThat(run("--no-build", dir.toString())).isZero();
        assertThat(err.toString())
                .contains("rust    1.0  -> 1.1 (cargoHash not updated)  " + rust + "\n")
                .doesNotContain("pinned")
                .containsPattern("\n1 updated, 0 up to date, 1 skipped in ");

        assertThat(run("-v", dir.toString())).isZero();
        assertThat(err.toString()).contains("pinned       skipped: pinned to a commit  " + dir.resolve("pinned.nix"));
    }

    @Test
    void ignoresHiddenDirectories() throws IOException {
        Path hidden = file(".worktree/hello.nix", pkg("hello", "1.0"));
        release("hello", "v2.0");

        assertThat(run(dir.toString())).isZero();

        assertThat(Files.getLastModifiedTime(hidden)).isEqualTo(EPOCH);
        assertThat(err.toString()).startsWith("0 updated");
    }

    @Test
    void aFailedPackageDoesNotStopTheOthers() throws IOException {
        Path missing = file("a.nix", pkg("missing", "1.0"));
        Path b = file("b.nix", pkg("b", "1.0"));
        release("b", "v1.1");
        github.put("/repos/acme/missing/releases/latest", "not json");

        assertThat(run(dir.toString())).isEqualTo(1);

        assertThat(Files.readString(b)).contains("1.1");
        assertThat(Files.getLastModifiedTime(missing)).isEqualTo(EPOCH);
        assertThat(err.toString()).contains("error: GitHub sent invalid JSON").contains(", 1 failed in ");
    }

    @Test
    void namesPackagesAfterTheirFlakeAttribute() throws IOException {
        Path p = file("pkgs/hello.nix", pkg("hello", "1.0"));
        file("flake.nix", """
            {
              outputs = { self }: {
                packages = builtins.listToAttrs (map (system: {
                  name = system;
                  value.hi = import ./pkgs/hello.nix {
                    stdenv.mkDerivation = a: a;
                    fetchFromGitHub = a: a;
                  };
                }) [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ]);
              };
            }
            """);
        release("hello", "v1.1");

        assertThat(run("--check", dir.toString())).isEqualTo(1);
        assertThat(err.toString()).startsWith("hi  1.0  -> 1.1  " + p + "\n");

        assertThat(run("--check", "--no-eval", dir.toString())).isEqualTo(1);
        assertThat(err.toString()).contains("\nhello  1.0  -> 1.1  " + p + "\n");
    }

    @Test
    void aFlakeThatDoesNotEvaluateKeepsTheFileNames() throws IOException {
        file("hello.nix", pkg("hello", "1.0"));
        file("flake.nix", "{ outputs = { self }: throw \"broken\"; }");
        release("hello", "v1.1");

        assertThat(run("--check", dir.toString())).isEqualTo(1);
        assertThat(err.toString())
                .startsWith(dir.resolve("flake.nix") + ": warning: cannot evaluate the packages: ")
                .contains("\nhello  1.0  -> 1.1  ");
    }

    @Test
    void anUnreachableApiIsReported() throws IOException {
        file("hello.nix", pkg("hello", "1.0"));
        String api = "http://localhost:" + server.getAddress().getPort();
        server.stop(0);

        assertThat(run(dir.toString())).isEqualTo(1);

        assertThat(err.toString()).contains("hello  1.0  error: cannot reach " + api + ": ");
    }

    private static String rust(String version, String cargoHash) {
        return pkg("rust", version).replace("  src =", "  cargoHash = \"" + cargoHash + "\";\n  src =");
    }

    @Test
    void updatesDependencyHashesWithCallPackage() throws IOException {
        Path p = file("pkgs/rust/package.nix", rust("1.0", OLD));
        release("rust", "v1.1");

        assertThat(run(dir.toString())).isZero();

        assertThat(Files.readString(p)).isEqualTo(rust("1.1", GOT).replace("hash = \"" + OLD, "hash = \"" + SRI));
        assertThat(built())
                .matches("--no-out-link -E \\(import <nixpkgs> \\{ }\\).callPackage \\(/. \\+ \""
                        + Pattern.quote(p.getParent() + "/.affogato-update-") + "\\d+\\.nix\"\\) \\{ } -A cargoDeps\n"
                        + "cargoHash = \"" + FAKE + "\"\n");
        try (var files = Files.list(p.getParent())) {
            assertThat(files).containsExactly(p);
        }
        assertThat(err.toString()).startsWith("rust  1.0  -> 1.1  " + p + "\n");
    }

    @Test
    void buildsWithTheNixpkgsCheckoutTheFileIsIn() throws IOException {
        file("pkgs/top-level/all-packages.nix", "{ }");
        file("pkgs/rust/package.nix", rust("1.0", OLD));
        release("rust", "v1.1");

        assertThat(run(dir.toString())).isZero();

        assertThat(built()).startsWith("--no-out-link -E (import (/. + \"" + dir + "\") { }).callPackage ");
    }

    @Test
    void aFailedBuildLeavesTheFileAsItWas() throws IOException {
        Path p = file("pkgs/rust/package.nix", rust("1.0", OLD));
        release("rust", "v1.1");
        nixReports("error: builder failed");

        assertThat(run(dir.toString())).isEqualTo(1);

        assertThat(Files.readString(p)).isEqualTo(rust("1.0", OLD));
        assertThat(err.toString()).contains("rust  1.0  error: cargoHash: cannot build: error: builder failed  ");
    }

    @Test
    void buildsTheAttributeOfTheFlakePackage() throws IOException {
        Path p = file("pkgs/rust.nix", rust("1.0", OLD));
        file("flake.nix", """
            {
              outputs = { self }: {
                packages = builtins.listToAttrs (map (system: {
                  name = system;
                  value.rs = import ./pkgs/rust.nix {
                    stdenv.mkDerivation = a: a;
                    fetchFromGitHub = a: a;
                  };
                }) [ "x86_64-linux" "aarch64-linux" "x86_64-darwin" "aarch64-darwin" ]);
              };
            }
            """);
        release("rust", "v1.1");

        assertThat(run(dir.toString())).isZero();

        assertThat(Files.readString(p)).contains("cargoHash = \"" + GOT + "\";");
        assertThat(built())
                .matches("--extra-experimental-features nix-command flakes build --no-link"
                        + " path:/\\S+/affogato-update-\\d+#rs\\.cargoDeps\n"
                        + "cargoHash = \"" + FAKE + "\"\n");
    }

    @Test
    void refreshesTheHashesAtTheVersionTheyHave() throws IOException {
        Path p = file("pkgs/hello/default.nix", pkg("hello", "1.2.0"));
        Path right = file("pkgs/right/default.nix", pkg("right", "2.0").replace(OLD, SRI));

        assertThat(run("--refresh", "-v", dir.toString())).isZero();

        assertThat(Files.readString(p)).isEqualTo(pkg("hello", "1.2.0").replace(OLD, SRI));
        assertThat(Files.getLastModifiedTime(right)).isEqualTo(EPOCH);
        assertThat(prefetched().lines())
                .containsExactlyInAnyOrder(
                        "https://github.com/acme/hello/archive/v1.2.0.tar.gz",
                        "https://github.com/acme/right/archive/v2.0.tar.gz");
        assertThat(err.toString())
                .contains("hello  1.2.0  hashes refreshed  " + p + "\n")
                .contains("right  2.0    hashes up to date  " + right + "\n")
                .containsPattern("\n1 refreshed, 1 up to date, 0 skipped in ");
    }

    @Test
    void refreshesTheHashOfAPackagePinnedToACommit() throws IOException {
        String commit = "0123456789abcdef0123456789abcdef01234567";
        Path p = file("pinned.nix", pkg("pinned", "0-unstable-2026-11-01").replace("v${version}", commit));

        assertThat(run("--refresh", "--check", dir.toString())).isEqualTo(1);
        assertThat(Files.getLastModifiedTime(p)).isEqualTo(EPOCH);
        assertThat(err.toString()).contains("hashes to refresh").containsPattern("\n1 to refresh, ");

        assertThat(run("--refresh", dir.toString())).isZero();
        assertThat(Files.readString(p)).contains("hash = \"" + SRI + "\";").contains(commit);
        assertThat(prefetched()).endsWith("https://github.com/acme/pinned/archive/" + commit + ".tar.gz\n");
    }

    @Test
    void refreshesTheDependencyHashesToo() throws IOException {
        Path p = file("pkgs/rust/package.nix", rust("1.0", OLD).replace(OLD, SRI));

        assertThat(run("--refresh", dir.toString())).isZero();
        assertThat(Files.readString(p)).isEqualTo(rust("1.0", GOT).replace("hash = \"" + OLD, "hash = \"" + SRI));
    }

    @Test
    void buildsAPackageInALetThroughItsName() throws IOException {
        Path p = file(
                "pkgs/both/package.nix",
                "{ stdenv, fetchFromGitHub }:\nlet\n  inner = "
                        + rust("1.0", OLD)
                                .replace(OLD, SRI)
                                .replace("{ stdenv, fetchFromGitHub }:\n", "")
                                .replace("\n", "\n  ")
                                .strip()
                        + ";\n"
                        + "in\n"
                        + "stdenv.mkDerivation { pname = \"outer\"; version = \"2.0\"; buildInputs = [ inner ];"
                        + " }\n");

        assertThat(run("--refresh", dir.toString())).as(() -> err.toString()).isZero();

        assertThat(Files.readString(p)).contains("cargoHash = \"" + GOT + "\";");
        assertThat(built()).contains(" -A cargoDeps\n").contains("cargoHash = \"" + FAKE + "\"");
    }

    @Test
    void updatesOnlyThePackagesNamed() throws IOException {
        Path a = file("a/default.nix", pkg("a", "1.0"));
        Path b = file("b/default.nix", pkg("b", "1.0"));
        release("a", "v1.1");
        release("b", "v1.1");

        assertThat(run(dir.toString(), "b", "nope")).isEqualTo(1);

        assertThat(Files.getLastModifiedTime(a)).isEqualTo(EPOCH);
        assertThat(Files.readString(b)).contains("1.1");
        assertThat(err.toString())
                .startsWith("error: no package or path named nope\n")
                .contains("b  1.0  -> 1.1  " + b + "\n")
                .doesNotContain(a.toString());
    }

    @Test
    void movesAPackagePinnedToACommitToTheHeadOfItsBranch() throws IOException {
        String commit = "0123456789abcdef0123456789abcdef01234567";
        String head = "fedcba9876543210fedcba9876543210fedcba98";
        Path p = file("tool.nix", pkg("tool", "0-unstable-2026-01-02").replace("v${version}", commit));
        github.put("/repos/acme/tool", "{\"default_branch\": \"main\"}");
        github.put(
                "/repos/acme/tool/commits/main",
                "{\"sha\": \"" + head + "\", \"commit\": {\"committer\": {\"date\": \"2026-11-20T23:30:00-02:00\"}}}");

        assertThat(run(p.toString())).as(err::toString).isZero();

        assertThat(Files.readString(p))
                .isEqualTo(pkg("tool", "0-unstable-2026-11-21")
                        .replace("v${version}", head)
                        .replace(OLD, SRI));
        assertThat(prefetched()).isEqualTo("https://github.com/acme/tool/archive/" + head + ".tar.gz\n");
        assertThat(err.toString()).startsWith("tool  0-unstable-2026-01-02  -> 0-unstable-2026-11-21  ");
    }

    @Test
    void movesAReleasePinnedToACommitOnlyWithBranch() throws IOException {
        String commit = "0123456789abcdef0123456789abcdef01234567";
        Path p = file("tool.nix", pkg("tool", "1.2").replace("v${version}", commit));
        github.put("/repos/acme/tool", "{\"default_branch\": \"dev\"}");
        github.put(
                "/repos/acme/tool/commits/dev",
                "{\"sha\": \"" + "a".repeat(40)
                        + "\", \"commit\": {\"committer\": {\"date\": \"2026-11-20T10:00:00Z\"}}}");

        assertThat(run("-v", p.toString())).isZero();
        assertThat(Files.getLastModifiedTime(p)).isEqualTo(EPOCH);
        assertThat(err.toString()).contains("skipped: pinned to a commit");

        assertThat(run("--branch", p.toString())).isZero();
        assertThat(Files.readString(p)).contains("version = \"1.2-unstable-2026-11-20\";");
    }

    @Test
    void buildsTheHashOfASourceWithSubmodules() throws IOException {
        Path p = file(
                "pkgs/sub/package.nix",
                pkg("sub", "1.0").replace("    hash =", "    fetchSubmodules = true;\n    hash ="));
        release("sub", "v1.1");

        assertThat(run(dir.toString())).as(err::toString).isZero();

        assertThat(Files.readString(p)).contains("version = \"1.1\";").contains("hash = \"" + GOT + "\";");
        assertThat(prefetched()).isEmpty();
        assertThat(built()).contains(" -A src\n");
    }

    @Test
    void updatesFromOtherForgesAndRegistries() throws IOException {
        String host = "http://localhost:" + server.getAddress().getPort();
        Path gitlab = file("gitlab.nix", pkg("lab", "1.0").replace("fetchFromGitHub", "fetchFromGitLab"));
        Path pypi = file("pypi.nix", """
            { buildPythonPackage, fetchPypi }:
            buildPythonPackage rec {
              pname = "requests";
              version = "2.31.0";
              src = fetchPypi { inherit pname version; hash = "%s"; };
            }
            """.formatted(OLD));
        Path npm = file("npm.nix", """
            { stdenv, fetchurl }:
            stdenv.mkDerivation rec {
              pname = "left-pad";
              version = "1.1.0";
              src = fetchurl { url = "https://registry.npmjs.org/left-pad/-/left-pad-${version}.tgz"; hash = "%s"; };
            }
            """.formatted(OLD));
        github.put("/api/v4/projects/acme%2Flab/releases/permalink/latest", "{\"tag_name\": \"v1.3\"}");
        github.put("/pypi/requests/json", "{\"info\": {\"version\": \"2.32.3\"}}");
        github.put("/left-pad/latest", "{\"version\": \"1.3.0\"}");

        assertThat(run(
                        "--host",
                        "gitlab.com=" + host,
                        "--host",
                        "pypi.org=" + host,
                        "--host",
                        "registry.npmjs.org=" + host,
                        dir.toString()))
                .as(err::toString)
                .isZero();

        assertThat(Files.readString(gitlab)).contains("version = \"1.3\";");
        assertThat(Files.readString(pypi)).contains("version = \"2.32.3\";").contains(SRI);
        assertThat(Files.readString(npm)).contains("version = \"1.3.0\";");
        assertThat(prefetched().lines())
                .containsExactlyInAnyOrder(
                        "https://gitlab.com/api/v4/projects/acme%2Flab/repository/archive.tar.gz?sha=v1.3",
                        "https://files.pythonhosted.org/packages/source/r/requests/requests-2.32.3.tar.gz",
                        "https://registry.npmjs.org/left-pad/-/left-pad-1.3.0.tgz");
    }

    @Test
    void findsTheNewestFileOfADirectoryListing() throws IOException {
        String host = "http://localhost:" + server.getAddress().getPort();
        Path hello = file("hello.nix", """
            { stdenv, fetchurl }:
            stdenv.mkDerivation rec {
              pname = "hello";
              version = "2.10";
              src = fetchurl { url = "%s/gnu/hello/hello-${version}.tar.gz"; hash = "%s"; };
            }
            """.formatted(host, OLD));
        Path glib = file("glib.nix", """
            { stdenv, lib, fetchurl }:
            stdenv.mkDerivation rec {
              pname = "glib";
              version = "2.80.1";
              src = fetchurl {
                url = "%s/sources/glib/${lib.versions.majorMinor version}/glib-${version}.tar.xz";
                hash = "%s";
              };
            }
            """.formatted(host, OLD));
        github.put("/gnu/hello/", """
            <a href="hello-2.10.tar.gz">hello-2.10.tar.gz</a>
            <a href="hello-2.12.1.tar.gz">hello-2.12.1.tar.gz</a> <a href="hello-2.12.1.tar.gz.sig">sig</a>
            <a href="hello-2.13-rc1.tar.gz">rc</a> <a href="../">up</a>
            """);
        github.put(
                "/sources/glib/",
                "<a href=\"2.80/\">2.80/</a> <a href=\"2.82/\">2.82/</a> <a href=\"2.83/\">2.83/</a>");
        github.put("/sources/glib/2.83/", "<a href=\"glib-2.83.0.tar.xz.sha256sum\">sum</a>");
        github.put("/sources/glib/2.82/", "<a href=\"glib-2.82.0.tar.xz\">0</a> <a href=\"glib-2.82.2.tar.xz\">2</a>");

        assertThat(run(dir.toString())).as(err::toString).isZero();

        assertThat(Files.readString(hello)).contains("version = \"2.12.1\";");
        assertThat(Files.readString(glib)).contains("version = \"2.82.2\";");
        assertThat(prefetched()).contains(host + "/sources/glib/2.82/glib-2.82.2.tar.xz\n");
    }

    @Test
    void buildsThePackageBeforeKeepingTheUpdate() throws IOException {
        Path p = file("pkgs/hello/default.nix", pkg("hello", "1.0"));
        release("hello", "v1.1");

        assertThat(run("--build", "--test", dir.toString())).as(err::toString).isZero();
        assertThat(Files.readString(p)).contains("1.1");
        assertThat(built()).contains("{ })\n").contains("{ }).tests or { }\n");

        file("pkgs/hello/default.nix", pkg("hello", "1.0"));
        nixReports("error: builder failed", true);
        assertThat(run("--build", dir.toString())).isEqualTo(1);
        assertThat(Files.readString(p)).isEqualTo(pkg("hello", "1.0"));
        assertThat(err.toString()).contains("error: cannot build the package: error: builder failed");
    }

    @Test
    void runsTheUpdateScriptOfAPackage() throws IOException {
        Path p = file(
                "pkgs/hello/default.nix",
                pkg("hello", "1.0").replace("  src =", "  passthru.updateScript = ./update.sh;\n  src ="));
        Path script = file("pkgs/hello/update.sh", """
            #!/bin/sh
            sed -i "s/1.0/1.5/" pkgs/hello/default.nix
            echo "$UPDATE_NIX_ATTR_PATH $UPDATE_NIX_OLD_VERSION" > script.log
            """);
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        file("pkgs/top-level/all-packages.nix", "{ }");
        Path instantiate = dir.resolve("bin/nix-instantiate");
        Files.writeString(instantiate, "#!/bin/sh\necho '[\"" + script + "\"]'\n");
        Files.setPosixFilePermissions(instantiate, PosixFilePermissions.fromString("rwx------"));
        release("hello", "v9.9");

        assertThat(run(dir.toString())).as(err::toString).isZero();

        assertThat(Files.readString(p)).contains("version = \"1.5\";");
        assertThat(Files.readString(dir.resolve("script.log"))).isEqualTo("hello 1.0\n");
        assertThat(err.toString()).contains("hello  1.0  -> 1.5 (update script)  ");

        file(
                "pkgs/hello/default.nix",
                pkg("hello", "1.0").replace("  src =", "  passthru.updateScript = ./update.sh;\n  src ="));
        assertThat(run("--no-update-script", dir.toString())).isZero();
        assertThat(Files.readString(p)).contains("version = \"9.9\";");
    }

    @Test
    void takesTheCargoLockOfTheNewSource() throws IOException {
        Path p = file(
                "pkgs/rs/package.nix",
                pkg("rs", "1.0").replace("  src =", "  cargoLock.lockFile = ./Cargo.lock;\n  src ="));
        Path lock = file("pkgs/rs/Cargo.lock", "old\n");
        Files.createDirectories(Path.of(prefetcher + ".src"));
        Files.writeString(Path.of(prefetcher + ".src/Cargo.lock"), "new\n");
        release("rs", "v1.1");

        assertThat(run(dir.toString())).as(err::toString).isZero();

        assertThat(Files.readString(p)).contains("1.1");
        assertThat(Files.readString(lock)).isEqualTo("new\n");
    }

    @Test
    void cancellingKillsWhatTheUpdateRuns() throws Exception {
        Path p = file("hello.nix", pkg("hello", "1.0"));
        release("hello", "v1.1");
        Path pid = dir.resolve("pid");
        Files.writeString(prefetcher, "#!/bin/sh\necho $$ > " + pid + "\nexec sleep 60\n");
        var updater = new org.affogato.update.Updater(new org.affogato.update.Updater.Options(
                "http://localhost:" + server.getAddress().getPort(),
                null,
                prefetcher.toString(),
                nix.toString(),
                nix.toString(),
                null));
        var failed = new java.util.concurrent.CompletableFuture<String>();
        Runnable cancel = updater.update(
                p.toString(),
                Files.readString(p),
                0,
                (step, done) -> {},
                u -> failed.complete("done"),
                failed::complete);
        long deadline = System.currentTimeMillis() + 30_000;
        while (!Files.exists(pid) || Files.readString(pid).isBlank()) {
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
            Thread.onSpinWait();
        }
        ProcessHandle sleeping =
                ProcessHandle.of(Long.parseLong(Files.readString(pid).strip())).orElseThrow();

        cancel.run();

        assertThat(failed.get(30, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("cancelled");
        assertThat(sleeping.onExit()
                        .get(30, java.util.concurrent.TimeUnit.SECONDS)
                        .isAlive())
                .isFalse();
    }
}

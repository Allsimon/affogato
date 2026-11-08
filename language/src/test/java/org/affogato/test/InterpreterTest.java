package org.affogato.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.affogato.EvalException;
import org.affogato.Interpreter;
import org.affogato.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InterpreterTest {
    private static String eval(Interpreter.Output output, String expr, long timeout) throws Exception {
        var options = new Interpreter.Options("", "x86_64-linux", "/nix/store", output, "", List.of());
        return Interpreter.evaluate(options, null, expr, timeout);
    }

    @Test
    void rendersEachOutput() throws Exception {
        assertThat(eval(Interpreter.Output.EVAL, "{ a = 1 + 1; }", 0)).isEqualTo("{ a = <CODE>; }");
        assertThat(eval(Interpreter.Output.JSON, "{ a = [ 1 \"b\" ]; }", 0)).isEqualTo("{\"a\":[1,\"b\"]}");
        assertThat(eval(Interpreter.Output.RAW, "\"a\\nb\"", 0)).isEqualTo("a\nb");
    }

    @Test
    void reReadsAnImportedFileEditedBetweenEvaluations(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.nix");
        String expr = "import " + file;
        Files.writeString(file, "1");
        assertThat(eval(Interpreter.Output.EVAL, expr, 0)).isEqualTo("1");
        assertThat(eval(Interpreter.Output.EVAL, expr, 0)).isEqualTo("1");
        Files.writeString(file, "2");
        assertThat(eval(Interpreter.Output.EVAL, expr, 0)).isEqualTo("2");
    }

    @Test
    void keepsASessionsValuesUntilReset(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.nix");
        Files.writeString(file, "{ }: { a = { c = 1; b = throw \"x\"; }; f = { x ? 1 }: { y = x; }; }");
        try (Session session = new Session("", "", "", "import " + file, dir.toString())) {
            assertThat(session.names(List.of()).get()).containsExactly("a", "f");
            assertThat(session.names(List.of("a")).get()).containsExactly("b", "c");
            assertThat(session.names(List.of("f")).get()).containsExactly("y");
            assertThat(session.names(List.of("a", "b")).get()).isEmpty();
            assertThat(session.names(List.of("z")).get()).isEmpty();
            Files.writeString(file, "{ g = 1; }");
            assertThat(session.names(List.of()).get()).containsExactly("a", "f");
            session.reset();
            assertThat(session.names(List.of()).get()).containsExactly("g");
            session.configure("{ h = 1; }", dir.toString());
            assertThat(session.names(List.of()).get()).containsExactly("h");
        }
    }

    @Test
    void describesTheAttributesOfASession(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.nix");
        Files.writeString(file, """
            {
              hello = {
                type = "derivation";
                pname = "hello";
                version = "2.12";
                meta = { description = "Greets"; longDescription = "  More.\\n"; homepage = [ "https://h" ]; position = "/p/hello.nix:3"; };
              };
              /**
                Adds one.

                # Example
                  inc 1
              */
              inc = x: x + 1;
              s = "str";
              bad = throw "renamed";
            }
            """);
        try (Session session = new Session("", "", "", "import " + file, dir.toString())) {
            assertThat(session.describe(List.of("hello")).get())
                    .isEqualTo(new Session.Description(
                            "derivation",
                            null,
                            "hello",
                            "2.12",
                            "Greets\n\nMore.",
                            "https://h",
                            null,
                            null,
                            null,
                            "/p/hello.nix",
                            3,
                            1));
            assertThat(session.describe(List.of("inc")).get())
                    .isEqualTo(new Session.Description(
                            "lambda",
                            null,
                            null,
                            null,
                            null,
                            null,
                            "Adds one.\n\n# Example\n  inc 1",
                            null,
                            null,
                            file.toString(),
                            14,
                            9));
            assertThat(session.describe(List.of("s")).get().value()).isEqualTo("\"str\"");
            assertThat(session.describe(List.of("bad")).get())
                    .extracting(Session.Description::type, Session.Description::line)
                    .containsExactly("error", 16);
            assertThat(session.describe(List.of("bad")).get().value()).contains("renamed");
            assertThat(session.describe(List.of("none")).get()).isEqualTo(Session.Description.NONE);
        }
    }

    @Test
    void describesTheOptionsOfAModuleSystem(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("options.nix");
        Files.writeString(file, """
            let
              option = type: attrs: { _type = "option"; inherit type; } // attrs;
            in
            {
              services.web = {
                enable = option { name = "bool"; description = "boolean"; } {
                  description = "Whether to serve.";
                  default = false;
                  declarationPositions = [ { file = "/m.nix"; line = 3; column = 5; } ];
                };
                hosts = option {
                  name = "attrsOf";
                  description = "attribute set of submodule";
                  getSubOptions = prefix: {
                    _module = { };
                    root = option { name = "str"; description = "string"; } {
                      description = "Root.";
                      defaultText = { _type = "literalExpression"; text = "\\"/var\\""; };
                    };
                  };
                } { description = "Hosts."; };
              };
            }
            """);
        try (Session session = new Session("", "", "", "import " + file, dir.toString(), true)) {
            assertThat(session.names(List.of()).get()).containsExactly("services");
            assertThat(session.names(List.of("services", "web")).get()).containsExactly("enable", "hosts");
            assertThat(session.names(List.of("services", "web", "hosts")).get()).isEmpty();
            assertThat(session.names(List.of("services", "web", "hosts", "a.org"))
                            .get())
                    .containsExactly("root");
            assertThat(session.describe(List.of("services", "web", "enable")).get())
                    .isEqualTo(new Session.Description(
                            "option",
                            "boolean",
                            null,
                            null,
                            "Whether to serve.",
                            null,
                            null,
                            "false",
                            null,
                            "/m.nix",
                            3,
                            5));
            assertThat(session.describe(List.of("services", "web", "hosts", "a.org", "root"))
                            .get())
                    .extracting(Session.Description::value, Session.Description::defaultValue)
                    .containsExactly("string", "\"/var\"");
            assertThat(session.describe(List.of("services", "web")).get()).isEqualTo(Session.Description.NONE);
        }
    }

    @Test
    void evaluatesALocalFlakeAndItsPathInputs(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("sub"));
        Files.writeString(
                dir.resolve("flake.nix"),
                "{ inputs.sub.url = \"path:./sub\"; outputs = { self, sub }: { x = sub.y + 1; me = self.outPath; }; }");
        Files.writeString(dir.resolve("sub/flake.nix"), "{ outputs = { self }: { y = 41; }; }");
        Files.writeString(dir.resolve("flake.lock"), """
            {"nodes": {"root": {"inputs": {"sub": "sub"}},
             "sub": {"locked": {"path": "./sub", "type": "path"}, "original": {"path": "./sub", "type": "path"},
                     "parent": []}},
             "root": "root", "version": 7}
            """);
        assertThat(eval(Interpreter.Output.EVAL, "(builtins.getFlake \"path:" + dir + "\").x", 0))
                .isEqualTo("42");
        assertThat(eval(Interpreter.Output.EVAL, "(builtins.getFlake \"git+file://" + dir + "\").me", 0))
                .isEqualTo("\"" + dir + "\"");
        assertThat(eval(Interpreter.Output.EVAL, "(builtins.getFlake \"" + dir + "?dir=sub\").y", 0))
                .isEqualTo("41");
        assertThatThrownBy(() -> eval(
                        Interpreter.Output.EVAL,
                        "builtins.fetchTree { type = \"github\"; owner = \"o\"; repo = \"r\";"
                                + " narHash = \"sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=\"; }",
                        0))
                .hasMessageContaining("input 'github:o/r' is not in the Nix store at /nix/store/");
        assertThatThrownBy(() -> eval(Interpreter.Output.EVAL, "builtins.getFlake \"github:o/r\"", 0))
                .hasMessageContaining("only for local flakes");
    }

    @Test
    void sharesOneContextBetweenTheRootsOfASession(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("f.nix");
        Files.writeString(file, "{ n = { one = 1; }; }");
        try (Session session = new Session("", "", "")) {
            session.configure(
                    Map.of(
                            "a", new Session.Root("(import " + file + ").n", false),
                            "b", new Session.Root("(import " + file + ").n", false)),
                    dir.toString());
            assertThat(session.names("a", List.of()).get()).containsExactly("one");
            Files.writeString(file, "{ n = { two = 2; }; }");
            assertThat(session.names("b", List.of()).get()).containsExactly("one");
            session.reset();
            assertThat(session.names("b", List.of()).get()).containsExactly("two");
            assertThat(session.names("c", List.of()).get()).isEmpty();
        }
    }

    @Test
    void looksUpTheArgumentsTheModulesOfAnOptionSetReceive(@TempDir Path dir) throws Exception {
        String options = "{ _module = {"
                + " args = { _type = \"option\"; value = { a = 1; pkgs = { }; }; };"
                + " specialArgs = { _type = \"option\"; value = { a = 2; inputs.x.y = 3; }; }; }; }";
        try (Session session = new Session("", "", "")) {
            session.configure(
                    Map.of("o", new Session.Root(options, true), "p", new Session.Root("{ }", false)), dir.toString());
            assertThat(session.names(Session.arguments("o"), List.of()).get()).containsExactly("a", "inputs", "pkgs");
            assertThat(session.names(Session.arguments("o"), List.of("inputs", "x"))
                            .get())
                    .containsExactly("y");
            assertThat(session.describe(Session.arguments("o"), List.of("a"))
                            .get()
                            .value())
                    .isEqualTo("2");
            assertThat(session.names(Session.arguments("p"), List.of()).get()).isEmpty();
        }
    }

    @Test
    void printsTheEntryTypesOfReadDir(@TempDir Path dir) throws Exception {
        Files.createDirectory(dir.resolve("d"));
        Files.writeString(dir.resolve("f"), "");
        assertThat(eval(Interpreter.Output.EVAL, "builtins.readDir " + dir, 0))
                .isEqualTo("{ d = \"directory\"; f = \"regular\"; }");
    }

    @Test
    void listToAttrsKeepsTheFirstOfEqualNames() throws Exception {
        assertThat(eval(
                        Interpreter.Output.JSON,
                        "builtins.listToAttrs [ { name = \"b\"; value = 1; } { name = \"a\"; value = 2; }"
                                + " { name = \"b\"; value = 3; } ]",
                        0))
                .isEqualTo("{\"a\":2,\"b\":1}");
    }

    @Test
    void reReadsADirectoryChangedBetweenEvaluations(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a"), "");
        Files.setLastModifiedTime(dir, java.nio.file.attribute.FileTime.fromMillis(0));
        String expr = "builtins.attrNames (builtins.readDir " + dir + ")";
        assertThat(eval(Interpreter.Output.JSON, expr, 0)).isEqualTo("[\"a\"]");
        assertThat(eval(Interpreter.Output.JSON, expr, 0)).isEqualTo("[\"a\"]");
        Files.writeString(dir.resolve("b"), "");
        assertThat(eval(Interpreter.Output.JSON, expr, 0)).isEqualTo("[\"a\",\"b\"]");
    }

    @Test
    void sharesTranslatedFilesAcrossContextsWithTheirOwnGlobals(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.nix");
        Files.writeString(file, "[ builtins.storeDir __storeDir (builtins.isFunction derivation) ]");
        for (String store : List.of("/a", "/b", "/a")) {
            var options = new Interpreter.Options("", "x86_64-linux", store, Interpreter.Output.JSON, "", List.of());
            assertThat(Interpreter.evaluate(options, null, "import " + file, 0))
                    .isEqualTo("[\"" + store + "\",\"" + store + "\",true]");
        }
    }

    @Test
    void reHashesAFileChangedBetweenEvaluations(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a");
        Files.writeString(file, "1");
        Thread.sleep(1100);
        String expr = "\"${" + file + "}\"";
        String first = eval(Interpreter.Output.RAW, expr, 0);
        assertThat(eval(Interpreter.Output.RAW, expr, 0)).isEqualTo(first);
        Files.writeString(file, "2");
        assertThat(eval(Interpreter.Output.RAW, expr, 0)).isNotEqualTo(first).endsWith("-a");
    }

    @Test
    void intersectAttrsTakesTheLastOfAUnion() throws Exception {
        String y = "builtins.listToAttrs (map (n: { name = n; value = 1; })"
                + " [ \"a\" \"b\" \"c\" \"d\" \"e\" \"f\" \"g\" \"h\" \"i\" \"j\" ]) // { b = 2; k = 3; }";
        assertThat(eval(
                        Interpreter.Output.JSON,
                        "builtins.intersectAttrs { b = 0; c = 0; k = 0; z = 0; } (" + y + ")",
                        0))
                .isEqualTo("{\"b\":2,\"c\":1,\"k\":3}");
    }

    @Test
    void reReadsASettledImportedFileEditedBetweenEvaluations(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("a.nix");
        String expr = "import " + file;
        Files.writeString(file, "1");
        Thread.sleep(1100);
        assertThat(eval(Interpreter.Output.EVAL, expr, 0)).isEqualTo("1");
        assertThat(eval(Interpreter.Output.EVAL, expr, 0)).isEqualTo("1");
        Files.writeString(file, "2");
        assertThat(eval(Interpreter.Output.EVAL, expr, 0)).isEqualTo("2");
    }

    @Test
    void derivationsOnEquivalentFixedOutputsShareOutPathsButNotDrvPaths() throws Exception {
        String expr =
                "let fod = b: derivation { name = \"f\"; system = \"x\"; builder = b; outputHashAlgo = \"sha256\";"
                        + " outputHash = \"0000000000000000000000000000000000000000000000000000000000000000\"; };"
                        + " d = f: derivation { name = \"d\"; system = \"x\"; builder = \"/b\"; src = f; };"
                        + " a = d (fod \"/a\"); b = d (fod \"/b\");"
                        + " in [ (a.drvPath == b.drvPath) (a.outPath == b.outPath) ]";
        assertThat(eval(Interpreter.Output.JSON, expr, 0)).isEqualTo("[false,true]");
        assertThat(eval(Interpreter.Output.JSON, expr, 0)).isEqualTo("[false,true]");
    }

    @Test
    void stopsAtTheTimeout() {
        long start = System.nanoTime();
        assertThatThrownBy(() -> eval(
                        Interpreter.Output.EVAL,
                        "let l = builtins.genList (x: x) 10000; in"
                                + " builtins.foldl' (a: i: a + builtins.foldl' (b: j: b + j) 0 l) 0 l",
                        100))
                .isInstanceOf(EvalException.class)
                .hasMessage("evaluation timed out after 100 ms");
        assertThat(System.nanoTime() - start).isLessThan(5_000_000_000L);
    }

    @Test
    void reportsTheLocationOfTheFailingExpression() {
        assertThatThrownBy(() -> eval(Interpreter.Output.EVAL, "1 + { a = 1; }.b", 0))
                .isInstanceOf(EvalException.class)
                .hasMessage("attribute 'b' missing")
                .extracting(e -> ((EvalException) e).location)
                .isEqualTo("\u00c2\u00abstring\u00c2\u00bb:1:5");
        assertThatThrownBy(() -> eval(Interpreter.Output.EVAL, "throw \"no\"", 0))
                .extracting(e -> ((EvalException) e).location)
                .isNull();
    }

    @Test
    void turnsAJavaStackOverflowIntoAnError() {
        assertThatThrownBy(() -> eval(
                        Interpreter.Output.STRICT,
                        "builtins.foldl' (acc: _: [ acc ]) [ ] (builtins.genList (x: x) 30000000)",
                        0))
                .isInstanceOf(EvalException.class)
                .hasMessage("stack overflow");
    }

    @Test
    void internalErrorsAreNotEvaluationErrors() {
        assertThatThrownBy(() -> eval(Interpreter.Output.EVAL, null, 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("internal error");
    }
}

package org.affogato.lsp.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.affogato.lsp.Json;
import org.affogato.lsp.Server;
import org.junit.jupiter.api.Test;

class ServerTest {
    private static final String URI = "file:///p/default.nix";
    private static final String DOCUMENT = "{\"textDocument\":{\"uri\":\"" + URI + "\"}}";

    private final Server server = new Server();
    private int id;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private static List<Object> names(Object symbols) {
        return list(symbols).stream().map(s -> map(s).get("name")).toList();
    }

    private static Map<String, Object> range(long startLine, long start, long endLine, long end) {
        return Map.of(
                "start", Map.of("line", startLine, "character", start),
                "end", Map.of("line", endLine, "character", end));
    }

    private List<Map<String, Object>> send(String method, String params) {
        return server.handle(Json.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":" + (++id) + ",\"method\":\"" + method + "\",\"params\":" + params + "}"));
    }

    private Object result(String method, String params) {
        return send(method, params).getFirst().get("result");
    }

    private List<Map<String, Object>> open(String text) {
        return server.handle(Json.parse("{\"jsonrpc\":\"2.0\",\"method\":\"textDocument/didOpen\",\"params\":"
                + "{\"textDocument\":{\"uri\":\"" + URI + "\",\"languageId\":\"nix\",\"version\":1,\"text\":"
                + Json.write(text) + "}}}"));
    }

    private static String at(int line, int character) {
        return "{\"textDocument\":{\"uri\":\"" + URI + "\"},\"position\":{\"line\":" + line + ",\"character\":"
                + character + "}}";
    }

    @Test
    void initializesWithItsCapabilities() {
        assertThat(map(map(result("initialize", "{\"capabilities\":{}}")).get("capabilities")))
                .containsKeys("definitionProvider", "renameProvider", "documentFormattingProvider");
    }

    @Test
    void publishesTheLintersDiagnostics() {
        Map<String, Object> published = open("let unused = 1; in 2\n").getFirst();
        assertThat(published).containsEntry("method", "textDocument/publishDiagnostics");
        List<Object> diagnostics = list(map(published.get("params")).get("diagnostics"));
        assertThat(diagnostics).hasSize(1);
        assertThat(map(diagnostics.getFirst()))
                .containsEntry("code", "unused-let-binding")
                .containsEntry("severity", 2L)
                .containsEntry("range", range(0, 4, 0, 10));
    }

    @Test
    void resolvesAVariableToItsBinding() {
        open("let\n  name = 1;\nin\nname + name\n");
        assertThat(map(result("textDocument/definition", at(3, 1))).get("range"))
                .isEqualTo(range(1, 2, 1, 6));
    }

    @Test
    void renamesADefinitionAndItsUses() {
        open("let a = 1; in { inherit a; b = a; }\n");
        Object result = result(
                "textDocument/rename",
                "{\"textDocument\":{\"uri\":\"" + URI + "\"},\"position\":{\"line\":0,\"character\":4},"
                        + "\"newName\":\"x\"}");
        assertThat(list(map(map(result).get("changes")).get(URI))).hasSize(3);
    }

    @Test
    void preparesToRenameALocalNameOnly() {
        open("let abc = 1; in abc + map\n");
        assertThat(result("textDocument/prepareRename", at(0, 17)))
                .isEqualTo(Map.of("range", range(0, 16, 0, 19), "placeholder", "abc"));
        assertThat(result("textDocument/prepareRename", at(0, 23))).isNull();
    }

    @Test
    void findsTheReferences() {
        open("x: [ x x (x: x) ]\n");
        Object references = result(
                "textDocument/references",
                "{\"textDocument\":{\"uri\":\"" + URI + "\"},\"position\":{\"line\":0,\"character\":0},"
                        + "\"context\":{\"includeDeclaration\":false}}");
        assertThat(list(references)).hasSize(2);
    }

    @Test
    void highlightsTheDefinitionAndItsUses() {
        open("let a = 1; in [ a (a: a) a ]\n");
        assertThat(list(result("textDocument/documentHighlight", at(0, 16))))
                .containsExactly(
                        Map.of("range", range(0, 4, 0, 5), "kind", 3L),
                        Map.of("range", range(0, 16, 0, 17), "kind", 2L),
                        Map.of("range", range(0, 25, 0, 26), "kind", 2L));
    }

    @Test
    void completesTheNamesInScope() {
        open("let foo = 1; bar = 2; in f\n");
        List<Object> labels = list(result("textDocument/completion", at(0, 26))).stream()
                .map(i -> map(i).get("label"))
                .toList();
        assertThat(labels).contains("foo", "bar");
    }

    @Test
    void completesTheGlobalsAndKeywords() {
        open("let map = 1; in f\n");
        List<Map<String, Object>> items = list(result("textDocument/completion", at(0, 17))).stream()
                .map(ServerTest::map)
                .toList();
        assertThat(items)
                .contains(
                        Map.of("label", "import", "kind", 3L),
                        Map.of("label", "null", "kind", 21L),
                        Map.of("label", "inherit", "kind", 14L))
                .filteredOn(i -> i.get("label").equals("map"))
                .containsExactly(Map.of("label", "map", "kind", 6L));
    }

    @Test
    void completesTheAttributesOfBuiltins() {
        open("let a = 1; in builtins.ma\n");
        assertThat(list(result("textDocument/completion", at(0, 25))))
                .contains(Map.of("label", "mapAttrs", "kind", 3L), Map.of("label", "true", "kind", 21L))
                .doesNotContain(Map.of("label", "a", "kind", 6L));
        open("x.builtins.\n");
        assertThat(list(result("textDocument/completion", at(0, 11))))
                .doesNotContain(Map.of("label", "mapAttrs", "kind", 3L));
        open("builtins: builtins.\n");
        assertThat(list(result("textDocument/completion", at(0, 19))))
                .contains(Map.of("label", "builtins", "kind", 6L))
                .doesNotContain(Map.of("label", "mapAttrs", "kind", 3L));
    }

    @Test
    void listsTheBindingsAsSymbols() {
        open("{\n  a = 1;\n  b.c = { d = 2; };\n}\n");
        List<Object> symbols = list(result("textDocument/documentSymbol", DOCUMENT));
        assertThat(names(symbols)).containsExactly("a", "b.c");
        assertThat(names(map(symbols.getLast()).get("children"))).containsExactly("d");
    }

    @Test
    void growsTheSelectionThroughTheTree() {
        open("{ a = [ b c ]; }");
        Object selection = list(result(
                        "textDocument/selectionRange",
                        "{\"textDocument\":{\"uri\":\"" + URI + "\"},\"positions\":[{\"line\":0,\"character\":8}]}"))
                .getFirst();
        List<Object> ranges = new ArrayList<>();
        for (; selection != null; selection = map(selection).get("parent"))
            ranges.add(map(selection).get("range"));
        assertThat(ranges)
                .containsExactly(range(0, 8, 0, 9), range(0, 6, 0, 13), range(0, 2, 0, 14), range(0, 0, 0, 16));
    }

    @Test
    void foldsWhatSpansLines() {
        open("{\n  a = [\n    1\n  ];\n}\n");
        assertThat(list(result("textDocument/foldingRange", DOCUMENT)))
                .containsExactlyInAnyOrder(
                        Map.of("startLine", 0L, "endLine", 4L), Map.of("startLine", 1L, "endLine", 3L));
    }

    @Test
    void formatsTheDocument() {
        open("{a=1;}");
        assertThat(map(list(result("textDocument/formatting", DOCUMENT)).getFirst())
                        .get("newText"))
                .isEqualTo("{ a = 1; }\n");
    }

    @Test
    void formatsWithTheSettingsAndTheTabSize() {
        server.handle(Json.parse("{\"jsonrpc\":\"2.0\",\"method\":\"workspace/didChangeConfiguration\","
                + "\"params\":{\"settings\":{\"affogato\":{\"width\":10}}}}"));
        open("{a=1;b=2;}");
        String params =
                "{\"textDocument\":{\"uri\":\"" + URI + "\"},\"options\":{\"tabSize\":4,\"insertSpaces\":true}}";
        assertThat(map(list(result("textDocument/formatting", params)).getFirst())
                        .get("newText"))
                .isEqualTo("{\n    a = 1;\n    b = 2;\n}\n");
    }

    @Test
    void republishesTheDiagnosticsOfTheConfiguredRules() {
        open("let unused = 1; in x: 2\n");
        List<Map<String, Object>> published =
                server.handle(Json.parse("{\"jsonrpc\":\"2.0\",\"method\":\"workspace/didChangeConfiguration\","
                        + "\"params\":{\"settings\":{\"affogato\":{\"rules\":[\"unused-lambda-arg\"]}}}}"));
        List<Object> diagnostics = list(map(published.getFirst().get("params")).get("diagnostics"));
        assertThat(diagnostics.stream().map(d -> map(d).get("code"))).containsExactly("unused-lambda-arg");
    }

    @Test
    void classifiesTheTokens() {
        open("let\n  a = ''\n    x\n  '';\nin a\n");
        List<Object> data =
                list(map(result("textDocument/semanticTokens/full", DOCUMENT)).get("data"));
        assertThat(data.size() % 5).isZero();
        assertThat(data.subList(0, 5)).containsExactly(0L, 0L, 3L, 0L, 0L);
        long lines = 0;
        for (int i = 0; i < data.size(); i += 5) lines += (Long) data.get(i);
        assertThat(lines).isEqualTo(4);
    }

    @Test
    void reportsTheLanguageOfEachString() {
        open("{ installPhase = ''make''; config = /* json */ \"{}\"; name = \"x\"; }\n");
        List<Object> injections = list(result("affogato/injections", DOCUMENT));
        assertThat(injections)
                .containsExactly(
                        Map.of("range", range(0, 19, 0, 23), "kind", "shell", "value", "shell"),
                        Map.of("range", range(0, 48, 0, 50), "kind", "named", "value", "json"));
    }

    @Test
    void offersTheFixAndTheSuppressionsOfAFinding() {
        open("[\n  (let unused = 1; in 2)\n]\n");
        List<Object> actions = list(result(
                "textDocument/codeAction",
                "{\"textDocument\":{\"uri\":\"" + URI + "\"},\"range\":" + Json.write(range(1, 7, 1, 7))
                        + ",\"context\":{\"diagnostics\":[]}}"));
        assertThat(actions.stream().map(a -> map(a).get("title")))
                .contains(
                        "Fix unused-let-binding",
                        "Suppress unused-let-binding on this line",
                        "Suppress unused-let-binding in this file");
        Object suppress = actions.stream()
                .filter(a -> map(a).get("title").equals("Suppress unused-let-binding on this line"))
                .findFirst()
                .orElseThrow();
        Object edit = list(map(map(map(suppress).get("edit")).get("changes")).get(URI))
                .getFirst();
        assertThat(map(edit))
                .containsEntry("range", range(1, 0, 1, 0))
                .containsEntry("newText", "  # affogato: ignore unused-let-binding\n");
    }

    @Test
    void fixesAllWhenAskedForTheSourceAction() {
        open("let unused = 1; in 2\n");
        List<Object> actions = list(result(
                "textDocument/codeAction",
                "{\"textDocument\":{\"uri\":\"" + URI + "\"},\"range\":" + Json.write(range(0, 0, 0, 0))
                        + ",\"context\":{\"diagnostics\":[],\"only\":[\"source.fixAll\"]}}"));
        assertThat(actions).hasSize(1);
        assertThat(map(actions.getFirst())).containsEntry("kind", "source.fixAll");
        Object edit = list(map(map(map(actions.getFirst()).get("edit")).get("changes"))
                        .get(URI))
                .getFirst();
        assertThat(map(edit)).containsEntry("newText", "2\n");
    }

    @Test
    void offersNoActionAwayFromAFinding() {
        open("let unused = 1; in 2\n");
        assertThat(list(result(
                        "textDocument/codeAction",
                        "{\"textDocument\":{\"uri\":\"" + URI + "\"},\"range\":" + Json.write(range(0, 19, 0, 20))
                                + ",\"context\":{\"diagnostics\":[]}}")))
                .isEmpty();
    }

    @Test
    void answersAnUnknownRequestWithAnError() {
        assertThat(send("textDocument/hover", at(0, 0)).getFirst()).containsKey("error");
    }

    @Test
    void servesFramedMessagesUntilExit() throws Exception {
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        String exit = "{\"jsonrpc\":\"2.0\",\"method\":\"exit\"}";
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Server.serve(
                new ByteArrayInputStream(
                        (frame(initialize) + frame(exit) + frame(initialize)).getBytes(StandardCharsets.UTF_8)),
                out);

        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).startsWith("Content-Length: ").contains("\"capabilities\"");
        assertThat(output.split("Content-Length", -1)).hasSize(2);
    }

    @Test
    void answersInvalidJsonWithAParseErrorAndGoesOn() throws Exception {
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        Server.serve(
                new ByteArrayInputStream((frame("{\"id\":") + frame(initialize)).getBytes(StandardCharsets.UTF_8)),
                out);

        String output = out.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("\"id\":null", "-32700", "\"capabilities\"");
    }

    @Test
    void answersWhatIsNoRequestWithAnInvalidRequestError() {
        for (String message : List.of("[1]", "1", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":2}")) {
            Map<String, Object> reply = server.handle(Json.parse(message)).getFirst();
            assertThat(reply).containsEntry("id", null);
            assertThat(map(reply.get("error"))).containsEntry("code", -32600L);
        }
    }

    private static String frame(String body) {
        return "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
    }
}

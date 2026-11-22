package org.affogato.lsp.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.affogato.lint.Edit;
import org.affogato.lsp.Json;
import org.affogato.lsp.Server;
import org.affogato.lsp.Updates;
import org.junit.jupiter.api.Test;

class UpdateCommandTest {
    private static final String URI = "file:///p/default.nix";
    private static final String PACKAGE = """
        { stdenv, fetchFromGitHub }:
        stdenv.mkDerivation rec {
          pname = "hello";
          version = "1.2";
          src = fetchFromGitHub { owner = "acme"; repo = "hello"; rev = "v${version}"; hash = ""; };
        }
        """;

    /** Keeps what it was asked, for the test to answer. */
    private static final class Fake implements Updates {
        String file;
        String text;
        int index = -1;
        String asked;
        Progress progress;
        Consumer<Update> done;
        Consumer<String> failed;
        boolean cancelled;
        final List<Integer> checked = new ArrayList<>();
        Consumer<String> newer;
        List<String> roots;
        Consumer<WorkspaceUpdate> updated;

        @Override
        public Runnable newer(String file, String text, int index, Consumer<String> done, Consumer<String> failed) {
            checked.add(index);
            newer = done;
            return () -> {};
        }

        @Override
        public Runnable updateWorkspace(
                List<String> roots, Progress progress, Consumer<WorkspaceUpdate> done, Consumer<String> failed) {
            this.roots = roots;
            this.progress = progress;
            updated = done;
            this.failed = failed;
            return () -> cancelled = true;
        }

        @Override
        public Runnable update(
                String file,
                String text,
                int index,
                Progress progress,
                Consumer<Update> done,
                Consumer<String> failed) {
            this.progress = progress;
            return ask("update", file, text, index, done, failed);
        }

        @Override
        public Runnable refresh(
                String file,
                String text,
                int index,
                Progress progress,
                Consumer<Update> done,
                Consumer<String> failed) {
            this.progress = progress;
            return ask("refresh", file, text, index, done, failed);
        }

        private Runnable ask(
                String asked, String file, String text, int index, Consumer<Update> done, Consumer<String> failed) {
            this.asked = asked;
            this.file = file;
            this.text = text;
            this.index = index;
            this.done = done;
            this.failed = failed;
            return () -> cancelled = true;
        }
    }

    private final Fake updates = new Fake();
    private final List<Map<String, Object>> sent = new ArrayList<>();
    private Server server = new Server(null, updates, sent::add);
    private int id;

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private List<Map<String, Object>> request(String method, String params) {
        return server.handle(Json.parse(
                "{\"jsonrpc\":\"2.0\",\"id\":" + (++id) + ",\"method\":\"" + method + "\",\"params\":" + params + "}"));
    }

    private void open(String text) {
        notify(
                "textDocument/didOpen",
                "{\"textDocument\":{\"uri\":\"" + URI + "\",\"text\":" + Json.write(text) + "}}");
    }

    private void notify(String method, String params) {
        server.handle(Json.parse("{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\",\"params\":" + params + "}"));
    }

    private List<Object> actions(int line, int character) {
        return actions(line, character, "{\"diagnostics\":[]}");
    }

    private List<Object> actions(int line, int character, String context) {
        String position = "{\"line\":" + line + ",\"character\":" + character + "}";
        return list(request(
                        "textDocument/codeAction",
                        "{\"textDocument\":{\"uri\":\"" + URI + "\"},\"range\":{\"start\":" + position + ",\"end\":"
                                + position + "},\"context\":" + context + "}")
                .getFirst()
                .get("result"));
    }

    private Map<String, Object> updateAction(int line, int character) {
        return actions(line, character).stream()
                .map(UpdateCommandTest::map)
                .filter(a -> a.get("title").toString().startsWith("Update"))
                .findFirst()
                .orElse(null);
    }

    private Object execute() {
        return execute(Server.UPDATE);
    }

    private Object execute(String command) {
        return request(
                        "workspace/executeCommand",
                        "{\"command\":\"" + command + "\",\"arguments\":[\"" + URI + "\",0]}")
                .getFirst()
                .get("result");
    }

    private List<Object> messages() {
        return sent.stream()
                .filter(m -> "window/showMessage".equals(m.get("method")))
                .map(m -> map(m.get("params")).get("message"))
                .toList();
    }

    @Test
    void declaresTheCommand() {
        Map<String, Object> capabilities = map(
                map(request("initialize", "{\"capabilities\":{}}").getFirst().get("result"))
                        .get("capabilities"));
        assertThat(capabilities)
                .containsEntry(
                        "executeCommandProvider",
                        Map.of("commands", List.of(Server.UPDATE, Server.REFRESH, Server.UPDATE_WORKSPACE)));

        server = new Server(null, sent::add);
        assertThat(map(map(request("initialize", "{\"capabilities\":{}}")
                                .getFirst()
                                .get("result"))
                        .get("capabilities")))
                .doesNotContainKey("executeCommandProvider");
    }

    @Test
    void offersToUpdateThePackageAroundTheCursor() {
        open(PACKAGE);

        assertThat(updateAction(3, 14))
                .containsEntry("title", "Update hello")
                .containsEntry("kind", "refactor")
                .containsEntry(
                        "command",
                        Map.of("title", "Update hello", "command", Server.UPDATE, "arguments", List.of(URI, 0L)))
                .doesNotContainKey("disabled");
        assertThat(updateAction(0, 0)).isNull();

        server = new Server(null, sent::add);
        open(PACKAGE);
        assertThat(updateAction(3, 14)).isNull();
    }

    @Test
    void saysWhyAPackageCannotBeUpdated() {
        open(PACKAGE.replace("rev = \"v${version}\"", "rev = \"0123456789abcdef0123456789abcdef01234567\""));

        assertThat(updateAction(3, 14)).containsEntry("disabled", Map.of("reason", "pinned to a commit"));
    }

    @Test
    void offersTheUpdateAmongTheKindsTheClientAsksFor() {
        open(PACKAGE);
        String all = "{\"diagnostics\":[],\"only\":[\"quickfix\",\"refactor\",\"refactor.rewrite\",\"source.fixAll\"]}";

        assertThat(actions(3, 14, all))
                .map(a -> map(a).get("title"))
                .contains("Update hello", "Refresh the hashes of hello");
        assertThat(actions(3, 14, "{\"diagnostics\":[],\"only\":[\"quickfix\"]}"))
                .map(a -> map(a).get("title"))
                .doesNotContain("Update hello", "Refresh the hashes of hello");
    }

    @Test
    void appliesTheEditsOnceTheUpdateIsDone() {
        open(PACKAGE);

        assertThat(execute()).isNull();
        assertThat(updates.file).isEqualTo("/p/default.nix");
        assertThat(updates.text).isEqualTo(PACKAGE);
        assertThat(updates.index).isZero();
        assertThat(sent).isEmpty();

        int start = PACKAGE.indexOf("\"1.2\"");
        updates.done.accept(
                new Updates.Update("1.3", List.of(new Edit(start, start + 5, "\"1.3\"")), List.of("cargoHash")));

        Map<String, Object> apply = sent.getFirst();
        assertThat(apply).containsEntry("method", "workspace/applyEdit").containsKey("id");
        assertThat(map(apply.get("params")))
                .containsEntry("label", "Update hello")
                .containsEntry(
                        "edit",
                        Map.of(
                                "changes",
                                Map.of(
                                        URI,
                                        List.of(Map.of(
                                                "range",
                                                Map.of(
                                                        "start", Map.of("line", 3L, "character", 12L),
                                                        "end", Map.of("line", 3L, "character", 17L)),
                                                "newText",
                                                "\"1.3\"")))));
        assertThat(messages()).containsExactly("hello: 1.2 -> 1.3 (cargoHash not updated)");
        assertThat(server.handle(Json.parse(
                        "{\"jsonrpc\":\"2.0\",\"id\":\"" + apply.get("id") + "\",\"result\":" + "{\"applied\":true}}")))
                .isEmpty();
    }

    @Test
    void reportsAnUpToDatePackageAndAFailure() {
        open(PACKAGE);

        execute();
        updates.done.accept(null);
        execute();
        updates.failed.accept("GitHub's rate limit is reached");

        assertThat(messages())
                .containsExactly("hello 1.2 is up to date", "Updating hello failed: GitHub's rate limit is reached");
        assertThat(sent).noneMatch(m -> "workspace/applyEdit".equals(m.get("method")));
    }

    @Test
    void leavesADocumentThatChangedMeanwhile() {
        open(PACKAGE);
        execute();
        notify(
                "textDocument/didChange",
                "{\"textDocument\":{\"uri\":\"" + URI + "\"},\"contentChanges\":[{\"text\":"
                        + Json.write(PACKAGE + "\n") + "}]}");

        updates.done.accept(new Updates.Update("1.3", List.of(new Edit(0, 0, "")), List.of()));

        assertThat(sent).noneMatch(m -> "workspace/applyEdit".equals(m.get("method")));
        assertThat(messages()).containsExactly("Not changing hello: the document changed meanwhile");
    }

    @Test
    void showsTheUpdateAsWorkDoneProgress() {
        request("initialize", "{\"capabilities\":{\"window\":{\"workDoneProgress\":true}}}");
        open(PACKAGE);
        sent.clear();

        execute();

        assertThat(sent)
                .extracting(m -> m.get("method"))
                .containsExactly("window/workDoneProgress/create", "$/progress");
        assertThat(map(map(sent.get(1).get("params")).get("value")))
                .isEqualTo(Map.of("kind", "begin", "title", "Updating hello", "percentage", 0L, "cancellable", true));
        updates.progress.report("Building cargoDeps for cargoHash", 0.75);
        assertThat(map(map(sent.get(2).get("params")).get("value")))
                .isEqualTo(Map.of("kind", "report", "message", "Building cargoDeps for cargoHash", "percentage", 75L));
        updates.done.accept(null);
        assertThat(map(map(sent.get(3).get("params")).get("value"))).containsEntry("kind", "end");
    }

    @Test
    void reportsNoStepsToAClientWithoutProgress() {
        open(PACKAGE);
        execute(Server.REFRESH);

        updates.progress.report("Fetching acme/hello at v1.2", 0);

        assertThat(sent).isEmpty();
    }

    private Map<String, Object> refreshAction(int line, int character) {
        return actions(line, character).stream()
                .map(UpdateCommandTest::map)
                .filter(a -> a.get("title").toString().startsWith("Refresh"))
                .findFirst()
                .orElse(null);
    }

    @Test
    void offersToRefreshTheHashesEvenOfAPackagePinnedToACommit() {
        open(PACKAGE);
        assertThat(refreshAction(3, 14))
                .containsEntry("title", "Refresh the hashes of hello")
                .containsEntry(
                        "command",
                        Map.of(
                                "title",
                                "Refresh the hashes of hello",
                                "command",
                                Server.REFRESH,
                                "arguments",
                                List.of(URI, 0L)))
                .doesNotContainKey("disabled");

        open(PACKAGE.replace("rev = \"v${version}\"", "rev = \"0123456789abcdef0123456789abcdef01234567\""));
        assertThat(refreshAction(3, 14)).doesNotContainKey("disabled");

        open(PACKAGE.replace("owner = \"acme\"", "owner = x"));
        assertThat(refreshAction(3, 14)).isNull();
    }

    @Test
    void refreshesTheHashes() {
        open(PACKAGE);

        execute(Server.REFRESH);
        assertThat(updates.asked).isEqualTo("refresh");
        int hash = PACKAGE.indexOf("hash = \"\"") + 7;
        updates.done.accept(new Updates.Update("1.2", List.of(new Edit(hash, hash + 2, "\"sha256-x\"")), List.of()));

        assertThat(map(sent.getFirst().get("params"))).containsEntry("label", "Refresh the hashes of hello");
        assertThat(messages()).containsExactly("Refreshed the hashes of hello");
    }

    @Test
    void saysWhenTheHashesAreRight() {
        open(PACKAGE);

        execute(Server.REFRESH);
        updates.done.accept(new Updates.Update("1.2", List.of(), List.of()));
        execute(Server.REFRESH);
        updates.failed.accept("cargoHash: cannot build: error: builder failed");

        assertThat(sent).noneMatch(m -> "workspace/applyEdit".equals(m.get("method")));
        assertThat(messages())
                .containsExactly(
                        "The hashes of hello are up to date",
                        "Refreshing the hashes of hello failed: cargoHash: cannot build: error: builder failed");
    }

    private List<Object> diagnostics() {
        return sent.stream()
                .filter(m -> "textDocument/publishDiagnostics".equals(m.get("method")))
                .map(m -> (Object) map(m.get("params")).get("diagnostics"))
                .reduce((a, b) -> b)
                .map(UpdateCommandTest::list)
                .orElse(List.of());
    }

    @Test
    void showsAnOutdatedPackageWithItsUpdateAsQuickFix() {
        open(PACKAGE);
        assertThat(updates.checked).containsExactly(0);

        updates.newer.accept("1.3");

        Map<String, Object> diagnostic = map(diagnostics().getLast());
        assertThat(diagnostic)
                .containsEntry("code", Server.OUTDATED)
                .containsEntry("severity", 3L)
                .containsEntry("message", "hello 1.3 is available")
                .containsEntry(
                        "range",
                        Map.of(
                                "start", Map.of("line", 3L, "character", 12L),
                                "end", Map.of("line", 3L, "character", 17L)));
        Map<String, Object> fix = map(actions(3, 14, "{\"diagnostics\":[" + Json.write(diagnostic) + "]}").stream()
                .filter(a -> "quickfix".equals(map(a).get("kind")))
                .findFirst()
                .orElseThrow());
        assertThat(fix)
                .containsEntry("title", "Update hello to 1.3")
                .containsEntry("isPreferred", true)
                .containsEntry("diagnostics", List.of(diagnostic));
    }

    @Test
    void checksNothingWhenTold() {
        request("initialize", "{\"capabilities\":{},\"initializationOptions\":{\"checkUpdates\":false}}");
        open(PACKAGE);

        assertThat(updates.checked).isEmpty();
    }

    @Test
    void cancelsTheUpdateWithItsProgress() {
        request("initialize", "{\"capabilities\":{\"window\":{\"workDoneProgress\":true}}}");
        open(PACKAGE);
        sent.clear();
        execute();
        Object token = map(sent.getFirst().get("params")).get("token");

        notify("window/workDoneProgress/cancel", "{\"token\":" + Json.write(token) + "}");

        assertThat(updates.cancelled).isTrue();
    }

    @Test
    void updatesTheWorkspace() {
        request("initialize", "{\"capabilities\":{},\"rootUri\":\"file:///p\"}");
        open(PACKAGE);
        String other = "{ x = 1; }\n";

        assertThat(request(
                                "workspace/executeCommand",
                                "{\"command\":\"" + Server.UPDATE_WORKSPACE + "\",\"arguments\":[]}")
                        .getFirst())
                .containsEntry("result", null);
        assertThat(updates.roots).containsExactly("/p");
        updates.updated.accept(new Updates.WorkspaceUpdate(
                Map.of(
                        "/p/default.nix", new Updates.Change(PACKAGE, PACKAGE.replace("1.2", "1.3")),
                        "/p/other.nix", new Updates.Change(other, "{ x = 2; }\n")),
                List.of("hello: 1.2 -> 1.3")));

        Map<String, Object> edit = map(map(sent.stream()
                        .filter(m -> "workspace/applyEdit".equals(m.get("method")))
                        .findFirst()
                        .orElseThrow()
                        .get("params"))
                .get("edit"));
        assertThat(map(edit.get("changes")))
                .containsEntry(
                        "file:///p/other.nix",
                        List.of(Map.of(
                                "range",
                                Map.of(
                                        "start", Map.of("line", 0L, "character", 0L),
                                        "end", Map.of("line", 1L, "character", 0L)),
                                "newText",
                                "{ x = 2; }\n")))
                .containsKey(URI);
        assertThat(messages()).containsExactly("hello: 1.2 -> 1.3");
    }
}

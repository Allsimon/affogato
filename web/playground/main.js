import "@codingame/monaco-vscode-theme-defaults-default-extension";
import { EditorApp } from "monaco-languageclient/editorApp";
import { LanguageClientWrapper, LcWorker } from "monaco-languageclient/lcwrapper";
import { MonacoVscodeApiWrapper } from "monaco-languageclient/vscodeApiWrapper";
import { configureDefaultWorkerFactory } from "monaco-languageclient/workerFactory";
import { examples } from "./examples.js";
// pkgs/by-name/kc/kcgi/package.nix of nixpkgs 718f670e845328e9c2cecdd832835712b2cbd065
import kcgi from "./kcgi.nix?raw";

const uri = "/workspace/default.nix";
const status = document.getElementById("status");
const rule = document.getElementById("rule");
const settings = () => ({ rules: rule.value ? [rule.value] : [] });

await new MonacoVscodeApiWrapper({
  $type: "extended",
  viewsConfig: { $type: "EditorService", htmlContainer: document.getElementById("editor") },
  userConfiguration: {
    json: JSON.stringify({
      "editor.semanticHighlighting.enabled": true,
      "editor.tabSize": 2,
      "editor.detectIndentation": false,
      "editor.wordBasedSuggestions": "off",
      "editor.lightbulb.enabled": "on",
      "editor.minimap.enabled": false,
      "editor.automaticLayout": true,
    }),
  },
  extensions: [{
    config: {
      name: "nix",
      publisher: "affogato",
      version: "1.0.0",
      engines: { vscode: "*" },
      contributes: {
        languages: [{ id: "nix", extensions: [".nix"], aliases: ["Nix"], configuration: "./language-configuration.json" }],
      },
    },
    filesOrContents: new Map([["/language-configuration.json", JSON.stringify({
      comments: { lineComment: "#", blockComment: ["/*", "*/"] },
      brackets: [["{", "}"], ["[", "]"], ["(", ")"]],
      autoClosingPairs: [["{", "}"], ["[", "]"], ["(", ")"], ['"', '"'], ["''", "''"]],
    })]]),
  }],
  advanced: { enforceSemanticHighlighting: true },
  monacoWorkerFactory: configureDefaultWorkerFactory,
}).start();

const client = new LanguageClientWrapper({
  languageId: "nix",
  connection: {
    options: { $family: "Worker", workerUrl: new URL("server.js", location.href), type: "classic", realization: () => new LcWorker() },
  },
  clientOptions: { documentSelector: ["nix"], initializationOptions: settings() },
});
await client.start();

const app = new EditorApp({ codeResources: { modified: { text: kcgi, uri } } });
await app.start(document.getElementById("editor"));
const editor = app.getEditor();
status.textContent = "";

const configure = () => client.getLanguageClient().sendNotification("workspace/didChangeConfiguration", {
  settings: { affogato: settings() },
});

for (const [button, action] of [["format", "editor.action.formatDocument"], ["fix", "editor.action.fixAll"]]) {
  document.getElementById(button).addEventListener("click", () => {
    editor.focus();
    editor.getAction(action).run();
  });
}

examples[""] = kcgi;
for (const id of Object.keys(examples).sort()) if (id) rule.add(new Option(id, id));
const pick = () => {
  editor.setValue(examples[rule.value]);
  editor.setScrollTop(0);
  configure();
};
rule.addEventListener("change", () => {
  pick();
  const url = new URL(location.href);
  if (rule.value) url.searchParams.set("rule", rule.value); else url.searchParams.delete("rule");
  history.replaceState(null, "", url);
});
const linked = new URLSearchParams(location.search).get("rule");
if (linked && Object.hasOwn(examples, linked)) {
  rule.value = linked;
  pick();
}

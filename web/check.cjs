// Runs the formatter and linter test cases, and checks the semantic tokens, through the language server of the JavaScript build: node check.cjs affogato.js <format tests> <lint cases>
"use strict";
const fs = require("fs");
const path = require("path");
const [script, formatTests, lintCases] = process.argv.slice(2);
const g = require(path.resolve(script));

const WIDTH = 80;
const sources = [];
const read = file => {
  const src = fs.readFileSync(file, "utf8");
  if (file.endsWith(".nix")) sources.push([file, src]);
  return src;
};
const list = dir => fs.readdirSync(dir).sort();
const skip = new Set(read(path.join(formatTests, "format-skip.txt")).split("\n")
  .map(l => l.replace(/#.*/, "").trim()).filter(l => l));
let passed = 0;
const failed = [];

function check(name, actual, expected) {
  if (skip.has(name)) return;
  if (actual === expected) passed++;
  else failed.push(name);
}

let id = 0;
const send = (method, params) => JSON.parse(g.handle(JSON.stringify({ jsonrpc: "2.0", id: ++id, method, params })));
const notify = (method, params) => JSON.parse(g.handle(JSON.stringify({ jsonrpc: "2.0", method, params })));
const URI = "file:///check.nix";
const document = { uri: URI };
const legend = send("initialize", {})[0].result.capabilities.semanticTokensProvider.legend.tokenTypes;

/** Opens src with the settings, and returns the diagnostics it publishes. */
function open(src, settings) {
  notify("workspace/didChangeConfiguration", { settings: { affogato: { width: WIDTH, strict: false, rules: [], ...settings } } });
  return notify("textDocument/didOpen", { textDocument: { uri: URI, languageId: "nix", version: 1, text: src } })[0].params.diagnostics;
}

/** The text after edits that each span the whole document, as the server's formatting and fix-all do. */
const edited = (src, edits) => edits.length ? edits[edits.length - 1].newText : src;

function format(src, indent, strict) {
  open(src, { strict });
  const result = send("textDocument/formatting", { textDocument: document, options: { tabSize: indent, insertSpaces: true } })[0].result;
  return result === null ? "error" : edited(src, result);
}

const echoed = out => out.replace(/\n+$/, "") + "\n";
const nixfmt = path.join(formatTests, "nixfmt");
for (const file of list(path.join(nixfmt, "correct"))) {
  const src = read(path.join(nixfmt, "correct", file));
  check("correct/" + file, echoed(format(src, 2, true)), src);
}
const indent4 = read(path.join(nixfmt, "correct-indent-4.nix"));
check("correct-indent-4.nix", echoed(format(indent4, 4, true)), indent4);
for (const dir of list(path.join(nixfmt, "diff"))) {
  const src = read(path.join(nixfmt, "diff", dir, "in.nix"));
  check("diff/" + dir, format(src, 2, false), read(path.join(nixfmt, "diff", dir, "out.nix")));
  check("diff/" + dir + " --strict", format(src, 2, true), read(path.join(nixfmt, "diff", dir, "out-pure.nix")));
}
for (const file of list(path.join(nixfmt, "invalid"))) {
  check("invalid/" + file, format(read(path.join(nixfmt, "invalid", file)), 2, false), "error");
}

const SEVERITIES = { 1: "error", 2: "warning", 4: "hint" };
const render = (name, d) =>
  `${name}:${d.range.start.line + 1}:${d.range.start.character + 1}: ${SEVERITIES[d.severity]}[${d.code}]: ${d.message}\n`;

// Whether a finding has a fix is not part of a diagnostic; fix-all below checks the fixes.
for (const file of list(lintCases).filter(f => f.endsWith(".nix") && !f.endsWith(".fixed.nix"))) {
  const base = file.slice(0, -".nix".length);
  const src = read(path.join(lintCases, file));
  const expected = read(path.join(lintCases, base + ".expected")).replaceAll(" [fix]\n", "\n");
  check(file, open(src, { rules: [base] }).map(d => render(file, d)).join(""), expected);
  const actions = send("textDocument/codeAction", {
    textDocument: document,
    range: { start: { line: 0, character: 0 }, end: { line: 0, character: 0 } },
    context: { diagnostics: [], only: ["source.fixAll"] },
  })[0].result;
  const fixed = actions.length ? edited(src, actions[0].edit.changes[URI]) : src;
  check(file + " fix", format(fixed, 2, false), format(read(path.join(lintCases, base + ".fixed.nix")), 2, false));
}

/** The semantic tokens of src as [type, text], or what makes them invalid. */
function tokens(src) {
  open(src, {});
  const data = send("textDocument/semanticTokens/full", { textDocument: document })[0].result.data;
  const lines = src.split("\n");
  const out = [];
  let line = 0, start = 0;
  for (let i = 0; i < data.length; i += 5) {
    const [dLine, dStart, length, type] = data.slice(i, i + 5);
    line += dLine;
    start = dLine ? dStart : start + dStart;
    const text = lines[line]?.slice(start, start + length);
    if (length <= 0 || text === undefined || text.length !== length || !legend[type]) return `bad token ${line}:${start}+${length}`;
    out.push([legend[type], text]);
  }
  return out;
}

for (const [file, src] of sources) {
  const result = tokens(src);
  check("highlight " + file, typeof result === "string" ? result : "ok", "ok");
}
const sample = [
  "# c",
  "{ x, y ? 2 }@args: rec {",
  '  a.b = "s${x + "t"}\\n"; /* d */',
  "  inherit (x) c;",
  "  p = ./a/${y}.nix;",
  "  i = \'\'",
  "    l \'\'${\'\'\'",
  "  \'\';",
  "  e = map true x.map;",
  "}",
].join("\n");
check("highlight sample", JSON.stringify(tokens(sample)), JSON.stringify([
  ["comment", "# c"], ["operator", "{"], ["parameter", "x"], ["operator", ","], ["parameter", "y"], ["operator", "?"],
  ["number", "2"], ["operator", "}@"], ["parameter", "args"], ["operator", ":"], ["keyword", "rec"], ["operator", "{"],
  ["property", "a"], ["operator", "."], ["property", "b"], ["operator", "="],
  ["string", '"s'], ["macro", "${"], ["operator", "+"], ["string", '"t"'], ["macro", "}"], ["regexp", "\\n"], ["string", '"'],
  ["operator", ";"], ["comment", "/* d */"],
  ["keyword", "inherit"], ["operator", "("], ["operator", ")"], ["property", "c"], ["operator", ";"],
  ["property", "p"], ["operator", "="], ["string", "./a/"], ["macro", "${"], ["macro", "}"], ["string", ".nix"], ["operator", ";"],
  ["property", "i"], ["operator", "="], ["string", "\'\'"], ["string", "    l "], ["regexp", "\'\'$"], ["string", "{"], ["regexp", "\'\'\'"],
  ["string", "  \'\'"], ["operator", ";"],
  ["property", "e"], ["operator", "="], ["function", "map"], ["enumMember", "true"], ["operator", "."], ["operator", ";"],
  ["operator", "}"],
]));

console.log(`${passed} passed, ${failed.length} failed`);
for (const name of failed) console.log("FAILED " + name);
process.exit(failed.length ? 1 : 0);

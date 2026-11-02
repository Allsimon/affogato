// Runs the formatter and linter test cases, and checks the highlighting, against the JavaScript build: node check.cjs graalnix.js <format tests> <lint cases>
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

function format(src, indent, strict) {
  const result = JSON.parse(g.format(src, WIDTH, indent, strict));
  return result.error ? "error: " + result.error.message : result.output;
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
  check("invalid/" + file, "error" in JSON.parse(g.format(read(path.join(nixfmt, "invalid", file)), WIDTH, 2, false)), true);
}

function render(src, name, d) {
  const before = src.slice(0, d.start);
  const line = before.split("\n").length;
  const column = d.start - before.lastIndexOf("\n");
  return `${name}:${line}:${column}: ${d.severity}[${d.rule}]: ${d.message}${d.fixable ? " [fix]" : ""}\n`;
}

for (const file of list(lintCases).filter(f => f.endsWith(".nix") && !f.endsWith(".fixed.nix"))) {
  const base = file.slice(0, -".nix".length);
  const src = read(path.join(lintCases, file));
  const result = JSON.parse(g.lint(src, base));
  check(file, result.diagnostics.map(d => render(src, file, d)).join(""), read(path.join(lintCases, base + ".expected")));
  const fixed = JSON.parse(g.fix(src, WIDTH, 2, false, base));
  check(file + " fix", fixed.output ?? fixed.error.message, format(read(path.join(lintCases, base + ".fixed.nix")), 2, false));
}

function spans(src) {
  const result = JSON.parse(g.highlight(src));
  if (result.error) return "error: " + result.error.message;
  let at = 0;
  for (const [start, end, kind] of result.tokens) {
    if (start < at || end <= start || end > src.length || !kind) return `bad span ${start}-${end} ${kind}`;
    at = end;
  }
  return result.tokens.map(([start, end, kind]) => [kind, src.slice(start, end)]);
}

for (const [file, src] of sources) {
  const result = spans(src);
  check("highlight " + file, typeof result === "string" ? result : "ok", "ok");
}
const sample = [
  "# c",
  "{ x, y ? 2 }@args: rec {",
  '  a.b = "s${x + "t"}\\n"; /* d */',
  "  inherit (x) c;",
  "  p = ./a/${y}.nix;",
  "  i = ''",
  "    l ''${'''",
  "  '';",
  "  e = map true x.map;",
  "}",
].join("\n");
check("highlight sample", JSON.stringify(spans(sample)), JSON.stringify([
  ["comment", "# c"], ["operator", "{"], ["param", "x"], ["operator", ","], ["param", "y"], ["operator", "?"],
  ["number", "2"], ["operator", "}@"], ["param", "args"], ["operator", ":"], ["keyword", "rec"], ["operator", "{"],
  ["attr", "a"], ["operator", "."], ["attr", "b"], ["operator", "="],
  ["string", '"s'], ["interp", "${"], ["operator", "+"], ["string", '"t"'], ["interp", "}"], ["escape", "\\n"], ["string", '"'],
  ["operator", ";"], ["comment", "/* d */"],
  ["keyword", "inherit"], ["operator", "("], ["operator", ")"], ["attr", "c"], ["operator", ";"],
  ["attr", "p"], ["operator", "="], ["path", "./a/"], ["interp", "${"], ["interp", "}"], ["path", ".nix"], ["operator", ";"],
  ["attr", "i"], ["operator", "="], ["string", "''\n    l "], ["escape", "''$"], ["string", "{"], ["escape", "'''"],
  ["string", "\n  ''"], ["operator", ";"],
  ["attr", "e"], ["operator", "="], ["builtin", "map"], ["constant", "true"], ["operator", "."], ["operator", ";"],
  ["operator", "}"],
]));

console.log(`${passed} passed, ${failed.length} failed`);
for (const name of failed) console.log("FAILED " + name);
process.exit(failed.length ? 1 : 0);

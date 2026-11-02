# affogato

A suite of tools for the Nix language, written in Java:

- a **formatter**, a port of [nixfmt](https://github.com/NixOS/nixfmt) that
  leaves all of nixpkgs unchanged;
- a **linter**, with rules drawn from [deadnix](https://github.com/astro/deadnix),
  [statix](https://github.com/oppiliappan/statix) and
  [nixd](https://github.com/nix-community/nixd), many with an automatic fix;
- a **syntax highlighter**, which works from the lexer alone and so copes with
  syntax errors;
- an **evaluator** on GraalVM, as a Truffle AST interpreter, which evaluates
  nixpkgs unmodified and computes store paths and `.drv` paths in memory,
  without writing anything to `/nix/store`;
- a **playground** that runs the formatter, linter and highlighter in the
  browser, compiled to JavaScript with [TeaVM](https://teavm.org/).

```sh
nix build .#affogato                  # format, check and fix, on OpenJDK 25
nix build .#affogato-native           # the same as a static native binary (musl), built with GraalVM
./result/bin/affogato format .        # format like nixfmt
./result/bin/affogato check .         # lint
./result/bin/affogato fix .           # apply the lint fixes, then format
```

The evaluator comes in its own package, which adds `eval` and `drv-path`:

```sh
nix build .#affogato-truffle          # on OpenJDK 25; also affogato-truffle-native
./result/bin/affogato eval --strict -E 'builtins.map (x: x * 2) [ 1 2 3 ]'
./result/bin/affogato drv-path -I nixpkgs=/path/to/nixpkgs -E 'import <nixpkgs> {}' -A hello
```

The documentation, published at <https://allsimon.github.io/affogato/> with
the [playground](https://allsimon.github.io/affogato/playground/), lives in
[`docs/`](docs/index.adoc): the command line, parity status, lint rules,
architecture and how to build.
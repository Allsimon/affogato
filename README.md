# affogato

A suite of tools for the Nix language, written in Java:

- a **formatter**, a port of [nixfmt](https://github.com/NixOS/nixfmt) whose
  output matches nixfmt's on every file of nixpkgs;
- a **linter**, with rules drawn from [deadnix](https://github.com/astro/deadnix),
  [statix](https://github.com/oppiliappan/statix) and
  [nixd](https://github.com/nix-community/nixd), many with an automatic fix;
- a **syntax highlighter**, which works from the lexer alone and so copes with
  syntax errors;
- an **evaluator**, a tree-walking interpreter, which evaluates nixpkgs
  unmodified and computes store paths and `.drv` paths in memory,
  without writing anything to `/nix/store`;
- a **language server**, built on all of the above: the linter's findings and
  fixes, formatting, rename and references across the workspace, and
  completion, hover and go to definition on builtins, nixpkgs, flake inputs and
  the options of NixOS, home-manager and devenv modules, evaluated live;
- a **playground**: a Monaco editor with the language server running in the
  browser, compiled to JavaScript with [TeaVM](https://teavm.org/).

```sh
nix build .#affogato                  # on OpenJDK 25
nix build .#affogato-native           # the same as a static native binary (musl), built with GraalVM
affogato format .                     # format like nixfmt
affogato check .                      # lint
affogato fix .                        # apply the lint fixes, then format
affogato update .                     # bump the packages of a tree to their latest version
affogato eval --strict -E 'builtins.map (x: x * 2) [ 1 2 3 ]'
affogato lsp                          # language server over stdio
nix develop .#zed -c zed-affogato .   # Zed (or .#vscodium -c codium-affogato) with the language server
affogato drv-path -I nixpkgs=/path/to/nixpkgs -E 'import <nixpkgs> {}' -A hello
```

The documentation, published at <https://allsimon.github.io/affogato/> with
the [playground](https://allsimon.github.io/affogato/playground/), lives in
[`docs/`](docs/index.adoc): the command line, the language server and how to
set up an editor, parity status, lint rules, architecture and how to build.
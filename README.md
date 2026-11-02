# graalnix

An implementation of the Nix language on GraalVM, as a Truffle AST interpreter.
It evaluates nixpkgs unmodified and computes store paths and `.drv` paths in
memory, without writing anything to `/nix/store`.

```sh
nix build .#graalnix          # on OpenJDK 25
nix build .#graalnix-native   # static native binary (musl), built with GraalVM
nix build .#graalnix-lite     # format, check and fix only, without Truffle; also graalnix-lite-native
nix build .#playground        # format, fix and lint in the browser: open result/index.html
./result/bin/graalnix eval --strict -E 'builtins.map (x: x * 2) [ 1 2 3 ]'
./result/bin/graalnix drv-path -I nixpkgs=/path/to/nixpkgs -E 'import <nixpkgs> {}' -A hello
./result/bin/graalnix check .   # lint
./result/bin/graalnix fix .     # apply the lint fixes, then format like nixfmt
```

The documentation, published at <https://allsimon.github.io/graalnix/> with
the [playground](https://allsimon.github.io/graalnix/playground/), lives in
[`docs/`](docs/index.adoc): the command line, parity status, architecture and
how to build. `nix build .#site` renders it to HTML.

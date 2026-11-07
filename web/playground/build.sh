#!/bin/sh
# Bundles the page into $1 with esbuild, from the installed node_modules.
set -eu
out=$1
m=node_modules/@codingame
esbuild main.js editor.worker=$m/monaco-vscode-editor-api/esm/vs/editor/editor.worker.js \
  --bundle --format=esm --splitting --minify --outdir="$out" --log-level=warning \
  --loader:.css=text --loader:.nix=text '--external:node:*'
cp index.html "$out"
cp -r public/. "$out"
# esbuild leaves `new URL(..., import.meta.url)` alone, so the files the page reaches that way are copied beside it.
cp $m/monaco-vscode-api/vscode/src/vs/base/browser/ui/codicons/codicon/codicon.ttf "$out"
cp -r $m/monaco-vscode-theme-defaults-default-extension/resources "$out"

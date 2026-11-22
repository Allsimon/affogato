{
  lib,
  coreutils,
  vscode-with-extensions,
  vscodium,
  vscode-extensions,
  writeShellScriptBin,
  affogato,
  settings,
}:
let
  codium = vscode-with-extensions.override {
    vscode = vscodium;
    vscodeExtensions = [ vscode-extensions.jnoortheen.nix-ide ];
  };
  user = builtins.toJSON {
    "security.workspace.trust.enabled" = false;
    "nix.enableLanguageServer" = true;
    "nix.serverPath" = [
      (lib.getExe affogato)
      "lsp"
    ];
    "nix.serverSettings".affogato = settings;
  };
in
writeShellScriptBin "codium-affogato" ''
  set -e
  dir="''${XDG_CACHE_HOME:-$HOME/.cache}/affogato-codium"
  ${coreutils}/bin/mkdir -p "$dir/User"
  ${coreutils}/bin/cat >"$dir/User/settings.json" <<'JSON'
  ${user}
  JSON
  exec ${lib.getExe codium} --user-data-dir "$dir" "$@"
''

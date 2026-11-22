{
  lib,
  coreutils,
  zed-editor,
  writeShellScriptBin,
  affogato,
  settings,
}:
let
  # The Nix extension only knows nixd and nil, so affogato takes nixd's place.
  user = builtins.toJSON {
    auto_install_extensions.nix = true;
    telemetry = {
      diagnostics = false;
      metrics = false;
    };
    languages.Nix.language_servers = [
      "nixd"
      "!nil"
    ];
    lsp.nixd = {
      binary = {
        path = lib.getExe affogato;
        arguments = [ "lsp" ];
      };
      # Its workspace configuration nests the settings under nixd, where affogato does not look.
      initialization_options = settings;
    };
  };
in
writeShellScriptBin "zed-affogato" ''
  set -e
  dir="''${XDG_CACHE_HOME:-$HOME/.cache}/affogato-zed"
  ${coreutils}/bin/mkdir -p "$dir/config"
  ${coreutils}/bin/cat >"$dir/config/settings.json" <<'JSON'
  ${user}
  JSON
  exec ${lib.getExe zed-editor} --user-data-dir "$dir" "$@"
''

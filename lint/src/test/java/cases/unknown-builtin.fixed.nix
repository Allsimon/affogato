[
  builtins.map
  builtins.mapAttrs'
  (builtins.fetchClosure or null)
  # Tested first, so the file works where Nix has it.
  (if builtins ? getFlakeAttr then builtins.getFlakeAttr else null)
  (
    let
      builtins = { };
    in
    builtins.custom
  )
]

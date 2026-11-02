{ lib }:
[
  builtins.groupBy
  builtins.zipAttrsWith
  lib.mapAttrs
  (builtins: lib.groupBy)
]

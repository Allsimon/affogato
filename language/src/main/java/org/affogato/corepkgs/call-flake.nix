# Port of Nix's src/libflake/call-flake.nix: the outputs of a locked flake, its inputs fetched lazily.
lockFileStr: overrides: fetchTreeFinal:

let
  lockFile = builtins.fromJSON lockFileStr;

  # A node name, or a `follows` path from the root.
  resolveInput =
    inputSpec: if builtins.isList inputSpec then getInputByPath lockFile.root inputSpec else inputSpec;

  getInputByPath =
    nodeName: path:
    if path == [ ] then
      nodeName
    else
      getInputByPath (resolveInput lockFile.nodes.${nodeName}.inputs.${builtins.head path}) (
        builtins.tail path
      );

  allNodes = builtins.mapAttrs (
    key: node:
    let
      parentNode = allNodes.${getInputByPath lockFile.root node.parent};

      sourceInfo =
        if overrides ? ${key} then
          overrides.${key}.sourceInfo
        else if node.locked.type == "path" && builtins.substring 0 1 node.locked.path != "/" then
          parentNode.sourceInfo // { outPath = parentNode.sourceInfo.outPath + ("/" + node.locked.path); }
        else
          fetchTreeFinal (node.info or { } // removeAttrs node.locked [ "dir" ]);

      subdir = overrides.${key}.dir or node.locked.dir or "";

      outPath = sourceInfo.outPath + ((if subdir == "" then "" else "/") + subdir);

      flake = import (outPath + "/flake.nix");

      inputs = builtins.mapAttrs (inputName: inputSpec: allNodes.${resolveInput inputSpec}.result) (
        node.inputs or { }
      );

      outputs = flake.outputs (inputs // { self = result; });

      result =
        outputs
        // sourceInfo
        // {
          inherit outPath inputs outputs sourceInfo;
          _type = "flake";
        };
    in
    {
      result =
        if node.flake or true then
          assert builtins.isFunction flake.outputs;
          result
        else
          sourceInfo // { inherit sourceInfo outPath; };

      inherit sourceInfo;
    }
  ) lockFile.nodes;
in
allNodes.${lockFile.root}.result

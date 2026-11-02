{
  lib,
  fetchurl,
  linkFarm,
}:
root:
let
  central = "https://repo1.maven.org/maven2/";

  moduleInfos = lib.concatMap (
    dir:
    lib.filter builtins.pathExists (
      map (set: root + "/${dir}/src/${set}/java/module-info.java") [
        "main"
        "test"
      ]
    )
  ) (lib.attrNames (lib.filterAttrs (_: type: type == "directory") (builtins.readDir root)));

  lines = file: lib.splitString "\n" (builtins.readFile file);

  pinsOf =
    pattern: file:
    lib.concatMap (
      line:
      let
        m = builtins.match pattern line;
      in
      lib.optional (m != null) {
        key = lib.replaceStrings [ "\\:" ] [ ":" ] (lib.elemAt m 0);
        version = lib.elemAt m 1;
        sha256 = lib.elemAt m 2;
      }
    ) (lines file);

  pins =
    lib.concatMap (pinsOf ".*@jenesis\\.pin ([^ ]+) ([^ ]+) SHA-256/([0-9a-f]{64}).*") moduleInfos
    ++ lib.concatMap (pinsOf "([^#=][^=]*)=([^ ]+) SHA-256/([0-9a-f]{64}).*") [
      (root + "/build.jenesis/pin-affogato.properties")
      (root + "/jenesis.plugins.pin.properties")
    ];

  maven =
    {
      group,
      artifact,
      version,
      classifier ? null,
      extension,
      sha256,
    }:
    let
      file = "${artifact}-${version}${
        lib.optionalString (classifier != null) "-${classifier}"
      }.${extension}";
      path = "${lib.replaceStrings [ "." ] [ "/" ] group}/${artifact}/${version}/${file}";
    in
    {
      name = "maven/${path}";
      url = central + path;
      inherit sha256;
    };

  # A pin is [<group>/]maven/<g>/<a>[/pom[/<v>]|/jar/<classifier>], [<group>/]module/<name>, <g>/<a> or a module name.
  parse =
    {
      key,
      version,
      sha256,
    }:
    let
      qualified = builtins.match "([^/]+/)?(maven|module)/(.*)" key;
      kind = if qualified == null then null else lib.elemAt qualified 1;
      segments = lib.splitString "/" (if qualified == null then key else lib.elemAt qualified 2);
      coordinate = {
        group = lib.elemAt segments 0;
        artifact = lib.elemAt segments 1;
        inherit version sha256;
      };
      n = lib.length segments;
    in
    if qualified == null && n == 1 then
      {
        module = key;
        inherit version sha256;
      }
    else if kind == "module" && n == 1 && lib.hasPrefix "build.jenesis" (lib.head segments) then
      let
        name = lib.head segments;
      in
      {
        name = "modules/${name}/${version}/${name}.jar";
        url = "${central}build/jenesis/${name}/${version}/${name}-${version}.jar";
        inherit sha256;
      }
    else if n == 2 then
      maven (coordinate // { extension = "jar"; })
    else if kind == "maven" && lib.elemAt segments 2 == "pom" && n <= 4 then
      maven (coordinate // { extension = "pom"; })
    else if kind == "maven" && lib.elemAt segments 2 == "jar" && n == 4 then
      maven (
        coordinate
        // {
          extension = "jar";
          classifier = lib.elemAt segments 3;
        }
      )
    else
      throw "Unsupported Jenesis pin ${key}";

  parsed = map parse pins;
  files = lib.filter (pin: pin ? url) parsed;

  # The module repository holds the POM of a module pinned by name, which is the POM of the Maven jar with the same checksum.
  jars = lib.listToAttrs (
    map (file: lib.nameValuePair file.sha256 file.name) (
      lib.filter (file: lib.hasSuffix ".jar" file.name) files
    )
  );
  modulePoms = map (
    pin:
    let
      jar = jars.${pin.sha256} or (throw "No Maven jar pinned with the checksum of module ${pin.module}");
      pom = lib.removeSuffix ".jar" jar + ".pom";
    in
    {
      name = "modules/${pin.module}/${pin.version}/${pin.module}.pom";
      path =
        byName.${pom}.path or (throw "No POM pinned for ${jar}, which module ${pin.module} resolves to");
    }
  ) (lib.filter (pin: pin ? module) parsed);

  byName = lib.foldl' (
    acc: file:
    if acc ? ${file.name} && acc.${file.name}.sha256 != file.sha256 then
      throw "Two checksums pinned for ${file.name}"
    else
      acc
      // {
        ${file.name} = {
          inherit (file) sha256;
          path = fetchurl {
            inherit (file) url;
            hash = builtins.convertHash {
              hash = file.sha256;
              hashAlgo = "sha256";
              toHashFormat = "sri";
            };
          };
        };
      }
  ) { } files;
in
linkFarm "affogato-deps" (
  lib.mapAttrsToList (name: file: {
    inherit name;
    inherit (file) path;
  }) byName
  ++ modulePoms
)

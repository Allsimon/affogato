{
  stdenv,
  buildGoModule,
  buildDotnetModule,
  pname,
  version,
}:
{
  # The two fixes put both flags together.
  both = stdenv.mkDerivation {
    pname = "both";
    version = "1.0";

    strictDeps = true;

    src = ./.;
  };

  structured = stdenv.mkDerivation (finalAttrs: {
    inherit pname version;

    __structuredAttrs = true;
    strictDeps = true;

    meta.description = "${finalAttrs.pname} example";
  });

  set = stdenv.mkDerivation {
    pname = "set";
    __structuredAttrs = true;
    strictDeps = true;
  };

  # Deliberately off.
  off = stdenv.mkDerivation {
    pname = "off";
    __structuredAttrs = true;
    strictDeps = false;
  };

  # Go's builder sets it already.
  go = buildGoModule {
    __structuredAttrs = true;
    src = ./.;
  };

  dotnet = buildDotnetModule {
    inherit pname version;
    __structuredAttrs = true;
    strictDeps = true;
  };
}

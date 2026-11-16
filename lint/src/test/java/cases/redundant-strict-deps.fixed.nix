{
  stdenv,
  buildGoModule,
  buildRustPackage,
  buildNpmPackage,
  buildDotnetModule,
  pname,
  version,
}:
{
  go = buildGoModule (finalAttrs: {
    inherit pname version;

    __structuredAttrs = true;

    vendorHash = null;
  });

  rust = buildRustPackage {
    inherit pname version;
    strictDeps = true; # Always.
  };

  npm = buildNpmPackage {
    inherit pname version;
  };

  # Not a literal.
  conditional = buildGoModule {
    inherit pname version;
    strictDeps = !stdenv.hostPlatform.isDarwin;
  };

  plain = stdenv.mkDerivation {
    inherit pname version;
    strictDeps = true;
  };

  dotnet = buildDotnetModule {
    inherit pname version;
    strictDeps = true;
  };
}

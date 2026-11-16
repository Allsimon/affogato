{
  stdenv,
  python,
  python27,
  pypyPackages,
  buildMavenPackage,
  buildGoModule,
  buildPythonPackage,
  buildHomeAssistantComponent,
  pname,
  version,
}:
{
  maven = buildMavenPackage {
    inherit pname version;

    src = ./.;
  };

  python = buildPythonPackage (finalAttrs: {
    pname = "python";
    version = "1.0";
    strictDeps = true;
  });

  component = buildHomeAssistantComponent {
    inherit pname version;
  };

  application = python.pkgs.buildPythonApplication {
    inherit pname version;
  };

  # The fix keeps the comment, so there is none.
  commented = buildMavenPackage {
    inherit pname version;
    # Kept for overrides.
    __structuredAttrs = true;
  };

  # The builder of stdenv does not set it.
  plain = stdenv.mkDerivation {
    inherit pname version;
    __structuredAttrs = true;
  };

  # Deliberately off.
  off = buildMavenPackage {
    inherit pname version;
    __structuredAttrs = false;
  };

  # So does the builder of Python 2.
  python2 = python27.pkgs.buildPythonApplication {
    inherit pname version;
    __structuredAttrs = true;
  };

  pypy = pypyPackages.buildPythonPackage {
    inherit pname version;
    __structuredAttrs = true;
  };

  # Go's builder leaves it unset.
  go = buildGoModule {
    inherit pname version;
    __structuredAttrs = true;
  };
}

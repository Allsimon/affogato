{ stdenv, buildPythonPackage }:
{
  # Hooks and phases swapped, with their comments and other attributes between them.
  swapped = stdenv.mkDerivation (finalAttrs: {
    pname = "swapped";
    version = "1.0";

    preBuild = "export A=1";

    meta.description = "Stays in place";

    buildPhase = ''
      runHook preBuild
      make
      runHook postBuild
    '';
    postBuild = "make docs"; # the docs
    # Installs the binary.
    installPhase = ''
      runHook preInstall
      install -Dm755 a $out/bin/a
      runHook postInstall
    '';
  });

  # Already in order, around attributes that are not phases.
  ordered = buildPythonPackage {
    pname = "ordered";
    preConfigure = "a";
    configurePhase = "b";
    doCheck = true;
    checkPhase = "c";
    postInstall = "d";
    installCheckPhase = "e";
  };

  # Reported, but not on lines of their own.
  inline = stdenv.mkDerivation { installPhase = "a"; buildPhase = "b"; };

  # Not a derivation.
  plain = { installPhase = "a"; buildPhase = "b"; };
}

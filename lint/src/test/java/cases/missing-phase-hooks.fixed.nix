{ stdenv }:
stdenv.mkDerivation {
  pname = "example";
  version = "1.0";

  installPhase = ''
    runHook preInstall

    mkdir -p $out/bin
    cp example $out/bin/

    runHook postInstall
  '';

  # Only the missing call is added.
  buildPhase = ''
    runHook preBuild
    make

    runHook postBuild
  '';

  checkPhase = ''
    runHook preCheck
    make check
    runHook postCheck
  '';

  # Not fixed: the calls need lines of their own.
  configurePhase = "./configure --prefix=$out";
  fixupPhase = ''true'';
}

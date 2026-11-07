{ lib, fetchurl, fetchFromGitHub }:
{
  a = fetchurl {
    url = "https://example.org/a.tar.gz";
    hash = lib.fakeHash;
  };
  b = fetchFromGitHub {
    owner = "o";
    repo = "r";
    rev = "v1";
    hash = "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
  };
  c = fetchurl {
    url = "https://example.org/c.tar.gz";
    sha256 = lib.fakeSha256;
  };
  # A real hash is left alone, and so is an attribute of another set.
  d = fetchurl {
    url = "https://example.org/d.tar.gz";
    hash = "sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=";
  };
  e = pkgs.fakeHash;
}

{
  lib,
  fetchurl,
  fetchFromGitHub,
  fetchHex,
  sha256,
}:
# Each hash is sha256-LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ= in another encoding.
[
  (fetchurl {
    url = "https://example.org/a.tar.gz";
    hash = "sha256-LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ=";
  })
  (fetchurl {
    url = "https://example.org/b.tar.gz";
    hash = "sha256-LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ=";
  })
  (fetchFromGitHub {
    owner = "example";
    repo = "c";
    rev = "v1";
    hash = "sha256-LPJNul+wow4m6DsqxbninhsWHlwfp0JecwQzYpOLmCQ=";
  })
  (fetchFromGitHub {
    owner = "example";
    repo = "d";
    rev = "v1";
    hash = lib.fakeHash;
  })
  # Not fixed: only a literal can be converted.
  (fetchurl {
    url = "https://example.org/e.tar.gz";
    inherit sha256;
  })
  (fetchurl {
    url = "https://example.org/f.tar.gz";
    sha256 = sha256;
  })
  # Not fixed: the url refers to it.
  (fetchurl rec {
    url = "https://example.org/i_${sha256}.deb";
    sha256 = "094qif9n4cq4fdg459qzbhg1c6wywawwaaivx0k0x8xhbyx4vwic";
  })
  # A fetcher of the file's own, which may take only sha256.
  (
    let
      fetchurl = { url, sha256 }: builtins.fetchurl { inherit url sha256; };
    in
    fetchurl {
      url = "https://example.org/h.tar.gz";
      sha256 = "094qif9n4cq4fdg459qzbhg1c6wywawwaaivx0k0x8xhbyx4vwic";
    }
  )
  # fetchHex takes only sha256.
  (fetchHex {
    pkg = "g";
    version = "1";
    sha256 = "094qif9n4cq4fdg459qzbhg1c6wywawwaaivx0k0x8xhbyx4vwic";
  })
]

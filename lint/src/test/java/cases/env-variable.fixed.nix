{
  lib,
  stdenv,
  buildGoModule,
}:
{
  string = stdenv.mkDerivation {
    pname = "string";
    env.NIX_CFLAGS_COMPILE = "-Wno-error";
    env.NIX_LDFLAGS = lib.optionalString stdenv.hostPlatform.isDarwin "-framework Foundation";
    # A list, which env does not take as such.
    env.NIX_CFLAGS_LINK = toString [
      "-lm"
      "-lpthread"
    ];
    env.NIX_DEBUG = toString (lib.optionals stdenv.cc.isClang [ "1" ]);
    env.NIX_ENFORCE_PURITY = 0;
    NIX_HARDENING_ENABLE = lib.optionalString stdenv.isLinux "pie" + " fortify";
    # Shell code reads it from env as well.
    preBuild = ''
      export NIX_CFLAGS_COMPILE+=" -DNDEBUG"
    '';
  };

  # Merged with the env written in place.
  merged = buildGoModule (finalAttrs: {
    pname = "merged";
    env = {
      CGO_ENABLED = 0;
    };
    env.NIX_LDFLAGS = "-lz";
  });

  # Not fixed: env is not written in place.
  computed = stdenv.mkDerivation {
    pname = "computed";
    env = lib.optionalAttrs stdenv.hostPlatform.isDarwin { CC = "clang"; };
    NIX_LDFLAGS = "-lz";
  };

  # Not fixed: the file refers to it.
  referred = stdenv.mkDerivation (finalAttrs: {
    pname = "referred";
    NIX_HARDENING_ENABLE = "fortify";
    passthru.hardening = finalAttrs.NIX_HARDENING_ENABLE;
  });
}

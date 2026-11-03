{
  a = {
    b = 1;
    c = 2; # This comment moves with its binding.

    # So does this one, above its binding.
    d = {
      e = ''
        kept
      '';
      # Its second line is part of the string, so it stays.
      f = "not
    moved";
    };
  };
  x = 0;

  f = { g = { h = 1; i = 2; j = 3; }; };

  # Grouped under the prefix they share.
  k.l = {
    m = 1;
    n = 2;
    o = 3;
  };

  # Not fixed: `p` is also set whole.
  p = { };
  p.q = 1;
  p.r = 2;
  p.s = 3;

  w = {
    a = 1;

    b = 2;

    c = 3;
  };
  y = 0;

  t.u = 1;
  t.v = 2;
}

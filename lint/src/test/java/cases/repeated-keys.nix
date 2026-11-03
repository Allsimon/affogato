{
  a.b = 1;
  a.c = 2; # This comment moves with its binding.
  x = 0;

  # So does this one, above its binding.
  a.d = {
    e = ''
      kept
    '';
    # Its second line is part of the string, so it stays.
    f = "not
    moved";
  };

  f = { g.h = 1; g.i = 2; g.j = 3; };

  # Grouped under the prefix they share.
  k.l.m = 1;
  k.l.n = 2;
  k.l.o = 3;

  # Not fixed: `p` is also set whole.
  p = { };
  p.q = 1;
  p.r = 2;
  p.s = 3;

  w.a = 1;

  w.b = 2;

  w.c = 3;
  y = 0;

  t.u = 1;
  t.v = 2;
}

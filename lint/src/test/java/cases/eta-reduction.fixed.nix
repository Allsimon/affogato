{ f, lib }:
[
  (f)
  (lib.length)
  (x: f x x)
  # Partial applications are kept.
  (x: lib.map f x)
  (map (f) [ ])
]

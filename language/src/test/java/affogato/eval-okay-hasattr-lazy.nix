{ a = throw "forced"; b.c = throw "forced"; } ? a && { b.c = throw "forced"; } ? b.c

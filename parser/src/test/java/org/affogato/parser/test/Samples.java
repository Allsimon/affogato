package org.affogato.parser.test;

import java.util.List;
import java.util.stream.Stream;

final class Samples {
    private Samples() {}

    static final List<String> VALID = List.of(
            "1",
            "x: y: x + y * 2",
            "{ a, b ? 1, ... }@args: a",
            "args @ { a }: a",
            "{ }: 1",
            "let a = 1; inherit (b) c \"d\"; in a",
            "rec { a.b.\"c\" = 1; ${d} = 2; inherit e; }",
            "let { body = 1; }",
            "if a then b else c",
            "assert a; with b; c",
            "[ 1 2.5 ./a/b ~/c <nixpkgs> https://x.org/a (f x) ]",
            "a.b.c or d",
            "f or",
            "a ? b.c",
            "!a && -b || c -> d",
            "a // b ++ c |> f <| g",
            "\"a\\n${b}c $${d}\"",
            "''\n  a ''$ ''' ''\\t ${b}\n''",
            "./a/${b}/c",
            "# comment\n/* block */ x",
            """
            { stdenv, fetchurl }:
            stdenv.mkDerivation (finalAttrs: {
              pname = "hello";
              src = fetchurl { url = "mirror://gnu/${finalAttrs.pname}.tar.gz"; };
              installPhase = ''
                mkdir -p $out/bin
                cp ${./hello.sh} $out/bin/hello
              '';
            })
            """);

    static final List<String> INVALID = List.of(
            "{ a = ; }",
            "{ a = 1 }",
            "{ a }",
            "let a = 1;",
            "f (",
            "[ 1 = ]",
            "\"unterminated ${",
            "''unterminated",
            "/* unterminated",
            "a == b == c",
            "x: ",
            "{ a, b ",
            "inherit",
            ") ] }",
            "a.",
            "é + 1",
            "./trailing/");

    static Stream<String> valid() {
        return VALID.stream();
    }

    static Stream<String> invalid() {
        return INVALID.stream();
    }

    static Stream<String> all() {
        return Stream.concat(valid(), invalid());
    }

    /** Every prefix of {@code src}, the empty one and itself included. */
    static Stream<String> prefixes(String src) {
        return Stream.iterate(0, n -> n <= src.length(), n -> n + 1).map(n -> src.substring(0, n));
    }
}

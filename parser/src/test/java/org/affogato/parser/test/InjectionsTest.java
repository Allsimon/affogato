package org.affogato.parser.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.affogato.parser.Injections;
import org.affogato.parser.NodeKind;
import org.affogato.parser.SyntaxTree;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** The language of the last string of each source. */
class InjectionsTest {
    private static Injections.Injection injection(String source) {
        SyntaxTree tree = SyntaxTree.parse(source.replace("\\n", "\n"));
        List<SyntaxTree.Node> strings = new ArrayList<>();
        collect(tree.root(), strings);
        return Injections.of(tree.view(), strings.getLast(), Injections.Rules.DEFAULT);
    }

    private static void collect(SyntaxTree.Node node, List<SyntaxTree.Node> strings) {
        if (node.kind == NodeKind.STRING || node.kind == NodeKind.IND_STRING) strings.add(node);
        node.children().forEach(child -> collect(child, strings));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "{ installPhase = ''cp ${./a.sh} $out''; }                          | SHELL | shell",
                "{ enterShell = \"echo hi\"; }                                      | SHELL | shell",
                "{ scripts.hello.exec = ''echo ${name}''; }                         | SHELL | shell",
                "{ enterShell = lib.mkAfter \"echo hi\"; }                          | SHELL | shell",
                "pkgs.runCommand \"name\" { } \"touch $out\"                        | SHELL | shell",
                "writeShellScript \"name\" \"echo\"                                 | SHELL | shell",
                "pkgs.writeShellApplication { name = \"x\"; text = \"echo hi\"; }   | SHELL | shell",
                "pkgs.writers.writeJS \"hello\" { } ''console.log(1)''              | FILE  | script.js",
                "writers.writeBashBin \"hello\" \"echo\"                            | FILE  | script.sh",
                "pkgs.writeText \"config.json\" ''{ }''                             | FILE  | config.json",
                "builtins.toFile \"run.sh\" \"echo\"                                | FILE  | run.sh",
                "{ environment.etc.\"app/settings.json\".text = \"{}\"; }           | FILE  | settings.json",
                "{ files.\"a.json\" = { text = \"[]\"; }; }                         | FILE  | a.json",
                "{ config = /* json */ ''{ }''; }                                   | NAMED | json",
                "{\\n  # language=JavaScript\\n  code = \"let a = 1\";\\n}          | NAMED | JavaScript",
                "{ installPhase = /* python */ \"print()\"; }                       | NAMED | python",
            })
    void injects(String source, Injections.Injection.Kind kind, String value) {
        assertThat(injection(source)).isEqualTo(new Injections.Injection(kind, value));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{ pname = \"hello\"; }",
                "{ preferLocalBuild = \"x\"; }",
                "writeShellScript \"name\" \"echo\" 1",
                "pkgs.writeText \"notes\" \"hello\"",
                "{ text = \"hello\"; }",
                "{ x = /* not a language */ \"hello\"; }",
            })
    void injectsNothing(String source) {
        assertThat(injection(source)).isNull();
    }
}

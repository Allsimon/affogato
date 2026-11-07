/**
 * Command line of affogato without the evaluator: formats and lints Nix files.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-affogato.properties
 * @jenesis.bom palantir:tool/pin-affogato.properties
 * @jenesis.main org.affogato.cli.Main
 * @jenesis.plugin maven/info.picocli/picocli-codegen
 * @jenesis.pin palantir/module/build.jenesis 0.18.0 SHA-256/35a94fc0761110ebcd2fa51b2226a3e7e0a942b14cd69d67cc584bf5f3519f70
 * @jenesis.pin palantir/module/build.jenesis.plugin.palantir 0.2.0 SHA-256/f898acd9e03d753cdcdc7e41e0eeb9f21915496e99eb0317aaf443c8df49b73e
 * @jenesis.pin plugin/maven/info.picocli/picocli 4.7.7 SHA-256/f86e30fffd10d2b13b8caa8d4b237a7ee61f2ffccf5b1941de718b765d235bf8
 * @jenesis.pin plugin/maven/info.picocli/picocli-codegen 4.7.7 SHA-256/6dae68232ce1d152b8e474d5bd1888d100bc8cea2ab9307a4e5aa04e1357d261
 * @jenesis.pin plugin/maven/info.picocli/picocli-codegen/pom 4.7.7 SHA-256/26c800a3f6fb767e300ab2db17aaa1cf536cb84629a3d4756d540b9c1d01c12c
 * @jenesis.pin plugin/maven/info.picocli/picocli/pom 4.7.7 SHA-256/1b18d363134df66631d2b9f7475068d734225ba389cdf9082a1bd8bda90d57d3
 */
module org.affogato.cli {
    requires info.picocli;
    requires org.affogato.format;
    requires org.affogato.lint;
    requires org.affogato.lsp;
    requires org.affogato.parser;

    exports org.affogato.cli;

    opens org.affogato.cli to
            info.picocli;
}

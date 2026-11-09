/**
 * A language server for Nix over stdio: the linter's diagnostics, the formatter, and navigation over the parser's
 * tree.
 *
 * @jenesis.release 21
 * @jenesis.bom pin-affogato.properties
 * @jenesis.bom palantir:tool/pin-affogato.properties
 * @jenesis.pin palantir/module/build.jenesis 0.18.0 SHA-256/35a94fc0761110ebcd2fa51b2226a3e7e0a942b14cd69d67cc584bf5f3519f70
 * @jenesis.pin palantir/module/build.jenesis.plugin.palantir 0.2.0 SHA-256/f898acd9e03d753cdcdc7e41e0eeb9f21915496e99eb0317aaf443c8df49b73e
 */
module org.affogato.lsp {
    requires org.affogato.format;
    requires org.affogato.lint;
    requires transitive org.affogato.parser;

    exports org.affogato.lsp;
}

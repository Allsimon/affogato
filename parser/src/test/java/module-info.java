/**
 * Tests the tokenizer.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-affogato.properties
 * @jenesis.bom palantir:tool/pin-affogato.properties
 * @jenesis.test org.affogato.parser
 * @jenesis.pin palantir/module/build.jenesis 0.18.0 SHA-256/35a94fc0761110ebcd2fa51b2226a3e7e0a942b14cd69d67cc584bf5f3519f70
 * @jenesis.pin palantir/module/build.jenesis.plugin.palantir 0.2.0 SHA-256/f898acd9e03d753cdcdc7e41e0eeb9f21915496e99eb0317aaf443c8df49b73e
 */
module org.affogato.parser.test {
    requires org.affogato.parser;
    requires org.junit.jupiter;
    requires org.assertj.core;
}

/**
 * Runs NixOS/nix's language test suite against affogato.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-affogato.properties
 * @jenesis.bom jacoco:agent/pin-affogato.properties
 * @jenesis.bom jacoco:tool/pin-affogato.properties
 * @jenesis.bom palantir:tool/pin-affogato.properties
 * @jenesis.test org.affogato
 * @jenesis.pin jacoco/module/build.jenesis 0.18.0 SHA-256/35a94fc0761110ebcd2fa51b2226a3e7e0a942b14cd69d67cc584bf5f3519f70
 * @jenesis.pin jacoco/module/build.jenesis.plugin.jacoco 0.2.0 SHA-256/73caf862dbc54224eeaf1f9a277e6346a13baceb188a105874ad4a4190908a37
 * @jenesis.pin palantir/module/build.jenesis 0.18.0 SHA-256/35a94fc0761110ebcd2fa51b2226a3e7e0a942b14cd69d67cc584bf5f3519f70
 * @jenesis.pin palantir/module/build.jenesis.plugin.palantir 0.2.0 SHA-256/f898acd9e03d753cdcdc7e41e0eeb9f21915496e99eb0317aaf443c8df49b73e
 */
module org.affogato.test {
    requires org.affogato;
    requires org.affogato.parser;
    requires org.affogato.lint;
    requires org.junit.jupiter;
    requires org.assertj.core;
}

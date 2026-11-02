/**
 * The Nix language, implemented as a Truffle language.
 *
 * @jenesis.release 25
 * @jenesis.bom pin-affogato.properties
 * @jenesis.bom palantir:tool/pin-affogato.properties
 * @jenesis.plugin maven/org.graalvm.truffle/truffle-dsl-processor
 * @jenesis.pin palantir/module/build.jenesis 0.18.0 SHA-256/35a94fc0761110ebcd2fa51b2226a3e7e0a942b14cd69d67cc584bf5f3519f70
 * @jenesis.pin palantir/module/build.jenesis.plugin.palantir 0.2.0 SHA-256/f898acd9e03d753cdcdc7e41e0eeb9f21915496e99eb0317aaf443c8df49b73e
 * @jenesis.pin plugin/maven/org.graalvm.truffle/truffle-dsl-processor 25.4.4.1.1 SHA-256/f6fdebcb85da6587d764ffcb0f859a84f5ae1b53866b96012d9e822412cf1518
 * @jenesis.pin plugin/maven/org.graalvm.truffle/truffle-dsl-processor/pom 25.4.4.1.1 SHA-256/ba518b50fdc2436276a145c7d1bc97197a448cc8528e5162bb1c99197ba4c357
 */
module org.affogato {
    requires org.graalvm.truffle;
    requires org.graalvm.polyglot;
    requires org.affogato.parser;
    requires org.affogato.store;

    provides com.oracle.truffle.api.provider.TruffleLanguageProvider with org.affogato.NixLanguageProvider;
}

{
  lib,
  stdenv,
  jdk25_headless,
  graalvmPackages,
  buildNpmPackage,
  importNpmLock,
  fetchurl,
  installShellFiles,
  linkFarm,
  nodejs,
  runCommand,
  makeWrapper,
  unzip,
}:
let
  jdk = jdk25_headless;

  sources =
    paths:
    lib.fileset.toSource {
      root = ../.;
      fileset = lib.fileset.unions (
        [
          ../build/jenesis
          ../build.jenesis
          ../jenesis.properties
          ../cli/src/main
          ../format/src/main
          ../lint/src/main
          ../lsp/src/main
          ../parser/src/main
          ../store/src/main
        ]
        ++ paths
      );
    };
  evaluator = [
    ../language/src/main
    ../launcher/src/main
  ];
  testPaths = [
    ../language/src/test
    ../launcher/src/test
    ../format/src/test
    ../format/_versions.adoc
    ../vendir.yml
    ../flake.lock
    ../lint/src/test
    ../lsp/src/test
    ../parser/src/test
    ../docs/cli
    ../docs/_lint-rules.adoc
    ../web/playground/examples.js
  ];
  docPaths = [
    ../docs
    ../jenesis.plugins.pin.properties
  ];
  src = sources evaluator;
  testSrc = sources (evaluator ++ testPaths);

  deps = import ./pins.nix { inherit lib fetchurl linkFarm; } ../.;

  offline = ''
    export HOME=$TMPDIR MAVEN_REPOSITORY_LOCAL=$TMPDIR/maven JENESIS_REPOSITORY_LOCAL=$TMPDIR/modules
    cp -rL ${deps}/maven $MAVEN_REPOSITORY_LOCAL
    cp -rL ${deps}/modules $JENESIS_REPOSITORY_LOCAL
    chmod -R u+w $MAVEN_REPOSITORY_LOCAL $JENESIS_REPOSITORY_LOCAL
  '';

  tests = stdenv.mkDerivation {
    name = "affogato-tests";
    src = testSrc;
    nativeBuildInputs = [ jdk ];
    buildPhase = ''
      runHook preBuild
      ${offline}
      java -Djenesis.make.mode=source -Djenesis.repository.offline=true -Djenesis.dependency.pin=strict -Djenesis.documentation.asciidoctor=false \
        build/jenesis/Make.java
      runHook postBuild
    '';
    outputs = [
      "out"
      "coverage"
    ];
    installPhase = ''
      runHook preInstall
      touch $out
      cp -r target/build/modules/compose/module/*/produce/assemble/observed/observations/jacoco/report/output/reports/jacoco $coverage
      runHook postInstall
    '';
  };

  docs = stdenv.mkDerivation {
    name = "affogato-docs";
    src = sources (evaluator ++ docPaths);
    nativeBuildInputs = [ jdk ];
    buildPhase = ''
      runHook preBuild
      ${offline}
      java -Djenesis.make.mode=source -Djenesis.repository.offline=true -Djenesis.dependency.pin=strict -Djenesis.test.skip=true \
        build/jenesis/Make.java stage
      runHook postBuild
    '';
    installPhase = ''
      runHook preInstall
      cp -r target/stage/project/output/docs $out
      runHook postInstall
    '';
  };

  # The formatter and linter as a Maven repository, for the IntelliJ plugin (Allsimon/devenv-intellij) to build against.
  mavenRepo = stdenv.mkDerivation {
    name = "affogato-maven-repo";
    src = lib.fileset.toSource {
      root = ../.;
      fileset = lib.fileset.unions [
        ../build/jenesis
        ../build.jenesis
        ../jenesis.properties
        ../format/src/main
        ../lint/src/main
        ../parser/src/main
        ../store/src/main
      ];
    };
    nativeBuildInputs = [ jdk ];
    buildPhase = ''
      runHook preBuild
      ${offline}
      java -Djenesis.make.mode=source -Djenesis.repository.offline=true -Djenesis.dependency.pin=strict -Djenesis.test.skip=true \
        -Djenesis.documentation.asciidoctor=false build/jenesis/Make.java stage/maven
      runHook postBuild
    '';
    installPhase = ''
      runHook preInstall
      mkdir -p $out/org/affogato
      cp -r target/stage/maven/output/org/affogato/org.affogato.{parser,format,lint,store} $out/org/affogato
      runHook postInstall
    '';
  };

  # Builds only the package step of a module, and so only the modules it requires.
  package =
    module: profile:
    lib.optionalString (profile != null) "-Djenesis.make.profiles=${profile} "
    + "build/jenesis/Make.java build/modules/compose/module/package-module-${module}+src+main+java";
  output = module: "target/build/modules/compose/module/package-module-${module}+src+main+java";

  # picocli's script works in zsh only through bashcompinit, so the zsh function sources it.
  # Completion runs with NO_aliases, so its compopt alias does not expand.
  completions = ''
    $out/bin/affogato generate-completion > affogato.bash
    cat > _affogato <<EOF
    #compdef affogato
    compopt() { :; }
    source $out/share/bash-completion/completions/affogato.bash
    _bash_complete -F _complete_affogato -o default
    EOF
    installShellCompletion affogato.bash _affogato
  '';

  jvm =
    {
      pname,
      module,
      src,
      meta,
    }:
    stdenv.mkDerivation {
      inherit pname src meta;
      version = "0.1.0";

      nativeBuildInputs = [
        jdk
        installShellFiles
        makeWrapper
        unzip
      ];

      buildPhase = ''
        runHook preBuild
        ${offline}
        java -Djenesis.make.mode=source -Djenesis.repository.offline=true -Djenesis.dependency.pin=strict -Djenesis.test.skip=true \
          -Djenesis.documentation.asciidoctor=false ${package module null}
        runHook postBuild
      '';

      installPhase = ''
        runHook preInstall
        mkdir -p $out/share/affogato
        unzip -q ${output module}/bundle/output/bundle/bundle.zip jars/'*' application.unix.args -d $out/share/affogato
        sed -i -E "s#([\":])jars/#\1$out/share/affogato/jars/#g" $out/share/affogato/application.unix.args
        makeWrapper ${jdk}/bin/java $out/bin/affogato --add-flags @$out/share/affogato/application.unix.args
        ${completions}
        runHook postInstall
      '';
    };

  native =
    {
      pname,
      module,
      profile,
      src,
      meta,
    }:
    stdenv.mkDerivation {
      inherit pname src;
      version = "0.1.0";

      nativeBuildInputs = [
        graalvmPackages.graalvm-ce-musl
        installShellFiles
      ];

      buildPhase = ''
        runHook preBuild
        ${offline}
        java -Djenesis.make.mode=source -Djenesis.repository.offline=true -Djenesis.dependency.pin=strict -Djenesis.test.skip=true \
          -Djenesis.documentation.asciidoctor=false ${package module profile}
        runHook postBuild
      '';

      installPhase = ''
        runHook preInstall
        install -Dm755 ${output module}/native-image/output/native/org.affogato.${module} $out/bin/affogato
        ${completions}
        runHook postInstall
      '';

      meta = meta // {
        description = meta.description + ", as a native image";
      };
    };

  cli = {
    pname = "affogato";
    module = "cli";
    src = sources [ ];
    meta = {
      description = "Nix formatter and linter of affogato";
      mainProgram = "affogato";
      platforms = lib.platforms.linux;
    };
  };

  playgroundServer = stdenv.mkDerivation {
    pname = "affogato-playground-server";
    version = "0.1.0";
    src = sources [
      ../web/src/main
      ../plugins
      ../jenesis.plugins.properties
      ../jenesis.plugins.pin.properties
    ];
    nativeBuildInputs = [ jdk ];
    buildPhase = ''
      runHook preBuild
      ${offline}
      java -Djenesis.make.mode=source -Djenesis.repository.offline=true -Djenesis.dependency.pin=strict -Djenesis.test.skip=true \
        -Djenesis.documentation.asciidoctor=false ${package "web" null}
      runHook postBuild
    '';
    installPhase = ''
      runHook preInstall
      install -Dm644 ${output "web"}/custom/teavm/delegate/teavm/output/javascript/affogato.js $out/affogato.js
      runHook postInstall
    '';
    meta = {
      description = "The language server of affogato compiled to JavaScript by TeaVM";
      platforms = lib.platforms.all;
    };
  };

  playground = buildNpmPackage {
    pname = "affogato-playground";
    version = "0.1.0";
    src = lib.fileset.toSource {
      root = ../web/playground;
      fileset = lib.fileset.unions [
        ../web/playground/index.html
        ../web/playground/main.js
        ../web/playground/examples.js
        ../web/playground/kcgi.nix
        ../web/playground/public/server.js
        ../web/playground/vite.config.js
        ../web/playground/package.json
        ../web/playground/package-lock.json
      ];
    };
    npmDeps = importNpmLock {
      package = lib.importJSON ../web/playground/package.json;
      packageLock = lib.importJSON ../web/playground/package-lock.json;
    };
    npmConfigHook = importNpmLock.npmConfigHook;
    installPhase = ''
      runHook preInstall
      cp -r dist $out
      install -Dm644 ${playgroundServer}/affogato.js $out/affogato.js
      runHook postInstall
    '';
    meta = {
      description = "Monaco editing Nix in the browser with the language server of affogato";
      platforms = lib.platforms.all;
    };
  };

  playgroundTests = runCommand "affogato-playground-tests" { nativeBuildInputs = [ nodejs ]; } ''
    node ${../web/check.cjs} ${playgroundServer}/affogato.js ${../format/src/test/java} ${../lint/src/test/java/cases} | tee $out
  '';

  # The website published to GitHub Pages: the documentation, with the playground under playground/.
  site = runCommand "affogato-site" { } ''
    cp -r ${docs} $out
    chmod u+w $out
    cp -r ${playground} $out/playground
  '';

  truffle = {
    pname = "affogato-truffle";
    module = "launcher";
    inherit src;
    meta = cli.meta // {
      description = "Nix language implementation on GraalVM/Truffle";
    };
  };
in
(jvm cli).overrideAttrs {
  passthru = {
    inherit
      deps
      tests
      docs
      mavenRepo
      playground
      playgroundTests
      site
      ;
    native = native (
      cli
      // {
        pname = "affogato-native";
        profile = "native-lite";
      }
    );
    truffle = jvm truffle;
    truffle-native = native (
      truffle
      // {
        pname = "affogato-truffle-native";
        profile = "native";
      }
    );
  };
}

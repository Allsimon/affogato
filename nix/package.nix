{
  lib,
  stdenv,
  jdk25_headless,
  graalvmPackages,
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
    name = "graalnix-tests";
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
    name = "graalnix-docs";
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
    name = "graalnix-maven-repo";
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
      mkdir -p $out/org/graalnix
      cp -r target/stage/maven/output/org/graalnix/org.graalnix.{parser,format,lint,store} $out/org/graalnix
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
    $out/bin/graalnix generate-completion > graalnix.bash
    cat > _graalnix <<EOF
    #compdef graalnix
    compopt() { :; }
    source $out/share/bash-completion/completions/graalnix.bash
    _bash_complete -F _complete_graalnix -o default
    EOF
    installShellCompletion graalnix.bash _graalnix
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
        mkdir -p $out/share/graalnix
        unzip -q ${output module}/bundle/output/bundle/bundle.zip jars/'*' application.unix.args -d $out/share/graalnix
        sed -i -E "s#([\":])jars/#\1$out/share/graalnix/jars/#g" $out/share/graalnix/application.unix.args
        makeWrapper ${jdk}/bin/java $out/bin/graalnix --add-flags @$out/share/graalnix/application.unix.args
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
        install -Dm755 ${output module}/native-image/output/native/org.graalnix.${module} $out/bin/graalnix
        ${completions}
        runHook postInstall
      '';

      meta = meta // {
        description = meta.description + ", as a native image";
      };
    };

  full = {
    pname = "graalnix";
    module = "launcher";
    inherit src;
    meta = {
      description = "Nix language implementation on GraalVM/Truffle";
      mainProgram = "graalnix";
      platforms = lib.platforms.linux;
    };
  };

  playground = stdenv.mkDerivation {
    pname = "graalnix-playground";
    version = "0.1.0";
    src = sources [
      ../web/src/main
      ../web/playground
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
      install -Dm644 web/playground/index.html $out/index.html
      install -Dm644 web/playground/examples.js $out/examples.js
      install -Dm644 ${output "web"}/custom/teavm/delegate/teavm/output/javascript/graalnix.js $out/graalnix.js
      runHook postInstall
    '';
    meta = {
      description = "The formatter and linter of graalnix in the browser, compiled to JavaScript by TeaVM";
      platforms = lib.platforms.all;
    };
  };

  playgroundTests = runCommand "graalnix-playground-tests" { nativeBuildInputs = [ nodejs ]; } ''
    node ${../web/check.cjs} ${playground}/graalnix.js ${../format/src/test/java} ${../lint/src/test/java/cases} | tee $out
  '';

  # The website published to GitHub Pages: the documentation, with the playground under playground/.
  site = runCommand "graalnix-site" { } ''
    cp -r ${docs} $out
    chmod u+w $out
    cp -r ${playground} $out/playground
  '';

  lite = {
    pname = "graalnix-lite";
    module = "cli";
    src = sources [ ];
    meta = full.meta // {
      description = "Nix formatter and linter of graalnix, without the evaluator";
    };
  };
in
(jvm full).overrideAttrs {
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
      full
      // {
        pname = "graalnix-native";
        profile = "native";
      }
    );
    lite = jvm lite;
    lite-native = native (
      lite
      // {
        pname = "graalnix-lite-native";
        profile = "native-lite";
      }
    );
  };
}

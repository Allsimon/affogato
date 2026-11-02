package affogato.teavm;

import module build.jenesis;
import module java.base;
import build.jenesis.step.Dependencies;

/**
 * Resolves TeaVM in the plugin's tool group and runs it. It resolves onto a class path, as TeaVM's relocated-libs
 * jars name the same modules as the libraries they relocate, which their POMs depend on as well.
 */
final class TeaVMModule implements BuildExecutorModule {

    private static final List<String> TOOLS = List.of("teavm-tooling", "teavm-classlib", "teavm-jso-impl");

    private final Environment environment;
    private final Map<String, Repository> repositories;
    private final Map<String, Resolver> resolvers;
    private final String group, version, main, file, module;

    TeaVMModule(Environment environment,
                Map<String, Repository> repositories,
                Map<String, Resolver> resolvers,
                String group,
                String version,
                String main,
                String file,
                String module) {
        this.environment = environment;
        this.repositories = repositories;
        this.resolvers = resolvers;
        this.group = group;
        this.version = version;
        this.main = main;
        this.file = file;
        this.module = module;
    }

    @Override
    public void accept(BuildExecutor executor, SequencedMap<String, Path> inherited) throws IOException {
        executor.addStep("required", new Requires(group, version), inherited.sequencedKeySet());
        SequencedSet<String> required = new LinkedHashSet<>(List.of("required"));
        required.addAll(inherited.sequencedKeySet());
        executor.addModule("dependencies",
                Dependencies.ofEnvironment(environment, repositories, resolvers)
                        .pinning(Pinning.ofEnvironment(environment))
                        .group(group)
                        .pathPlacement(PathPlacement.CLASS_PATH),
                required);
        SequencedSet<String> resolved = new LinkedHashSet<>(List.of("dependencies"));
        resolved.addAll(inherited.sequencedKeySet());
        executor.addStep("teavm", new TeaVM(environment, group, main, file, module), resolved);
    }

    private record Requires(String group, String version) implements BuildStep {

        @Override
        public boolean shouldRun(SequencedMap<String, BuildStepArgument> arguments) {
            return true;
        }

        @Override
        public CompletionStage<BuildStepResult> apply(Executor executor,
                                                      BuildStepContext context,
                                                      SequencedMap<String, BuildStepArgument> arguments)
                throws IOException {
            SequencedProperties requires = new SequencedProperties();
            for (String artifact : TOOLS) {
                requires.setProperty(group + "/runtime/maven/org.teavm/" + artifact + "/" + version, "");
            }
            requires.store(context.next().resolve(BuildStep.REQUIRES));
            return CompletableFuture.completedStage(new BuildStepResult(true));
        }
    }
}

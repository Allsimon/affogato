package affogato.teavm;

import module build.jenesis;
import module java.base;
import build.jenesis.step.Dependencies;
import build.jenesis.step.ProcessBuildStep;
import build.jenesis.step.ProcessHandler;

/**
 * Runs TeaVM on the module's jar and its runtime dependencies, writing javascript/{@code file}. TeaVM's jars are no
 * modules, so the plugin's layer cannot load them: a JVM of their own runs {@link #RUNNER}.
 */
final class TeaVM extends ProcessBuildStep {

    @Serial
    private static final long serialVersionUID = 1L;

    static final String JAVASCRIPT = "javascript/";

    private static final String RUNNER = """
            import java.io.File;
            import java.net.URL;
            import java.net.URLClassLoader;
            import java.util.ArrayList;
            import java.util.List;
            import org.teavm.backend.javascript.JSModuleType;
            import org.teavm.tooling.ConsoleTeaVMToolLog;
            import org.teavm.tooling.TeaVMProblemRenderer;
            import org.teavm.tooling.TeaVMTargetType;
            import org.teavm.tooling.TeaVMTool;
            import org.teavm.vm.TeaVMOptimizationLevel;

            public class Runner {
                public static void main(String[] args) throws Exception {
                    List<File> classPath = new ArrayList<>();
                    for (int i = 4; i < args.length; i++) classPath.add(new File(args[i]));
                    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
                        classPath.add(new File(entry));
                    }
                    URL[] urls = new URL[classPath.size()];
                    for (int i = 0; i < urls.length; i++) urls[i] = classPath.get(i).toURI().toURL();
                    TeaVMTool tool = new TeaVMTool();
                    tool.setLog(new ConsoleTeaVMToolLog(false));
                    tool.setTargetType(TeaVMTargetType.JAVASCRIPT);
                    tool.setTargetDirectory(new File(args[0]));
                    tool.setTargetFileName(args[1]);
                    tool.setMainClass(args[2]);
                    tool.setJsModuleType(JSModuleType.valueOf(args[3]));
                    tool.setOptimizationLevel(TeaVMOptimizationLevel.ADVANCED);
                    tool.setObfuscated(true);
                    tool.setClassLoader(new URLClassLoader(urls, Runner.class.getClassLoader()));
                    tool.setClassPath(classPath);
                    tool.generate();
                    TeaVMProblemRenderer.describeProblems(
                            tool.getDependencyInfo().getCallGraph(), tool.getProblemProvider(), tool.getLog());
                    if (!tool.getProblemProvider().getSevereProblems().isEmpty()) System.exit(1);
                }
            }
            """;

    private final String group, main, file, module;

    TeaVM(Environment environment, String group, String main, String file, String module) {
        super("teavm", ProcessHandler.OfProcess.ofJavaMain(), Terms.ofEnvironment(environment, "teavm"));
        this.group = group;
        this.main = main;
        this.file = file;
        this.module = module;
    }

    @Override
    protected CompletionStage<List<String>> process(Executor executor,
                                                    BuildStepContext context,
                                                    SequencedMap<String, BuildStepArgument> arguments,
                                                    SequencedMap<String, SequencedMap<String, String>> properties)
            throws IOException {
        List<String> tools = new ArrayList<>(), application = new ArrayList<>();
        for (BuildStepArgument argument : arguments.values()) {
            if (argument.removed()) {
                continue;
            }
            Path artifacts = argument.folder().resolve(BuildStep.ARTIFACTS);
            if (Files.isDirectory(artifacts)) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(artifacts)) {
                    for (Path jar : files) {
                        application.add(jar.toString());
                    }
                }
            }
            for (Path jar : Dependencies.select(argument.folder(), "main", "runtime")) {
                application.add(jar.toString());
            }
            for (Path jar : Dependencies.select(argument.folder(), group, "runtime")) {
                tools.add(jar.toString());
            }
        }
        if (application.isEmpty()) {
            return CompletableFuture.completedStage(null);
        }
        if (tools.isEmpty()) {
            throw new IllegalStateException("No TeaVM jars resolved in " + group);
        }
        Path runner = ProcessHandler.OfProcess.temporary("teavm").resolve("Runner.java");
        Files.writeString(runner, RUNNER);
        List<String> commands = new ArrayList<>(List.of("-cp", String.join(File.pathSeparator, tools),
                runner.toString(),
                Files.createDirectories(context.next().resolve(JAVASCRIPT)).toString(),
                file,
                main,
                module));
        commands.addAll(application);
        return CompletableFuture.completedStage(commands);
    }
}

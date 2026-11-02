package affogato.teavm;

import module build.jenesis;
import module java.base;

public class TeaVMFactory implements BuildModuleFactory {

    @Override
    public BuildExecutorModule create(Environment environment,
                                      SequencedMap<String, String> properties,
                                      Permits permits,
                                      String group,
                                      Map<String, Repository> repositories,
                                      Map<String, Resolver> resolvers) {
        return new TeaVMModule(environment,
                repositories,
                resolvers,
                group + ":tool",
                properties.getOrDefault("version", "0.16.0"),
                required(properties, "main"),
                properties.getOrDefault("file", "classes.js"),
                properties.getOrDefault("module", "UMD"));
    }

    private static String required(SequencedMap<String, String> properties, String key) {
        String value = properties.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("plugin-teavm.properties names no " + key);
        }
        return value;
    }
}

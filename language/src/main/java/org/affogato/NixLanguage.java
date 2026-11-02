package org.affogato;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Option;
import com.oracle.truffle.api.TruffleLanguage;
import org.graalvm.options.OptionCategory;
import org.graalvm.options.OptionDescriptors;
import org.graalvm.options.OptionKey;
import org.graalvm.options.OptionStability;

@TruffleLanguage.Registration(
        id = NixLanguage.ID,
        name = "Nix",
        defaultMimeType = NixLanguage.MIME_TYPE,
        characterMimeTypes = NixLanguage.MIME_TYPE,
        contextPolicy = TruffleLanguage.ContextPolicy.SHARED,
        website = "https://nixos.org/manual/nix/stable/language/")
@Option.Group(NixLanguage.ID)
public final class NixLanguage extends TruffleLanguage<NixContext> {
    public static final String ID = "nix";
    public static final String MIME_TYPE = "application/x-nix";

    @Option(help = "Search path, as in NIX_PATH.", category = OptionCategory.USER, stability = OptionStability.STABLE)
    static final OptionKey<String> NixPath = new OptionKey<>("");

    @Option(
            help = "Value of builtins.currentSystem.",
            category = OptionCategory.USER,
            stability = OptionStability.STABLE)
    static final OptionKey<String> System = new OptionKey<>("");

    @Option(help = "Nix store directory.", category = OptionCategory.USER, stability = OptionStability.STABLE)
    static final OptionKey<String> StoreDir = new OptionKey<>("");

    @Option(
            help = "Output: eval, strict, json, raw or drv-path.",
            category = OptionCategory.USER,
            stability = OptionStability.STABLE)
    static final OptionKey<String> Output = new OptionKey<>("eval");

    @Option(
            help = "Attribute path to select, as with -A.",
            category = OptionCategory.USER,
            stability = OptionStability.STABLE)
    static final OptionKey<String> Attr = new OptionKey<>("");

    @Option(
            help = "Arguments for auto-calling, as kind\\u0002name\\u0002value entries separated by \\u0001.",
            category = OptionCategory.USER,
            stability = OptionStability.STABLE)
    static final OptionKey<String> Args = new OptionKey<>("");

    @Override
    protected NixContext createContext(Env env) {
        return new NixContext(this, env);
    }

    @Override
    protected OptionDescriptors getOptionDescriptors() {
        return new NixLanguageOptionDescriptors();
    }

    @Override
    protected CallTarget parse(ParsingRequest request) {
        return new EvalRootNode(this, request.getSource()).getCallTarget();
    }
}

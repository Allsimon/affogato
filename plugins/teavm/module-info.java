/** Compiles a module to JavaScript with TeaVM, at its package step. */
module affogato.teavm {
    requires build.jenesis;

    provides build.jenesis.BuildModuleFactory with
            affogato.teavm.TeaVMFactory;
}

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import org.affogato.Interpreter;

/**
 * Evaluates an expression repeatedly in one JVM, each time in a fresh context as the language server does, and
 * prints the time and allocation per evaluation once warm.
 *
 * <p>{@code java --module-path JARS --add-modules org.affogato scripts/WarmEval.java NIXPATH EXPECTED [WARMUP [RUNS
 * [EXPR]]]}; RUNS 0 loops until killed, for profiling. Prints {@code WARM pid=...} on stderr after the warmup.
 */
public final class WarmEval {
    public static void main(String[] a) throws Exception {
        String nixPath = a[0];
        String expected = a[1];
        int warmup = a.length > 2 ? Integer.parseInt(a[2]) : 50;
        int runs = a.length > 3 ? Integer.parseInt(a[3]) : 200;
        String expr = a.length > 4 ? a[4] : "(import <nixpkgs> {}).hello.drvPath";
        var options = new Interpreter.Options(nixPath, "", "", Interpreter.Output.EVAL, "", List.of());
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

        for (int i = 0; i < warmup; i++) check(Interpreter.evaluate(options, null, expr, 0), expected);
        System.err.println("WARM pid=" + ProcessHandle.current().pid());
        if (runs == 0) {
            while (true) check(Interpreter.evaluate(options, null, expr, 0), expected);
        }

        double[] ms = new double[runs];
        long bytes0 = threads.getTotalThreadAllocatedBytes();
        for (int i = 0; i < runs; i++) {
            long t = System.nanoTime();
            String r = Interpreter.evaluate(options, null, expr, 0);
            ms[i] = (System.nanoTime() - t) / 1e6;
            check(r, expected);
        }
        long bytes = (threads.getTotalThreadAllocatedBytes() - bytes0) / runs;
        Arrays.sort(ms);
        System.out.printf(
                "median %.1f ms  p10 %.1f  p90 %.1f  alloc %.1f MB/eval  (%d runs after %d warmup)%n",
                ms[runs / 2], ms[runs / 10], ms[runs * 9 / 10], bytes / 1e6, runs, warmup);
    }

    private static void check(String result, String expected) {
        if (!result.equals(expected)) throw new IllegalStateException("got " + result + ", expected " + expected);
    }
}

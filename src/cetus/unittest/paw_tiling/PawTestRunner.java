package cetus.unittest.paw_tiling;

import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

/** Runs all PAW tiling unit tests; exit code 0 iff everything passes. */
public class PawTestRunner {
    public static void main(String[] args) {
        Result result = JUnitCore.runClasses(
                DirectionVectorLemmasTest.class,
                TileSizeMathTest.class,
                ReuseOrderAnalyzerTest.class,
                EmptyDvAuditorTest.class);
        for (Failure failure : result.getFailures()) {
            System.out.println(failure.toString());
            if (failure.getException() != null) {
                failure.getException().printStackTrace(System.out);
            }
        }
        System.out.println(String.format("PAW unit tests: %d run, %d failed",
                result.getRunCount(), result.getFailureCount()));
        System.exit(result.wasSuccessful() ? 0 : 1);
    }
}

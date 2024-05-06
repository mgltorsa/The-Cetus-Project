package AOTEnvironment.planner.application;

import cetus.hir.Program;

public class SourceFilesReader {

    public static final String PROGRAM_DEPENDENCIES_ENV = "PROGRAM_DEPS";

    private Program program;

    public SourceFilesReader(Program program) {
        this.program = program;
    }

    public Program readSourceCodeDependencies() {
        String allDepPaths = System.getenv(PROGRAM_DEPENDENCIES_ENV);
        if (allDepPaths == null) {
            return program;
        }
        String[] depPaths = allDepPaths.split(":");
        return readSourceCodeDependencies(depPaths);
    }

    public Program readSourceCodeDependencies(String... paths) {

        return program;
    }

}

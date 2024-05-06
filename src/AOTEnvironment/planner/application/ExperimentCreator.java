package AOTEnvironment.planner.application;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import AOTEnvironment.planner.domain.ExperimentalSection;
import cetus.exec.Driver;
import cetus.hir.CompoundStatement;
import cetus.hir.DFIterator;
import cetus.hir.ForLoop;
import cetus.hir.FunctionCall;
import cetus.hir.IRTools;
import cetus.hir.OmpAnnotation;
import cetus.hir.PrintTools;
import cetus.hir.Traversable;

public class ExperimentCreator {

    private ExperimentPreprocessor preprocessor;
    private ExperimentFileCreator creator;

    public ExperimentCreator(ExperimentPreprocessor preprocessor,
            ExperimentFileCreator creator) {
        this.preprocessor = preprocessor;
        this.creator = creator;
    }

    public String createExperimentFile(ExperimentalSection experimentalSection) {
        // String experimentalFileBasePath =
        // preprocessor.createExperimetalFileBase(experimentalSection);
        writeProperties(experimentalSection);
        // String experimentalFilePath =
        // creator.createExperimentalFile(experimentalFileBasePath);
        // return experimentalFilePath;
        return "";
    }

    public void writeProperties(ExperimentalSection experimentalSection) {
        // TODO: Validate getOptionValue get any value or it filter them
        String prof = Driver.getOptionValue("aot");
        boolean printPatterns = false;
        if (prof != null && !prof.isEmpty() && !prof.equals("1")) {
            printPatterns = true;
        }
        try {

            if (printPatterns) {
                printPatterns(experimentalSection);
                return;
            }
            printLoopLineCodes(experimentalSection);
        } catch (Exception e) {
            // TODO: handle exception
            e.printStackTrace();
        }
    }

    private boolean isParallelRegion(Traversable t) {
        List<OmpAnnotation> annots = IRTools.collectPragmas(t, OmpAnnotation.class, "parallel");
        if (!(t instanceof CompoundStatement)) {
            return false;
        }
        boolean hasSeveralLoops = false;
        int loopsCounter = 0;
        for (Traversable child : t.getChildren()) {
            if (!(child instanceof ForLoop)) {
                continue;
            }
            loopsCounter++;
        }
        hasSeveralLoops = loopsCounter > 1;
        return !annots.isEmpty() && hasSeveralLoops;
    }

    private void printPatterns(ExperimentalSection experimentalSection) throws Exception {

        String kernel = Driver.getOptionValue("kernel");
        String routine = Driver.getOptionValue("routine");
        
        File file = new File(String.format("patterns/%s_%s_patterns.csv", kernel, routine));
        if (!file.exists()) {
            file.getParentFile().mkdirs();
            file.createNewFile();
        }
        String appendMode = Driver.getOptionValue("append");
        boolean append = appendMode != null && !appendMode.isEmpty();
        FileWriter fw = new FileWriter(file, append);
        PrintWriter pw = new PrintWriter(fw);
        if (!append) {
            pw.write("benchmark;function;subroutine;loopId;patterns\n");
        }

        int loopId = 1;
        for (Traversable t : experimentalSection.getContent()) {

            if (!(t instanceof ForLoop)) {
                boolean isParallelRegion = isParallelRegion(t);
                if (!(t instanceof CompoundStatement) || !isParallelRegion) {
                    continue;
                } else {
                    Set<String> foundPatterns = new HashSet<>();
                    if (isParallelRegion) {
                        foundPatterns.add("Parallel Regions Enclosing Multiple Parallel Loops");
                    }
                    for (Traversable child : t.getChildren()) {
                        if (!(child instanceof ForLoop)) {
                            continue;
                        }
                        printPerLoopPatterns(child, pw, loopId, foundPatterns);
                        loopId++;
                    }

                }
                continue;
            }

            printPerLoopPatterns(t, pw, loopId, new HashSet<>());
            loopId++;

        }

        pw.close();
    }

    public void printPerLoopPatterns(Traversable t, PrintWriter pw, int loopId, Set<String> previousPatterns) {

        Set<String> foundPatterns = new HashSet<>();
        foundPatterns.addAll(previousPatterns);

        String bench = Driver.getOptionValue("bench");

        String kernel = Driver.getOptionValue("kernel");
        String routine = Driver.getOptionValue("routine");

        ForLoop fLoop = (ForLoop) t;
        System.out.println(fLoop.toString());
        boolean hasNoWait = !IRTools.collectPragmas(t, OmpAnnotation.class, "nowait").isEmpty();

        if (hasNoWait) {
            foundPatterns.add("NOWAIT -- Eliminating Barrier Synchronizations");
        }

        boolean hasReduction = !IRTools.collectPragmas(t, OmpAnnotation.class, "reduction").isEmpty();

        if (hasReduction) {
            foundPatterns.add("Array Reductions");
        }

        boolean isParallelFor = !IRTools.collectPragmas(t, OmpAnnotation.class, "for").isEmpty();

        if (isParallelFor) {
            DFIterator<FunctionCall> fcalls = new DFIterator<>(fLoop, FunctionCall.class);
            if (fcalls.hasNext()) {
                foundPatterns.add("Parallelizing Loops Containing Function Calls");

            }
            if (isOutermostParallelLoop(fLoop)) {

                foundPatterns.add("Parallelizing Nested Loops at the Outermost Level Possible");

            }
        }
        List<OmpAnnotation> scheduleAnnotations = IRTools.collectPragmas(t, OmpAnnotation.class, "schedule");
        for (OmpAnnotation ompAnnotation : scheduleAnnotations) {
            String scheduleOpt = ompAnnotation.get("schedule");
            if (scheduleOpt != null && !scheduleOpt.isEmpty() && scheduleOpt.contains("dynamic")) {
                foundPatterns.add("Avoiding Load Imbalance Through Dynamic Scheduling");
            }
        }

        String patterns = String.join(", ", new ArrayList<>(foundPatterns));

        pw.write(String.format("%s;%s;%s;LOOP_%d;%s\n", bench, kernel, routine, loopId,
                patterns));
    }

    private boolean isOutermostParallelLoop(ForLoop floop) {
        return floop.getAnnotation(OmpAnnotation.class, "for") != null;
    }

    public void printLoopLineCodes(ExperimentalSection experimentalSection) throws Exception {
        String routine = Driver.getOptionValue("routine");
        String kernel = Driver.getOptionValue("kernel");

        File file = new File(String.format("sizes/%s_%s_lines.csv", kernel, routine));
        if (!file.exists()) {
            // if file does not exist create the file and the containing folder if necessary
            file.getParentFile().mkdirs();
            file.createNewFile();
        }

        String appendMode = Driver.getOptionValue("append");
        boolean append = appendMode != null && !appendMode.isEmpty();
        FileWriter fw = new FileWriter(file, append);

        PrintWriter pw = new PrintWriter(fw);

        if (!append) {
            pw.write("benchmark;function;subroutine;loopId;size\n");
        }
        int loopId = 1;
        for (Traversable t : experimentalSection.getContent()) {
            if (!(t instanceof ForLoop)) {
                if (!isParallelRegion(t)) {
                    continue;
                }
                for (Traversable child : t.getChildren()) {
                    if (!(child instanceof ForLoop)) {
                        continue;
                    }
                    printLoopLineCodes((ForLoop) child, loopId, pw);
                    loopId++;
                }

                continue;
            }

            printLoopLineCodes((ForLoop) t, loopId, pw);
            loopId++;

        }

        pw.close();

    }

    private void printLoopLineCodes(ForLoop t, int loopId, PrintWriter pw) {
        String bench = Driver.getOptionValue("bench");
        String routine = Driver.getOptionValue("routine");
        String kernel = Driver.getOptionValue("kernel");

        ForLoop fLoop = (ForLoop) t;
        String floopStr = fLoop.toString();
        floopStr = floopStr.replaceAll("\\{\n", "{");
        if (PrintTools.getVerbosity() > 3) {

            System.out.println(floopStr);
        }
        int codeLines = floopStr.length();
        pw.write(String.format("%s;%s;%s;LOOP_%d;%d\n", bench, kernel, routine, loopId,
                codeLines));
    }

}

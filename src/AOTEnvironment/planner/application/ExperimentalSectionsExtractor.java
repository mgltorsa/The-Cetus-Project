package AOTEnvironment.planner.application;

import java.util.ArrayList;
import java.util.List;

import AOTEnvironment.planner.domain.ExperimentalSection;
import cetus.hir.Annotatable;
import cetus.hir.AnnotationStatement;
import cetus.hir.DFIterator;
import cetus.hir.PragmaAnnotation;
import cetus.hir.PrintTools;
import cetus.hir.Program;
import cetus.hir.Traversable;

public class ExperimentalSectionsExtractor {

    private Program program;

    public ExperimentalSectionsExtractor(Program program) {
        this.program = program;
    }

    public List<ExperimentalSection> get() {
        System.out.println(program);
        List<ExperimentalSection> targetSections = new ArrayList<ExperimentalSection>();
        DFIterator<Annotatable> iter = new DFIterator<Annotatable>(program, Annotatable.class);
        String experimentalSectionBaseName = "experiment-";
        int experimentVersion = 1;
        while (iter.hasNext()) {
            Annotatable at = iter.next();
            List<PragmaAnnotation> pragmas = at.getAnnotations(PragmaAnnotation.class);
            for (int i = 0; i < pragmas.size(); i++) {
                PragmaAnnotation pragma = pragmas.get(i);
                for (Object values : pragma.values()) {
                    if (values == null) {
                        continue;
                    }
                    if (!values.toString().contains("experimental section start")) {
                        continue;
                    }
                    // TODO: GET NAME FROM values.toString or from pragma
                    String[] pragmaContent = values.toString().split(" ");
                    String experimentalSectionName = experimentalSectionBaseName + experimentVersion;

                    if (pragmaContent.length > 3) {
                        experimentalSectionName = pragmaContent[4].trim();
                    } else {
                        experimentVersion++;
                    }
                    Traversable experimentalSectionParent = at.getParent();
                    List<Traversable> experimentalSectionContent = getExperimentalSectionContent(
                            experimentalSectionParent);
                    if (experimentalSectionContent == null) {
                        continue;
                    }
                    ExperimentalSection experimentalSection = new ExperimentalSection(experimentalSectionName,
                            experimentalSectionParent, experimentalSectionContent);
                    targetSections.add(experimentalSection);
                }

            }
        }

        return targetSections;
    }

    private List<Traversable> getExperimentalSectionContent(Traversable codeSection) {
        return getExperimentalSectionContent(codeSection, "experimental section start", "experimental section stop");

    }

    private List<Traversable> getExperimentalSectionContent(Traversable parentCodeSection, String startPragma,
            String endPragma) {

        List<Traversable> experimentalSectionContent = new ArrayList<Traversable>();
        // found position of startPragma in the t obj
        boolean foundExperimentStartPragma = false;
        boolean foundExperimentStopPragma = false;

        for (int i = 0; i < parentCodeSection.getChildren().size(); i++) {
            Traversable child = parentCodeSection.getChildren().get(i);

            if (!(child instanceof AnnotationStatement)) {
                if (!foundExperimentStartPragma) {
                    continue;
                }
                experimentalSectionContent.add(child);
                continue;
            }

            // `AnnotationStatement` is a class in the Cetus library that represents a statement in the
            // code that contains an annotation. In this context, it is used to identify statements in
            // the code that have annotations related to experimental sections. The
            // `AnnotationStatement` class allows the program to extract and analyze annotations
            // attached to specific statements in the code.
            AnnotationStatement a = (AnnotationStatement) child;

            if (a.toString().contains(startPragma) && foundExperimentStartPragma) {
                if (PrintTools.shouldErrorVerbose()) {
                    System.err.println("MALFORMED_EXPERIMENTAL_SECTION:");
                    System.err.println("START_PRAGMA_APPEAR_TWICE_OR_MORE");
                    System.err.println("TARGET_CODE_SECTION:");
                    System.err.println(parentCodeSection);
                }
                return null;
            }
            if (a.toString().contains(startPragma)) {
                foundExperimentStartPragma = true;
                continue;
            }
            if (a.toString().contains(endPragma) && foundExperimentStopPragma) {
                if (PrintTools.shouldErrorVerbose()) {

                    System.err.println("MALFORMED_EXPERIMENTAL_SECTION:");
                    System.err.println("STOP_PRAGMA_APPEAR_TWICE_OR_MORE");
                    System.err.println("TARGET_CODE_SECTION:");
                    System.err.println(parentCodeSection);
                }
                return null;
            }
            if (a.toString().contains(endPragma)) {
                foundExperimentStopPragma = true;
                break;

            }

            if (foundExperimentStartPragma) {
                experimentalSectionContent.add(child);
            }

        }

        if (!foundExperimentStartPragma || !foundExperimentStopPragma)

        {
            if (PrintTools.shouldErrorVerbose()) {
                System.err.println("MALFORMED_EXPERIMENTAL_SECTION:");
                System.err.println("START_OR_STOP_PRAGMA_NOT_FOUND_IN_TARGET_CODE_SECTION:");
                System.err.println(parentCodeSection);
            }
            return null;
        }
        return experimentalSectionContent;

    }
}

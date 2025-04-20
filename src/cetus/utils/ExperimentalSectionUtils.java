package cetus.utils;

import java.util.ArrayList;
import java.util.List;

import cetus.hir.Annotatable;
import cetus.hir.AnnotationStatement;
import cetus.hir.DFIterator;
import cetus.hir.PragmaAnnotation;
import cetus.hir.Program;
import cetus.hir.Traversable;

public class ExperimentalSectionUtils {

    public static List<List<Traversable>> findExperimentalSections(Program program) {

        List<List<Traversable>> sections = new ArrayList<List<Traversable>>();

        DFIterator<Annotatable> iter = new DFIterator<Annotatable>(program, Annotatable.class);

        while (iter.hasNext()) {
            Annotatable at = iter.next();
            List<PragmaAnnotation> pragmas = at.getAnnotations(PragmaAnnotation.class);
            for (int i = 0; i < pragmas.size(); i++) {
                PragmaAnnotation pragma = pragmas.get(i);
                if (pragma == null || pragma.getName() == null)
                    continue;

                String pragmaName = pragma.getName().toLowerCase();
                if (!pragmaName.contains("experimental section start"))
                    continue;

                sections.add(collectSection((AnnotationStatement) at));
            }
        }
        return sections;
    }

    private static List<Traversable> collectSection(AnnotationStatement start) {
        List<Traversable> section = new ArrayList<Traversable>();
        DFIterator<Traversable> iter = new DFIterator<Traversable>(start.getParent(), Traversable.class);
        boolean stopCollecting = false;
        boolean startCollecting = false;
        while (iter.hasNext()) {
            if (stopCollecting)
                break;

            Traversable t = iter.next();

            if (!(t instanceof AnnotationStatement)) {
                if (!startCollecting)
                    continue;

                section.add(t);
            } else {
                AnnotationStatement as = (AnnotationStatement) t;
                List<PragmaAnnotation> pragmas = as.getAnnotations(PragmaAnnotation.class);
                for (int i = 0; i < pragmas.size(); i++) {
                    PragmaAnnotation pragma = pragmas.get(i);
                    String pragmaName = pragma.getName().toLowerCase();
                    if (pragmaName.contains("experimental section start")) {
                        startCollecting = true;
                        continue;
                    }

                    if (pragmaName.contains("experimental section stop")) {
                        stopCollecting = true;
                        continue;
                    }
                }
            }
        }
        return section;
    }
}
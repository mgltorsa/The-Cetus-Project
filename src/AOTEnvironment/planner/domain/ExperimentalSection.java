package AOTEnvironment.planner.domain;

import java.util.ArrayList;
import java.util.List;

import cetus.hir.CompoundStatement;
import cetus.hir.DFIterator;
import cetus.hir.IRTools;
import cetus.hir.OmpAnnotation;
import cetus.hir.Traversable;

public class ExperimentalSection {

    private String name;
    private Traversable originalParent;
    private List<Traversable> content = new ArrayList<>();

    public ExperimentalSection(String name, Traversable originalParent, List<Traversable> content) {
        this.originalParent = originalParent;
        this.name = name;

        for (Traversable t : content) {
            addStatement(t);
        }
    }

    public String getName() {
        return name;
    }

    public void addStatement(Traversable t) {

        content.add(t);

        // if (t instanceof CompoundStatement) {
        //     List<OmpAnnotation> annots = IRTools.collectPragmas(t, OmpAnnotation.class, "parallel");
        //     if (annots.isEmpty()) {
        //         return;
        //     }
        //     t.getChildren().forEach(child -> {
        //         if (child instanceof Traversable) {
        //             addStatement((Traversable) child);
        //         }
        //     });
        // }
    }

    public List<Traversable> getContent() {
        return content;
    }

    public Traversable getOriginalParent() {
        return originalParent;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Traversable t : content) {
            sb.append(t.toString());
            sb.append("\n");
        }
        return sb.toString();
    }

}

package cetus.transforms.LLMTransformations;

import java.util.ArrayList;
import java.util.List;

import cetus.hir.Traversable;

public class LLMTargetSection {

    private Traversable originalParent;
    private List<Traversable> section;

    public LLMTargetSection(Traversable originalParent) {
        this.originalParent=originalParent;
        section = new ArrayList<>();
    }

    public void addStatement(Traversable t) {
        section.add(t);
    }

    public List<Traversable> getSection() {
        return section;
    }

    public Traversable getOriginalParent() {
        return originalParent;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        for (Traversable t : section) {
            sb.append(t.toString());
            sb.append("\n");
        }
        return sb.toString();
    }
}

package cetus.transforms.LLMTransformations;

import java.util.ArrayList;
import java.util.List;

import cetus.hir.CompoundStatement;
import cetus.hir.Statement;
import cetus.hir.Traversable;

public class LLMTargetSection {

    private List<Traversable> section;

    public LLMTargetSection() {
        section = new ArrayList<>();
    }

    public void addStatement(Traversable t) {
        section.add(t);
    }

    @Override
    public String toString() {
        CompoundStatement codeSection = new CompoundStatement();
        for (Traversable t : section) {
            codeSection.addStatement(((Statement) t).clone());
        }

        return codeSection.toString();
    }

}

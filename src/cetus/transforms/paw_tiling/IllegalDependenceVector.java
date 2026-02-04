package cetus.transforms.paw_tiling;

import cetus.analysis.DependenceVector;
import cetus.hir.ForLoop;

public class IllegalDependenceVector extends Exception {

    public DependenceVector dependenceVector;
    public ForLoop loopNest;

    public IllegalDependenceVector(DependenceVector dependenceVector, ForLoop loopNest) {
        super();
        this.dependenceVector=dependenceVector;
        this.loopNest=loopNest;
    }
}

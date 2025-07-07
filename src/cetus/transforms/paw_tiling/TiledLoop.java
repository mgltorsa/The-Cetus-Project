package cetus.transforms.paw_tiling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import cetus.analysis.DependenceVector;
import cetus.analysis.LoopTools;
import cetus.hir.DFIterator;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.Loop;
import cetus.hir.Statement;

public class TiledLoop extends ForLoop {

    private List<ForLoop> nestedLoops = new ArrayList<>();
    private List<DependenceVector> dependeceVectors = new ArrayList<>();

    private ForLoop originalLoop;
    private List<DependenceVector> originalDvs;

    private ForLoop outermostParallelizableLoop;
    private Map<Expression, Expression> tileSizes;

    public TiledLoop(ForLoop loopNest, List<DependenceVector> dvs)
            throws Exception {
        this(loopNest.getInitialStatement().clone(false),
                loopNest.getCondition().clone(),
                loopNest.getStep().clone(),
                loopNest.getBody().clone(false));

        new DFIterator<Loop>(loopNest, Loop.class).forEachRemaining(loop -> nestedLoops.add(lookupLoop(loop, this)));
        
        setInternalDependenceVectors(dvs);
        this.tileSizes = new HashMap<>();

    }


    public void setOriginalLoop(ForLoop originaLoop) {
        this.originalLoop = originaLoop;
    }

    public ForLoop getOriginalLoop() {
        return originalLoop;
    }

    public void setOriginalDvs(List<DependenceVector> originalDvs) {
        this.originalDvs = originalDvs;
    }

    public List<DependenceVector> getOriginalDvs() {
        return originalDvs;
    }

    public Map<Expression, Expression> getTileSizes() {
        return tileSizes;
    }

    public void setTileSize(Expression indexVariable, Expression tileSize) {
        this.tileSizes.put(indexVariable, tileSize);
    }

    public void setTileSizes(Map<Expression, Expression> tileSizes) {
        this.tileSizes = tileSizes;
    }

    public List<ForLoop> getNestedLoops() {
        return nestedLoops;
    }

    public void calculateOutermostParallelLoop() {

        int loopIdx = -1;

        for (int i = 0; i < nestedLoops.size(); i++) {
            ForLoop curLoop = nestedLoops.get(i);
            int curIdx = i;
            for (DependenceVector dv : dependeceVectors) {
                int direction = dv.getDirection(curLoop);
                if (direction != DependenceVector.equal) {
                    curIdx = -1;
                    break;
                }
            }
            if (curIdx != -1) {
                loopIdx = curIdx;
                break;
            }
        }

        if (loopIdx != -1) {
            this.outermostParallelizableLoop = nestedLoops.get(loopIdx);
        }

    }

    public void setNewDependenceVectors(List<DependenceVector> dvs){
        this.dependeceVectors = dvs;
    }


    private void setInternalDependenceVectors(List<DependenceVector> dvs) throws Exception {
        for (DependenceVector dv : dvs) {

            boolean legal = false;
            boolean isValid = true;
            boolean hasNilDirections = false;

            DependenceVector newDV = new DependenceVector();

            for (Loop dvLoop : dv.getLoops()) {

                Loop loopInNest = lookupLoop(dvLoop, this);
                if (loopInNest == null) {
                    throw new Exception(
                            "Error on setting dvs, a loop in one of the DVs does not correspond with any loop in the loopnest");
                }

                int direction = dv.getDirection(dvLoop);

                if(direction == DependenceVector.nil) {
                    hasNilDirections=true;
                    break;
                }

                if (direction == DependenceVector.greater && !legal) {
                    isValid = false;
                }
                if (direction == DependenceVector.less && !legal) {
                    legal = true;
                }
                newDV.setDirection(loopInNest, direction);
            }

            if(hasNilDirections){
                continue;
            }

            //Is valid and 
            if(isValid && !legal) {
                legal=true;
            }

            newDV.setValid(isValid);
            // System.out.printf("original DV: %s\n", dv.toString());
            // System.out.printf("New DV: %s\n", newDV.toString());
            // if(newDV.toString().contains(".")){
            //     System.out.println("Unexpected");
            // }
            dependeceVectors.add(newDV);
        }
    }

    public void setOutermostParallelizableLoop(int positionOfParallelizableLoop) {
        List<ForLoop> nestedLoops = new ArrayList<>();
        new DFIterator<ForLoop>(this, Loop.class).forEachRemaining(nestedLoops::add);

        if (positionOfParallelizableLoop < 0 || positionOfParallelizableLoop >= nestedLoops.size()) {
            return;
        }
        this.outermostParallelizableLoop = nestedLoops.get(positionOfParallelizableLoop);

        int loopsSize = nestedLoops.size();
        int outermostParLoopIdx = -1;

        if (this.dependeceVectors.size() == 0) {
            return;
        }

        for (int i = 0; i < loopsSize; i++) {
            Loop loop = nestedLoops.get(i);
            outermostParLoopIdx = i;
            for (DependenceVector dv : this.dependeceVectors) {
                int direction = dv.getDirection(loop);
                if (direction != DependenceVector.equal) {
                    outermostParLoopIdx = -1;
                    break;
                }
            }
        }

        if (outermostParLoopIdx != -1) {
            this.outermostParallelizableLoop = nestedLoops.get(outermostParLoopIdx);
        }
    }

    public ForLoop getOutermostParallelizableLoop() {
        return this.outermostParallelizableLoop;
    }

    private ForLoop lookupLoop(Loop loopInDV, ForLoop loopNest) {

        List<ForLoop> nestedLoops = new ArrayList<>();
        new DFIterator<ForLoop>(loopNest, Loop.class).forEachRemaining(nestedLoops::add);

        for (ForLoop loop : nestedLoops) {
            if (areEquals(loopInDV, loop)) {
                return loop;
            }
        }

        return null;
    }

    private boolean areEquals(Loop loop, Loop target) {
        String symbolName = LoopTools.getLoopIndexSymbol(loop).getSymbolName();
        String targetSymbolName = LoopTools.getLoopIndexSymbol(target).getSymbolName();

        return symbolName.equals(targetSymbolName);
    }

    public TiledLoop(Statement init, Expression condition, Expression step, Statement body) {
        super(init, condition, step, body);
    }

    public List<DependenceVector> getDependenceVectors() {
        return dependeceVectors;
    }

    @Override
    public TiledLoop clone(boolean mustHaveAnnotations) {
        ForLoop origLoop = super.clone(mustHaveAnnotations);

        TiledLoop clon = new TiledLoop(origLoop.getInitialStatement().clone(mustHaveAnnotations),
                origLoop.getCondition().clone(),
                origLoop.getStep().clone(),
                origLoop.getBody().clone(mustHaveAnnotations));




        clon.dependeceVectors = this.dependeceVectors;
        clon.nestedLoops = this.nestedLoops;
        clon.tileSizes = new HashMap<>(this.tileSizes);
        clon.calculateOutermostParallelLoop();

        ForLoop parallelLoop = clon.getOutermostParallelizableLoop();
        ForLoop actualParLoop = lookupLoop(parallelLoop, clon);
        clon.outermostParallelizableLoop = actualParLoop;

        return clon;
    }

    @Override
    public TiledLoop clone() {

        return clone(false);
    }

    // public boolean isCrossStripParallel() {

    // Expression expr =
    // LoopTools.getIncrementExpression(outermostParallelizableLoop);

    // return expr.toString().contains(TilingUtils.TILE_SUFFIX);
    // }

}

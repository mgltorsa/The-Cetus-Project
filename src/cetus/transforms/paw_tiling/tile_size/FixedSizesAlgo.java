package cetus.transforms.paw_tiling.tile_size;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import cetus.analysis.LoopTools;
import cetus.hir.DFIterator;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IntegerLiteral;

public class FixedSizesAlgo implements TileSizeSelectionAlgo {

    private class LastValueIterator implements Iterator<Integer> {
        private final int[] values;
        private int index;

        public LastValueIterator(int[] values) {
            this.values = values;
            this.index = 0;
        }

        @Override
        public boolean hasNext() {
            if (index >= values.length) {
                index = values.length - 1;
            }
            return index < values.length;
        }

        @Override
        public Integer next() {
            Integer valuInteger = values[index];
            index = Math.min(index + 1, values.length - 1);
            return valuInteger;
        }

    }

    private LastValueIterator tileSizeIter;

    public FixedSizesAlgo(int[] tileSizes) {
        tileSizeIter = new LastValueIterator(tileSizes);
    }

    @Override
    public Map<Expression, Expression> getTileSizes(ForLoop loopNest) {
        Map<Expression, Expression> tileSizes = new HashMap<>();
        DFIterator<ForLoop> loopIter = new DFIterator<>(loopNest, ForLoop.class);
        while (loopIter.hasNext()) {
            ForLoop loop = loopIter.next();
            Expression indexVar = LoopTools.getIndexVariable(loop);
            if (indexVar == null)
                continue;
            long tileValue = tileSizeIter.next();
            if (tileValue <= 1) {
                continue;
            }
            tileSizes.put(indexVar, new IntegerLiteral(tileValue));
        }
        return tileSizes;
    }

}

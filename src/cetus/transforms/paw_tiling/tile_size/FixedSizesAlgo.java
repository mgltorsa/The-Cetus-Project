package cetus.transforms.paw_tiling.tile_size;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cetus.analysis.LoopTools;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IntegerLiteral;

/**
 * Manual (Fixed) tile sizes from {@code -tileSizes=a,b,...}: values are
 * assigned to the browsed loops in browse order, repeating the last value.
 * Values {@code <= 1} mean "do not tile this loop".
 */
public class FixedSizesAlgo implements TileSizeSelectionAlgo {

    private final int[] tileSizes;

    public FixedSizesAlgo(int[] tileSizes) {
        this.tileSizes = tileSizes == null ? new int[0] : tileSizes;
    }

    @Override
    public Map<Expression, Expression> getTileSizes(ForLoop loopNest,
            List<ForLoop> browseOrder) {
        Map<Expression, Expression> sizes = new LinkedHashMap<>();
        if (tileSizes.length == 0) {
            return sizes;
        }
        int pos = 0;
        for (ForLoop loop : browseOrder) {
            Expression indexVar = LoopTools.getIndexVariable(loop);
            if (indexVar == null) {
                continue;
            }
            int value = tileSizes[Math.min(pos, tileSizes.length - 1)];
            pos++;
            if (value <= 1) {
                continue;
            }
            sizes.put(indexVar, new IntegerLiteral(value));
        }
        return sizes;
    }
}

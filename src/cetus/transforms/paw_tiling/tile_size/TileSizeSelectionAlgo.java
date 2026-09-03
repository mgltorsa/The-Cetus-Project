package cetus.transforms.paw_tiling.tile_size;

import java.util.List;
import java.util.Map;

import cetus.hir.Expression;
import cetus.hir.ForLoop;

/**
 * Raw tile-size selection (thesis sec. 3.3). Implementations return raw
 * sizes T per original loop index variable; the parallel load-balancing
 * substitution (BalancedTileCalculator) is applied afterwards by the pass
 * and is not part of these algorithms.
 */
public interface TileSizeSelectionAlgo {

    /**
     * @param loopNest    the original (untiled) perfect nest
     * @param browseOrder loops selected for tiling, in reuse-browse order
     *                    (subset of the nest's loops)
     * @return map original-loop index variable -> raw tile size; sizes
     *         {@code <= 1} mean "do not tile this loop"
     */
    Map<Expression, Expression> getTileSizes(ForLoop loopNest,
            List<ForLoop> browseOrder);
}

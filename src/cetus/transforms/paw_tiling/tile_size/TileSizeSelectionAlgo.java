package cetus.transforms.paw_tiling.tile_size;

import java.util.Map;

import cetus.hir.Expression;
import cetus.hir.ForLoop;

public interface TileSizeSelectionAlgo {
    public Map<Expression, Expression> getTileSizes(ForLoop loopNest);
}

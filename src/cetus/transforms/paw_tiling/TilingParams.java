package cetus.transforms.paw_tiling;

import cetus.exec.Driver;
import cetus.hir.PrintTools;
import cetus.transforms.paw_tiling.tile_size.FixedSizesAlgo;
import cetus.transforms.paw_tiling.tile_size.LRWSelectionAlgo;
import cetus.transforms.paw_tiling.tile_size.NTSelectionAlgo;
import cetus.transforms.paw_tiling.tile_size.TileSizeSelectionAlgo;

public class TilingParams {

    public enum SelectionAlgorithm {
        LRW,
        NT,
        FIXED;

        public static String getInfo(SelectionAlgorithm algo) {
            switch (algo) {
                case LRW:
                    return "To calculates Largest square block without self interference";
                case NT:
                    return "To apply a Naive Tile algorithm based on access per loop iteration";
                case FIXED:
                    return "To use fixed tile sizes";
                default:
                    return "Unknown algorithm";
            }
        }

    }

    public final static String CORES_PARAM_NAME = "cores";
    public final static String CACHE_PARAM_NAME = "cacheSize";
    public final static String CACHE_LINE_PARAM_NAME = "cacheLine";
    public final static String SELECTION_ALGORITHM_PARAM_NAME = "selection";
    public final static String FIXED_TILE_SIZE_PARAM_NAME = "tileSizes";
    public final static String TILING_PROFITABILITY_PARAM_NAME = "tile-profitability";
    public final static String TILING_LEVEL_PARAM_NAME = "tilingLevel";

    public final static String TILING_LEVEL_PARAM_DESCR = "Tiling depth d: maximum number of loops"
            + " to strip-mine per nest, browsed in decreasing-reuse order."
            + " 0 (default) means the full nest depth.";
    public final static int DEFAULT_TILING_LEVEL = 0;
    
    public final static String TILING_PROFITABILITY_PARAM_DESCR = "To check profitability of tiling and then generate the tiled code.\n'0' - Disable\n'1' - Enable (Default)";
    public final static String DEFAULT_TILING_PROFITABILITY = "1";
    

    public final static String CORES_PARAM_DESCR = "To define the number of cores to be used for parallel aware tiling. (default=4). \nThis data is used for load balancing across cores.";
    public final static int DEFAULT_PROCESSORS = 4;

    public final static String CACHE_PARAM_DESCR = "Define the cache size in KiB. (default=32768 ~32MiB). \nUsed for calculating tile sizes.";
    public final static long DEFAULT_CACHE_SIZE_IN_KB = 32 * 1024; // 32 KiBi = 32 * 1024 bits

    public final static String CACHE_LINE_PARAM_DESCR = "Cache line size in bytes";
    public final static int DEFAULT_CACHE_ALIGNMENT_IN_BYTES = 64; // common cache alignment

    public final static String SELECTION_ALGORITHM_DESCR = String.format("LRW:  %s\nNT: %s",
            SelectionAlgorithm.getInfo(SelectionAlgorithm.LRW),
            SelectionAlgorithm.getInfo(SelectionAlgorithm.NT));
    public final static SelectionAlgorithm DEFAULT_SELECTION_ALGORITHM = SelectionAlgorithm.NT;

    public final static String FIXED_TILE_SIZE_DESCR = "If defined, the tile sizes will be fixed to the given values. \nThe values should be separated by commas. \nExample: 32,64,128. Not defined by default.";

    public final static int MAX_ITERATIONS_TO_PARALLELIZE = 100000;

    private int maxIterationsToParallelize = MAX_ITERATIONS_TO_PARALLELIZE;
    private int numOfProcessors = DEFAULT_PROCESSORS;
    private long cacheSizeInKB = DEFAULT_CACHE_SIZE_IN_KB;
    private int cacheLineInBytes = DEFAULT_CACHE_ALIGNMENT_IN_BYTES;
    private int tilingLevel = DEFAULT_TILING_LEVEL;
    private boolean enableTilingProfitability = true;
    private TileSizeSelectionAlgo tileSizeSelectionAlgo;
    private SelectionAlgorithm selectionAlgo;
    private int[] fixedTileSizes;

    private static TilingParams _instance;

    private TilingParams() {
        super();
        try {
            numOfProcessors = Integer.parseInt(Driver.getOptionValue(CORES_PARAM_NAME));
            assert numOfProcessors > 0;
        } catch (Exception e) {
            PrintTools.print(
                    "Error on setting num of processors. The default value: " + DEFAULT_PROCESSORS + " will be used",
                    2);
        }

        try {
            cacheSizeInKB = Integer.parseInt(Driver.getOptionValue(CACHE_PARAM_NAME));
            assert cacheSizeInKB > 0;
        } catch (Exception e) {
            PrintTools.print(
                    "Error on setting cache size. The default value: " + DEFAULT_CACHE_SIZE_IN_KB + " will be used", 2);
        }

        try {
            cacheLineInBytes = Integer.parseInt(Driver.getOptionValue(CACHE_LINE_PARAM_NAME));
            assert cacheLineInBytes > 0;
        } catch (Exception e) {
            PrintTools.print(
                    "Error on setting cache line size. The default value: " + CACHE_LINE_PARAM_NAME + " will be used",
                    2);
        }

        maxIterationsToParallelize = MAX_ITERATIONS_TO_PARALLELIZE;

        try {
            String tilingLevelStr = Driver.getOptionValue(TILING_LEVEL_PARAM_NAME);
            if (tilingLevelStr != null) {
                tilingLevel = Integer.parseInt(tilingLevelStr);
            }
            assert tilingLevel >= 0;
        } catch (Exception e) {
            PrintTools.print("Error on setting tiling level. The default value: "
                    + DEFAULT_TILING_LEVEL + " (full depth) will be used", 2);
            tilingLevel = DEFAULT_TILING_LEVEL;
        }

        
        String fixedTileSizeOption = Driver.getOptionValue(FIXED_TILE_SIZE_PARAM_NAME);
        if(fixedTileSizeOption != null) {
            String[] tileSizesStr = fixedTileSizeOption.split(",");
            fixedTileSizes = new int[tileSizesStr.length];
            for (int i = 0; i < tileSizesStr.length; i++) {
                fixedTileSizes[i] = Integer.parseInt(tileSizesStr[i]);
            }
        } 

        String selectionAlgoStr = Driver.getOptionValue(SELECTION_ALGORITHM_PARAM_NAME);
        selectionAlgo = DEFAULT_SELECTION_ALGORITHM;
        if (selectionAlgoStr != null) {
            selectionAlgo = SelectionAlgorithm.valueOf(selectionAlgoStr.toUpperCase());
        }
        
        if(fixedTileSizes != null) {
            selectionAlgo = SelectionAlgorithm.FIXED;
        }

        tileSizeSelectionAlgo = createSelectionAlgo(selectionAlgo);

        String enableTilingProfitabilityStr = Driver.getOptionValue(TILING_PROFITABILITY_PARAM_NAME);
        if (enableTilingProfitabilityStr != null) {
            enableTilingProfitability = Integer.parseInt(enableTilingProfitabilityStr) >= 1;
        }
    }

    private TileSizeSelectionAlgo createSelectionAlgo(SelectionAlgorithm algorithm) {
        // Serial tiling (-paw_tiling=0) still runs this pass, but only one
        // thread executes the nest: do not shrink tiles as if P cores shared L3.
        int sizeCores = tileSizeModelCores();
        switch (algorithm) {
            case LRW:
                return new LRWSelectionAlgo(cacheSizeInKB, cacheLineInBytes, sizeCores);
            case NT:
                return new NTSelectionAlgo(cacheSizeInKB, cacheLineInBytes, sizeCores);
            case FIXED:
                return new FixedSizesAlgo(fixedTileSizes);
            default:
                PrintTools.printlnDebug("Unknown tile size selection algorithm: " + algorithm);
                return new LRWSelectionAlgo(cacheSizeInKB, cacheLineInBytes, sizeCores);
        }
    }

    /**
     * Cores used in NT/LRW capacity models. Parallel-aware tiling uses
     * {@code -cores}; serial tiling always models one thread.
     */
    int tileSizeModelCores() {
        String paw = Driver.getOptionValue("paw_tiling");
        if ("0".equals(paw)) {
            return 1;
        }
        return numOfProcessors;
    }

    /** Tiling depth d; 0 means "full nest depth". */
    public int getTilingLevel() {
        return tilingLevel;
    }

    /** Test hook: drops the singleton so option changes are re-read. */
    public static void reset() {
        _instance = null;
    }

    public int getNumOfProcessors() {
        return numOfProcessors;
    }

    public long getCacheSizeInKB() {
        return cacheSizeInKB;
    }

    public int getCacheLineInBytes() {
        return cacheLineInBytes;
    }

    public TileSizeSelectionAlgo getTileSizeSelectionAlgo() {
        return tileSizeSelectionAlgo;
    }

    public int getMaxIterationsToParallelize() {
        return maxIterationsToParallelize;
    }

    public boolean isEnableTilingProfitability() {
        return enableTilingProfitability;
    }

    public SelectionAlgorithm getTypeSelectionAlgo() {
        return selectionAlgo;
    }

    public static TilingParams getTilingParams() {
        if (_instance == null) {
            _instance = new TilingParams();
        }
        return _instance;
    }

    
}

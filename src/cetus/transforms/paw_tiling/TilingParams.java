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
        long cacheSizeInKiB = 1024; // Example cache size in KiB
        int cacheLineSizeInBytes = 64; // Example cache line size in bytes

        switch (algorithm) {
            case LRW:
                return new LRWSelectionAlgo(cacheSizeInKiB, cacheLineSizeInBytes);
            case NT:
                return new NTSelectionAlgo(cacheSizeInKiB, cacheLineSizeInBytes);
            case FIXED:
                return new FixedSizesAlgo(fixedTileSizes);
            default:
                PrintTools.printlnDebug("Unknown tile size selection algorithm: " + algorithm);
                return new LRWSelectionAlgo(cacheSizeInKiB, cacheLineSizeInBytes);
        }
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

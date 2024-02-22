package cetus.transforms.tiling.pawTiling;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;

import cetus.analysis.AnalysisPass;
import cetus.analysis.ArrayPrivatization;
import cetus.analysis.DependenceVector;
import cetus.analysis.LoopParallelizationPass;
import cetus.analysis.LoopTools;
import cetus.analysis.Reduction;
import cetus.analysis.loopTools.LoopInstructionsUtils;
import cetus.codegen.CodeGenPass;
import cetus.codegen.ompGen;
import cetus.exec.CommandLineOptionSet;
import cetus.exec.Driver;
import cetus.hir.ArrayAccess;
import cetus.hir.AssignmentExpression;
import cetus.hir.BinaryExpression;
import cetus.hir.BinaryOperator;
import cetus.hir.CompoundStatement;
import cetus.hir.DFIterator;
import cetus.hir.Declaration;
import cetus.hir.Declarator;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IDExpression;
import cetus.hir.Identifier;
import cetus.hir.IfStatement;
import cetus.hir.Initializer;
import cetus.hir.IntegerLiteral;
import cetus.hir.Loop;
import cetus.hir.NameID;
import cetus.hir.Program;
import cetus.hir.Statement;
import cetus.hir.Symbol;
import cetus.hir.SymbolTable;
import cetus.hir.Symbolic;
import cetus.hir.Traversable;
import cetus.transforms.LoopInterchange;
import cetus.transforms.TransformPass;
import cetus.transforms.tiling.TiledLoop;
import cetus.transforms.tiling.TilingUtils;
import cetus.transforms.tiling.pawTiling.logging.PawTilingFormatter;
import cetus.transforms.tiling.pawTiling.optimizer.VersionChooser;
import cetus.transforms.tiling.pawTiling.optimizer.providers.ComplexChooserProvider;
import cetus.transforms.tiling.pawTiling.optimizer.providers.NthGuidedChooserProvider;
import cetus.transforms.tiling.pawTiling.optimizer.providers.VersionChooserProvider;
import cetus.utils.ArrayUtils;
import cetus.utils.CacheUtils;
import cetus.utils.DataReuseAnalysisUtils;
import cetus.utils.VariableDeclarationUtils;
import cetus.utils.reuseAnalysis.DataReuseAnalysis;
import cetus.utils.reuseAnalysis.SimpleReuseAnalysisFactory;
import cetus.utils.reuseAnalysis.factory.ReuseAnalysisFactory;

public class ParallelAwareTilingPass extends TransformPass {

    public final static String PAW_TILING = "paw-tiling";

    public final static int DISTRIBUTED_CACHE_OPTION = 1;
    public final static int NON_DISTRIBUTED_CACHE_OPTION = 0;

    public final static String CORES_PARAM_NAME = "cores";
    public final static String CACHE_PARAM_NAME = "cacheSize";

    public final static int DEFAULT_PROCESSORS = 4;
    public final static int DEFAULT_CACHE_SIZE = 8 * 1024; // 32 KiBi = 32 * 1024 bits
    public final static int DEFAULT_CACHE_ALIGNMENT = 16; // common cache alignment

    public final static int MAX_ITERATIONS_TO_PARALLELIZE = 100000;
    public final static String BALANCED_TILE_SIZE_NAME = "balancedTileSize";

    public static final String NTH_ORDER_PARAM = "tiling-level";
    public static final String NO_PERFECT_NEST_FLAG = "paw-tiling-no-perfect-nest";
    public static final int DEFAULT_NTH_ORDER = -1;

    private Logger logger = Logger.getLogger("PawTiling");

    private int numOfProcessors = DEFAULT_PROCESSORS;
    private int cacheSize = DEFAULT_CACHE_SIZE;
    private int cacheAlignment = DEFAULT_CACHE_ALIGNMENT;

    private List<Loop> selectedOutermostLoops;

    private PawAnalysisUtils pawAnalysisUtils = new PawAnalysisUtils();

    private ReuseAnalysisFactory reuseAnalysisFactory;

    private VersionChooserProvider chooserProvider;

    public ParallelAwareTilingPass(Program program) {
        this(program, null);
    }

    public ParallelAwareTilingPass(Program program, CommandLineOptionSet commandLineOptions) {
        this(program, commandLineOptions, new SimpleReuseAnalysisFactory());
    }

    public ParallelAwareTilingPass(Program program, CommandLineOptionSet commandLineOptions,
            ReuseAnalysisFactory reuseAnalysisFactory) {

        this(program, commandLineOptions, reuseAnalysisFactory,
                commandLineOptions.getValue(PAW_TILING) != null && commandLineOptions.getValue(PAW_TILING).equals("1")
                        ? new ComplexChooserProvider()
                        : new NthGuidedChooserProvider(commandLineOptions));
    }

    public ParallelAwareTilingPass(Program program, CommandLineOptionSet commandLineOptions,
            ReuseAnalysisFactory reuseAnalysisFactory, VersionChooserProvider chooserFactory) {
        super(program);

        if (commandLineOptions.getValue("verbosity").equals("1")) {
            pawAnalysisUtils.verbosity = true;
            setLogger(true);
        } else {
            setLogger(false);
        }

        if (chooserFactory instanceof ComplexChooserProvider) {
            logger.info("Complex provider selected");
        } else {
            logger.info("Depth-first provider selected");
        }

        this.reuseAnalysisFactory = reuseAnalysisFactory;
        this.chooserProvider = chooserFactory;

        try {
            numOfProcessors = Integer.parseInt(commandLineOptions.getValue(CORES_PARAM_NAME));
            assert numOfProcessors > 0;
        } catch (Exception e) {
            logger.warning(
                    "Error on setting num of processors. The default value: " + DEFAULT_PROCESSORS + " will be used");
        }

        try {
            cacheSize = Integer.parseInt(commandLineOptions.getValue(CACHE_PARAM_NAME));
            assert cacheSize > 0;
        } catch (Exception e) {
            logger.warning(
                    "Error on setting cache size. The default value: " + DEFAULT_CACHE_SIZE + " will be used");
        }

    }

    private void setLogger(boolean verbosity) {
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setFormatter(new PawTilingFormatter());

        logger.addHandler(consoleHandler);
        logger.setUseParentHandlers(true);

        if (verbosity) {
            logger.setLevel(Level.ALL);
            logger.log(Level.INFO, "VERBOSITY_SET=ALL");
        } else {
            logger.setLevel(Level.INFO);
        }

    }

    @Override
    public String getPassName() {
        return "[PawTiling]";
    }

    @Override
    public void start() {

        List<Loop> outermostLoops = LoopTools.getOutermostLoops(program);

        List<Loop> perfectLoops = pawAnalysisUtils.filterValidLoops(outermostLoops);
        this.selectedOutermostLoops = perfectLoops;

        logger.log(Level.FINE, "#### Selected loops: " + selectedOutermostLoops.size() + "\n");
        for (Loop outermostLoop : selectedOutermostLoops) {
            logger.log(Level.FINE, outermostLoop + "\n");
        }
        logger.log(Level.FINE, "#### END Selected loops");

        logger.log(Level.FINE, pawAnalysisUtils.toString());

        // In case of rollback
        List<Loop> originalLoopNestCopy = new ArrayList<>();
        outermostLoops.forEach(loop -> {
            originalLoopNestCopy.add(((ForLoop) loop).clone(false));
        });

        if (selectedOutermostLoops.size() == 0) {
            return;
        }

        for (Loop outermostLoop : selectedOutermostLoops) {
            try {

                Expression cores = createCoresVariable(
                        VariableDeclarationUtils
                                .getVariableDeclarationSpace(outermostLoop.getParent()));

                Expression cache = createCacheVariable(
                        VariableDeclarationUtils
                                .getVariableDeclarationSpace(outermostLoop.getParent()));

                runPawTiling((ForLoop) outermostLoop, cores, cache);
            } catch (Exception e) {
                logger.log(Level.SEVERE, " ----");
                logger.log(Level.SEVERE, "Error trying to run paw tiling");
                logger.log(Level.SEVERE, "Loop:");
                logger.log(Level.SEVERE, outermostLoop.toString());
                logger.log(Level.SEVERE, "ERROR", e);
                logger.log(Level.SEVERE, " ---- ");
            }
        }

        reRunPasses();

    }

    public void reRunPasses() {

        // TODO: need to change the way of doing things. First I need to
        // run everything when I just have the tiled version in the program
        // once the tiled version is parallelized I can add the if statement
        // with the default version.!!!!!!!!!
        // #WARNING:

        LoopTools.addLoopName(program, true);

        AnalysisPass.run(new ArrayPrivatization(program));

        try {
            AnalysisPass.run(new Reduction(program));

        } catch (Exception e) {
            logger.severe(e.getMessage());
            // e.printStackTrace(logger);
        }

        AnalysisPass.run(new LoopParallelizationPass(program));

        String profitableOmpCopy = Driver.getOptionValue("profitable-omp");
        Driver.setOptionValue("profitable-omp", "0");
        CodeGenPass.run(new ompGen(program));

        Driver.setOptionValue("profitable-omp", profitableOmpCopy);

    }

    private void runPawTiling(ForLoop loopNest, Expression cores, Expression cache) throws Exception {

        DataReuseAnalysis reuseAnalysis = null;

        reuseAnalysis = runReuseAnalysis(loopNest);

        if (!hasReuse(reuseAnalysis)) {
            return;
        }

        LinkedList<Loop> nestedLoops = new LinkedList<>();
        new DFIterator<Loop>(loopNest, Loop.class).forEachRemaining(nestedLoops::add);

        CompoundStatement variableDeclarations = new CompoundStatement();

        List<DependenceVector> dependenceVectors = program.getDDGraph().getDirectionMatrix(nestedLoops);

        logger.log(Level.FINE, "### ORIGINAL VERSION ###");
        logger.log(Level.FINE, loopNest.toString());

        logger.log(Level.FINE, ("#### ORIGINAL DVs ####"));
        logger.log(Level.FINE, dependenceVectors.toString());
        logger.log(Level.FINE, "#### ORIGINAL DVs END ####");

        logger.log(Level.FINE, "### ORIGINAL VERSION END ###");

        VersionChooser optimizer = chooserProvider.chooseOptimalVersion(variableDeclarations, loopNest,
                dependenceVectors, reuseAnalysis);

        TiledLoop leastCostTiledVersion = optimizer.getChoosenVersion();

        logger.log(Level.FINE, "### TILED VERSION SELECTION ###");
        logger.log(Level.FINE, leastCostTiledVersion.toString());
        logger.log(Level.FINE, "### SELECTED VERSION ###");

        Expression totalOfInstructions = LoopInstructionsUtils.getTotalOfInstructions(loopNest);

        Expression typesValuesInCache = getTypesValuesInCacheSize(loopNest, cache);

        Expression balancedTileSize = computeBalancedCrossStripSize(typesValuesInCache, cores);

        replaceTileSize(variableDeclarations, balancedTileSize);

        // create two versions if iterations isn't enough to parallelize

        CompoundStatement leastCostVersionStm = new CompoundStatement();
        leastCostVersionStm.addStatement(leastCostTiledVersion);

        VariableDeclarationUtils.addNewVariableDeclarations(leastCostVersionStm,
                filterValidDeclarations(variableDeclarations, leastCostTiledVersion));

        ForLoop newLoopNest = loopNest.clone(false);

        Expression dataMatrixSize = computeFullDataSize(loopNest);
        logger.log(Level.FINE, "DATA_FULL_SIZE_IN_KB", dataMatrixSize.toString());

        Statement twoVersionsStm = createTwoVersionsStm(totalOfInstructions, cache, dataMatrixSize,
                newLoopNest,
                leastCostVersionStm);

        replaceLoop(loopNest, twoVersionsStm);

        logger.log(Level.FINE, "### Updated attributes ###");

    }

    private Expression createCoresVariable(SymbolTable variableDeclarations) {
        Identifier coresIdentifier = VariableDeclarationUtils.declareVariable(variableDeclarations, CORES_PARAM_NAME,
                new IntegerLiteral(numOfProcessors));

        return ((Expression) coresIdentifier);
    }

    private Expression createCacheVariable(SymbolTable variableDeclarations) {
        Identifier cacheIdentifier = VariableDeclarationUtils.declareVariable(variableDeclarations, CACHE_PARAM_NAME,
                new IntegerLiteral(cacheSize));

        return ((Expression) cacheIdentifier);
    }

    private boolean hasReuse(DataReuseAnalysis reuseAnalysis) {

        boolean isReusable = true;

        isReusable &= reuseAnalysis != null
                && reuseAnalysis.hasReuse();

        return isReusable;
    }

    private Statement createTwoVersionsStm(Expression maxOfInstructions, Expression cache, Expression dataFullSize,
            Statement trueClause, Statement falseClause) {

        Expression instructionsCondition = new BinaryExpression(maxOfInstructions.clone(), BinaryOperator.COMPARE_LE,
                new IntegerLiteral(MAX_ITERATIONS_TO_PARALLELIZE));

        Expression cacheCond = new BinaryExpression(cache.clone(), BinaryOperator.COMPARE_GT, dataFullSize.clone());
        Expression condition = new BinaryExpression(instructionsCondition.clone(), BinaryOperator.LOGICAL_AND,
                cacheCond.clone());

        IfStatement ifStm = new IfStatement(condition, trueClause, falseClause);
        Expression exp = Symbolic.simplify(maxOfInstructions);
        if (!(maxOfInstructions instanceof IntegerLiteral)
                || !(cache instanceof Identifier)
                || !(dataFullSize instanceof IntegerLiteral)) {
            return ifStm;

        } else {
            long inst = ((IntegerLiteral) maxOfInstructions).getValue();
            long fullSizeData = ((IntegerLiteral) dataFullSize).getValue();
            if (inst <= MAX_ITERATIONS_TO_PARALLELIZE && cacheSize > fullSizeData) {
                return trueClause.clone();
            } else {
                return falseClause.clone();
            }
        }
    }

    private List<Declaration> filterValidDeclarations(CompoundStatement variableDeclarations,
            ForLoop loopNest) {

        List<Declaration> declarations = new ArrayList<>();

        List<ForLoop> loops = new ArrayList<>();
        new DFIterator<ForLoop>(loopNest, ForLoop.class).forEachRemaining(loops::add);

        IDExpression balancedTileSize = new NameID(BALANCED_TILE_SIZE_NAME);

        Declaration balancedTileSizeDeclaration = variableDeclarations.findSymbol(balancedTileSize);
        if (balancedTileSizeDeclaration != null) {
            declarations.add(balancedTileSizeDeclaration);
        }

        for (ForLoop loop : loops) {
            Symbol symbol = LoopTools.getLoopIndexSymbol(loop);

            if (symbol == null) {
                continue;
            }

            NameID name = new NameID(symbol.getSymbolName());

            Declaration declaration = variableDeclarations.findSymbol(name);

            if (declaration != null) {
                declarations.add(declaration);
            }

            Expression rawStepExpr = loop.getStep();
            if (!(rawStepExpr instanceof AssignmentExpression)) {
                continue;
            }

            AssignmentExpression stepExpr = (AssignmentExpression) rawStepExpr;
            Expression RHSExpr = stepExpr.getRHS();

            NameID assignationName = new NameID(RHSExpr.toString());

            Declaration assignationDeclaration = variableDeclarations.findSymbol(assignationName);

            if (assignationDeclaration != null) {
                declarations.add(assignationDeclaration);
            }
        }

        return declarations;
    }

    private void replaceTileSize(SymbolTable variableDeclarationSpace,
            Expression newTileSize) {

        Identifier balancedTileVariable = VariableDeclarationUtils.declareVariable(variableDeclarationSpace,
                BALANCED_TILE_SIZE_NAME,
                newTileSize);

        for (Declaration declaration : variableDeclarationSpace.getDeclarations()) {
            List<Traversable> children = declaration.getChildren();
            Declarator declarator = null;

            for (Traversable child : children) {
                if (!(child instanceof Declarator)) {
                    continue;
                }

                declarator = (Declarator) child;
                break;
            }

            if (declarator == null) {
                continue;
            }

            IDExpression id = declarator.getID();
            if (!id.getName().contains(TilingUtils.TILE_SUFFIX)) {
                continue;
            }

            if (id.getName().equals(BALANCED_TILE_SIZE_NAME)) {
                continue;
            }
            Initializer init = new Initializer(balancedTileVariable.clone());
            declarator.setInitializer(init);
        }
    }

    private Expression computeBalancedCrossStripSize(Expression rawTileSize, Expression numOfProcessors) {

        // Making rawTile size multiple of num of processors
        Expression strip = Symbolic.divide(rawTileSize, numOfProcessors);
        return strip;
    }

    /**
     * Compute the data full size in bytes
     * 
     * @param loop the loop where to obtain the array accesses to calculate the
     *             block size
     * @return the block size in bits to use from the cache
     */
    private Expression computeFullDataSize(Loop loop) {

        // Accessing only using loop bounds
        Expression upperBound = LoopTools.getUpperBoundExpression(loop);

        return Symbolic.multiply(upperBound, new IntegerLiteral(cacheAlignment));

        // Full matrices
        // List<ArrayAccess> arrayAccesses = new ArrayList<>();

        // Set<String> alreadyAdded = new HashSet<>();
        // new DFIterator<ArrayAccess>(loop, ArrayAccess.class).forEachRemaining(access
        // -> {
        // String accessName = access.getArrayName().toString();
        // if (!alreadyAdded.contains(accessName)) {
        // arrayAccesses.add(access);
        // alreadyAdded.add(accessName);

        // }
        // });

        // return CacheUtils.getRawBlockSize(cache, arrayAccesses);
        // return
        // ArrayUtils.getFullSizeInBytes(VariableDeclarationUtils.getVariableDeclarationSpace(loop.getParent()),
        // arrayAccesses);

    }

    private Expression getTypesValuesInCacheSize(Loop loop, Expression cache) {
        List<ArrayAccess> arrayAccesses = new ArrayList<>();
        new DFIterator<ArrayAccess>(loop, ArrayAccess.class).forEachRemaining(arrayAccesses::add);

        return CacheUtils.getRawBlockSize(cache, arrayAccesses);
    }

    public void runLoopInterchage(Loop loopNest) {
        LoopInterchange loopInterchangePass = new LoopInterchange(program);

        LinkedList<Loop> loops = new LinkedList<>();

        List<Expression> originalOrder = new ArrayList<>();

        new DFIterator<Loop>(loopNest, Loop.class).forEachRemaining(loop -> {

            originalOrder.add(LoopTools.getIndexVariable(loop));
            loops.add(loop);
        });

        Map<ForLoop, String> loopMap = new HashMap<>();

        loopInterchangePass.interchangeLoops(loopNest, loops.get(loops.size() - 1), loops, loopMap, originalOrder);

        logger.log(Level.FINE, program.toString());
    }

    private DataReuseAnalysis runReuseAnalysis(Loop loopNest) {

        DataReuseAnalysis reuseAnalysis = reuseAnalysisFactory.getReuseAnalysis(loopNest);
        List<Expression> memoryOrder = reuseAnalysis.getLoopNestMemoryOrder();

        logger.log(Level.FINE, "### REUSE ANALYSIS ###");

        logger.log(Level.FINE, "#### LOOP COSTS:");
        logger.log(Level.FINE, DataReuseAnalysisUtils.printLoopCosts(reuseAnalysis.getLoopCosts()));
        logger.log(Level.FINE, "#### LOOP COSTS END");

        logger.log(Level.FINE, "#### Memory order:");

        for (int i = 0; i < memoryOrder.size(); i++) {
            Expression expr = memoryOrder.get(i);
            logger.log(Level.FINE, expr + "\n");
        }

        logger.log(Level.FINE, "#### Memory order END");
        logger.log(Level.FINE, "### REUSABILITY END ###");

        return reuseAnalysis;

    }

    private void replaceLoop(ForLoop originalLoop, Statement newLoop) {
        Traversable originalParent = originalLoop.getParent();

        int originalLoopIdx = -1;
        for (int i = 0; i < originalParent.getChildren().size(); i++) {
            if (originalParent.getChildren().get(i) == originalLoop) {
                originalLoopIdx = i;
                break;
            }
        }

        originalParent.setChild(originalLoopIdx, newLoop);
    }

}

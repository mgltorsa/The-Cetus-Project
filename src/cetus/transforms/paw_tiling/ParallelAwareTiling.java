package cetus.transforms.paw_tiling;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import cetus.analysis.AnalysisPass;
import cetus.analysis.ArrayPrivatization;
import cetus.analysis.DDTDriver;
import cetus.analysis.DependenceVector;
import cetus.analysis.LoopParallelizationPass;
import cetus.analysis.LoopTools;
import cetus.analysis.Reduction;
import cetus.codegen.CodeGenPass;
import cetus.codegen.ompGen;
import cetus.exec.Driver;
import cetus.hir.Annotation;
import cetus.hir.AnnotationDeclaration;
import cetus.hir.ArrayAccess;
import cetus.hir.BinaryExpression;
import cetus.hir.BinaryOperator;
import cetus.hir.CodeAnnotation;
import cetus.hir.CompoundStatement;
import cetus.hir.DFIterator;
import cetus.hir.Expression;
import cetus.hir.FloatLiteral;
import cetus.hir.ForLoop;
import cetus.hir.IfStatement;
import cetus.hir.IntegerLiteral;
import cetus.hir.Literal;
import cetus.hir.Loop;
import cetus.hir.PrintTools;
import cetus.hir.Program;
import cetus.hir.Statement;
import cetus.hir.Symbol;
import cetus.hir.SymbolTable;
import cetus.hir.TranslationUnit;
import cetus.hir.Traversable;
import cetus.transforms.TransformPass;
import cetus.utils.ArrayUtils;
import cetus.utils.ExperimentalSectionUtils;
import cetus.utils.MemoryUtils;
import cetus.utils.VariableDeclarationUtils;

public class ParallelAwareTiling extends TransformPass {

    public final static String PASS_NAME = "paw_tiling";

    public final static String CORES_PARAM_NAME = "cores";
    public final static String CACHE_PARAM_NAME = "cacheSize";

    public final static int MAX_ITERATIONS_TO_PARALLELIZE = 100000;
    public final static int DEFAULT_PROCESSORS = 4;
    public final static int DEFAULT_CACHE_SIZE = 32 * 1024; // 32 KiBi = 32 * 1024 bits
    public final static int DEFAULT_CACHE_ALIGNMENT = 16; // common cache alignment

    private static final CodeAnnotation requiredCodeAnnotation = new CodeAnnotation(
            "#define MIN(a,b) ((a)<(b)?(a):(b))\n" +
                    "#define MAX(a,b) ((a)>(b)?(a):(b))\n" +
                    "#define ABS(x) ((x)<0?-(x):(x))\n" +
                    "#define CEIL(x) ((int)((x)+0.5))\n" +
                    "#define FLOOR(x) ((int)((x)-0.5))\n" +
                    "#define ROUND(x) ((int)((x)+0.5))\n");

    private int numOfProcessors = DEFAULT_PROCESSORS;
    private int cacheSizeInKB = DEFAULT_CACHE_SIZE;

    public ParallelAwareTiling(Program program) {
        super(program);
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
                    "Error on setting cache size. The default value: " + DEFAULT_CACHE_SIZE + " will be used", 2);
        }
    }

    private boolean isValidForTiling(ForLoop loop) {
        // Check if the the loop is perfectly nested, if it has no branches, and if it
        // has clear boundaries

        if (!LoopTools.isPerfectNest(loop))
            return false;

        if (!LoopTools.isCanonical(loop))
            return false;

        return true;
    }

    private List<ForLoop> filterValidLoopNests() {
        List<ForLoop> validLoops = new ArrayList<>();

        List<List<Traversable>> sections = ExperimentalSectionUtils.findExperimentalSections(program);

        if (sections.isEmpty()) {
            PrintTools.printlnDebug("No experimental sections found.");
            
            DFIterator<ForLoop> loopIterator = new DFIterator<>(program, ForLoop.class);
            while (loopIterator.hasNext()) {
                ForLoop loop = loopIterator.next();
                // Check if the loop is valid for tiling
                if (!isValidForTiling(loop) || !LoopTools.isOutermostLoop(loop))
                    continue;

                validLoops.add(loop);
            }
        }else{
            PrintTools.printlnDebug("Experimental sections found: " + sections.size());
            for (List<Traversable> section : sections) {
                for (Traversable t : section) {
                    if (!(t instanceof ForLoop)) {
                        continue;
                    }
                    ForLoop loop = (ForLoop) t;
                    // Check if the loop is valid for tiling
                    if (!isValidForTiling(loop) || !LoopTools.isOutermostLoop(loop))
                        continue;

                    validLoops.add(loop);
                }
            }
        }

        return validLoops;
    }

    @Override
    public void start() {
        // Implementation of the parallel-aware tiling transformation
        // This is a placeholder for the actual implementation
        PrintTools.printlnDebug("Starting parallel-aware tiling transformation...");

        PrintTools.printlnDebug("Adding math library to the program.");
        for (Traversable t : program.getChildren()) {
            if (!(t instanceof TranslationUnit)) {
                continue;
            }
            TranslationUnit tu = (TranslationUnit) t;

            tu.addDeclarationFirst(new AnnotationDeclaration(requiredCodeAnnotation));
        }

        List<ForLoop> validLoops = filterValidLoopNests();
        boolean hasTilableLoops = !validLoops.isEmpty();
        if (!hasTilableLoops) {
            PrintTools.printlnDebug("No tilable loops found.");
            return;
        }

        for (ForLoop targetLoop : validLoops) {
            try {
                processLoop(targetLoop);
            } catch (Exception e) {
                e.printStackTrace();
                PrintTools.print(
                        String.format("Error processing loop %s: %s", targetLoop.toString(), e.getMessage()), 0);
            }
        }

        reRunPasses();
    }

    private void processLoop(ForLoop targetLoop) throws Exception {
        // Perform tiling transformation on the loop
        // This is a placeholder for the actual implementation
        PrintTools.printlnDebug("Tiling loop: " + targetLoop);

        // For every loop create a map of tile sizes
        Map<Expression, Expression> tileSizes = new java.util.HashMap<>();

        // Example tile sizes for the loop indices
        DFIterator<ForLoop> loopIterator = new DFIterator<>(targetLoop, ForLoop.class);

        int initialTileSize = 16; // Example initial tile size
        while (loopIterator.hasNext()) {
            ForLoop loop = loopIterator.next();
            Expression indexVar = LoopTools.getIndexVariable(loop);
            // Example tile size, this should be replaced with actual logic to determine
            // tile sizes
            Expression tileSize = new IntegerLiteral(initialTileSize); // Example tile size
            tileSizes.put(indexVar, tileSize);
            initialTileSize += 16; // Example logic to increase tile size for next loop
        }

        ForLoop tiledLoop = tile(targetLoop, tileSizes); // Example tile size

        Expression totalOfInstructions = calculateTotalOfInstructions(targetLoop);
        Expression totalElementsInCache = calculateCacheInNumberOfElements(targetLoop, cacheSizeInKB);
        Expression dataFullSize = calculateDataFullSize(targetLoop);

        Statement optimizedStatement = createOptimizedStatement(totalOfInstructions, totalElementsInCache,
                dataFullSize, targetLoop.clone(false), tiledLoop.clone(false));

        replaceLoop(targetLoop, optimizedStatement);

    }

    private Expression calculateDataFullSize(ForLoop loop) {
        // Placeholder for the actual data size calculation
        // This is where the data size calculation would be applied to the loop
        PrintTools.printlnDebug("Calculating data full size for loop: " + loop);
        List<ArrayAccess> arrayAccesses = new ArrayList<>();
        new DFIterator<ArrayAccess>(loop, ArrayAccess.class).forEachRemaining(arrayAccesses::add);

        return ArrayUtils.getFullSize(VariableDeclarationUtils.getVariableDeclarationSpace(loop.getParent()),
                arrayAccesses);
    }

    private Expression calculateCacheInNumberOfElements(ForLoop loop, int cacheSizeInKB) {
        // Placeholder for the actual cache size calculation
        // This is where the cache size calculation would be applied to the loop
        PrintTools.printlnDebug("Calculating cache size in number of elements for loop: " + loop);
        List<ArrayAccess> arrayAccesses = new ArrayList<>();
        new DFIterator<ArrayAccess>(loop, ArrayAccess.class).forEachRemaining(arrayAccesses::add);

        return new IntegerLiteral(MemoryUtils.getCacheInArrayElements(cacheSizeInKB, arrayAccesses));
    }

    public void reRunPasses() {
        AnalysisPass.run(new ArrayPrivatization(program));
        AnalysisPass.run(new DDTDriver(program));

        try {
            AnalysisPass.run(new Reduction(program));

        } catch (Exception e) {
            e.printStackTrace();
        }

        AnalysisPass.run(new LoopParallelizationPass(program));

        String profitableOmpCopy = Driver.getOptionValue("profitable-omp");

        Driver.setOptionValue("profitable-omp", "0");
        CodeGenPass.run(new ompGen(program));

        Driver.setOptionValue("profitable-omp", profitableOmpCopy);

    }

    private Expression calculateTotalOfInstructions(ForLoop loop) throws Exception {
        // TODO: TAKE A LOOK AT THIS!
        // LoopTools.getReuseDistance(loop, null, null)
        DFIterator<ForLoop> loopIterator = new DFIterator<>(loop, ForLoop.class);
        Expression totalInstructions = new IntegerLiteral(1);
        while (loopIterator.hasNext()) {
            ForLoop innerLoop = loopIterator.next();
            Expression instructions = LoopTools.getUpperBoundExpression(innerLoop);
            if (instructions == null)
                throw new Exception("Loop upper bound expression is null.");

            totalInstructions = new BinaryExpression(totalInstructions.clone(), BinaryOperator.MULTIPLY,
                    instructions.clone());

        }

        return totalInstructions;
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

        cloneCodeAnnotations(originalLoop, newLoop);

        originalParent.setChild(originalLoopIdx, newLoop);
    }

    private void cloneCodeAnnotations(ForLoop originalLoop, Statement newLoop) {
        List<Annotation> annotations = originalLoop.getAnnotations();
        for (Annotation annotation : annotations) {
            if (annotation instanceof CodeAnnotation) {
                newLoop.annotateBefore(annotation.clone());
            }
        }
    }

    private Statement createOptimizedStatement(Expression maxOfInstructions, Expression cache, Expression dataFullSize,
            Statement noTiledCode, Statement tiledCode) {

        Expression instructionsCondition = new BinaryExpression(maxOfInstructions.clone(), BinaryOperator.COMPARE_LE,
                new IntegerLiteral(MAX_ITERATIONS_TO_PARALLELIZE));
        Expression cacheCond = new BinaryExpression(cache.clone(), BinaryOperator.COMPARE_GT, dataFullSize.clone());
        Expression condition = new BinaryExpression(instructionsCondition.clone(), BinaryOperator.LOGICAL_AND,
                cacheCond.clone());

        CompoundStatement leastCostVersionStm = new CompoundStatement();
        leastCostVersionStm.addStatement(tiledCode);

        if (!(maxOfInstructions instanceof Literal)
                || !(cache instanceof Literal)
                || !(dataFullSize instanceof Literal)) {
            IfStatement ifStm = new IfStatement(condition, noTiledCode, leastCostVersionStm);
            return ifStm;

        } else {
            boolean isProfitableParallelIterations = false;
            boolean isEnoughCache = false;
            if (maxOfInstructions instanceof IntegerLiteral) {
                long inst = ((IntegerLiteral) maxOfInstructions).getValue();
                isProfitableParallelIterations = inst >= MAX_ITERATIONS_TO_PARALLELIZE;
            } else if (maxOfInstructions instanceof FloatLiteral) {
                double inst = ((FloatLiteral) maxOfInstructions).getValue();
                isProfitableParallelIterations = inst <= MAX_ITERATIONS_TO_PARALLELIZE;
            }

            long cacheSize = ((IntegerLiteral) cache).getValue();

            if (dataFullSize instanceof IntegerLiteral) {
                long fullSizeData = ((IntegerLiteral) dataFullSize).getValue();
                isEnoughCache = cacheSize > fullSizeData;

            } else if (dataFullSize instanceof FloatLiteral) {
                double fullSizeData = ((FloatLiteral) dataFullSize).getValue();
                isEnoughCache = cacheSize > fullSizeData;

            }
            if (isProfitableParallelIterations && isEnoughCache) {
                return tiledCode.clone();
            } else {
                return noTiledCode.clone();
            }
        }
    }

    public TiledLoop tile(ForLoop loop, Map<Expression, Expression> tileSizes) throws Exception {

        // Placeholder for the actual tiling implementation
        // This is where the tiling transformation would be applied to the loop
        PrintTools.printlnDebug("Tiling loop: " + loop + " with tile size: " + tileSizes);

        SymbolTable symbolTable = VariableDeclarationUtils
                .getVariableDeclarationSpace(loop.getParent());

        LinkedList<Loop> nestedLoops = new LinkedList<>();
        new DFIterator<Loop>(loop, Loop.class).forEachRemaining(nestedLoops::add);

        List<DependenceVector> originalDvs = program.getDDGraph().getDirectionMatrix(nestedLoops);

        TiledLoop tiledLoop = new TiledLoop(loop, originalDvs);
        List<DependenceVector> curDvs = originalDvs;
        for (int i = 0; i < nestedLoops.size(); i++) {
            ForLoop currNestedLoop = (ForLoop) nestedLoops.get(i);
            Expression indexVar = LoopTools.getIndexVariable(currNestedLoop);

            if (!tileSizes.containsKey(indexVar))
                continue;

            int targetLoopPos = getLoopPosByIndex(tiledLoop, indexVar);

            TiledLoop clonedTiledLoop = tiledLoop.clone(false);
            Expression tileSize = tileSizes.get(indexVar);
            tiledLoop = Tiler.tile(symbolTable, clonedTiledLoop, tileSize, targetLoopPos, curDvs);

            curDvs = tiledLoop.getDependenceVectors();
        }

        return tiledLoop;
    }

    private int getLoopPosByIndex(Loop loopNest, Expression index) throws Exception {
        int foundPos = -1;
        DFIterator<Loop> loopIter = new DFIterator<Loop>(loopNest, Loop.class);

        int curPos = 0;
        while (loopIter.hasNext() && foundPos == -1) {
            Loop curLoop = loopIter.next();
            Symbol curSymbol = LoopTools.getLoopIndexSymbol(curLoop);

            if (curSymbol == null || !curSymbol.getSymbolName().equals(index.toString())) {
                curPos++;
                continue;
            }

            foundPos = curPos;
            break;

        }

        if (foundPos == -1) {
            throw new Exception("Index does not exist in the given loop nest");
        }

        return foundPos;
    }

    @Override
    public String getPassName() {
        return "[Parallel-Aware Tiling]";
    }

}
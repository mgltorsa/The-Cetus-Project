package cetus.transforms.paw_tiling;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import cetus.analysis.AnalysisPass;
import cetus.analysis.ArrayPrivatization;
import cetus.analysis.DDTDriver;
import cetus.analysis.DependenceVector;
import cetus.analysis.LoopTools;
import cetus.analysis.Reduction;
import cetus.exec.Driver;
import cetus.hir.Annotation;
import cetus.hir.AnnotationDeclaration;
import cetus.hir.ArrayAccess;
import cetus.hir.AssignmentExpression;
import cetus.hir.AssignmentOperator;
import cetus.hir.BinaryExpression;
import cetus.hir.BinaryOperator;
import cetus.hir.CetusAnnotation;
import cetus.hir.CodeAnnotation;
import cetus.hir.CompoundStatement;
import cetus.hir.DFIterator;
import cetus.hir.Expression;
import cetus.hir.FloatLiteral;
import cetus.hir.ForLoop;
import cetus.hir.IDExpression;
import cetus.hir.IfStatement;
import cetus.hir.IntegerLiteral;
import cetus.hir.Literal;
import cetus.hir.Loop;
import cetus.hir.PragmaAnnotation;
import cetus.hir.PrintTools;
import cetus.hir.Program;
import cetus.hir.Specifier;
import cetus.hir.Statement;
import cetus.hir.Symbol;
import cetus.hir.SymbolTable;
import cetus.hir.Symbolic;
import cetus.hir.TranslationUnit;
import cetus.hir.Traversable;
import cetus.transforms.LoopInterchange;
import cetus.transforms.TransformPass;
import cetus.utils.ArrayUtils;
import cetus.utils.ExperimentalSectionUtils;
import cetus.utils.MemoryUtils;
import cetus.utils.VariableDeclarationUtils;

public class ParallelAwareTiling extends TransformPass {

    public final static String PASS_NAME = "paw_tiling";

    private static final CodeAnnotation requiredCodeAnnotation = new CodeAnnotation(
            "#define MIN(a,b) ((a)<(b)?(a):(b))\n" +
                    "#define MAX(a,b) ((a)>(b)?(a):(b))\n" +
                    "#define ABS(x) ((x)<0?-(x):(x))\n" +
                    "#define CEIL(x) ((int)((x)+0.5))\n" +
                    "#define FLOOR(x) ((int)((x)-0.5))\n" +
                    "#define ROUND(x) ((int)((x)+0.5))\n");

    private TilingParams tilingParams;

    public ParallelAwareTiling(Program program) {
        super(program);
        tilingParams = TilingParams.getTilingParams();
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
        } else {
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

    public void checkPreconditions() {
        String ddtOption = Driver.getOptionValue("ddt");
        if (ddtOption != null && !ddtOption.equals("0")) {
            // Run DDT if disabled
            AnalysisPass.run(new DDTDriver(program)); // DDT Allow many things like loop naming and graph calculation
        }
    }

    @Override
    public void start() {


        //perform loop interchange
        try {
            TransformPass.run(new LoopInterchange(program));
        } catch (Exception e) {
            // TODO: handle exception
        }

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

        List<TiledLoop> updatedLoops = new ArrayList<>();
        for (ForLoop targetLoop : validLoops) {
            try {
                TiledLoop newLoop = processLoop(targetLoop);
                updatedLoops.add(newLoop);
            } catch (Exception e) {
                e.printStackTrace();
                PrintTools.print(
                        String.format("Error processing loop %s: %s", targetLoop.toString(), e.getMessage()), 0);
            }
        }

        reRunPasses();
        for (TiledLoop forLoop : updatedLoops) {
            updateLoopInfo(forLoop);
        }

    }

    private void updateLoopInfo(TiledLoop loop) {
        balanceTileSizesAndEnsuringParallelizability(loop);
        setupTileSizesMetadata(loop);
    }

    private String calculateTileSizeValue(Expression tileSize) {
        if (tileSize instanceof IntegerLiteral) {
            return tileSize.toString();
        }
        long tileSizeValueLong = Symbolic.getConstantCoefficient(tileSize);
        if (tileSizeValueLong > 0) {
            return "coeff_" + String.valueOf(tileSizeValueLong);
        }

        List<Expression> factors = Symbolic.getFactors(tileSize);

        List<IntegerLiteral> integerFactors = factors.stream().filter(factor -> factor instanceof IntegerLiteral)
                .map(factor -> (IntegerLiteral) factor).collect(Collectors.toList());

        if (integerFactors.size() >= 1) {
            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < integerFactors.size(); i++) {
                IntegerLiteral factor = integerFactors.get(i);
                String factorStr = "fc_" + i + "#" + factor.toString();
                sb.append(factorStr);
            }
            return sb.toString();
        }

        List<Expression> terms = Symbolic.getTerms(tileSize);

        List<IntegerLiteral> integerTerms = terms.stream().filter(term -> term instanceof IntegerLiteral)
                .map(term -> (IntegerLiteral) term).collect(Collectors.toList());

        if (integerTerms.size() >= 1) {
            StringBuilder sb = new StringBuilder();
            for (IntegerLiteral term : integerTerms) {
                String termStr = "term_" + term.toString();
                sb.append(termStr);
            }
            return sb.toString();
        }

        return "complex";

    }

    private void setupTileSizesMetadata(TiledLoop loop) {
        PrintTools.printlnDebug("Setting up tile sizes metadata for loop: " + loop);
        LoopTools.addLoopName(program, true);
        Map<Expression, Expression> tileSizes = loop.getTileSizes();
        for (Expression tileSizeIndexVar : tileSizes.keySet()) {
            String loopName = LoopTools.getLoopName(loop);
            String tileSizeName = tileSizeIndexVar.toString();

            Expression actualTileSize = tileSizes.get(tileSizeIndexVar);
            String tileSizeValue = calculateTileSizeValue(actualTileSize);

            String metadata = String.format("%s=%s#%s", loopName, tileSizeName, tileSizeValue);

            String pragmaStr = String.format("c_paw_tiling %s", metadata);
            PragmaAnnotation pragmaAnnot = new PragmaAnnotation(pragmaStr);
            loop.annotateBefore(pragmaAnnot);
        }
    }

    private void balanceTileSizesAndEnsuringParallelizability(TiledLoop loop) {
        PrintTools.printlnDebug("Balancing tile size for loop: " + loop);
        SymbolTable symbolTable = VariableDeclarationUtils.getVariableDeclarationSpace(loop.getParent());
        Map<Expression, Expression> tileSizes = loop.getTileSizes();
        DFIterator<ForLoop> loopIterator = new DFIterator<>(loop, ForLoop.class);
        while (loopIterator.hasNext()) {
            ForLoop innerLoop = loopIterator.next();
            List<CetusAnnotation> cetusAnnots = innerLoop.getAnnotations(CetusAnnotation.class);

            boolean isParallel = false;
            for (CetusAnnotation cetusAnnot : cetusAnnots) {
                isParallel = Boolean.parseBoolean(cetusAnnot.get("parallel"));
                if (isParallel)
                    break;

            }

            if (!isParallel)
                continue;

            if (!Tiler.isCrossStripLoop(innerLoop))
                continue;

            // handle cross strip loops
            Expression balancedTile = getBalancedTile(symbolTable, innerLoop, tileSizes);

            Expression indexVariable = LoopTools.getIndexVariable(innerLoop);
            Expression stepLHS = indexVariable;
            Expression stepRHS = balancedTile;
            Expression newStepExpr = null;

            if (balancedTile instanceof IntegerLiteral) {
                newStepExpr = new AssignmentExpression(stepLHS.clone(), AssignmentOperator.ADD,
                        stepRHS.clone());
            } else {
                Symbol loopSymbol = LoopTools.getLoopIndexSymbol(loop);

                IDExpression stripIdentifier = VariableDeclarationUtils.getIdentifier(symbolTable,
                        loopSymbol.getSymbolName());

                if (stripIdentifier == null) {
                    stripIdentifier = VariableDeclarationUtils.declareVariable(symbolTable,
                            loopSymbol.getSymbolName(),
                            balancedTile);

                } else {
                    VariableDeclarationUtils.replaceVariableDeclaration(symbolTable, stripIdentifier, balancedTile);
                }

                newStepExpr = new AssignmentExpression(stepLHS.clone(), AssignmentOperator.ADD,
                        stripIdentifier);
            }
            innerLoop.setStep(newStepExpr);

        }
    }

    private Expression getBalancedTile(SymbolTable symbolTable, ForLoop loop, Map<Expression, Expression> tileSizes) {
        Expression indexVar = LoopTools.getIndexVariable(loop);
        Expression balancedTile = null;

        for (Expression tileSize : tileSizes.keySet()) {

            if (!indexVar.toString().toLowerCase().contains(tileSize.toString().toLowerCase()))
                continue;

            balancedTile = tileSizes.get(tileSize);

            // Expression coresExpression = new
            // IntegerLiteral(tilingParams.getNumOfProcessors());
            // if (tilingParams.getTypeSelectionAlgo() != SelectionAlgorithm.FIXED) {
            // balancedTile = Symbolic.divide(balancedTile, coresExpression);
            // }

            int integerSizeInBits = ArrayUtils.getTypeSizeInBits(Specifier.INT);
            int cacheLineInBytes = tilingParams.getCacheLineInBytes();
            int cacheLineInIntegers = cacheLineInBytes / (integerSizeInBits / 8);
            IntegerLiteral cacheLineInIntegersLiteral = new IntegerLiteral(cacheLineInIntegers);

            balancedTile = Symbolic.simplify(balancedTile);

            // align cache size
            Expression alignmentExpr = Symbolic.mod(balancedTile,
                    cacheLineInIntegersLiteral);
            balancedTile = Symbolic.add(balancedTile, alignmentExpr);

            return balancedTile;

        }

        PrintTools.printlnDebug("No balanced tile found for loop: " + loop);
        throw new RuntimeException("No balanced tile found for loop: " + loop);

    }

    private TiledLoop processLoop(ForLoop targetLoop) throws Exception {
        // Perform tiling transformation on the loop
        // This is a placeholder for the actual implementation
        PrintTools.printlnDebug("Tiling loop: " + targetLoop);

        // For every loop create a map of tile sizes
        Map<Expression, Expression> tileSizes = tilingParams.getTileSizeSelectionAlgo().getTileSizes(targetLoop);
        if (tileSizes == null || tileSizes.isEmpty()) {
            throw new Exception("No tile sizes found for loop: " + targetLoop);
        }

        TiledLoop tiledLoop = tile(targetLoop, tileSizes); // Example tile size

        Expression totalOfInstructions = calculateTotalOfInstructions(targetLoop);
        Expression totalElementsInCache = calculateCacheInNumberOfElements(targetLoop, tilingParams.getCacheSizeInKB());
        Expression dataFullSize = calculateDataFullSize(targetLoop);

        TiledLoop clonedTiledLoop = tiledLoop.clone(false);
        Statement optimizedStatement = clonedTiledLoop;

        if (tilingParams.isEnableTilingProfitability()) {
            optimizedStatement = createOptimizedStatement(totalOfInstructions, totalElementsInCache,
                    dataFullSize, targetLoop.clone(false), clonedTiledLoop);

            if (optimizedStatement instanceof IfStatement) {
                CompoundStatement elseStmt = (CompoundStatement) ((IfStatement) optimizedStatement).getElseStatement();
                for (Traversable stmt : elseStmt.getChildren()) {
                    if (!(stmt instanceof TiledLoop))
                        continue;

                    clonedTiledLoop = (TiledLoop) stmt;
                    break;
                }
            }
        }

        replaceLoop(targetLoop, optimizedStatement);

        return clonedTiledLoop;

    }

    private Expression calculateDataFullSize(ForLoop loop) {
        PrintTools.printlnDebug("Calculating data full size for loop: " + loop);
        List<ArrayAccess> arrayAccesses = new ArrayList<>();
        new DFIterator<ArrayAccess>(loop, ArrayAccess.class).forEachRemaining(arrayAccesses::add);

        return ArrayUtils.getFullSize(VariableDeclarationUtils.getVariableDeclarationSpace(loop.getParent()),
                arrayAccesses);
    }

    private Expression calculateCacheInNumberOfElements(ForLoop loop, long cacheSizeInKB) {
        // Placeholder for the actual cache size calculation
        // This is where the cache size calculation would be applied to the loop
        PrintTools.printlnDebug("Calculating cache size in number of elements for loop: " + loop);
        List<ArrayAccess> arrayAccesses = new ArrayList<>();
        new DFIterator<ArrayAccess>(loop, ArrayAccess.class).forEachRemaining(arrayAccesses::add);

        return new IntegerLiteral(MemoryUtils.getCacheInArrayElements(cacheSizeInKB, arrayAccesses));
    }

    public void reRunPasses() {

        boolean isSerialTiling = Driver.getOptionValue(PASS_NAME) != null && !Driver.getOptionValue(PASS_NAME).equals("0");

        String privatizeOption = Driver.getOptionValue("private");
        String ddtOption = Driver.getOptionValue("ddt");
        String reductionOption = Driver.getOptionValue("reduction");


        if (!isSerialTiling && privatizeOption != null && !privatizeOption.equals("0")) {
            AnalysisPass.run(new ArrayPrivatization(program));
        }
        if (!isSerialTiling && ddtOption != null && !ddtOption.equals("0")) {
            AnalysisPass.run(new DDTDriver(program));
        }
        if (!isSerialTiling && reductionOption != null && !reductionOption.equals("0")) {
            try {
                AnalysisPass.run(new Reduction(program));

            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // String profitableOmpCopy = Driver.getOptionValue("profitable-omp");

        // TODO: Fix this. Since in tiling tiles are usually smaller, it is not
        // profitable to run ompGen
        // However, tiles do not represent the actual parallel iterations, so we need to
        // run ompGen after
        // loop parallelization pass.
        Driver.setOptionValue("profitable-omp", "0");

        // CodeGenPass.run(new ompGen(program));

        // Driver.setOptionValue("profitable-omp", profitableOmpCopy);

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
                new IntegerLiteral(tilingParams.getMaxIterationsToParallelize()));

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
                isProfitableParallelIterations = inst >= tilingParams.getMaxIterationsToParallelize();
            } else if (maxOfInstructions instanceof FloatLiteral) {
                double inst = ((FloatLiteral) maxOfInstructions).getValue();
                isProfitableParallelIterations = inst <= tilingParams.getMaxIterationsToParallelize();
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
                return tiledCode;
            } else {
                return noTiledCode;
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

        List<DependenceVector> originalDvs = new ArrayList<>();
        if (program.getDDGraph() != null) {
            originalDvs = program.getDDGraph().getDirectionMatrix(nestedLoops);
            if (originalDvs == null) {
                PrintTools.printlnDebug("Original DVs are null for loop: " + loop);
                originalDvs = new ArrayList<>();
            }
        }

        TiledLoop tiledLoop = new TiledLoop(loop, originalDvs);
        List<DependenceVector> curDvs = originalDvs;
        for (int i = 0; i < nestedLoops.size(); i++) {
            ForLoop currNestedLoop = (ForLoop) nestedLoops.get(i);
            Expression indexVar = LoopTools.getIndexVariable(currNestedLoop);

            if (!tileSizes.containsKey(indexVar))
                continue;

            Expression tileSize = tileSizes.get(indexVar);
            if (tileSize == null) {
                PrintTools.printlnDebug("Tile size is null for index: " + indexVar);
                continue;
            }
            if (tileSize.toString().equals("1")) {
                PrintTools.printlnDebug("Tile size is 1 for index: " + indexVar + "so, skipping tiling.");
                continue;
            }

            int targetLoopPos = getLoopPosByIndex(tiledLoop, indexVar);

            TiledLoop clonedTiledLoop = tiledLoop.clone(false);
            tiledLoop = Tiler.tile(symbolTable, clonedTiledLoop, tileSize, targetLoopPos, curDvs);

            curDvs = tiledLoop.getDependenceVectors();
        }
        tiledLoop.setTileSizes(tileSizes);
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
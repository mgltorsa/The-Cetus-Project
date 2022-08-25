package cetus.transforms.pawTiling;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import cetus.analysis.AnalysisPass;
import cetus.analysis.ArrayPrivatization;
import cetus.analysis.DDGraph;
import cetus.analysis.DDTDriver;
import cetus.analysis.DependenceVector;
import cetus.analysis.LoopTools;
import cetus.analysis.Reduction;
import cetus.exec.CommandLineOptionSet;
import cetus.hir.ArrayAccess;
import cetus.hir.AssignmentExpression;
import cetus.hir.AssignmentOperator;
import cetus.hir.BinaryExpression;
import cetus.hir.BinaryOperator;
import cetus.hir.CompoundStatement;
import cetus.hir.ConditionalExpression;
import cetus.hir.DFIterator;
import cetus.hir.Declaration;
import cetus.hir.Declarator;
import cetus.hir.DepthFirstIterator;
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
import cetus.hir.Specifier;
import cetus.hir.Statement;
import cetus.hir.Symbol;
import cetus.hir.SymbolTable;
import cetus.hir.Symbolic;
import cetus.hir.Traversable;
import cetus.hir.VariableDeclaration;
import cetus.hir.VariableDeclarator;
import cetus.transforms.LoopInterchange;
import cetus.transforms.TransformPass;
import cetus.utils.DataDependenceUtils;
import cetus.utils.DataReuseAnalysis;

public class ParallelAwareTilingPass extends TransformPass {

    public final static String PARAM_NAME = "paw-tiling";

    public final static int DISTRIBUTED_CACHE_OPTION = 1;
    public final static int NON_DISTRIBUTED_CACHE_OPTION = 0;

    public final static String CORES_PARAM_NAME = "cores";
    public final static String CACHE_PARAM_NAME = "cache-size";

    public final static int DEFAULT_PROCESSORS = 4;
    public final static int DEFAULT_CACHE_SIZE = 4096;

    public final static int MAX_NEST_LEVEL = 20;
    public final static int MAX_ITERATIONS_TO_PARALLELIZE = 100000;
    public final static String TILE_SUFFIX = "Tile";
    public final static String BALANCED_TILE_SIZE_NAME = "balancedTileSize";

    private Logger logger = Logger.getLogger(this.getClass().getSimpleName());

    private int numOfProcessors = DEFAULT_PROCESSORS;
    private int cacheSize = DEFAULT_CACHE_SIZE;

    // TODO: non implemented yet
    private boolean isCacheDistributed = false;

    // For reuse analysis
    private List<Expression> memoryOrder;
    private HashMap<Expression, ?> loopCostMap;

    private List<Loop> selectedOutermostLoops;

    private PawAnalysisData analysisData = new PawAnalysisData();

    public ParallelAwareTilingPass(Program program) {
        this(program, null);
    }

    public ParallelAwareTilingPass(Program program, CommandLineOptionSet commandLineOptions) {
        super(program);

        if (commandLineOptions.getValue("verbosity").equals("1")) {
            setLogger(true);
            analysisData.verbosity = true;
        }

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

        try {
            int cacheType = Integer.parseInt(commandLineOptions.getValue(PARAM_NAME));
            if (cacheType == DISTRIBUTED_CACHE_OPTION) {
                isCacheDistributed = true;
            }
        } catch (Exception e) {
            logger.warning(
                    "Error on setting cache type. The cache will be considered as non distributed");
        }

    }

    private void setLogger(boolean verbosity) {
        if (verbosity) {
            logger.setLevel(Level.ALL);
        } else {
            logger.setLevel(Level.WARNING);
        }

        FileHandler handler;
        try {
            handler = new FileHandler("./out/" + this.getClass().getSimpleName() + ".log");
            handler.setFormatter(new SimpleFormatter());
            handler.setEncoding("utf-8");
            logger.addHandler(handler);
        } catch (SecurityException | IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public String getPassName() {
        return "[Parallel-aware tiling]";
    }

    @Override
    public void start() {
     
        List<Loop> outermostLoops = LoopTools.getOutermostLoops(program);
        List<Loop> perfectLoops = filterValidLoops(outermostLoops);
        this.selectedOutermostLoops = perfectLoops;

        logger.info("#### Selected loops: " + selectedOutermostLoops.size() + "\n");
        for (Loop outermostLoop : selectedOutermostLoops) {
            logger.info(outermostLoop + "\n");
        }
        logger.info("#### END Selected loops");

        logger.info(analysisData.toString());

        for (Loop outermostLoop : selectedOutermostLoops) {
            try {
                runPawTiling((ForLoop) outermostLoop);
            } catch (Exception e) {
                logger.info(" ----");
                logger.info("Error trying to run paw tiling");
                logger.info("Loop:");
                System.err.println(outermostLoop);
                logger.info("Error:");
                e.printStackTrace();
                logger.info(" ---- ");
            }
        }

    }

    private void runPawTiling(ForLoop loopNest) throws Exception {
        try {
            runReusabilityAnalysis(loopNest);
        } catch (Exception e) {
            logger.severe(
                    "It was not possible to perform loop interchange. However, paw tiling will continue. Error:");
            e.printStackTrace();

        }
        CompoundStatement variableDeclarations = new CompoundStatement();

        List<ForLoop> tiledVersions = createTiledVersions(loopNest, variableDeclarations);
        ForLoop leastCostTiledVersion = chooseOptimalVersion(loopNest, tiledVersions);

        logger.info("### TILED VERSION SELECTION ###");
        logger.info(leastCostTiledVersion.toString());
        logger.info("### SELECTED VERSION ###");

        Expression totalOfInstructions = getTotalOfInstructions(loopNest);

        Expression rawTileSize = new IntegerLiteral(computeRawTileSize(MAX_ITERATIONS_TO_PARALLELIZE * 2, cacheSize));

        Expression balancedTileSize = computeBalancedTileSize(totalOfInstructions, numOfProcessors,
                rawTileSize);

        replaceTileSize(variableDeclarations, balancedTileSize);

        // create two versions if iterations isn't enough to parallelize

        CompoundStatement leastCostVersionStm = new CompoundStatement();
        leastCostVersionStm.addStatement(leastCostTiledVersion.clone(false));

        addNewVariableDeclarations(leastCostVersionStm,
                filterValidDeclarations(variableDeclarations, leastCostTiledVersion));

        Statement twoVersionsStm = createTwoVersionsStm(totalOfInstructions, loopNest.clone(false),
                leastCostVersionStm);

        replaceLoop(loopNest, twoVersionsStm);

        updateAttributes();

    }

    private Statement createTwoVersionsStm(Expression maxOfInstructions, Statement trueClause, Statement falseClause) {

        BinaryExpression condition = new BinaryExpression(maxOfInstructions, BinaryOperator.COMPARE_LE,
                new IntegerLiteral(MAX_ITERATIONS_TO_PARALLELIZE));

        IfStatement ifStm = new IfStatement(condition, trueClause, falseClause);

        return ifStm;

    }

    private ForLoop chooseOptimalVersion(ForLoop originalLoopNest, List<ForLoop> tiledVersions) {

        int outerParallelLoopIndex = -1;

        ForLoop choosenVersion = originalLoopNest;

        Traversable parent = originalLoopNest.getParent();

        int loopIdx = parent.getChildren().indexOf(originalLoopNest);

        if (tiledVersions.size() == 0) {
            return originalLoopNest;
        }

        ForLoop firstTiledVersion = tiledVersions.get(0);
        List<DependenceVector> firstTiledVersionDVs = calculateDependenceVectors(parent, loopIdx, firstTiledVersion);
        outerParallelLoopIndex = getOuterParallelLoopIdx(firstTiledVersion, firstTiledVersionDVs);

        List<ForLoop> loopsWithSameParallelization = new ArrayList<>();

        loopsWithSameParallelization.add(firstTiledVersion);

        // decide version based on parallelizability
        for (int i = 1; i < tiledVersions.size(); i++) {
            ForLoop tiledVersion = tiledVersions.get(i);
            List<DependenceVector> dependenceVectors = calculateDependenceVectors(parent, loopIdx, tiledVersion);
            int auxOuterParallelLoopIdx = getOuterParallelLoopIdx(tiledVersion, dependenceVectors);

            logger.info("### Tiled version: ");
            logger.info(tiledVersion + "\n");
            logger.info("### Outer parallelizable loop position: " + auxOuterParallelLoopIdx);

            if (auxOuterParallelLoopIdx == -1) {
                continue;
            }
            if (auxOuterParallelLoopIdx == outerParallelLoopIndex) {
                loopsWithSameParallelization.add(tiledVersion);
            }

            else if (auxOuterParallelLoopIdx < outerParallelLoopIndex) {
                outerParallelLoopIndex = auxOuterParallelLoopIdx;
                choosenVersion = tiledVersion;
                loopsWithSameParallelization.clear();
                loopsWithSameParallelization.add(choosenVersion);
            }
        }

        // decide tiled version to take based on reuse analysis
        if (loopsWithSameParallelization.size() > 0) {
            if (this.loopCostMap != null) {

                // init expresion for the most reusable loop
                Expression initExpr = memoryOrder.get(memoryOrder.size() - 1);
                int positionOfMostReusableInnerLoop = getByIndexInLoopNestPosition(choosenVersion, initExpr);

                for (ForLoop loop : loopsWithSameParallelization) {
                    int auxPosition = getByIndexInLoopNestPosition(loop, initExpr);
                    if (auxPosition == -1 || auxPosition == positionOfMostReusableInnerLoop) {
                        continue;
                    }

                    if (auxPosition > positionOfMostReusableInnerLoop) {
                        positionOfMostReusableInnerLoop = auxPosition;
                        choosenVersion = loop;
                    }
                }
            }
        }

        // back the program to its original state.
        parent.setChild(loopIdx, originalLoopNest);

        return choosenVersion;
    }

    private int getByIndexInLoopNestPosition(ForLoop loopNest, Expression indexExpression) {
        List<ForLoop> loops = new ArrayList<>();
        LoopTools.calculateInnerLoopNest(loopNest).descendingIterator()
                .forEachRemaining((loop) -> loops.add((ForLoop) loop));

        if (loops.get(0) != loopNest) {
            Collections.reverse(loops);
        }

        int position = -1;

        for (int i = 0; i < loops.size(); i++) {
            ForLoop loop = loops.get(i);
            Expression initStm = loop.getStep();
            if (initStm.findExpression(indexExpression).size() == 0) {
                continue;
            }

            position = i;
        }

        return position;
    }

    private List<DependenceVector> calculateDependenceVectors(Traversable parentOfLoop, int loopIdx,
            ForLoop tiledVersion) {

        List<DependenceVector> dependenceVectors = new ArrayList<>();

        parentOfLoop.setChild(loopIdx, tiledVersion);

        DDTDriver ddt = new DDTDriver(program);

        ddt.start();
        ddt.analyzeLoopsForDependence(tiledVersion);

        DDGraph ddGraph = program.getDDGraph();

        LinkedList<Loop> loops = new LinkedList<>();
        new DFIterator<Loop>(tiledVersion, Loop.class).forEachRemaining(loops::add);
        dependenceVectors = ddGraph.getDirectionMatrix(loops);

        logger.info("### Tiled version ###");
        logger.info(tiledVersion + "\n");

        logger.info("### Direction vector");
        logger.info(dependenceVectors + "\n");

        return dependenceVectors;

    }

    private int getOuterParallelLoopIdx(ForLoop loopNest, List<DependenceVector> dependenceVectors) {
        int position = -1;

        List<ForLoop> loops = new ArrayList<>();
        new DFIterator<ForLoop>(loopNest, ForLoop.class).forEachRemaining(loops::add);

        if (loops.size() == 0) {
            return -1;
        }

        if (loops.get(0) != loopNest) {
            // to have a list with ordered loops from the outermost to the innermost
            Collections.reverse(loops);
        }

        for (int i = 0; i < loops.size(); i++) {
            ForLoop loop = loops.get(i);
            for (DependenceVector dependenceVector : dependenceVectors) {
                int direction = dependenceVector.getDirection(loop);
                if (direction == DependenceVector.less) {
                    position = i;
                }
            }
        }

        return position;
    }

    // TODO: Update reduction/private variable attributes
    private void updateAttributes() {
        AnalysisPass.run(new ArrayPrivatization(program));
        AnalysisPass.run(new DDTDriver(program));
        AnalysisPass.run(new Reduction(program));
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

    private Expression getTotalOfInstructions(ForLoop loopNest) throws Exception {
        List<ForLoop> loops = new ArrayList<>();
        new DFIterator<ForLoop>(loopNest, ForLoop.class).forEachRemaining(loops::add);

        Expression loopNestCond = loopNest.getCondition();
        if (!(loopNestCond instanceof BinaryExpression)) {
            throw new Exception("Condition should be a binary expression");
        }

        BinaryExpression cond = (BinaryExpression) loopNestCond;
        Expression totalOfInstructions = cond.getRHS();
        for (int i = 1; i < loops.size(); i++) {
            Expression loopCond = loops.get(i).getCondition();
            if ((loopCond instanceof BinaryExpression)) {
                totalOfInstructions = Symbolic.multiply(totalOfInstructions, ((BinaryExpression) loopCond).getRHS());
            }
        }

        return totalOfInstructions;
    }

    private void replaceTileSize(SymbolTable variableDeclarationSpace,
            Expression newTileSize) {

        Identifier balancedTileVariable = declareVariable(variableDeclarationSpace, BALANCED_TILE_SIZE_NAME,
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
            if (!id.getName().contains(TILE_SUFFIX)) {
                continue;
            }

            if (id.getName().equals(BALANCED_TILE_SIZE_NAME)) {
                continue;
            }
            Initializer init = new Initializer(balancedTileVariable.clone());
            declarator.setInitializer(init);
        }
    }

    private Expression computeBalancedTileSize(Expression numOfInstructions, int numOfProcessors,
            Expression rawTileSize) {

        Expression numOfProcessorsExp = new IntegerLiteral(numOfProcessors);

        // (numOfInstructions / (processors * rawSize)) * processors
        Expression divisor = Symbolic.multiply(
                Symbolic.divide(numOfInstructions, Symbolic.multiply(numOfProcessorsExp, rawTileSize)),
                numOfProcessorsExp);

        // instructions / ( ( instructions / (processors * rawSize) ) * processors )
        return Symbolic.divide(numOfInstructions, divisor);
    }

    // Implements the algorithm to find the block sized proposed in the paper the
    // Cache Performance and Optimization of Blocked Algorithms.
    // By Monica D. Lam, Edward E. Rothberg, Michael E. Wolf
    private int computeRawTileSize(int matrixSize, int cacheSize) {

        // int addr, di, dj, maxWidth;

        // maxWidth = Math.min(matrixSize, cacheSize);
        // addr = matrixSize / 2;
        // while (true) {
        // addr += cacheSize;
        // di = (int) Math.round((double) matrixSize / (double) addr);
        // dj = Math.abs((addr % matrixSize) - matrixSize / 2);
        // int auxMinMaxWidth = Math.min(maxWidth, dj);
        // if (di >= auxMinMaxWidth) {
        // return Math.round(Math.min(maxWidth, di));
        // }
        // maxWidth = auxMinMaxWidth;
        // }

        return 1000;
    }

    private void addNewVariableDeclarations(Traversable traversable, List<Declaration> variableDeclarations) {

        SymbolTable variableDeclarationSpace = getVariableDeclarationSpace(traversable);
        for (Declaration declaration : variableDeclarations) {
            variableDeclarationSpace.addDeclaration(declaration.clone());
        }

    }

    private List<Expression> runReusabilityAnalysis(Loop loopNest) {

        LoopInterchange loopInterchangePass = new LoopInterchange(program);
        loopInterchangePass.start();

        LinkedList<Loop> loopNestList = LoopTools.calculateInnerLoopNest(loopNest);

        memoryOrder = loopInterchangePass.ReusabilityAnalysis(program, loopNest,
                getLoopAssingmentExpressions(loopNest), getLoopArrayAccesses(loopNest), loopNestList);

        loopCostMap = loopInterchangePass.LoopCostMap != null ? loopInterchangePass.LoopCostMap
                : loopInterchangePass.Symbolic_LoopCostMap;

        DataReuseAnalysis.printLoopCosts(loopCostMap);

        logger.info(program.toString());

        logger.info("### REUSABILITY ###");

        for (int i = 0; i < memoryOrder.size(); i++) {
            Expression expr = memoryOrder.get(i);
            logger.info(expr + "\n");
        }

        logger.info("### REUSABILITY END ###");

        return memoryOrder;
    }

    private List<ForLoop> createTiledVersions(Loop loopNest, SymbolTable variableDeclarationSpace) throws Exception {

        List<ForLoop> nestedLoops = new ArrayList<>();
        new DFIterator<ForLoop>(loopNest, ForLoop.class).forEachRemaining(nestedLoops::add);

        List<ForLoop> tiledVersions = new ArrayList<>();
        HashMap<String, Boolean> stripminedBySymbol = new HashMap<>();

        if (nestedLoops.size() < MAX_NEST_LEVEL) {
            createTiledVersions(variableDeclarationSpace, (ForLoop) loopNest, 20,
                    stripminedBySymbol,
                    tiledVersions);

            int originalLoopNestIdx = -1;
            ForLoop originalLoopNestNoAnnotations = ((ForLoop) loopNest).clone(false);
            for (int i = 0; i < tiledVersions.size(); i++) {
                if (tiledVersions.get(i).equals(originalLoopNestNoAnnotations)) {
                    originalLoopNestIdx = i;
                    break;
                }
            }

            if (originalLoopNestIdx != -1) {
                tiledVersions.remove(originalLoopNestIdx);
            }

            logger.info("########## PRINTING TILED VERSIONS\n");

            for (ForLoop tiledVersion : tiledVersions) {
                logger.info("######## TILED VERSION #########\n");
                logger.info(tiledVersion + "\n");
                logger.info("######## END TILED VERSION #########\n");
            }
        }

        return tiledVersions;

    }

    private void createTiledVersions(SymbolTable variableDeclarationSpace, ForLoop loopNest,
            int strip,
            HashMap<String, Boolean> stripmined, List<ForLoop> versions) throws Exception {

        List<ForLoop> nestedLoops = new ArrayList<>();
        new DFIterator<ForLoop>(loopNest, ForLoop.class).forEachRemaining(nestedLoops::add);
        int loopNestSize = nestedLoops.size();

        for (int i = loopNestSize - 1; i >= 0; i--) {

            String loopSymbolName = LoopTools.getLoopIndexSymbol(nestedLoops.get(i)).getSymbolName();
            if (stripmined.containsKey(loopSymbolName)) {
                continue;
            }

            ForLoop stripminedLoop = (ForLoop) tiling(variableDeclarationSpace, loopNest.clone(false),
                    strip, i, stripmined);
            versions.add(stripminedLoop);

            HashMap<String, Boolean> newStripmined = new HashMap<>();
            for (String symbol : stripmined.keySet()) {
                newStripmined.put(symbol, true);
            }

            createTiledVersions(variableDeclarationSpace, stripminedLoop.clone(false),
                    strip,
                    newStripmined, versions);

        }

    }

    private void replaceLoop(ForLoop originalLoop, Statement ifStm) {
        Traversable originalParent = originalLoop.getParent();

        int originalLoopIdx = -1;
        for (int i = 0; i < originalParent.getChildren().size(); i++) {
            if (originalParent.getChildren().get(i) == originalLoop) {
                originalLoopIdx = i;
                break;
            }
        }

        originalParent.setChild(originalLoopIdx, ifStm);
    }

    private Loop tiling(SymbolTable variableDeclarationSpace, ForLoop outermostLoop, int strip,
            int targetLoopPos, HashMap<String, Boolean> stripmined) throws Exception {
        List<ForLoop> loops = new ArrayList<>();
        new DFIterator<ForLoop>(outermostLoop, ForLoop.class).forEachRemaining(loops::add);

        ForLoop targetLoop = loops.get(targetLoopPos);
        ForLoop crossStripLoop = (ForLoop) stripmining(variableDeclarationSpace, targetLoop, strip);

        stripmined.put(LoopTools.getLoopIndexSymbol(targetLoop).getSymbolName(), true);
        stripmined.put(LoopTools.getLoopIndexSymbol(crossStripLoop).getSymbolName(), true);

        if (targetLoopPos - 1 >= 0) {
            ForLoop parentLoop = loops.get(targetLoopPos - 1);
            parentLoop.setBody(crossStripLoop);
            permuteLoop(crossStripLoop, parentLoop);

            return outermostLoop;
        }

        return crossStripLoop;
    }

    private void permuteLoop(ForLoop loop, ForLoop targetLoop) {

        Statement originalInitStm = targetLoop.getInitialStatement().clone();
        Expression originalCond = targetLoop.getCondition().clone();
        Expression originalStep = targetLoop.getStep().clone();

        targetLoop.setInitialStatement(loop.getInitialStatement().clone());
        targetLoop.setCondition(loop.getCondition().clone());
        targetLoop.setStep(loop.getStep().clone());

        loop.setInitialStatement(originalInitStm);
        loop.setCondition(originalCond);
        loop.setStep(originalStep);
    }

    private Loop stripmining(SymbolTable variableDeclarationSpace, ForLoop loop, int strip) throws Exception {
        Statement loopInitialStatement = loop.getInitialStatement();
        if (loopInitialStatement.getChildren().size() > 1) {
            throw new Exception("Loop's initial statement has more than 2 expressions: " + loop.toString());
        }

        if (loopInitialStatement.getChildren().size() == 0) {
            throw new Exception("Loop's initial statement hasno expressions: " + loop.toString());
        }

        Symbol loopSymbol = LoopTools.getLoopIndexSymbol(loop);
        Identifier newIndexVariable = declareVariable(variableDeclarationSpace,
                loopSymbol.getSymbolName() + loopSymbol.getSymbolName());

        Identifier stripIdentifier = declareVariable(variableDeclarationSpace,
                loopSymbol.getSymbolName() + TILE_SUFFIX, new IntegerLiteral(strip));

        Loop inStripLoop = createInStripLoop(loop, stripIdentifier, newIndexVariable);
        Loop crossStripLoop = createCrossStripLoop(loop, stripIdentifier, newIndexVariable, (Statement) inStripLoop);

        return crossStripLoop;

    }

    private Loop createInStripLoop(ForLoop loop, Expression stripExpr, Identifier newIndexVariable) throws Exception {
        Statement originalInitStatement = loop.getInitialStatement();
        List<Traversable> originalInitStatements = originalInitStatement.getChildren();
        if (originalInitStatements.size() > 1) {
            throw new Exception("Loop has more than one initial statment");
        }

        if (originalInitStatements.size() == 0) {
            throw new Exception("Loop has no initial statment");
        }

        Expression originalInitExpr = (Expression) originalInitStatements.get(0);
        Expression originalCondition = loop.getCondition();

        if (!(originalInitExpr instanceof AssignmentExpression)) {
            throw new Exception("Loop's init statement is not an assignment expression");
        }

        if (!(originalCondition instanceof BinaryExpression)) {
            throw new Exception("Loop has no binary expression as original condition");
        }

        AssignmentExpression oriAssignmentExp = (AssignmentExpression) originalInitExpr;
        Expression initLHSExp = oriAssignmentExp.getLHS();
        AssignmentOperator assignmentOperator = oriAssignmentExp.getOperator();

        Expression newLoopInitExp = new AssignmentExpression(initLHSExp.clone(), assignmentOperator,
                newIndexVariable);

        BinaryExpression originalLoopCondition = (BinaryExpression) originalCondition;
        Expression condRHS = originalLoopCondition.getRHS();
        Expression condLHS = originalLoopCondition.getLHS();
        BinaryOperator condOperator = originalLoopCondition.getOperator();

        Symbol loopSymbol = LoopTools.getLoopIndexSymbol(loop);

        if (!loopSymbol.getSymbolName().equals(condLHS.toString())) {
            throw new Exception("LHS is not a symbol");
        }

        Expression stripCondition = Symbolic.subtract(stripExpr, new IntegerLiteral(1));
        stripCondition = Symbolic.add(stripCondition, newIndexVariable);
        Expression minExp = new ConditionalExpression(
                new BinaryExpression(stripCondition.clone(), BinaryOperator.COMPARE_LT, condRHS.clone()),
                stripCondition.clone(), condRHS.clone());

        Expression newLoopCondition = new BinaryExpression(
                condLHS.clone(), condOperator, minExp);

        Expression newLoopStepExp = loop.getStep().clone();

        Statement newLoopInitStm = loop.getInitialStatement().clone();
        ((Expression) newLoopInitStm.getChildren().get(0)).swapWith(newLoopInitExp);

        Statement newLoopBody = loop.getBody().clone(false);

        ForLoop inStripLoop = new ForLoop(newLoopInitStm, newLoopCondition, newLoopStepExp, newLoopBody);

        return inStripLoop;
    }

    private Loop createCrossStripLoop(ForLoop loop, Expression stripExpr, Identifier newIndexVariable,
            Statement inStripLoop) throws Exception {

        Statement originalInitStatement = loop.getInitialStatement();
        List<Traversable> originalInitStatements = originalInitStatement.getChildren();
        if (originalInitStatements.size() > 1) {
            throw new Exception("Loop has more than one initial statment");
        }

        if (originalInitStatements.size() == 0) {
            throw new Exception("Loop has no initial statment");
        }

        Expression originalInitExpr = (Expression) originalInitStatements.get(0);
        Expression originalCondition = loop.getCondition();

        if (!(originalInitExpr instanceof AssignmentExpression)) {
            throw new Exception("Loop's init statement is not an assignment expression");
        }

        if (!(originalCondition instanceof BinaryExpression)) {
            throw new Exception("Loop has no binary expression as original condition");
        }

        AssignmentExpression oriAssignmentExp = (AssignmentExpression) originalInitExpr;
        Expression initRHSExp = oriAssignmentExp.getRHS();
        AssignmentOperator assignmentOperator = oriAssignmentExp.getOperator();

        Symbol loopSymbol = newIndexVariable.getSymbol();
        Expression newLoopInitExp = new AssignmentExpression(newIndexVariable.clone(), assignmentOperator,
                initRHSExp.clone());

        BinaryExpression originalLoopCondition = (BinaryExpression) originalCondition;
        Expression condRHS = originalLoopCondition.getRHS();
        Expression condLHS = originalLoopCondition.getLHS();
        BinaryOperator condOperator = originalLoopCondition.getOperator();

        if (loopSymbol.getSymbolName().equals(condLHS.toString())) {
            condLHS = newIndexVariable;

        } else if (loopSymbol.getSymbolName().equals(condRHS.toString())) {
            condRHS = newIndexVariable;

        }

        Expression newLoopCondition = new BinaryExpression(
                newIndexVariable.clone(), condOperator, condRHS.clone());

        Expression stepLHS = newIndexVariable;
        Expression stepRHS = stripExpr;
        Expression newLoopStepExp = new AssignmentExpression(stepLHS.clone(), AssignmentOperator.ADD, stepRHS.clone());

        Statement newLoopInitStm = loop.getInitialStatement().clone();
        ((Expression) newLoopInitStm.getChildren().get(0)).swapWith(newLoopInitExp);

        ForLoop crossStripLoop = new ForLoop(newLoopInitStm, newLoopCondition, newLoopStepExp, inStripLoop);

        return crossStripLoop;
    }

    private SymbolTable getVariableDeclarationSpace(Traversable traversable) {

        if (traversable instanceof SymbolTable) {
            return (SymbolTable) traversable;
        }
        Traversable auxTraversable = traversable.getParent();
        while (auxTraversable != null && auxTraversable.getParent() != null
                && !(auxTraversable instanceof SymbolTable)) {
            auxTraversable = auxTraversable.getParent();
        }

        return (SymbolTable) auxTraversable;
    }

    private Identifier declareVariable(SymbolTable variableDeclarationSpace, String varName) {

        return declareVariable(variableDeclarationSpace, varName, null);
    }

    private Identifier declareVariable(SymbolTable variableDeclarationSpace, String varName,
            Expression value) {

        NameID variableNameID = new NameID(varName);

        VariableDeclarator varDeclarator = new VariableDeclarator(variableNameID);

        if (value != null) {
            Initializer initializer = new Initializer(value);
            varDeclarator.setInitializer(initializer);
        }
        Declaration varDeclaration = new VariableDeclaration(Specifier.INT, varDeclarator);

        if (variableDeclarationSpace.findSymbol(variableNameID) == null) {
            variableDeclarationSpace.addDeclaration(varDeclaration);
        }

        Identifier varIdentifier = new Identifier(varDeclarator);

        return varIdentifier;
    }

    private List<AssignmentExpression> getLoopAssingmentExpressions(Loop loop) {
        List<AssignmentExpression> assignmentExpressions = new ArrayList<>();
        DepthFirstIterator<Traversable> statementsIterator = new DepthFirstIterator<>(loop);
        while (statementsIterator.hasNext()) {
            Traversable stm = statementsIterator.next();
            if (stm instanceof AssignmentExpression) {
                assignmentExpressions.add((AssignmentExpression) stm);
            }
        }
        return assignmentExpressions;
    }

    public List<ArrayAccess> getLoopArrayAccesses(Loop loop) {
        List<ArrayAccess> arrayAccesses = new ArrayList<>();
        DepthFirstIterator<Traversable> statementsIterator = new DepthFirstIterator<>(loop);
        while (statementsIterator.hasNext()) {
            Traversable stm = statementsIterator.next();
            if (stm instanceof ArrayAccess) {
                arrayAccesses.add((ArrayAccess) stm);
            }
        }
        return arrayAccesses;
    }

    public List<Loop> filterValidLoops(List<Loop> loops) {
        List<Loop> validLoops = new ArrayList<>();
        for (Loop loop : loops) {
            if (isCanonical(loop)
                    && isPerfectNest(loop)
                    && !containsFunctionCalls(loop)
                    && isIncreasingOrder(loop)) {
                validLoops.add(loop);
            }
        }

        return validLoops;
    }

    public boolean isIncreasingOrder(Loop loop) {

        if (LoopTools.getIncrementExpression(loop).toString().equals("-1")) {
            analysisData.nonIncreasingOrderLoops.add(loop);
            return false;
        }

        return true;
    }

    public boolean isCanonical(Loop loop) {
        if (!LoopTools.isCanonical(loop)) {
            analysisData.nonCanonicalLoops.add(loop);
            return false;
        }
        return true;
    }

    public boolean isPerfectNest(Loop loop) {
        if (!LoopTools.isPerfectNest(loop)) {
            analysisData.nonPerfectNestLoops.add(loop);
            return false;
        }
        return true;
    }

    public boolean containsFunctionCalls(Loop loop) {
        if (LoopTools.containsFunctionCall(loop)) {
            analysisData.withFunctionCallLoops.add(loop);
            return true;
        }
        return false;
    }

}

package cetus.transforms;

import cetus.analysis.DDGraph;
import cetus.analysis.DependenceVector;
import cetus.analysis.LoopTools;
import cetus.exec.CommandLineOptionSet;
import cetus.exec.Driver;
import cetus.hir.*;
import cetus.utils.DataDependenceUtils;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Exchage loops if they are perfect nested loop.
 */
public class LoopTiling extends TransformPass {
    // protected Program program;

    private CommandLineOptionSet options;

    public LoopTiling(Program program) {
        this(program, null);
    }

    public LoopTiling(Program program, CommandLineOptionSet options) {
        super(program);
        this.options = options;
    }

    public String getPassName() {
        return new String("[LoopTiling]");
    }

    public void start() {

        DataDependenceUtils.printDirectionMatrix(program);

        LinkedList<Loop> loops = new LinkedList<Loop>();
        List<Statement> outer_loops = new ArrayList<Statement>();
        List<DependenceVector> depVec = new ArrayList<DependenceVector>();
        DepthFirstIterator iter = new DepthFirstIterator(program);
        List<Expression> expList = new LinkedList<Expression>();
        int i, j;
        int target_loops = 0;
        int num_single = 0, num_non_perfect = 0, num_contain_func = 0;
        int numOfTiling = 0, reuse_in_innermost = 0, no_reuse = 0;
        // DDTDriver ddtd = new DDTDriver(program);
        // this.program.getDDGraph();

        while (iter.hasNext()) {
            Object o = iter.next();
            if (o instanceof ForLoop)
                outer_loops.add((Statement) o);
        }

        for (i = outer_loops.size() - 1; i >= 0; i--) {
            if (!LoopTools.isOutermostLoop((ForLoop) outer_loops.get(i))) {
                // System.out.print("Remove : ");
                // System.out.println(((ForLoop)outer_loops.get(i)).getInitialStatement());
                outer_loops.remove(i);
            }
        }

        // System.out.println("# of Outermost : " + outer_loops.size());

        for (i = outer_loops.size() - 1; i >= 0; i--) {
            // System.out.println("Check #" + i + " loop");
            iter = new DepthFirstIterator(outer_loops.get(i));
            loops.clear();
            while (iter.hasNext()) {
                Object o = iter.next();
                if (o instanceof ForLoop)
                    loops.add((Loop) o);
            }
            // System.out.println("DDG ");
            // System.out.println(loops.size());
            if (loops.size() < 2) {
                // System.out.println("Single loop");
                num_single++;
            } else if (!LoopTools.isPerfectNest((ForLoop) loops.get(0))) {
                num_non_perfect++;
                // System.out.println("It is not a perfect nested loop.");
            } else if (LoopTools.containsFunctionCall((ForLoop) loops.get(0))) {
                num_contain_func++;
                // System.out.println("Contains Function.");
                // return;
                // break;
            } else {

                target_loops++;
                Statement stm = ((ForLoop) loops.get(loops.size() - 1)).getBody();
                List<ArrayAccess> arrays = new ArrayList<ArrayAccess>(); // Arrays in loop body
                DepthFirstIterator iter2 = new DepthFirstIterator(stm);

                // isLegal(loops, 0 , 1);
                while (iter2.hasNext()) {
                    Object child = iter2.next();
                    if (child instanceof ArrayAccess) {
                        arrays.add((ArrayAccess) child);
                    }
                }

                // System.out.println("Loop : ");
                // System.out.println(loops.get(0));
                // System.out.println();

                int loopSize = loops.size();
                int firstNonZero = -1;
                int[] reuseValue = new int[loopSize];

                // reuseValue : 'n' bit will be located in the cache before reuse
                getSelfReuseAll(loops, arrays, reuseValue);
                getGroupReuseAll(loops, arrays, reuseValue);

                for (j = 0; j < loopSize; j++) {
                    if (reuseValue[j] > 0) {
                        firstNonZero = j;
                        break;
                    }
                }

                int cacheSize = 32 * 1024 * 8;
                int arrayBits = 0;
                int blockSize, blockSizeRemain;
                // System.out.println("firstNonZero = " + firstNonZero + ", LoopSize = " +
                // loopSize);
                if (firstNonZero >= 0 && firstNonZero < (loopSize - 1)) {
                    for (j = 0; j < arrays.size(); j++)
                        arrayBits += getTypeSize(arrays.get(j));

                    blockSize = cacheSize / arrayBits;
                    blockSizeRemain = cacheSize % arrayBits;

                    for (j = 0; j < (loopSize - firstNonZero); j++) {
                        if (j == loops.size() - 1)
                            blockSize += blockSizeRemain;

                        Loop targetLoop = loops.get(firstNonZero + j * 2);

                        try {
                            tiling(loops, targetLoop, firstNonZero, blockSize);
                            numOfTiling++;

                        } catch (Exception e) {
                            System.out.println("Unable to be tiled: " + targetLoop);
                            System.out.println("Exception");
                            e.printStackTrace();
                        }
                    }

                } else if (firstNonZero > 0) {
                    reuse_in_innermost++;
                } else
                    no_reuse++;

                // temp_f.setInitialStatement(temp_f2.getInitialStatement());
                // temp_f.setStep(temp_f2.getStep());
                // temp_f.setCondition(temp_f2.getCondition());
                // temp_f.setBody();

                // System.out.println("For loop\n" + temp_for);
                // System.out.println("For loop end");

                // ((CompoundStatement)t.getParent()).addStatementBefore((Statement)(loops.get(0)),
                // (Statement)temp_for);

            }
        }

        reRunPasses();

        System.out.println("# of single loops : " + num_single);
        System.out.println("# of non-perfect loops : " + num_non_perfect);
        System.out.println("# of function nested loops : " + num_contain_func);
        System.out.println("# of target loops : " + target_loops);
        System.out.println("# of reuse only in the inner-most loops : " + reuse_in_innermost);
        System.out.println("# of no reuse in loops : " + no_reuse);
        System.out.println("# of tiled loops : " + numOfTiling);
        return;
    }

    private void reRunPasses() {
        System.out.println("###### re running passes");
        try {
            File temp = File.createTempFile("CetusTemp", ".c");
            FileWriter fr = new FileWriter(temp);
            fr.write(program.toString());
            fr.close();

            String tempPath = temp.getAbsolutePath();

            ArrayList<String> argsList = new ArrayList<>();

            for (String key : options.name_to_record.keySet()) {
                if (key.equals("loop-tiling")) {
                    continue;
                }
                String value = options.getValue(key);

                if (value == null) {
                    continue;
                }

                String arg = "-" + key + "=" + value;
                argsList.add(arg);

            }

            argsList.add(tempPath);

            String[] args = new String[argsList.size()];
            argsList.toArray(args);
            Driver.main(args);

        } catch (Exception e) {
            System.out.println("Cannot re run passes");
            e.printStackTrace();
        }

    }

    /**
     * Performs loop tiling with the given input. It assumes the loop nest is
     * perfectly nested.
     * 
     * @param loops       the list of loops within the nest.
     * @param targetLoop  the loop to be tiled.
     * @param newLocation the position in "loops" to which the blocking code is
     *                    inserted.
     * @param blockSize   the block size.
     */
    private boolean tiling(List<Loop> loops, Loop targetLoop, int newLocation, int blockSize) {
        // blockSize = 64;
        // System.out.println("blockSize = " + blockSize);
        Expression tempVariable;
        Symbol symbol = LoopTools.getLoopIndexSymbol(targetLoop);
        tempVariable = (Expression) declareTemp((Traversable) (loops.get(0)), symbol.getSymbolName());

        // System.out.println("before size of loops : " + loops.size());
        targetLoop = insertNewLoop(loops, targetLoop, tempVariable, symbol, newLocation, blockSize);
        if (targetLoop == null) {
            // System.out.println("targetLoop is NULL!");
            return false;
        }

        // System.out.println("after size of loops : " + loops.size());

        Statement oriInitStat = ((ForLoop) targetLoop).getInitialStatement();
        Statement newInitStat = oriInitStat.clone();
        List<Traversable> oriInit = newInitStat.getChildren();
        Expression oriCondition = ((ForLoop) targetLoop).getCondition();
        Expression oriStep = ((ForLoop) targetLoop).getStep();
        Expression newCondition;

        if (oriInit.size() > 1)
            System.out.println("For loop has more than one initial statement!");
        // else System.out.println("oriInit size : "+oriInit.size());

        Expression oriInitExp = (Expression) oriInit.get(0);

        if (oriInitExp instanceof AssignmentExpression) {
            Expression oriInitLHS = ((AssignmentExpression) oriInitExp).getLHS();
            Expression oriInitRHS = ((AssignmentExpression) oriInitExp).getRHS();
            AssignmentOperator oriInitOp = ((AssignmentExpression) oriInitExp).getOperator();
            Expression newInitExp = new AssignmentExpression(
                    oriInitLHS.clone(), oriInitOp, tempVariable.clone());
            oriInitExp.swapWith(newInitExp);
            // System.out.println("Target : New init Exp - " + newInitExp);
            // System.out.println("Target : New init Statement - " + newInitStat);
        } else
            System.out.println("Initial Expression is not Assignment!");

        if (oriCondition instanceof BinaryExpression) {
            Expression condRHS = ((BinaryExpression) oriCondition).getRHS();
            Expression condLHS = ((BinaryExpression) oriCondition).getLHS();
            BinaryOperator condOp = ((BinaryExpression) oriCondition).getOperator();

            if (symbol.getSymbolName().equals(condLHS.toString())) {
                Expression blk = new IntegerLiteral(blockSize);
                Expression one = new IntegerLiteral(1);
                Expression exp1 = Symbolic.add(tempVariable, blk);
                Expression exp2 = Symbolic.subtract(exp1, one);
                Expression minExp = new ConditionalExpression(
                        new BinaryExpression(
                                exp2.clone(),
                                BinaryOperator.COMPARE_LT,
                                condRHS.clone()),
                        exp2.clone(),
                        condRHS.clone());
                newCondition = new BinaryExpression(
                        condLHS.clone(), condOp, minExp);
                // System.out.println("Target : Condition : " + oriCondition);
                // System.out.println("Target : new Condition : " + newCondition);
            } else {
                System.out.println("LHS is not a symbol!");
                newCondition = oriCondition;
            }
        } else
            newCondition = oriCondition;
        ForLoop newLoop = new ForLoop(newInitStat.clone(),
                newCondition.clone(), oriStep.clone(),
                new CompoundStatement());
        targetLoop.getBody().swapWith(newLoop.getBody());
        ((Statement) (targetLoop)).swapWith((Statement) newLoop);
        return true;
    }

    private Loop insertNewLoop(List<Loop> loops, Loop targetLoop, Expression tempVar, Symbol symbol, int newLocation,
            int blockSize) {
        Statement oriInitStat = ((ForLoop) targetLoop).getInitialStatement();
        Statement newInitStat = oriInitStat.clone();
        List<Traversable> oriInit = newInitStat.getChildren();
        Expression oriCondition = ((ForLoop) targetLoop).getCondition();
        Expression oriStep = ((ForLoop) targetLoop).getStep();

        Expression newInitExp, newCondition, newStep;

        if (oriInit.size() > 1)
            System.out.println("For loop has more than one initial statement!");
        else
            System.out.println("oriInit size : " + oriInit.size());

        Expression oriInitExp = (Expression) oriInit.get(0);

        if (oriInitExp instanceof AssignmentExpression) {
            Expression oriInitLHS = ((AssignmentExpression) oriInitExp).getLHS();
            Expression oriInitRHS = ((AssignmentExpression) oriInitExp).getRHS();
            AssignmentOperator oriInitOp = ((AssignmentExpression) oriInitExp).getOperator();
            newInitExp = new AssignmentExpression(tempVar, oriInitOp, oriInitRHS.clone());
            oriInitExp.swapWith(newInitExp);

            // System.out.println("InitialStatement : " + oriInitStat);
            // System.out.println("new InitialStatement : " + newInitStat);
        }

        if (oriCondition instanceof BinaryExpression) {
            // System.out.println("oriCondition is Binary");
            Expression condRHS = ((BinaryExpression) oriCondition).getRHS();
            Expression condLHS = ((BinaryExpression) oriCondition).getLHS();
            BinaryOperator condOp = ((BinaryExpression) oriCondition).getOperator();

            if (symbol.getSymbolName().equals(condLHS.toString()))
                condLHS = tempVar;
            else if (symbol.getSymbolName().equals(condRHS.toString()))
                condRHS = tempVar;

            newCondition = new BinaryExpression(
                    condLHS.clone(), condOp, condRHS.clone());
            // System.out.println("Condition : " + oriCondition);
            // System.out.println("new Condition : " + newCondition);
        } else
            newCondition = oriCondition.clone();

        Expression stepRHS = new IntegerLiteral(blockSize);
        Expression stepLHS = tempVar;
        newStep = Symbolic.add(stepLHS, stepRHS);
        newStep = new AssignmentExpression(
                stepLHS.clone(),
                AssignmentOperator.ADD,
                stepRHS.clone());
        /*
         * if(oriStep instanceof BinaryExpression)
         * {
         * System.out.println("oriStep is Binary");
         * Expression stepRHS = ((BinaryExpression)oriStep).getRHS();
         * Expression stepLHS = ((BinaryExpression)oriStep).getLHS();
         * BinaryOperator stepOp = ((BinaryExpression)oriStep).getOperator();
         * 
         * if(symbol.getSymbolName().equals(stepLHS.toString()))
         * stepLHS = tempVar;
         * else if(symbol.getSymbolName().equals(stepRHS.toString()))
         * stepRHS = tempVar;
         * 
         * newStep = new BinaryExpression(stepLHS, stepOp, stepRHS);
         * System.out.println("Step : " + oriStep);
         * System.out.println("new Step : " + newStep);
         * } else if(oriStep instanceof UnaryExpression) {
         * System.out.println("oriStep is Unary");
         * UnaryOperator stepOp = ((UnaryExpression)oriStep).getOperator();
         * 
         * newStep = new UnaryExpression(stepOp, tempVar);
         * System.out.println("Step : " + oriStep);
         * System.out.println("new Step : " + newStep);
         * } else newStep = (Expression)oriStep.clone();
         */

        ForLoop tiledLoop = (ForLoop) loops.get(newLocation);

        // To avoid orphan exception
        Traversable parent = tiledLoop.getParent();
        // parent.setChild(index, t);
        int tiledLoopIdx = -1;
        for (int i = 0; i < parent.getChildren().size(); i++) {
            if (parent.getChildren().get(i) == tiledLoop) {
                tiledLoopIdx = i;
                break;
            }
        }

        parent.setChild(tiledLoopIdx, new CompoundStatement());

        CompoundStatement newLoopStatement = new CompoundStatement();
        newLoopStatement.addStatement((ForLoop) tiledLoop);
        // Traversable t = (Traversable) (loops.get(newLocation));

        ForLoop newLoop = new ForLoop(newInitStat, newCondition, newStep, newLoopStatement);
        // ((Statement) t).swapWith((Statement) newLoop);

        parent.setChild(tiledLoopIdx, newLoop);

        loops.add(newLocation, newLoop);

        DepthFirstIterator<Traversable> iter = new DepthFirstIterator<Traversable>(newLoop);
        // System.out.println("Target loop : " + targetLoop);
        while (iter.hasNext()) {
            Object o = iter.next();
            // System.out.println("Object : " + o);
            if (o instanceof ForLoop && ((ForLoop) o).toString().equals(targetLoop.toString()))
                return (Loop) o;
        }
        return null;
    }

    private Identifier declareTemp(Traversable t, String s) {
        t = t.getParent();
        while (!(t instanceof SymbolTable))
            t = t.getParent();
        SymbolTable symtab = (SymbolTable) t;

        NameID temp = new NameID(s);
        int temp_i = 1;
        while (symtab.findSymbol(temp) != null)
            temp = new NameID(s + "_tiling" + (temp_i++));

        VariableDeclarator temp_declarator = new VariableDeclarator(temp);
        Declaration temp_decl = new VariableDeclaration(Specifier.INT, temp_declarator);
        Identifier ret = new Identifier(temp_declarator);

        // temp = SymbolTools.getTemp(temp);
        // System.out.println(temp.getSymbol() + "name : "+temp.getName());
        symtab.addDeclaration(temp_decl);

        return ret;
    }

    // Find out which loops could legally interchanged with innermost loop.

    public boolean isLegal(LinkedList<Loop> nest, int src, int target) {
        int i, j, next;
        DDGraph ddg;
        String str;
        ArrayList<DependenceVector> dpv;
        DependenceVector dd;
        ddg = program.getDDGraph();
        dpv = ddg.getDirectionMatrix(nest);

        if (src == target)
            return true;
        if (src > target) {
            i = src;
            src = target;
            target = i;
        }

        // System.out.println("DDVector :");
        // System.out.println(dpv);

        for (i = 0; i < dpv.size(); i++) {
            dd = dpv.get(i);
            str = dd.VectorToString();
            for (j = 0; j < str.length(); j++) {
                if (j == src)
                    next = target;
                else if (j == target)
                    next = src;
                else
                    next = j;

                if (next < str.length()) {
                    if (str.charAt(next) == '>')
                        return false;
                    if (str.charAt(next) == '<')
                        break;
                }
            }
        }

        return true;
    }

    /**
     * Calculate for each loop the reuse distance between every pair of arrays in
     * the arrays passed as paramters. Then, if the reuse distance is greater than
     * the old reuse value for that loop in the reuseValue vector, then the
     * algorithm will replace it
     * 
     * @param loops      loop nest, ordered from the outermost to the innermost
     *                   loop.
     * @param arrays     array accesses to be analyzed for the group reuse.
     * @param reuseValue a reuse value of size the amount of loops in the loop nest.
     * @return
     */
    private int getGroupReuseAll(LinkedList<Loop> loops, List<ArrayAccess> arrays, int[] reuseValue) {
        long reuseDistance;
        int i, j, k;

        for (i = 0; i < loops.size(); i++) {
            for (j = 0; j < arrays.size(); j++) {
                for (k = j + 1; k < arrays.size(); k++) {
                    reuseDistance = LoopTools.getReuseDistance(loops.get(i), arrays.get(j), arrays.get(k));
                    if (reuseDistance > reuseValue[i])
                        reuseValue[i] = (int) reuseDistance;
                }
            }
        }

        // reuseDistance = LoopTools.getReuseDistance(loops.get(0), arrays.get(0),
        // arrays.get(1));
        // System.out.println("reuse dist : " + reuseDistance);\

        System.out.print("self group reuse value: < ");
        for (int z = 0; z < reuseValue.length; z++) {
            if (z == reuseValue.length - 1) {
                System.out.print("" + reuseValue[z]);
            } else {
                System.out.print(reuseValue[z] + ", ");
            }
        }

        System.out.println(" >");
        return 0;

    }

    /**
     * Calculate the amount of reusability for each loop in the loop list, based on
     * the array accesses passed as parameter.
     * First, the algorithm calculate for each loop if it has incidence in the array
     * accesess.
     * Next, if the loop has incidence in the array accesses, the algorithm identify
     * with arrays's specifier represents the most space in memory to be used. i.e.
     * if one of the array access is
     * for integers but the other one is for double or long, due to double uses 64
     * bits in memory and integer only 32 bits, the program will use 64 bits to
     * calculate the reuse for the loops that has incidence in that array access.
     * Finally, the algorithm evaluates the value of self reusing, (basically, the
     * specifier that will use the most memory space in the program) and if the
     * calculated value is greater than some older value in the reuse vector passed
     * as parameter, the algorithm will replace that information.
     * 
     * @param loops      loop nest as list, organized from outermost to innermost
     *                   position.
     * @param arrays     array access to analyze. Expected: Array accesses in the
     *                   innermost given a perfect loop nest
     * @param reuseValue list of reuse value to save or replace values resulting
     *                   from the algorithm
     * @return not used, always return 1. It can be used to validate if the
     *         algorithm ran successfully
     */

    private int getSelfReuseAll(LinkedList<Loop> loops, List<ArrayAccess> arrays, int[] reuseValue) {
        long[] kernelVector;
        int loopSize = loops.size();
        List type;
        int typeSize;

        kernelVector = new long[loopSize];

        for (int i = 0; i < arrays.size(); i++) {

            kernelVector = getKernelVector(loops, arrays.get(i));

            System.out.println();
            typeSize = getTypeSize(arrays.get(i));

            for (int j = 0; j < loopSize; j++)
                if (reuseValue[j] < (kernelVector[j] * typeSize))
                    reuseValue[j] = (int) (kernelVector[j] * typeSize);

            // System.out.print("reuseValue : ");
            // for(int j=0; j < loopSize; j++)
            // System.out.print(reuseValue[j]+ " ");
            // System.out.println();
        }

        System.out.print("self reuse value: < ");
        for (int i = 0; i < reuseValue.length; i++) {
            if (i == reuseValue.length - 1) {
                System.out.print("" + reuseValue[i]);
            } else {
                System.out.print(reuseValue[i] + ", ");
            }
        }

        System.out.println(" >");

        return 1;
    }

    /**
     * Get the size in bits of an specific type/specifier in an array.
     * Example: if array is a boolean array, the return type will be 1. 32 for
     * Integer
     * 
     * @param array An array with an specific type of specifier
     * @return An integer that represents the size in bits of the type of the
     *         array's specifier passed as a paramteer.
     */
    private int getTypeSize(ArrayAccess array) {
        int typeSize;
        List types;
        Specifier type;

        types = ((Identifier) array.getArrayName()).getSymbol().getTypeSpecifiers();
        type = (Specifier) types.get(0);
        // System.out.println("gettype : " + type);

        if (type == Specifier.BOOL)
            typeSize = 1;
        else if (type == Specifier.CHAR)
            typeSize = 8;
        else if (type == Specifier.WCHAR_T)
            typeSize = 32;
        else if (type == Specifier.SHORT)
            typeSize = 16;
        else if (type == Specifier.INT)
            typeSize = 32;
        else if (type == Specifier.LONG)
            typeSize = 32;
        else if (type == Specifier.SIGNED)
            typeSize = 32;
        else if (type == Specifier.UNSIGNED)
            typeSize = 32;
        else if (type == Specifier.FLOAT)
            typeSize = 32;
        else if (type == Specifier.DOUBLE)
            typeSize = 64;
        else if (type == Specifier.VOID)
            typeSize = 8;
        else
            typeSize = 32;

        return typeSize;
    }

    /**
     * Analyze if each of the loops has an incidence in the array access
     * passed as parameter
     * 
     * @param loops the loop nest to analyze incidence
     * @param array the array accesses to be analyzed
     * @return An array with lenght = count of loops in the loops list passed as
     *         parameter.
     */

    private long[] getKernelVector(LinkedList<Loop> loops, ArrayAccess array) {
        int numLoops = loops.size();
        int numIndices = array.getNumIndices();
        double[][] matrix = getLoopMatrix(loops, array);

        System.out.println("### Loop Matrix ### ");

        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix[0].length; j++) {
                System.out.print(" " + matrix[i][j]);
                if (j != matrix[0].length - 1) {
                    System.out.print(",");
                }
            }
            System.out.println();
        }

        System.out.println("### GAUSS JORDAN Elimination Loop Matrix ### ");

        matrix = GaussJordan(matrix, numIndices, numLoops);
        // System.out.println("Final = ");
        // printMatrix(matrix);

        for (int i = 0; i < matrix.length; i++) {
            for (int j = 0; j < matrix[0].length; j++) {
                System.out.print(" " + matrix[i][j]);
                if (j != matrix[0].length - 1) {
                    System.out.print(",");
                }
            }
            System.out.println();
        }

        long kernel[] = new long[numLoops];
        for (int i = 0; i < numLoops; i++)
            kernel[i] = 1;

        for (int i = 1; i <= numIndices; i++)
            for (int j = 1; j <= numLoops; j++)
                if (matrix[i][j] != 0)
                    kernel[j - 1] = 0;

        System.out.print("Kernel vector: < ");
        for (int i = 0; i < numLoops; i++) {
            if (i == numLoops - 1) {
                System.out.print(kernel[i] + " >");
            } else {
                System.out.print(kernel[i] + ", ");
            }
        }
        System.out.println();

        return kernel;
    }

    /**
     * Matriz which represents the indicence / weight matrix of comparing
     * loops against array accesses.
     * Note: The matrix created is augmented and the first row will be a 0 vector
     * Example: Given an matrix 'a' with lenght nxn:
     * for (int i = 0; i < n; i++) {
     * for (int j = 0; j < n; j++) {
     *  a[i][j]=a[i*2][j*3]+a[i*4][j*5];
     * }
     * }
     * The result matrix, when analyzing the matrix access a[i*2][j*3], should be:
     *                    0, i, j, 0 : note, columns are organized by loop hierarchy
     * ----------
     * emptyrow :     | [ 0, 0, 0, 0
     * 1stAccess :    |   0, 2, 0, 0
     * secondAccess : |   0, 0, 3, 0
     * nthAccess :    |   ........ . ]
     * 
     * note: rows are organized by index access order
     * 
     * @param loops loops in a loopNest
     * @param array A matrix with the following dimensions: array
     * @return An array with length: num indices + 1 X loops count + 2 which
     *         represents the loopNest's matrix incidence
     * 
     */

    private double[][] getLoopMatrix(LinkedList<Loop> loops, ArrayAccess array) {
        double matrix[][];

        int numLoops = loops.size();
        int numIndices = array.getNumIndices();
        matrix = new double[numIndices + 1][numLoops + 2];

        Expression loopIndex, arrayIndex;
        List<Expression> tempIndex;
        Traversable parentExp;
        Expression lhs, rhs;

        for (int i = 0; i <= numIndices; i++)
            for (int j = 0; j <= numLoops + 1; j++)
                matrix[i][j] = 0;

        for (int i = 1; i <= numIndices; i++) {
            arrayIndex = array.getIndex(i - 1);
            for (int j = 1; j <= numLoops; j++) {
                loopIndex = LoopTools.getIndexVariable((ForLoop) loops.get(j - 1));
                tempIndex = arrayIndex.findExpression(loopIndex);
                if (tempIndex.size() > 0) {
                    parentExp = tempIndex.get(0).getParent();
                    if (parentExp instanceof BinaryExpression) {
                        if ((((BinaryExpression) parentExp).getOperator()).toString() == "*") {
                            lhs = ((BinaryExpression) parentExp).getLHS();
                            rhs = ((BinaryExpression) parentExp).getRHS();

                            if (rhs.equals((Object) loopIndex)) {
                                if (lhs instanceof IntegerLiteral)
                                    matrix[i][j] = ((IntegerLiteral) lhs).getValue();
                            }
                            if (lhs.equals((Object) loopIndex)) {
                                if (rhs instanceof IntegerLiteral)
                                    matrix[i][j] = ((IntegerLiteral) rhs).getValue();
                            }
                        }
                    }
                    if (matrix[i][j] == 0)
                        matrix[i][j] = 1;
                }
            }
        }

        /*
         * System.out.println("Loop Matrix : ");
         * for(int i = 1; i <= numIndices; i++)
         * {
         * System.out.println("");
         * for(int j = 1; j <= numLoops; j++)
         * System.out.print(matrix[i][j]+" ");
         * }
         * System.out.println();
         * System.out.println();
         */
        return matrix;
    }

    private static void swap(double[][] A, int i, int k, int j) {
        int m = A[0].length - 1;
        double temp;
        for (int q = j; q <= m; q++) {
            temp = A[i][q];
            A[i][q] = A[k][q];
            A[k][q] = temp;
        }
    }

    private static void divide(double[][] A, int i, int j) {
        int m = A[0].length - 1;
        for (int q = j + 1; q <= m; q++)
            A[i][q] /= A[i][j];
        A[i][j] = 1;
    }

    private static void eliminate(double[][] A, int i, int j) {
        int n = A.length - 1;
        int m = A[0].length - 1;
        for (int p = 1; p <= n; p++) {
            if (p != i && A[p][j] != 0) {
                for (int q = j + 1; q <= m; q++) {
                    A[p][q] -= A[p][j] * A[i][q];
                }
                A[p][j] = 0;
            }
        }
    }

    private static void printMatrix(double[][] A) {
        int n = A.length - 1;
        int m = A[0].length - 1;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++)
                System.out.print(A[i][j] + "  ");
            System.out.println();
        }
        System.out.println();
        System.out.println();
    }

    private static double[][] GaussJordan(double[][] A, int n, int m) {
        int i, j, k;
        // printMatrix(A);

        // perform Gauss-Jordan Elimination algorithm
        i = 1;
        j = 1;
        while (i <= n && j <= m) {

            // look for a non-zero entry in col j at or below row i
            k = i;
            while (k <= n && A[k][j] == 0)
                k++;

            // if such an entry is found at row k
            if (k <= n) {

                // if k is not i, then swap row i with row k
                if (k != i) {
                    swap(A, i, k, j);
                    // printMatrix(A);
                }

                // if A[i][j] is not 1, then divide row i by A[i][j]
                if (A[i][j] != 1) {
                    divide(A, i, j);
                    // printMatrix(A);
                }

                // eliminate all other non-zero entries from col j by subtracting from each
                // row (other than i) an appropriate multiple of row i
                eliminate(A, i, j);
                // printMatrix(A);
                i++;
            }
            j++;
        }

        // System.out.println("rank = " + (i-1));
        return A;
    }
}

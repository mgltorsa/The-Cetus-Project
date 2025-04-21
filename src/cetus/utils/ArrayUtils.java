package cetus.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import cetus.analysis.LoopTools;
import cetus.hir.ArrayAccess;
import cetus.hir.ArraySpecifier;
import cetus.hir.BinaryExpression;
import cetus.hir.DFIterator;
import cetus.hir.Declaration;
import cetus.hir.Expression;
import cetus.hir.ForLoop;
import cetus.hir.IDExpression;
import cetus.hir.Identifier;
import cetus.hir.IntegerLiteral;
import cetus.hir.Specifier;
import cetus.hir.Symbol;
import cetus.hir.SymbolTable;
import cetus.hir.Traversable;
import cetus.hir.UnaryExpression;
import cetus.hir.VariableDeclarator;

public class ArrayUtils {

    private static final long DEFAULT_DATA_SIZE = 10000 * 10000;

    public static final Long getArraySizeFromBounds(ForLoop loopNest, ArrayAccess access) {
        List<Expression> indices = access.getIndices();
        if (indices.size() == 0) {
            return DEFAULT_DATA_SIZE;
        }
        Map<Expression, Long> sizesPerIndexMap = new java.util.HashMap<>();
        for (Expression iExpression : indices) {
            if (iExpression instanceof BinaryExpression) {
                Expression left = ((BinaryExpression) iExpression).getLHS();
                if (sizesPerIndexMap.containsKey(left)) {
                    continue;
                }
                sizesPerIndexMap.put(left, 1L);
            }
        }
        boolean atLeastOneBoundCalculated = false;

        for (Expression indexExpression : indices) {

            DFIterator<ForLoop> loopIter = new DFIterator<>(loopNest, ForLoop.class);
            while (loopIter.hasNext()) {
                ForLoop loop = loopIter.next();
                Expression ub = LoopTools.getUpperBoundExpression(loop);
                if (!ub.toString().toLowerCase().contains(indexExpression.toString().toLowerCase())) {
                    continue;
                }
                if (ub instanceof IntegerLiteral) {
                    Long ubValue = ((IntegerLiteral) ub).getValue();
                    if (sizesPerIndexMap.containsKey(indexExpression)) {
                        ubValue = Math.max(ubValue, sizesPerIndexMap.get(indexExpression));
                    }
                    sizesPerIndexMap.put(access, ubValue);
                    atLeastOneBoundCalculated = true;
                }
                if (ub instanceof BinaryExpression) {
                    boolean isComputable = ub.getChildren().stream()
                            .allMatch(ubChild -> ubChild instanceof IntegerLiteral);
                    if (!isComputable) {
                        continue;
                    }
                    for (Traversable childExpr : ub.getChildren()) {
                        if (!(childExpr instanceof IntegerLiteral))
                            continue;

                        long ubValue = ((IntegerLiteral) childExpr).getValue();
                        if (sizesPerIndexMap.containsKey(indexExpression)) {
                            ubValue = Math.max(ubValue, sizesPerIndexMap.get(indexExpression));
                        }
                        sizesPerIndexMap.put(indexExpression, ubValue);
                        atLeastOneBoundCalculated = true;
                    }
                }
            }
        }

        long dataSize = 1;
        for (Long size : sizesPerIndexMap.values()) {
            dataSize *= size;
        }
        if (dataSize == 1 || !atLeastOneBoundCalculated) {
            return DEFAULT_DATA_SIZE;
        }

        return dataSize;

    }

    /**
     * 
     * @param symbols
     * @param arrayAccess
     * @return Size in terms of number of elements of the array
     */
    public static final Expression getArraySize(SymbolTable symbols, ArrayAccess arrayAccess) {
        long arraySize = 1;
        Expression arrayName = arrayAccess.getArrayName();
        IDExpression arrayID;

        if (arrayName instanceof IDExpression) {
            arrayID = (IDExpression) arrayName;
        } else {
            List<Traversable> ids = arrayName.getChildren()
                    .stream()
                    .filter(name -> name instanceof Identifier)
                    .collect(Collectors.toList());

            if (ids.isEmpty()) {
                return new IntegerLiteral(arraySize);
            }
            arrayID = (IDExpression) ids.get(0);
        }
        Declaration declaration = symbols.findSymbol(arrayID);

        List<Traversable> children = declaration.getChildren();
        for (int i = 0; i < children.size(); i++) {
            Traversable childObj = children.get(i);
            if (!(childObj instanceof VariableDeclarator)) {
                continue;
            }
            VariableDeclarator child = (VariableDeclarator) childObj;
            if (!child.getSymbolName().equals(arrayName.toString())) {
                continue;
            }
            @SuppressWarnings("unchecked")
            List<ArraySpecifier> specs = child.getArraySpecifiers();
            for (ArraySpecifier arraySpecifier : specs) {
                if (arraySpecifier.getNumDimensions() == 0) {
                    continue;
                }
                // Only rows dimension required
                int dimensions = arraySpecifier.getNumDimensions();
                for (int j = 0; j < dimensions; j++) {
                    Expression dimension = arraySpecifier.getDimension(j);
                    if (dimension == null) {
                        continue;
                    }

                    if (dimension instanceof BinaryExpression) {
                        boolean isComputable = dimension.getChildren().stream()
                                .allMatch(nullChild -> nullChild instanceof IntegerLiteral);
                        if (!isComputable) {
                            continue;
                        }
                        for (Traversable childExpr : dimension.getChildren()) {
                            if (childExpr instanceof IntegerLiteral) {
                                long dimensionSize = ((IntegerLiteral) childExpr).getValue();
                                if (dimensionSize <= 0)
                                    continue;
                                arraySize *= dimensionSize;
                            }
                        }
                    } else if (dimension instanceof IntegerLiteral) {
                        arraySize *= ((IntegerLiteral) dimension).getValue();
                    }

                }
            }
        }
        return new IntegerLiteral(arraySize);
    }

    public static final Expression getFullSize(SymbolTable symbols, List<ArrayAccess> arrayAccesses) {
        long dataSize = 1;
        for (ArrayAccess arrayAccess : arrayAccesses) {
            long arraySize = 1;
            Expression arrayName = arrayAccess.getArrayName();
            IDExpression arrayID;

            if (arrayName instanceof IDExpression) {
                arrayID = (IDExpression) arrayName;
            } else {
                List<Traversable> ids = arrayName.getChildren()
                        .stream()
                        .filter(name -> name instanceof Identifier)
                        .collect(Collectors.toList());

                if (ids.isEmpty()) {
                    return new IntegerLiteral(dataSize);
                }
                arrayID = (IDExpression) ids.get(0);
            }
            Declaration declaration = symbols.findSymbol(arrayID);

            List<Traversable> children = declaration.getChildren();
            for (int i = 0; i < children.size(); i++) {
                Traversable childObj = children.get(i);
                if (!(childObj instanceof VariableDeclarator)) {
                    continue;
                }
                VariableDeclarator child = (VariableDeclarator) childObj;
                if (!child.getSymbolName().equals(arrayName.toString())) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<ArraySpecifier> specs = child.getArraySpecifiers();
                for (ArraySpecifier arraySpecifier : specs) {
                    if (arraySpecifier.getNumDimensions() == 0) {
                        continue;
                    }
                    // Only rows dimension required
                    int dimensions = arraySpecifier.getNumDimensions();
                    for (int j = 0; j < dimensions; j++) {
                        Expression dimension = arraySpecifier.getDimension(j);
                        if (dimension == null) {
                            continue;
                        }

                        if (dimension instanceof BinaryExpression) {
                            boolean isComputable = dimension.getChildren().stream()
                                    .allMatch(nullChild -> nullChild instanceof IntegerLiteral);
                            if (!isComputable) {
                                continue;
                            }
                            for (Traversable childExpr : dimension.getChildren()) {
                                if (childExpr instanceof IntegerLiteral) {
                                    long dimensionSize = ((IntegerLiteral) childExpr).getValue();
                                    if (dimensionSize <= 0)
                                        continue;
                                    arraySize *= dimensionSize;
                                }
                            }
                        } else if (dimension instanceof IntegerLiteral) {
                            arraySize *= ((IntegerLiteral) dimension).getValue();
                        }

                    }
                }
            }

            dataSize = dataSize + arraySize;
        }

        return new IntegerLiteral(dataSize);
    }

    /**
     * Get the size in bits of an specific type/specifier in an array.
     * Example: if array is a boolean array, the return type will be 1. 32 for
     * Integer
     * 
     * @param array An array with an specific type of specifier
     * @return An integer that represents the size in bits of the type of the
     *         array's specifier passed as a paramteer.
     * @throws Exception
     */

    @SuppressWarnings("unchecked")
    public static final int getTypeSizeInBits(ArrayAccess array) {
        int typeSize;
        List<?> types = new ArrayList<>();
        Specifier type;

        // when working with some benchmarks that defines
        // initializations functions we found this kind of expressions
        // after AST is build: (* E)[(800+0)][(900+0)]
        // So, the following expressions got into a class cast exception due to *E is an
        // unary
        // expression and it is not castable as an identifier.
        // types = ((Identifier) array.getArrayName()).getSymbol().getTypeSpecifiers();

        Expression arrayName = array.getArrayName();
        if (arrayName instanceof Identifier) {
            types = ((Identifier) arrayName).getSymbol().getTypeSpecifiers();
        } else if (arrayName instanceof UnaryExpression) {
            types = arrayName.getChildren()
                    .stream()
                    .filter(children -> children instanceof Identifier)
                    .map(identifier -> ((Identifier) identifier).getSymbol())
                    .flatMap(symbol -> (Stream<Specifier>) ((Symbol) symbol).getTypeSpecifiers().stream())
                    .collect(Collectors.toList());

        }

        if (types.isEmpty()) {
            return 1;
        }

        type = (Specifier) types.get(0);
        return getTypeSizeInBits(type);
    }

    public static final int getTypeSizeInBits(Specifier spec) {
        int typeSize;

        if (spec == Specifier.BOOL)
            typeSize = 1;
        else if (spec == Specifier.CHAR)
            typeSize = 8;
        else if (spec == Specifier.VOID)
            typeSize = 8;
        else if (spec == Specifier.WCHAR_T)
            typeSize = 32;
        else if (spec == Specifier.SHORT)
            typeSize = 16;
        else if (spec == Specifier.INT)
            typeSize = 32;
        else if (spec == Specifier.SIGNED)
            typeSize = 32;
        else if (spec == Specifier.UNSIGNED)
            typeSize = 32;
        else if (spec == Specifier.FLOAT)
            typeSize = 32;
        else if (spec == Specifier.LONG)
            typeSize = 64;
        else if (spec == Specifier.DOUBLE)
            typeSize = 64;
        else
            typeSize = 32;
        return typeSize;
    }

}

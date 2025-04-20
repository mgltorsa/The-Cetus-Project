package cetus.utils;

import java.util.List;
import java.util.stream.Collectors;

import cetus.hir.ArrayAccess;
import cetus.hir.ArraySpecifier;
import cetus.hir.BinaryExpression;
import cetus.hir.Declaration;
import cetus.hir.Expression;
import cetus.hir.IDExpression;
import cetus.hir.Identifier;
import cetus.hir.IntegerLiteral;
import cetus.hir.SymbolTable;
import cetus.hir.Traversable;
import cetus.hir.VariableDeclarator;

public class ArrayUtils {
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
}

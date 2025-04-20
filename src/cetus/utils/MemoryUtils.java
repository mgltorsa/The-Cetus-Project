package cetus.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import cetus.hir.ArrayAccess;
import cetus.hir.Expression;
import cetus.hir.Identifier;
import cetus.hir.Specifier;
import cetus.hir.Symbol;
import cetus.hir.UnaryExpression;

public class MemoryUtils {

    /**
     * Calculate the required cache in terms of elements
     * 
     * @param bitsCacheSize total amount of bits in cache
     * @param arrayAccesses array accesses to calculate the block size
     * @return the block size in bits required for all the array accesses.
     */
    public static final long getCacheInArrayElements(int cacheSizeInKB, List<ArrayAccess> arrayAccesses) {
        int typeSizeInBits = 0;
        for (ArrayAccess arrayAccess : arrayAccesses) {
            typeSizeInBits = Math.max(typeSizeInBits, getTypeSize(arrayAccess));
        }

        return (cacheSizeInKB * 1024 * 8) / typeSizeInBits;

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
    public static final int getTypeSize(ArrayAccess array) {
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

        if (type == Specifier.BOOL)
            typeSize = 1;
        else if (type == Specifier.CHAR)
            typeSize = 8;
        else if (type == Specifier.VOID)
            typeSize = 8;
        else if (type == Specifier.WCHAR_T)
            typeSize = 32;
        else if (type == Specifier.SHORT)
            typeSize = 16;
        else if (type == Specifier.INT)
            typeSize = 32;
        else if (type == Specifier.SIGNED)
            typeSize = 32;
        else if (type == Specifier.UNSIGNED)
            typeSize = 32;
        else if (type == Specifier.FLOAT)
            typeSize = 32;
        else if (type == Specifier.LONG)
            typeSize = 64;
        else if (type == Specifier.DOUBLE)
            typeSize = 64;
        else
            typeSize = 32;
        return typeSize;
    }
}

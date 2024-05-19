package cetus.utils;

import java.util.List;

import cetus.hir.ArrayAccess;

public class CacheUtils {

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
            typeSizeInBits = Math.max(typeSizeInBits, ArrayUtils.getTypeSize(arrayAccess));
        }

        return (cacheSizeInKB * 1024 * 8) / typeSizeInBits;

    }
}

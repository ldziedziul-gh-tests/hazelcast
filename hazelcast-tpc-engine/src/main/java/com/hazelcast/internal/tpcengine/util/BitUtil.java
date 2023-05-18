/*
 * Copyright (c) 2008-2023, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.internal.tpcengine.util;

@SuppressWarnings("checkstyle:MagicNumber")
public final class BitUtil {
    public static final int SIZEOF_BYTE = Byte.BYTES;
    public static final int SIZEOF_CHAR = Character.BYTES;
    public static final int SIZEOF_INT = Integer.BYTES;
    public static final int SIZEOF_LONG = Long.BYTES;
    public static final int SIZEOF_FLOAT = Float.BYTES;
    public static final int SIZEOF_DOUBLE = Double.BYTES;
    public static final int SIZEOF_UUID = SIZEOF_LONG + SIZEOF_LONG;
    public static final int SIZEOF_CRC32 = SIZEOF_INT;

    private BitUtil() {
    }

    /**
     * Hash function based on Knuth's multiplicative method. This version is faster than using Murmur hash but provides
     * acceptable behavior.
     *
     * @param k the long for which the hash will be calculated
     * @return the hash
     */
    public static long fastLongMix(long k) {
        // phi = 2^64 / goldenRatio
        final long phi = 0x9E3779B97F4A7C15L;
        long h = k * phi;
        h ^= h >>> 32;
        return h ^ (h >>> 16);
    }

    static int longHash(final long value, final int mask) {
        return ((int) fastLongMix(value)) & mask;
    }

    /**
     * Fast method of finding the next power of 2 greater than or equal to the supplied value.
     * <p>
     * If the value is &lt;= 0 then 1 will be returned.
     * <p>
     * This method is not suitable for {@link Integer#MIN_VALUE} or numbers greater than 2^30.
     *
     * @param value from which to search for next power of 2
     * @return The next power of 2 or the value itself if it is a power of 2
     */
    @SuppressWarnings("checkstyle:MagicNumber")
    public static int nextPowerOfTwo(final int value) {
        return 1 << (32 - Integer.numberOfLeadingZeros(value - 1));
    }

}

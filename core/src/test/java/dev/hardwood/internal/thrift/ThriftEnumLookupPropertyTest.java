/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.thrift;

import dev.hardwood.metadata.PageType;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Property checks for the integer-to-enum boundary used by Thrift metadata.
class ThriftEnumLookupPropertyTest {

    @Property(tries = 100)
    void physicalTypeRejectsEveryOutOfRangeOrdinal(@ForAll("invalidPhysicalOrdinals") int value) {
        assertThrows(IllegalArgumentException.class, () -> ThriftEnumLookup.physicalType(value));
    }

    @Property(tries = 100)
    void pageTypeUsesUnknownForEveryOutOfRangeOrdinal(@ForAll("invalidPageOrdinals") int value) {
        assertEquals(PageType.UNKNOWN, ThriftEnumLookup.pageType(value));
    }

    @Property(tries = 100)
    void encodingUsesUnknownForEveryOutOfRangeOrdinal(@ForAll("invalidEncodingOrdinals") int value) {
        assertEquals(dev.hardwood.metadata.Encoding.UNKNOWN, ThriftEnumLookup.encoding(value));
    }

    @Property(tries = 100)
    void convertedTypeUsesAbsenceForEveryOutOfRangeOrdinal(@ForAll("invalidConvertedOrdinals") int value) {
        assertNull(ThriftEnumLookup.convertedType(value));
    }

    @Provide
    Arbitrary<Integer> invalidPhysicalOrdinals() {
        return invalidOrdinals(7);
    }

    @Provide
    Arbitrary<Integer> invalidPageOrdinals() {
        return invalidOrdinals(3);
    }

    @Provide
    Arbitrary<Integer> invalidEncodingOrdinals() {
        return invalidOrdinals(9);
    }

    @Provide
    Arbitrary<Integer> invalidConvertedOrdinals() {
        return invalidOrdinals(21);
    }

    private static Arbitrary<Integer> invalidOrdinals(int lastValidOrdinal) {
        return Arbitraries.integers().between(Integer.MIN_VALUE, Integer.MAX_VALUE)
                .filter(value -> value < 0 || value > lastValidOrdinal);
    }
}

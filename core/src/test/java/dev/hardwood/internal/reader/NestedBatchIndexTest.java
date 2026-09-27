/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.reader;

import org.junit.jupiter.api.Test;

import dev.hardwood.schema.ColumnSchema;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The construction boundary of [NestedBatchIndex] rejects inputs that would
/// otherwise surface much later as access-time array faults.
class NestedBatchIndexTest {

    /// A schema array whose length disagrees with the batch array is rejected at
    /// the boundary: the `@SameLen("valueCounts")` family the index declares is
    /// established here or not at all.
    @Test
    void rejectsSchemaCountShorterThanBatchCount() {
        assertThatThrownBy(() -> NestedBatchIndex.buildFromBatches(
                new NestedBatch[2], new ColumnSchema[1], null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Column schema count 1 does not match the 2 batch columns");
    }
}

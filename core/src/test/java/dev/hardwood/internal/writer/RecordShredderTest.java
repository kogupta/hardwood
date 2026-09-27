/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.writer;

import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.hardwood.Validity;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.schema.FileSchema;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// The bind boundary of [RecordShredder] rejects a batch whose inputs do not
/// match the column count the shredder was built for.
class RecordShredderTest {

    /// `bind` re-establishes the per-column `@SameLen("layers")` family the
    /// shredder's fields declare; a batch sized against a different column count
    /// would break it, so it is rejected here.
    @Test
    void rejectsBatchInputsShorterThanTheColumnCount() {
        FileSchema schema = FileSchema.builder("schema")
                .addColumn("id", PhysicalType.INT32, RepetitionType.REQUIRED)
                .build();
        RecordShredder shredder = new RecordShredder(schema);
        assertThatThrownBy(() -> shredder.bind(new ColumnSource[1], new Validity[0],
                Map.of(), Map.of(), Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Batch binds 1 sources and 0 leaf validities against a "
                        + "shredder built for 1 columns");
    }
}

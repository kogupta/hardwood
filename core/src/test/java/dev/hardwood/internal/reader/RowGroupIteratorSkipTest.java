/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.reader;

import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.hardwood.InputFile;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// A negative skip handed to the iterator directly, past the reader builder's own checks.
class RowGroupIteratorSkipTest {

    private static final List<InputFile> FILES =
            List.of(InputFile.of(Paths.get("src/test/resources/plain_uncompressed.parquet")));

    @Test
    void negativeTailSkipRejected() throws Exception {
        try (HardwoodContextImpl context = HardwoodContextImpl.create()) {
            assertThatThrownBy(() -> new RowGroupIterator(FILES, context, 0, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("tailSkip must be non-negative, got -1");
        }
    }

    @Test
    void negativePhysicalSkipRejected() throws Exception {
        try (HardwoodContextImpl context = HardwoodContextImpl.create()) {
            assertThatThrownBy(() -> new RowGroupIterator(FILES, context, 0, 0, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("physicalSkip must be non-negative, got -1");
        }
    }

    @Test
    void negativeTailSkipRejectedLate() throws Exception {
        try (HardwoodContextImpl context = HardwoodContextImpl.create();
                RowGroupIterator iterator = new RowGroupIterator(FILES, context, 0, 0)) {
            assertThatThrownBy(() -> iterator.setTailSkip(-1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("tailSkip must be non-negative, got -1");
        }
    }
}

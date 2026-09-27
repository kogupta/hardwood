/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.metadata;

import org.checkerframework.checker.index.qual.NonNegative;

import dev.hardwood.metadata.Encoding;

/// Header for dictionary page.
public record DictionaryPageHeader(
        @NonNegative int numValues,
        Encoding encoding) {
}

/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.tools.columnindex;

import java.util.NavigableSet;

import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.qual.StubFiles;

/// Keeps the column-index spaces apart: an index of one space passed where another is
/// required, and a subscript of an [dev.hardwood.tools.columnindex.qual.IndexedBy] array or list
/// with an index of the wrong space, are compile errors.
///
/// `column-index.astub` marks the index space of core types the checker cannot see annotated;
/// run with `-AmergeStubsWithSource` so it applies to classes compiled from source.
@StubFiles("column-index.astub")
public final class ColumnIndexChecker extends BaseTypeChecker {

    @Override
    public NavigableSet<String> getSuppressWarningsPrefixes() {
        NavigableSet<String> prefixes = super.getSuppressWarningsPrefixes();
        prefixes.add("columnindex");
        return prefixes;
    }
}

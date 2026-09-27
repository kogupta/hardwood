/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.tools.columnindex;

import java.lang.annotation.Annotation;
import java.util.Set;

import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeChecker;

import dev.hardwood.tools.columnindex.qual.ColumnIndexBottom;
import dev.hardwood.tools.columnindex.qual.ColumnIndexUnknown;
import dev.hardwood.tools.columnindex.qual.FileOrdinal;
import dev.hardwood.tools.columnindex.qual.OriginalIndex;
import dev.hardwood.tools.columnindex.qual.ProjectedIndex;
import dev.hardwood.tools.columnindex.qual.ProjectedIndexOrAbsent;

/// The column-index qualifier hierarchy.
public final class ColumnIndexAnnotatedTypeFactory extends BaseAnnotatedTypeFactory {

    public ColumnIndexAnnotatedTypeFactory(BaseTypeChecker checker) {
        super(checker);
        postInit();
    }

    @Override
    protected Set<Class<? extends Annotation>> createSupportedTypeQualifiers() {
        return getBundledTypeQualifiers(ColumnIndexUnknown.class, OriginalIndex.class,
                ProjectedIndexOrAbsent.class, ProjectedIndex.class, FileOrdinal.class,
                ColumnIndexBottom.class);
    }
}

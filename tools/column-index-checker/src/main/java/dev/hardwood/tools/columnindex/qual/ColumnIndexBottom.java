/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.tools.columnindex.qual;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

import org.checkerframework.framework.qual.DefaultFor;
import org.checkerframework.framework.qual.LiteralKind;
import org.checkerframework.framework.qual.QualifierForLiterals;
import org.checkerframework.framework.qual.SubtypeOf;
import org.checkerframework.framework.qual.TypeUseLocation;

/// The bottom of the column-index hierarchy. Not written in code.
@SubtypeOf({ OriginalIndex.class, ProjectedIndex.class, FileOrdinal.class })
@DefaultFor(TypeUseLocation.LOWER_BOUND)
@QualifierForLiterals(LiteralKind.INT)
@Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
public @interface ColumnIndexBottom {
}

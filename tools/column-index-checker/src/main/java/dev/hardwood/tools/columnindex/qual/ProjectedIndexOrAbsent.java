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

import org.checkerframework.framework.qual.SubtypeOf;

/// A [ProjectedIndex], or `-1` for a column the projection does not include.
@SubtypeOf(ColumnIndexUnknown.class)
@Target({ ElementType.TYPE_USE, ElementType.TYPE_PARAMETER })
public @interface ProjectedIndexOrAbsent {
}

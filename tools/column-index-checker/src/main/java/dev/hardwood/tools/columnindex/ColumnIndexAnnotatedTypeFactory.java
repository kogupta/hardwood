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

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;

import org.checkerframework.common.basetype.BaseAnnotatedTypeFactory;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.TreeUtils;

import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.Tree;

import dev.hardwood.tools.columnindex.qual.ColumnIndexBottom;
import dev.hardwood.tools.columnindex.qual.ColumnIndexUnknown;
import dev.hardwood.tools.columnindex.qual.FileOrdinal;
import dev.hardwood.tools.columnindex.qual.OriginalIndex;
import dev.hardwood.tools.columnindex.qual.ProjectedIndex;
import dev.hardwood.tools.columnindex.qual.ProjectedIndexOrAbsent;

/// The column-index qualifier hierarchy.
///
/// A local variable that holds only a literal reads as its declared type, not as the literal's
/// bottom type. A counter such as `for (int i = 0; ...; i++)` therefore belongs to no space
/// unless its declaration names one, and `for (@ProjectedIndex int p = 0; ...; p++)` reads as a
/// projected index throughout the loop. A literal written in place still fits every space.
public final class ColumnIndexAnnotatedTypeFactory extends BaseAnnotatedTypeFactory {

    private final AnnotationMirror top;
    private final AnnotationMirror bottom;

    public ColumnIndexAnnotatedTypeFactory(BaseTypeChecker checker) {
        super(checker);
        top = AnnotationBuilder.fromClass(elements, ColumnIndexUnknown.class);
        bottom = AnnotationBuilder.fromClass(elements, ColumnIndexBottom.class);
        postInit();
    }

    @Override
    protected void addComputedTypeAnnotations(Tree tree, AnnotatedTypeMirror type, boolean iUseFlow) {
        super.addComputedTypeAnnotations(tree, type, iUseFlow);
        if (tree.getKind() == Tree.Kind.IDENTIFIER && type.hasPrimaryAnnotation(bottom)) {
            Element variable = TreeUtils.elementFromUse((IdentifierTree) tree);
            if (variable != null && variable.getKind() == ElementKind.LOCAL_VARIABLE) {
                AnnotationMirror declared = fromElement(variable).getPrimaryAnnotationInHierarchy(top);
                type.replaceAnnotation(declared == null ? top : declared);
            }
        }
    }

    @Override
    protected Set<Class<? extends Annotation>> createSupportedTypeQualifiers() {
        return getBundledTypeQualifiers(ColumnIndexUnknown.class, OriginalIndex.class,
                ProjectedIndexOrAbsent.class, ProjectedIndex.class, FileOrdinal.class,
                ColumnIndexBottom.class);
    }
}

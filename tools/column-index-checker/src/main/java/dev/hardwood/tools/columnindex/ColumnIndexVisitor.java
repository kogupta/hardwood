/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.tools.columnindex;

import java.util.ArrayList;
import java.util.List;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.common.basetype.BaseTypeChecker;
import org.checkerframework.common.basetype.BaseTypeVisitor;
import org.checkerframework.framework.type.AnnotatedTypeMirror;
import org.checkerframework.javacutil.AnnotationBuilder;
import org.checkerframework.javacutil.AnnotationUtils;
import org.checkerframework.javacutil.BugInCF;
import org.checkerframework.javacutil.ElementUtils;
import org.checkerframework.javacutil.TreePathUtil;
import org.checkerframework.javacutil.TreeUtils;

import com.sun.source.tree.ArrayAccessTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;

import dev.hardwood.tools.columnindex.qual.ColumnIndexUnknown;
import dev.hardwood.tools.columnindex.qual.IndexedBy;

/// Adds two rules to the subtyping rules.
///
/// - **Subscripts.** On a variable, field, parameter or method marked [IndexedBy], every `int`
///   argument of `array[i]`, of a `java.util.List` method taking a position (`get`, `set`,
///   `add`, `remove`, `listIterator`, `subList`) and of `FileSchema.getColumn(int)` must carry
///   the named qualifier.
/// - **Handovers.** An array, list or `FileSchema` assigned, passed or returned keeps its
///   [IndexedBy]: source and target must name the same space. A value without [IndexedBy] is in
///   a space of its own. A freshly allocated array or object fits any target, except that a copy
///   constructor such as `new ArrayList<>(chunks)` carries the space of its argument. Only
///   parameters of methods declared outside the checked classes accept any value.
///
/// A container carries the space of its source when it is a copy or a view: `a.clone()`,
/// `Arrays.copyOf(a, n)`, `Arrays.copyOfRange(a, from, to)`, `List.copyOf(l)` and
/// `schema.getColumns()`. A conditional carries the spaces of both branches.
///
/// A cast into a space is an error rather than a warning, so every conversion point carries a
/// visible suppression.
public final class ColumnIndexVisitor extends BaseTypeVisitor<ColumnIndexAnnotatedTypeFactory> {

    private static final String NONE = "none";
    private static final String FILE_SCHEMA = "dev.hardwood.schema.FileSchema";

    private final AnnotationMirror top;

    public ColumnIndexVisitor(BaseTypeChecker checker) {
        super(checker);
        top = AnnotationBuilder.fromClass(elements, ColumnIndexUnknown.class);
    }

    @Override
    public Void visitArrayAccess(ArrayAccessTree tree, Void p) {
        checkSubscript(tree.getExpression(), tree.getIndex());
        return super.visitArrayAccess(tree, p);
    }

    @Override
    public Void visitMethodInvocation(MethodInvocationTree tree, Void p) {
        ExecutableElement method = TreeUtils.elementFromUse(tree);
        if (isSubscript(method)) {
            ExpressionTree receiver = TreeUtils.getReceiverTree(tree);
            if (receiver != null) {
                List<? extends VariableElement> parameters = method.getParameters();
                for (int i = 0; i < parameters.size(); i++) {
                    if (parameters.get(i).asType().getKind() == TypeKind.INT) {
                        checkSubscript(receiver, tree.getArguments().get(i));
                    }
                }
            }
        }
        checkArguments(method, tree.getArguments(), TreeUtils.isVarargsCall(tree));
        return super.visitMethodInvocation(tree, p);
    }

    @Override
    public Void visitNewClass(NewClassTree tree, Void p) {
        checkArguments(TreeUtils.elementFromUse(tree), tree.getArguments(), TreeUtils.isVarargsCall(tree));
        return super.visitNewClass(tree, p);
    }

    @Override
    public Void visitAssignment(AssignmentTree tree, Void p) {
        Element target = TreeUtils.elementFromTree(tree.getVariable());
        if (target != null && tree.getVariable().getKind() != Tree.Kind.ARRAY_ACCESS) {
            checkHandover(tree.getExpression(), target, space(target), tree);
        }
        return super.visitAssignment(tree, p);
    }

    @Override
    public Void visitVariable(VariableTree tree, Void p) {
        VariableElement target = TreeUtils.elementFromDeclaration(tree);
        if (target != null) {
            checkDeclaredSpace(target, tree);
            if (tree.getInitializer() != null) {
                checkHandover(tree.getInitializer(), target, space(target), tree);
            }
        }
        return super.visitVariable(tree, p);
    }

    @Override
    public void processMethodTree(String className, MethodTree tree) {
        ExecutableElement method = TreeUtils.elementFromDeclaration(tree);
        if (method != null) {
            checkDeclaredSpace(method, tree);
        }
        super.processMethodTree(className, tree);
    }

    @Override
    public Void visitReturn(ReturnTree tree, Void p) {
        Tree enclosing = TreePathUtil.enclosingMethodOrLambda(getCurrentPath());
        if (tree.getExpression() != null && enclosing instanceof MethodTree method) {
            ExecutableElement target = TreeUtils.elementFromDeclaration(method);
            if (target != null) {
                checkHandover(tree.getExpression(), target, space(target), tree);
            }
        }
        return super.visitReturn(tree, p);
    }

    @Override
    protected void checkTypecastSafety(TypeCastTree typeCastTree) {
        AnnotatedTypeMirror castType = atypeFactory.getAnnotatedType(typeCastTree);
        AnnotatedTypeMirror exprType = atypeFactory.getAnnotatedType(typeCastTree.getExpression());
        if (!isTypeCastSafe(castType, exprType)) {
            checker.reportError(typeCastTree, "cast.unsafe", exprType.toString(true),
                    castType.toString(true));
        }
    }

    /// `List` methods and `FileSchema.getColumn`, with overrides, that take a position.
    private boolean isSubscript(ExecutableElement method) {
        String name = method.getSimpleName().toString();
        return switch (name) {
            case "get", "set", "add", "remove", "listIterator", "subList" ->
                    isMemberOf(method, "java.util.List");
            case "getColumn" -> isMemberOf(method, FILE_SCHEMA);
            default -> false;
        };
    }

    private boolean isMemberOf(ExecutableElement method, String typeName) {
        TypeElement type = elements.getTypeElement(typeName);
        if (type == null) {
            return false;
        }
        TypeMirror owner = types.erasure(method.getEnclosingElement().asType());
        return types.isSubtype(owner, types.erasure(type.asType()));
    }

    /// Arguments handed to parameters of a checked method, or to parameters marked [IndexedBy]
    /// of any method. The elements of a varargs call are not containers and are skipped.
    private void checkArguments(ExecutableElement method, List<? extends ExpressionTree> arguments,
            boolean varargsCall) {
        List<? extends VariableElement> parameters = method.getParameters();
        int count = Math.min(varargsCall ? parameters.size() - 1 : parameters.size(), arguments.size());
        boolean checked = isChecked(method);
        for (int i = 0; i < count; i++) {
            String required = space(parameters.get(i));
            if (checked || !required.equals(NONE)) {
                checkHandover(arguments.get(i), parameters.get(i), required, arguments.get(i));
            }
        }
    }

    /// Whether `element` is declared in a class this compilation checks.
    private boolean isChecked(Element element) {
        TypeElement type = ElementUtils.enclosingTypeElement(element);
        Tree declaration = type == null ? null : atypeFactory.declarationFromElement(type);
        return declaration instanceof ClassTree classTree && !checker.shouldSkipDefs(classTree);
    }

    private void checkHandover(ExpressionTree source, Element target, String required, Tree reportAt) {
        if (!isContainer(target.getKind().isExecutable()
                ? ((ExecutableElement) target).getReturnType() : target.asType())) {
            return;
        }
        for (ExpressionTree origin : origins(source)) {
            String found = space(origin);
            if (!found.equals(required)) {
                checker.reportError(reportAt, "indexedby.handover", display(found), display(required),
                        target);
            }
        }
    }

    /// The expressions whose [IndexedBy] the container `value` carries. A freshly allocated
    /// container carries none; a copy or view carries its source's; a conditional carries both
    /// branches'.
    private List<ExpressionTree> origins(ExpressionTree value) {
        List<ExpressionTree> origins = new ArrayList<>();
        collectOrigins(value, origins);
        return origins;
    }

    private void collectOrigins(ExpressionTree tree, List<ExpressionTree> origins) {
        ExpressionTree value = TreeUtils.withoutParens(tree);
        switch (value.getKind()) {
            case NULL_LITERAL, NEW_ARRAY -> {
            }
            case CONDITIONAL_EXPRESSION -> {
                ConditionalExpressionTree conditional = (ConditionalExpressionTree) value;
                collectOrigins(conditional.getTrueExpression(), origins);
                collectOrigins(conditional.getFalseExpression(), origins);
            }
            case NEW_CLASS -> {
                for (ExpressionTree argument : ((NewClassTree) value).getArguments()) {
                    if (isContainer(TreeUtils.typeOf(argument))) {
                        collectOrigins(argument, origins);
                    }
                }
            }
            case METHOD_INVOCATION -> {
                ExpressionTree copied = copiedFrom((MethodInvocationTree) value);
                if (copied == null) {
                    origins.add(value);
                }
                else {
                    collectOrigins(copied, origins);
                }
            }
            default -> origins.add(value);
        }
    }

    /// The container that `invocation` copies or views, or `null`.
    private @Nullable ExpressionTree copiedFrom(MethodInvocationTree invocation) {
        ExecutableElement method = TreeUtils.elementFromUse(invocation);
        ExpressionTree receiver = TreeUtils.getReceiverTree(invocation);
        List<? extends ExpressionTree> arguments = invocation.getArguments();
        return switch (method.getSimpleName().toString()) {
            case "clone" -> receiver != null && TreeUtils.typeOf(receiver).getKind() == TypeKind.ARRAY
                    ? receiver : null;
            case "getColumns" -> receiver != null && isMemberOf(method, FILE_SCHEMA) ? receiver : null;
            case "copyOf", "copyOfRange" -> !arguments.isEmpty()
                    && (isMemberOf(method, "java.util.Arrays") || isMemberOf(method, "java.util.List"))
                    ? arguments.get(0) : null;
            default -> null;
        };
    }

    /// Arrays, `java.util.List` and `FileSchema`: the types [IndexedBy] applies to.
    private boolean isContainer(TypeMirror type) {
        if (type.getKind() == TypeKind.ARRAY) {
            return true;
        }
        if (type.getKind() != TypeKind.DECLARED) {
            return false;
        }
        for (String name : List.of("java.util.List", FILE_SCHEMA)) {
            TypeElement container = elements.getTypeElement(name);
            if (container != null
                    && types.isSubtype(types.erasure(type), types.erasure(container.asType()))) {
                return true;
            }
        }
        return false;
    }

    /// The qualifier name in the [IndexedBy] of the variable or method `origin` names, or
    /// [#NONE] when it names none or is not a variable or method call.
    private String space(ExpressionTree origin) {
        if (origin.getKind() == Tree.Kind.ARRAY_ACCESS) {
            return NONE;
        }
        Element element = TreeUtils.elementFromTree(origin);
        return element == null ? NONE : space(element);
    }

    /// The qualifier name in `element`'s [IndexedBy], or [#NONE].
    @SuppressWarnings("deprecation") // getElementValueClassName: the class-valued element is read by name
    private String space(Element element) {
        AnnotationMirror indexedBy = atypeFactory.getDeclAnnotation(element, IndexedBy.class);
        if (indexedBy == null) {
            return NONE;
        }
        Name qualifierName = AnnotationUtils.getElementValueClassName(indexedBy, "value", false);
        return qualifierName.toString();
    }

    private static String display(String space) {
        if (space.equals(NONE)) {
            return "no @IndexedBy";
        }
        return "@IndexedBy(" + space.substring(space.lastIndexOf('.') + 1) + ".class)";
    }

    /// Reports an [IndexedBy] on `element` that names no column-index space.
    private void checkDeclaredSpace(Element element, Tree declaration) {
        String space = space(element);
        if (!space.equals(NONE) && qualifier(space) == null) {
            checker.reportError(declaration, "indexedby.invalid", space);
        }
    }

    /// The column-index qualifier named `space`, or `null` when it names none.
    private @Nullable AnnotationMirror qualifier(String space) {
        AnnotationMirror qualifier = AnnotationBuilder.fromName(elements, space);
        if (qualifier == null || !atypeFactory.isSupportedQualifier(qualifier)
                || AnnotationUtils.areSame(qualifier, top)) {
            return null;
        }
        return qualifier;
    }

    private void checkSubscript(ExpressionTree container, ExpressionTree index) {
        for (ExpressionTree origin : origins(container)) {
            String space = space(origin);
            if (space.equals(NONE)) {
                continue;
            }
            AnnotationMirror required = qualifier(space);
            if (required == null) {
                // A declaration this compilation checks reports this itself.
                Element element = TreeUtils.elementFromTree(origin);
                if (element == null || !isChecked(element)) {
                    checker.reportError(origin, "indexedby.invalid", space);
                }
                continue;
            }
            AnnotationMirror found = atypeFactory.getAnnotatedType(index).getPrimaryAnnotationInHierarchy(top);
            if (found == null) {
                throw new BugInCF("no column-index qualifier on subscript %s", index);
            }
            if (!qualHierarchy.isSubtypeQualifiersOnly(found, required)) {
                checker.reportError(index, "subscript.space", found, required, origin);
            }
        }
    }
}

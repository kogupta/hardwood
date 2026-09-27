/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.tools.columnindex;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Name;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
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
import com.sun.source.tree.BindingPatternTree;
import com.sun.source.tree.CaseLabelTree;
import com.sun.source.tree.CaseTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ConditionalExpressionTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.InstanceOfTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.PatternCaseLabelTree;
import com.sun.source.tree.ReturnTree;
import com.sun.source.tree.SwitchExpressionTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeCastTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreeScanner;

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
///   a space of its own. An array or object created with `new` fits any target, except that a copy
///   constructor such as `new ArrayList<>(chunks)` carries the space of its argument. Only
///   parameters of methods declared outside the checked classes accept any value.
///
/// A container carries the space of its source when it is a copy, a view or a cast: `a.clone()`,
/// `l.clone()`, `Arrays.copyOf(a, n)`, `Arrays.copyOfRange(a, from, to)`, `List.copyOf(l)`,
/// `l.toArray()`, `Arrays.asList(a)`, `List.of(a)`, `Collections.unmodifiableList(l)`,
/// `Collections.synchronizedList(l)`, `Objects.requireNonNull(x)` and `schema.getColumns()`. A
/// conditional or a `switch` expression carries the spaces of all its results.
///
/// A cast into a space is an error rather than a warning, so every conversion point carries a
/// visible suppression.
public final class ColumnIndexVisitor extends BaseTypeVisitor<ColumnIndexAnnotatedTypeFactory> {

    private static final String NONE = "none";
    private static final String FILE_SCHEMA = "dev.hardwood.schema.FileSchema";

    private final AnnotationMirror top;
    private final Pattern skipDefs;
    private final Pattern onlyDefs;

    public ColumnIndexVisitor(BaseTypeChecker checker) {
        super(checker);
        top = AnnotationBuilder.fromClass(elements, ColumnIndexUnknown.class);
        // The defaults of SourceChecker: skipDefs matches no class, onlyDefs matches every class.
        skipDefs = defsPattern("skipDefs", "\\]'\"\\]");
        onlyDefs = defsPattern("onlyDefs", ".");
    }

    /// The pattern of the `skipDefs` or `onlyDefs` option, read from the same places, in the same
    /// order, as `SourceChecker` reads it: the checker option, the system property
    /// `checkers.<name>`, then the environment variable `<name>`.
    private Pattern defsPattern(String name, String defaultPattern) {
        String pattern = checker.hasOption(name) ? checker.getOption(name) : System.getProperty("checkers." + name);
        if (pattern == null) {
            pattern = System.getenv(name);
        }
        return Pattern.compile(pattern == null || pattern.isEmpty() ? defaultPattern : pattern);
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
        ExpressionTree variable = tree.getVariable();
        if (variable instanceof ArrayAccessTree element) {
            // An array element carries no space of its own.
            checkHandover(tree.getExpression(), TreeUtils.typeOf(variable), NONE, element.getExpression(), tree);
        }
        else {
            Element target = TreeUtils.elementFromTree(variable);
            if (target != null) {
                checkHandover(tree.getExpression(), target, space(target), tree);
            }
        }
        return super.visitAssignment(tree, p);
    }

    @Override
    public Void visitNewArray(NewArrayTree tree, Void p) {
        if (tree.getInitializers() != null) {
            TypeMirror component = ((ArrayType) TreeUtils.typeOf(tree)).getComponentType();
            for (ExpressionTree initializer : tree.getInitializers()) {
                // An array element carries no space of its own.
                checkHandover(initializer, component, NONE, tree, initializer);
            }
        }
        return super.visitNewArray(tree, p);
    }

    @Override
    public Void visitInstanceOf(InstanceOfTree tree, Void p) {
        checkPatternHandover(tree.getExpression(), tree.getPattern());
        return super.visitInstanceOf(tree, p);
    }

    @Override
    public Void visitSwitch(SwitchTree tree, Void p) {
        checkCaseHandovers(tree.getExpression(), tree.getCases());
        return super.visitSwitch(tree, p);
    }

    @Override
    public Void visitSwitchExpression(SwitchExpressionTree tree, Void p) {
        checkCaseHandovers(tree.getExpression(), tree.getCases());
        return super.visitSwitchExpression(tree, p);
    }

    /// The type pattern of a `case` label receives the selector of its switch.
    private void checkCaseHandovers(ExpressionTree selector, List<? extends CaseTree> cases) {
        for (CaseTree caseTree : cases) {
            for (CaseLabelTree label : caseTree.getLabels()) {
                if (label instanceof PatternCaseLabelTree patternLabel) {
                    checkPatternHandover(selector, patternLabel.getPattern());
                }
            }
        }
    }

    /// The variable of a type pattern receives the value the pattern matches. The components of a
    /// record pattern are not followed.
    private void checkPatternHandover(ExpressionTree value, @Nullable Tree pattern) {
        if (pattern instanceof BindingPatternTree binding) {
            VariableElement target = TreeUtils.elementFromDeclaration(binding.getVariable());
            checkHandover(value, target, space(target), binding);
        }
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
    public void processClassTree(ClassTree tree) {
        for (Tree member : tree.getMembers()) {
            if (!(member instanceof ClassTree)) {
                reportSkippedClasses(member);
            }
        }
        super.processClassTree(tree);
    }

    /// Reports each local or anonymous class in `tree` whose code the checker does not check.
    /// `-AonlyDefs` matches qualified names, which local and anonymous classes do not have, so
    /// their bodies would otherwise go unchecked without notice.
    private void reportSkippedClasses(Tree tree) {
        new TreeScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree nested, Void p) {
                if (checker.shouldSkipDefs(nested) && hasCode(nested)) {
                    checker.reportError(nested, "class.unchecked");
                }
                return null;
            }
        }.scan(tree, null);
    }

    /// Whether `tree` declares code beyond what the compiler generates for it.
    private static boolean hasCode(ClassTree tree) {
        for (Tree member : tree.getMembers()) {
            boolean code = switch (member) {
                case MethodTree method -> method.getBody() != null && !TreeUtils.isAutoGeneratedRecordMember(method)
                        && !TreeUtils.isAnonymousConstructor(method);
                case VariableTree field -> field.getInitializer() != null
                        && !TreeUtils.isAutoGeneratedRecordMember(field) && !isPlainEnumConstant(field);
                case ClassTree nested -> hasCode(nested);
                default -> true;
            };
            if (code) {
                return true;
            }
        }
        return false;
    }

    /// An enum constant with no body whose arguments, if any, are literals.
    private static boolean isPlainEnumConstant(VariableTree field) {
        if (TreeUtils.elementFromDeclaration(field).getKind() != ElementKind.ENUM_CONSTANT
                || !(field.getInitializer() instanceof NewClassTree creation) || creation.getClassBody() != null) {
            return false;
        }
        for (ExpressionTree argument : creation.getArguments()) {
            if (!ColumnIndexAnnotatedTypeFactory.isLiteralValue(argument)) {
                return false;
            }
        }
        return true;
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

    /// Whether `element` is declared in a class this compilation checks. A local or anonymous class
    /// counts as part of the named class around it.
    ///
    /// The answer depends on the class symbol only. When javac compiles one class at a time, the
    /// tree of another class may not be attributed yet, or may already be lowered to bytecode
    /// form; `SourceChecker.shouldSkipDefs(ClassTree)` needs an attributed tree, so this method
    /// matches `skipDefs` and `onlyDefs` against the same name itself. The file options
    /// (`skipFiles`, `onlyFiles`, `skipDirs`) are not applied, so a class that only they skip
    /// counts as checked. The `column-index-check` profile does not set them.
    private boolean isChecked(Element element) {
        TypeElement type = ElementUtils.enclosingTypeElement(element);
        while (type != null && (type.getNestingKind() == NestingKind.LOCAL
                || type.getNestingKind() == NestingKind.ANONYMOUS)) {
            type = ElementUtils.enclosingTypeElement(type.getEnclosingElement());
        }
        if (type == null || !ElementUtils.isElementFromSourceCode(type)) {
            return false;
        }
        String name = type.asType().toString();
        return !skipDefs.matcher(name).find() && onlyDefs.matcher(name).find();
    }

    private void checkHandover(ExpressionTree source, Element target, String required, Tree reportAt) {
        TypeMirror targetType = target.getKind().isExecutable()
                ? ((ExecutableElement) target).getReturnType() : target.asType();
        checkHandover(source, targetType, required, target, reportAt);
    }

    /// Checks a container handed to a target of `required` space. A container widened to a type
    /// that is not a container, such as `Object` or `Iterable`, is still checked: the target has
    /// no [IndexedBy] and the container's space would be lost. So is a copy typed `Object`, such
    /// as the result of `ArrayList.clone()`.
    private void checkHandover(ExpressionTree source, TypeMirror targetType, String required, Object target,
            Tree reportAt) {
        List<ExpressionTree> origins = origins(source);
        if (!isContainer(targetType) && !anyContainer(origins)) {
            return;
        }
        for (String found : spaces(origins)) {
            if (!found.equals(required)) {
                checker.reportError(reportAt, "indexedby.handover", display(found), display(required),
                        target);
            }
        }
    }

    private boolean anyContainer(List<ExpressionTree> origins) {
        for (ExpressionTree origin : origins) {
            if (isContainer(TreeUtils.typeOf(origin))) {
                return true;
            }
        }
        return false;
    }

    /// The distinct spaces of `origins`, in order.
    private Set<String> spaces(List<ExpressionTree> origins) {
        Set<String> spaces = new LinkedHashSet<>();
        for (ExpressionTree origin : origins) {
            spaces.add(space(origin));
        }
        return spaces;
    }

    /// The expressions whose [IndexedBy] the container `value` carries. A freshly allocated
    /// container carries none; a copy, view or cast carries its source's; a conditional carries
    /// both branches'.
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
            case SWITCH_EXPRESSION -> {
                for (ExpressionTree result : ColumnIndexAnnotatedTypeFactory.switchResults(
                        (SwitchExpressionTree) value)) {
                    collectOrigins(result, origins);
                }
            }
            case TYPE_CAST -> collectOrigins(((TypeCastTree) value).getExpression(), origins);
            case NEW_CLASS -> {
                // A new container copies the containers it is given; any other new object is fresh.
                if (isContainer(TreeUtils.typeOf(value))) {
                    for (ExpressionTree argument : ((NewClassTree) value).getArguments()) {
                        if (isContainer(TreeUtils.typeOf(argument))) {
                            collectOrigins(argument, origins);
                        }
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
            case "clone" -> receiver != null && isContainer(TreeUtils.typeOf(receiver)) ? receiver : null;
            case "asList" -> wholeArray(invocation, method, "java.util.Arrays");
            case "of" -> wholeArray(invocation, method, "java.util.List");
            case "getColumns" -> receiver != null && isMemberOf(method, FILE_SCHEMA) ? receiver : null;
            case "toArray" -> receiver != null && isMemberOf(method, "java.util.List") ? receiver : null;
            case "copyOf", "copyOfRange" -> !arguments.isEmpty()
                    && (isMemberOf(method, "java.util.Arrays") || isMemberOf(method, "java.util.List"))
                    ? arguments.get(0) : null;
            case "unmodifiableList", "synchronizedList" -> !arguments.isEmpty() && isMemberOf(method, "java.util.Collections")
                    ? arguments.get(0) : null;
            case "requireNonNull" -> !arguments.isEmpty() && isMemberOf(method, "java.util.Objects")
                    ? arguments.get(0) : null;
            default -> null;
        };
    }

    /// The array that `invocation` hands as a whole to the varargs parameter of a method of `owner`,
    /// or `null` when the call collects its arguments into a new array. `Arrays.asList(a)` and
    /// `List.of(a)` keep the positions of `a`.
    private @Nullable ExpressionTree wholeArray(MethodInvocationTree invocation, ExecutableElement method,
            String owner) {
        List<? extends ExpressionTree> arguments = invocation.getArguments();
        return arguments.size() == 1 && method.isVarArgs() && !TreeUtils.isVarargsCall(invocation)
                && isMemberOf(method, owner) ? arguments.get(0) : null;
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
        Set<String> checked = new HashSet<>();
        for (ExpressionTree origin : origins(container)) {
            String space = space(origin);
            if (!checked.add(space)) {
                continue;
            }
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
            AnnotationMirror found = atypeFactory.getAnnotatedType(index).getAnnotationInHierarchy(top);
            if (found == null) {
                throw new BugInCF("no column-index qualifier on subscript %s", index);
            }
            if (!qualHierarchy.isSubtypeQualifiersOnly(found, required)) {
                checker.reportError(index, "subscript.space", found, required, origin);
            }
        }
    }
}

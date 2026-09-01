/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.command;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.aesh.command.Command;
import org.aesh.command.CommandDefinition;
import org.aesh.command.CommandResult;
import org.aesh.command.invocation.CommandInvocation;
import org.aesh.command.option.Mixin;
import org.aesh.command.option.Option;

import dev.hardwood.InputFile;
import dev.hardwood.cli.internal.JsonStrings;
import dev.hardwood.internal.schema.SchemaNames;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.reader.ParquetFileReader;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.schema.SchemaNode;

@CommandDefinition(name = "schema", description = "Print the file schema.", generateHelp = true)
public class SchemaCommand implements Command<CommandInvocation> {

    enum Format {
        NATIVE,
        AVRO,
        PROTO
    }

    @Mixin
    FileMixin fileMixin;

    @Option(shortName = 'F', name = "format", defaultValue = "NATIVE", description = "Output format: NATIVE (default), AVRO, PROTO.")
    Format format;

    @Override
    public CommandResult execute(CommandInvocation ci) {
        InputFile inputFile = fileMixin.toInputFile();
        if (inputFile == null) {
            return CommandResult.FAILURE;
        }

        try (ParquetFileReader reader = ParquetFileReader.open(inputFile)) {
            FileSchema schema = reader.getFileSchema();

            String output;
            try {
                output = switch (format) {
                    case NATIVE -> schema.toString();
                    case AVRO -> toAvroSchema(schema);
                    case PROTO -> toProtoSchema(schema);
                };
            }
            catch (IllegalArgumentException e) {
                System.err.println("Error rendering schema: " + e.getMessage());
                return CommandResult.FAILURE;
            }

            System.out.println(output);
        }
        catch (IOException e) {
            System.err.println("Error reading file: " + e.getMessage());
            return CommandResult.FAILURE;
        }

        return CommandResult.SUCCESS;
    }

    // ── Avro ─────────────────────────────────────────────────────────────────

    private static String toAvroSchema(FileSchema schema) {
        AvroTypeNames names = AvroTypeNames.forSchema(schema);
        StringBuilder sb = new StringBuilder();
        appendAvroRecord(sb, schema.getRootNode(), schema.getName(), 0, names);
        return sb.toString();
    }

    private static void appendAvroRecord(StringBuilder sb, SchemaNode.GroupNode group, String name, int indent,
            AvroTypeNames names) {
        String p = "  ".repeat(indent);
        sb.append(p).append("{\n");
        sb.append(p).append("  \"type\": \"record\",\n");
        sb.append(p).append("  \"name\": \"").append(SchemaNames.sanitize(capitalize(name))).append("\",\n");
        String doc = avroDoc(name);
        if (!doc.isEmpty()) {
            sb.append(p).append("  ").append(doc).append("\n");
        }
        sb.append(p).append("  \"fields\": [\n");

        List<SchemaNode> children = group.children();
        Set<String> usedNames = new HashSet<>(children.size());
        for (int i = 0; i < children.size(); i++) {
            SchemaNode child = children.get(i);
            appendAvroField(sb, child, disambiguate(SchemaNames.sanitize(child.name()), usedNames), indent + 2, names);
            if (i < children.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append(p).append("  ]\n");
        sb.append(p).append("}");
    }

    private static void appendAvroField(StringBuilder sb, SchemaNode node, String avroName, int indent,
            AvroTypeNames names) {
        boolean optional = node.repetitionType() == RepetitionType.OPTIONAL;
        String p = "  ".repeat(indent);
        sb.append(p).append("{ \"name\": \"").append(avroName).append("\", ");
        String doc = avroDoc(node.name());
        if (!doc.isEmpty()) {
            sb.append(doc).append(" ");
        }
        sb.append("\"type\": ");
        appendAvroType(sb, node, optional, indent, names);
        if (optional) {
            sb.append(", \"default\": null");
        }
        sb.append(" }");
    }

    /// Returns a `doc` attribute carrying the Parquet name whenever that name does not
    /// survive the mapping onto the Avro name grammar, so the rewrite is visible rather
    /// than silent. Compared against the sanitized name only: the capitalization applied
    /// to record names is cosmetic and does not warrant a note.
    private static String avroDoc(String parquetName) {
        if (parquetName.equals(SchemaNames.sanitize(parquetName))) {
            return "";
        }
        return "\"doc\": \"Parquet name: " + JsonStrings.escape(parquetName) + "\",";
    }

    /// Sanitizing is not injective, so two Parquet names can map onto the same name.
    /// Neither Avro nor proto allows a record or message to repeat a field name,
    /// hence the numeric suffix.
    private static String disambiguate(String name, Set<String> usedNames) {
        String candidate = name;
        for (int suffix = 2; !usedNames.add(candidate); suffix++) {
            candidate = name + "_" + suffix;
        }
        return candidate;
    }

    private static void appendAvroType(StringBuilder sb, SchemaNode node, boolean optional, int indent,
            AvroTypeNames names) {
        if (optional) {
            sb.append("[\"null\", ");
        }
        switch (node) {
            case SchemaNode.PrimitiveNode prim -> appendAvroPrimitiveType(sb, prim, names);
            case SchemaNode.GroupNode group when group.isList() -> {
                SchemaNode elem = group.getListElement();
                if (elem == null) {
                    throw new IllegalArgumentException("List '" + group.name() + "' has no resolvable element");
                }
                sb.append("{\"type\": \"array\", \"items\": ");
                appendAvroType(sb, elem, elem.repetitionType() == RepetitionType.OPTIONAL, indent + 1, names);
                sb.append("}");
            }
            case SchemaNode.GroupNode group when group.isMap() -> appendAvroMapType(sb, group, indent, names);
            case SchemaNode.GroupNode group -> {
                sb.append("\n");
                appendAvroRecord(sb, group, group.name(), indent, names);
            }
        }
        if (optional) {
            sb.append("]");
        }
    }

    /// Renders the type of a primitive. `FIXED_LEN_BYTE_ARRAY` and `INT96` preserve
    /// their physical width as a named Avro `fixed`; every other primitive keeps the
    /// plain scalar mapping.
    private static void appendAvroPrimitiveType(StringBuilder sb, SchemaNode.PrimitiveNode prim, AvroTypeNames names) {
        switch (prim.type()) {
            case FIXED_LEN_BYTE_ARRAY -> {
                if (prim.logicalType() instanceof LogicalType.IntervalType) {
                    sb.append(names.canonicalFixed("interval", 12));
                }
                else if (prim.logicalType() instanceof LogicalType.Float16Type) {
                    sb.append(names.canonicalFixed("float16", 2));
                }
                else {
                    sb.append(names.fixedReference(prim));
                }
            }
            case INT96 -> sb.append(names.fixedReference(prim));
            default -> sb.append("\"").append(primitiveToAvroType(prim)).append("\"");
        }
    }

    /// Avro fixes map keys to strings, so a map is only representable when its Parquet
    /// key is a string-compatible primitive; anything else is reported, never silently
    /// narrowed. The value keeps its recursive structure. A key-only map has no value
    /// type at all, which Avro renders as bare `null` values.
    private static void appendAvroMapType(StringBuilder sb, SchemaNode.GroupNode group, int indent,
            AvroTypeNames names) {
        SchemaNode key = group.getMapKey();
        validateAvroMapKey(key, group.name());
        sb.append("{\"type\": \"map\", \"values\": ");
        SchemaNode value = group.getMapValue();
        if (value == null) {
            sb.append("\"null\"");
        }
        else {
            appendAvroType(sb, value, value.repetitionType() == RepetitionType.OPTIONAL, indent + 1, names);
        }
        sb.append("}");
    }

    static void validateAvroMapKey(SchemaNode key, String mapName) {
        boolean representable = key instanceof SchemaNode.PrimitiveNode keyPrim
                && (keyPrim.logicalType() instanceof LogicalType.StringType
                        || keyPrim.logicalType() instanceof LogicalType.EnumType
                        || keyPrim.logicalType() instanceof LogicalType.JsonType);
        if (!representable) {
            String description = key == null ? "missing key" : describeKeyType(key);
            throw new IllegalArgumentException("Avro map keys must be STRING, ENUM, or JSON; map '" + mapName
                    + "' has key " + description);
        }
    }

    private static String describeKeyType(SchemaNode key) {
        if (key instanceof SchemaNode.PrimitiveNode prim) {
            return prim.logicalType() == null ? prim.type().toString() : prim.type() + " (" + prim.logicalType() + ")";
        }
        return "group '" + key.name() + "'";
    }

    private static String primitiveToAvroType(SchemaNode.PrimitiveNode prim) {
        return switch (prim.type()) {
            case BOOLEAN -> "boolean";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case INT32 -> {
                boolean unsignedInt = prim.logicalType() instanceof LogicalType.IntType it && !it.isSigned() && it.bitWidth() == 32;
                yield unsignedInt ? "long" : "int";
            }
            case INT64 -> "long";
            case BYTE_ARRAY -> prim.logicalType() instanceof LogicalType.StringType
                    || prim.logicalType() instanceof LogicalType.EnumType
                    || prim.logicalType() instanceof LogicalType.JsonType ? "string" : "bytes";
            case FIXED_LEN_BYTE_ARRAY, INT96 -> throw new IllegalStateException(
                    "Fixed-width types are rendered as named Avro fixed types, not scalars");
        };
    }

    // ── Proto ─────────────────────────────────────────────────────────────────

    private static String toProtoSchema(FileSchema schema) {
        StringBuilder sb = new StringBuilder();
        sb.append("syntax = \"proto3\";\n\n");
        appendProtoMessage(sb, schema.getRootNode(), 0);
        return sb.toString();
    }

    private static void appendProtoMessage(StringBuilder sb, SchemaNode.GroupNode group, int indent) {
        String p = "  ".repeat(indent);
        sb.append(p).append("message ").append(protoMessageName(group)).append(" {\n");

        List<SchemaNode.GroupNode> nestedStructs = new ArrayList<>();
        Set<String> usedNames = new HashSet<>(group.children().size());
        int fieldNum = 1;

        for (SchemaNode child : group.children()) {
            String protoName = disambiguate(SchemaNames.sanitize(child.name()), usedNames);
            fieldNum = appendProtoField(sb, child, protoName, fieldNum, indent + 1, nestedStructs);
        }

        for (SchemaNode.GroupNode nested : nestedStructs) {
            sb.append("\n");
            appendProtoMessage(sb, nested, indent + 1);
        }

        sb.append(p).append("}\n");
    }

    private static int appendProtoField(StringBuilder sb, SchemaNode node, String protoName, int fieldNum, int indent,
                                        List<SchemaNode.GroupNode> nestedStructs) {
        String p = "  ".repeat(indent);
        appendProtoComment(sb, p, node.name());
        switch (node) {
            case SchemaNode.PrimitiveNode prim -> {
                String mod = prim.repetitionType() == RepetitionType.OPTIONAL ? "optional " : "";
                sb.append(p).append(mod).append(primitiveToProtoType(prim))
                        .append(" ").append(protoName).append(" = ").append(fieldNum).append(";\n");
            }
            case SchemaNode.GroupNode group when group.isList() -> {
                SchemaNode elem = group.getListElement();
                String protoType = elem instanceof SchemaNode.PrimitiveNode prim ? primitiveToProtoType(prim) : protoMessageName(elem);
                sb.append(p).append("repeated ").append(protoType)
                        .append(" ").append(protoName).append(" = ").append(fieldNum).append(";\n");
            }
            case SchemaNode.GroupNode group when group.isMap() -> sb.append(p).append("map<string, string> ")
                    .append(protoName).append(" = ").append(fieldNum).append(";\n");
            case SchemaNode.GroupNode group -> {
                sb.append(p).append(protoMessageName(group)).append(" ")
                        .append(protoName).append(" = ").append(fieldNum).append(";\n");
                nestedStructs.add(group);
            }
        }
        return fieldNum + 1;
    }

    /// The message name a group is declared and referenced under. Both sites go through
    /// here so a rewritten name stays consistent between the declaration and the field
    /// that refers to it.
    private static String protoMessageName(SchemaNode node) {
        return SchemaNames.sanitize(capitalize(node.name()));
    }

    /// Notes the Parquet name in a line comment whenever it does not survive the mapping
    /// onto the proto identifier grammar — the counterpart to Avro's `doc` attribute.
    /// The name is escaped so that one containing a line break cannot end the comment
    /// early and swallow the field that follows.
    private static void appendProtoComment(StringBuilder sb, String p, String parquetName) {
        if (parquetName.equals(SchemaNames.sanitize(parquetName))) {
            return;
        }
        sb.append(p).append("// Parquet name: ").append(JsonStrings.escape(parquetName)).append("\n");
    }

    private static String primitiveToProtoType(SchemaNode.PrimitiveNode prim) {
        return switch (prim.type()) {
            case BOOLEAN -> "bool";
            case INT32 -> prim.logicalType() instanceof LogicalType.IntType it && !it.isSigned() ? "uint32" : "int32";
            case INT64, INT96 -> prim.logicalType() instanceof LogicalType.IntType it && !it.isSigned() ? "uint64" : "int64";
            case FLOAT -> "float";
            case DOUBLE -> "double";
            case BYTE_ARRAY -> prim.logicalType() instanceof LogicalType.StringType
                    || prim.logicalType() instanceof LogicalType.EnumType
                    || prim.logicalType() instanceof LogicalType.JsonType ? "string" : "bytes";
            case FIXED_LEN_BYTE_ARRAY -> prim.logicalType() instanceof LogicalType.UuidType ? "string" : "bytes";
        };
    }

    /// Conversion-wide registry of the named Avro types (records and fixed types) the
    /// emitter defines. Names are resolved for the whole schema before rendering begins,
    /// so every declaration and reference of a named type agrees on its final name.
    ///
    /// Path rules: the root keeps the CLI's existing effective name; a direct struct or
    /// fixed child of a record is namespaced by that record's full name; a list or map
    /// field contributes its disambiguated, uncapitalized, sanitized field-name segment
    /// before a named descendant. Local candidates are `sanitize(capitalize(node.name()))`.
    /// Candidates colliding inside one namespace resolve by the #895 ordering: a legal
    /// raw name wins the bare candidate, otherwise the smallest raw name wins, exact
    /// duplicates fall back to declaration order, and every loser receives `_2`, `_3`, ….
    /// The canonical `Interval` and `Float16` fixed types are reserved globally,
    /// unnamespaced, and defined once.
    private static final class AvroTypeNames {

        private final FileSchema schema;
        private final Map<SchemaNode, String> fullNames = new IdentityHashMap<>();
        private final Set<String> emitted = new HashSet<>();

        private AvroTypeNames(FileSchema schema) {
            this.schema = schema;
        }

        static AvroTypeNames forSchema(FileSchema schema) {
            AvroTypeNames names = new AvroTypeNames(schema);
            SchemaNode.GroupNode root = schema.getRootNode();
            String rootName = SchemaNames.sanitize(capitalize(schema.getName()));
            names.fullNames.put(root, rootName);
            names.visitRecordChildren(root, rootName);
            return names;
        }

        /// Renders the named `fixed` for `prim`: the definition at the type's first use,
        /// a full-name reference afterwards.
        String fixedReference(SchemaNode.PrimitiveNode prim) {
            String fullName = fullNames.get(prim);
            if (fullName == null) {
                throw new IllegalArgumentException("No resolved name for fixed column " + prim.name());
            }
            if (emitted.add(fullName)) {
                StringBuilder def = new StringBuilder();
                def.append("{\"type\": \"fixed\", \"name\": \"").append(localName(fullName)).append("\"");
                String namespace = namespaceOf(fullName);
                if (!namespace.isEmpty()) {
                    def.append(", \"namespace\": \"").append(namespace).append("\"");
                }
                def.append(", \"size\": ").append(fixedSize(prim)).append("}");
                return def.toString();
            }
            return "\"" + fullName + "\"";
        }

        /// Renders the canonical, unnamespaced fixed type `name`: the definition at its
        /// first use, a bare-name reference afterwards.
        String canonicalFixed(String name, int size) {
            if (emitted.add(name)) {
                return "{\"type\": \"fixed\", \"name\": \"" + name + "\", \"size\": " + size + "}";
            }
            return "\"" + name + "\"";
        }

        private int fixedSize(SchemaNode.PrimitiveNode prim) {
            if (prim.type() == PhysicalType.INT96) {
                return 12;
            }
            Integer typeLength = schema.getColumn(prim.columnIndex()).typeLength();
            if (typeLength == null) {
                throw new IllegalArgumentException("FIXED_LEN_BYTE_ARRAY column '" + prim.name()
                        + "' is missing its type length");
            }
            return typeLength;
        }


        private void visitRecordChildren(SchemaNode.GroupNode record, String scope) {
            List<SchemaNode> children = record.children();
            Map<SchemaNode, String> containerSegments = resolveContainerSegments(children);
            List<NodeCandidate> named = new ArrayList<>(children.size());
            for (SchemaNode child : children) {
                switch (child) {
                    case SchemaNode.GroupNode g when g.isList() -> {
                        SchemaNode elem = g.getListElement();
                        if (elem != null) {
                            visitContainer(elem, join(scope, containerSegments.get(g)));
                        }
                    }
                    case SchemaNode.GroupNode g when g.isMap() -> {
                        SchemaNode value = g.getMapValue();
                        if (value != null) {
                            visitContainer(value, join(scope, containerSegments.get(g)));
                        }
                    }
                    case SchemaNode.GroupNode g -> named.add(new NodeCandidate(g, scope, typeCandidate(g), g.name()));
                    case SchemaNode.PrimitiveNode p
                            when p.type() == PhysicalType.FIXED_LEN_BYTE_ARRAY || p.type() == PhysicalType.INT96 ->
                        named.add(new NodeCandidate(p, scope, typeCandidate(p), p.name()));
                    default -> { }
                }
            }
            resolve(named);
            for (NodeCandidate candidate : named) {
                if (candidate.node() instanceof SchemaNode.GroupNode g) {
                    visitRecordChildren(g, fullNames.get(g));
                }
            }
        }

        /// Visits a list element or map value sitting in `namespace`. Containers pass
        /// through, contributing their own name as the next segment; a struct or fixed
        /// descendant is the sole named type of its namespace.
        private void visitContainer(SchemaNode node, String namespace) {
            switch (node) {
                case SchemaNode.GroupNode g when g.isList() -> {
                    SchemaNode elem = g.getListElement();
                    if (elem != null) {
                        visitContainer(elem, join(namespace, SchemaNames.sanitize(g.name())));
                    }
                }
                case SchemaNode.GroupNode g when g.isMap() -> {
                    SchemaNode value = g.getMapValue();
                    if (value != null) {
                        visitContainer(value, join(namespace, SchemaNames.sanitize(g.name())));
                    }
                }
                case SchemaNode.GroupNode g -> {
                    String fullName = join(namespace, typeCandidate(g));
                    fullNames.put(g, fullName);
                    visitRecordChildren(g, fullName);
                }
                case SchemaNode.PrimitiveNode p
                        when p.type() == PhysicalType.FIXED_LEN_BYTE_ARRAY || p.type() == PhysicalType.INT96 ->
                    fullNames.put(p, join(namespace, typeCandidate(p)));
                default -> { }
            }
        }

        private static Map<SchemaNode, String> resolveContainerSegments(List<SchemaNode> children) {
            Map<String, List<SchemaNode>> groups = new TreeMap<>();
            for (SchemaNode child : children) {
                if (child instanceof SchemaNode.GroupNode group && (group.isList() || group.isMap())) {
                    String candidate = SchemaNames.sanitize(group.name());
                    groups.computeIfAbsent(candidate, ignored -> new ArrayList<>()).add(group);
                }
            }
            Set<String> used = new HashSet<>(groups.keySet());
            Map<SchemaNode, String> resolved = new IdentityHashMap<>();
            for (Map.Entry<String, List<SchemaNode>> entry : groups.entrySet()) {
                List<SchemaNode> members = entry.getValue();
                SchemaNode winner = members.stream()
                        .filter(node -> SchemaNames.isLegal(node.name()))
                        .min(Comparator.comparing(SchemaNode::name))
                        .orElseGet(() -> members.stream()
                                .min(Comparator.comparing(SchemaNode::name))
                                .orElseThrow());
                resolved.put(winner, entry.getKey());
                for (SchemaNode member : members) {
                    if (member == winner) {
                        continue;
                    }
                    String local = entry.getKey();
                    int suffix = 2;
                    while (!used.add(local + "_" + suffix)) {
                        suffix++;
                    }
                    resolved.put(member, local + "_" + suffix);
                }
            }
            return resolved;
        }

        private void resolve(List<NodeCandidate> named) {
            Map<String, List<NodeCandidate>> groups = new TreeMap<>();
            for (NodeCandidate candidate : named) {
                groups.computeIfAbsent(candidate.candidate(), ignored -> new ArrayList<>()).add(candidate);
            }
            // Every bare candidate is reserved up front, so a suffix never lands on
            // another sibling's bare name.
            Set<String> used = new HashSet<>(groups.keySet());
            for (List<NodeCandidate> members : groups.values()) {
                NodeCandidate winner = winnerOf(members);
                fullNames.put(winner.node(), join(winner.namespace(), winner.candidate()));
                List<NodeCandidate> losers = new ArrayList<>(members);
                losers.remove(winner);
                // Stable sort: exact duplicate raw names keep declaration order.
                losers.sort(Comparator.comparing(NodeCandidate::raw));
                for (NodeCandidate loser : losers) {
                    String local = winner.candidate();
                    String renamed = local;
                    for (int suffix = 2; !used.add(renamed); suffix++) {
                        renamed = local + "_" + suffix;
                    }
                    fullNames.put(loser.node(), join(loser.namespace(), renamed));
                }
            }
        }

        private static NodeCandidate winnerOf(List<NodeCandidate> members) {
            // The plan's total ordering: a legal raw candidate wins the bare candidate,
            // legal candidates competing with each other by raw name; without a legal
            // candidate the smallest raw name wins. Members arrive in declaration order,
            // so exact duplicate raw names keep source order.
            NodeCandidate winner = null;
            for (NodeCandidate member : members) {
                if (!SchemaNames.isLegal(member.raw())) {
                    continue;
                }
                if (winner == null || member.raw().compareTo(winner.raw()) < 0) {
                    winner = member;
                }
            }
            if (winner != null) {
                return winner;
            }
            for (NodeCandidate member : members) {
                if (winner == null || member.raw().compareTo(winner.raw()) < 0) {
                    winner = member;
                }
            }
            return winner;
        }

        private static String typeCandidate(SchemaNode node) {
            return SchemaNames.sanitize(capitalize(node.name()));
        }

        private static String join(String namespace, String segment) {
            return namespace.isEmpty() ? segment : namespace + "." + segment;
        }

        private static String localName(String fullName) {
            return fullName.substring(fullName.lastIndexOf('.') + 1);
        }

        private static String namespaceOf(String fullName) {
            int lastDot = fullName.lastIndexOf('.');
            return lastDot < 0 ? "" : fullName.substring(0, lastDot);
        }

        private record NodeCandidate(SchemaNode node, String namespace, String candidate, String raw) {
        }
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}

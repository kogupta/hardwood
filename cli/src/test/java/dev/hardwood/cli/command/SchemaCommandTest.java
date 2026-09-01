/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.cli.command;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.avro.Schema;
import org.apache.avro.SchemaParseException;
import org.apache.avro.file.DataFileWriter;
import org.apache.avro.generic.GenericDatumWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.hardwood.OutputFile;
import dev.hardwood.metadata.ConvertedType;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.metadata.SchemaElement;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.writer.ParquetFileWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaCommandTest implements SchemaCommandContract {

    private final String NESTED_FILE = this.getClass().getResource("/nested_struct_test.parquet").getPath();

    private final String VARIANT_FILE = this.getClass().getResource("/variant_test.parquet").getPath();

    private final String VARIANT_SHREDDED_FILE = this.getClass().getResource("/variant_shredded_test.parquet").getPath();

    private final String LIST_STRUCT_FILE = this.getClass().getResource("/list_struct_test.parquet").getPath();

    private final String MAP_STRUCT_VALUE_FILE = this.getClass().getResource("/map_struct_value_test.parquet").getPath();

    private final String INT96_FILE = this.getClass().getResource("/int96_timestamp_test.parquet").getPath();

    @Override
    public String plainFile() {
        return getClass().getResource("/plain_uncompressed.parquet").getPath();
    }

    @Override
    public String nonexistentFile() {
        return "nonexistent.parquet";
    }

    @Test
    void displaysAvroSchemaForNestedFile() {
        Cli.Result result = Cli.launch("schema", "-f", NESTED_FILE, "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("\"type\": \"record\"");
    }

    @Test
    void sanitizesNamesInAvroSchema(@TempDir Path tempDir) throws Exception {
        Path parquetFile = write(tempDir, FileSchema.builder("root \"schema\"\\path")
                .addColumn("say \"hi\"\\field", PhysicalType.INT32, RepetitionType.REQUIRED)
                .addColumn("bell\u0007field", PhysicalType.INT32, RepetitionType.REQUIRED)
                .build());

        Cli.Result result = Cli.launch("schema", "-f", parquetFile.toString(), "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        Schema parsed = new Schema.Parser().parse(result.output());
        assertThat(parsed.getName()).isEqualTo("Root__schema__path");
        assertThat(parsed.getDoc()).isEqualTo("Parquet name: root \"schema\"\\path");
        assertThat(parsed.getFields()).extracting(Schema.Field::name)
                .containsExactly("say__hi__field", "bell_field");
        assertThat(parsed.getFields()).extracting(Schema.Field::doc)
                .containsExactly("Parquet name: say \"hi\"\\field", "Parquet name: bell\u0007field");
    }

    @Test
    void disambiguatesCollidingFieldNamesInAvroSchema(@TempDir Path tempDir) throws Exception {
        Path parquetFile = write(tempDir, collidingNames());

        Cli.Result result = Cli.launch("schema", "-f", parquetFile.toString(), "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        Schema parsed = new Schema.Parser().parse(result.output());
        assertThat(parsed.getFields()).extracting(Schema.Field::name).containsExactly("a_b", "a_b_2", "a_b_3");
    }

    @Test
    void leavesLegalNamesUndocumentedInAvroSchema() {
        Cli.Result result = Cli.launch("schema", "-f", NESTED_FILE, "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).doesNotContain("\"doc\"");
        Schema parsed = new Schema.Parser().parse(result.output());
        assertThat(parsed.getType()).isEqualTo(Schema.Type.RECORD);
    }

    @Test
    void rendersRecordValuedListElementsInAvroSchema() throws Exception {
        Cli.Result result = Cli.launch("schema", "-f", LIST_STRUCT_FILE, "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        Schema parsed = parseAndCreateFileHeader(result.output());
        Schema elementType = nonNullBranch(nonNullBranch(parsed.getField("items").schema()).getElementType());
        assertThat(elementType.getType()).isEqualTo(Schema.Type.RECORD);
        assertThat(elementType.getFields()).extracting(Schema.Field::name).containsExactly("name", "quantity");
    }

    @Test
    void rendersRecordValuedMapValuesInAvroSchema() throws Exception {
        Cli.Result result = Cli.launch("schema", "-f", MAP_STRUCT_VALUE_FILE, "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        Schema parsed = parseAndCreateFileHeader(result.output());
        Schema values = nonNullBranch(nonNullBranch(parsed.getField("people").schema()).getValueType());
        assertThat(values.getType()).isEqualTo(Schema.Type.RECORD);
        assertThat(values.getFields()).extracting(Schema.Field::name).containsExactly("name", "age");
    }

    @Test
    void rendersOptionalMapValuesAsUnionsWithoutFieldDefault(@TempDir Path tempDir) throws Exception {
        Path parquetFile = write(tempDir, FileSchema.builder("schema")
                .map("scores", RepetitionType.REQUIRED, PhysicalType.BYTE_ARRAY, new LogicalType.StringType(),
                        value -> value.primitive(PhysicalType.INT32, RepetitionType.OPTIONAL))
                .build());

        Cli.Result result = Cli.launch("schema", "-f", parquetFile.toString(), "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("\"values\": [\"null\", \"int\"]");
        Schema parsed = parseAndCreateFileHeader(result.output());
        Schema.Field scores = parsed.getField("scores");
        assertThat(scores.schema().getValueType().getType()).isEqualTo(Schema.Type.UNION);
        assertThat(scores.defaultVal()).isNull();
    }

    @Test
    void rendersKeyOnlyMapsAsMapsOfNull(@TempDir Path tempDir) throws Exception {
        Path parquetFile = write(tempDir, keyOnlyMapSchema());

        Cli.Result result = Cli.launch("schema", "-f", parquetFile.toString(), "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("\"values\": \"null\"");
        Schema parsed = parseAndCreateFileHeader(result.output());
        assertThat(parsed.getField("tags").schema().getTypes().get(1).getValueType().getType())
                .isEqualTo(Schema.Type.NULL);
    }

    @Test
    void rejectsUnsupportedAvroMapKeys(@TempDir Path tempDir) throws Exception {
        Path parquetFile = write(tempDir, FileSchema.builder("schema")
                .map("counts", RepetitionType.REQUIRED, PhysicalType.INT32,
                        value -> value.primitive(PhysicalType.INT32, RepetitionType.OPTIONAL))
                .build());

        Cli.Result result = Cli.launch("schema", "-f", parquetFile.toString(), "--format", "AVRO");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.errorOutput()).contains("Avro map keys must be STRING, ENUM, or JSON");
    }

    /// The writer does not support INT96 columns, so this case uses the checked-in
    /// `int96_timestamp_test.parquet` fixture.
    @Test
    void rendersFixedWidthPrimitivesAsNamedFixedTypes(@TempDir Path tempDir) throws Exception {
        Path parquetFile = write(tempDir, FileSchema.builder("schema")
                .addColumn("code", PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 4)
                .build());

        Cli.Result result = Cli.launch("schema", "-f", parquetFile.toString(), "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains("{\"type\": \"fixed\", \"name\": \"Code\", \"namespace\": \"Schema\", \"size\": 4}");
        Schema parsed = parseAndCreateFileHeader(result.output());
        Schema code = nonNullBranch(parsed.getField("code").schema());
        assertThat(code.getType()).isEqualTo(Schema.Type.FIXED);
        assertThat(code.getFixedSize()).isEqualTo(4);
    }

    @Test
    void rendersInt96AsAFixedOfTwelveBytes() throws Exception {
        Cli.Result result = Cli.launch("schema", "-f", INT96_FILE, "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("\"size\": 12");
        parseAndCreateFileHeader(result.output());
    }

    @Test
    void distinguishesSameNamedFixedLeavesUnderDifferentParents(@TempDir Path tempDir) throws Exception {
        Path parquetFile = write(tempDir, FileSchema.builder("schema")
                .struct("home", RepetitionType.REQUIRED, home -> home
                        .addColumn("md5", PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 16))
                .struct("work", RepetitionType.REQUIRED, work -> work
                        .addColumn("md5", PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 16))
                .build());

        Cli.Result result = Cli.launch("schema", "-f", parquetFile.toString(), "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains("{\"type\": \"fixed\", \"name\": \"Md5\", \"namespace\": \"Schema.Home\", \"size\": 16}")
                .contains("{\"type\": \"fixed\", \"name\": \"Md5\", \"namespace\": \"Schema.Work\", \"size\": 16}");
        parseAndCreateFileHeader(result.output());
    }

    @Test
    void definesCanonicalIntervalAndFloat16Once(@TempDir Path tempDir) throws Exception {
        Path parquetFile = write(tempDir, FileSchema.builder("schema")
                .addColumn("first", PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 12,
                        new LogicalType.IntervalType())
                .addColumn("second", PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 12,
                        new LogicalType.IntervalType())
                .addColumn("half", PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 2,
                        new LogicalType.Float16Type())
                .build());

        Cli.Result result = Cli.launch("schema", "-f", parquetFile.toString(), "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains("{\"type\": \"fixed\", \"name\": \"interval\", \"size\": 12}")
                .contains(", \"type\": \"interval\"")
                .contains("{\"type\": \"fixed\", \"name\": \"float16\", \"size\": 2}");
        parseAndCreateFileHeader(result.output());
    }

    @Test
    void permitsCapitalizedRootAlongsideCanonicalFixedType(@TempDir Path tempDir) throws Exception {
        Path parquetFile = write(tempDir, FileSchema.builder("interval")
                .addColumn("span", PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 12,
                        new LogicalType.IntervalType())
                .build());

        Cli.Result result = Cli.launch("schema", "-f", parquetFile.toString(), "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains("\"name\": \"Interval\"")
                .contains("{\"type\": \"fixed\", \"name\": \"interval\", \"size\": 12}");
        parseAndCreateFileHeader(result.output());
    }

    @Test
    void rejectsMissingAvroMapKeyClearly() {
        assertThatThrownBy(() -> SchemaCommand.validateAvroMapKey(null, "broken"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("map 'broken'")
                .hasMessageContaining("missing key");
    }

    /// Two legal raw names that capitalize to one type candidate must resolve by raw
    /// name — not declaration order — so reordering the columns cannot swap their fixed
    /// full names (`Md5` sorts before `md5`).
    @Test
    void resolvesCollidingLegalFixedCandidatesIndependentlyOfDeclarationOrder(@TempDir Path tempDir) throws Exception {
        FileSchema.Builder schema = FileSchema.builder("schema");
        schema.addColumn("md5", PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 16)
                .addColumn("Md5", PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 16);
        Path declaredMd5First = write(Files.createDirectories(tempDir.resolve("md5-first")), schema.build());
        schema = FileSchema.builder("schema");
        schema.addColumn("Md5", PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 16)
                .addColumn("md5", PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 16);
        Path declaredMd5Last = write(Files.createDirectories(tempDir.resolve("md5-last")), schema.build());

        Cli.Result first = Cli.launch("schema", "-f", declaredMd5First.toString(), "--format", "AVRO");
        Cli.Result second = Cli.launch("schema", "-f", declaredMd5Last.toString(), "--format", "AVRO");

        assertThat(first.exitCode()).isZero();
        assertThat(second.exitCode()).isZero();
        for (Cli.Result result : List.of(first, second)) {
            assertThat(result.output())
                    .contains("{\"type\": \"fixed\", \"name\": \"Md5\", \"namespace\": \"Schema\", \"size\": 16}")
                    .contains("{\"type\": \"fixed\", \"name\": \"Md5_2\", \"namespace\": \"Schema\", \"size\": 16}");
            parseAndCreateFileHeader(result.output());
        }
    }

    @Test
    void keepsFixedContainerNamesStableWhenFieldsAreReordered(@TempDir Path tempDir) throws Exception {
        FileSchema.Builder firstBuilder = FileSchema.builder("schema")
                .list("a-b", RepetitionType.REQUIRED, element -> element
                        .primitive(PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 4))
                .list("a b", RepetitionType.REQUIRED, element -> element
                        .primitive(PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 8));
        Path firstFile = write(Files.createDirectories(tempDir.resolve("first")), firstBuilder.build());

        FileSchema.Builder secondBuilder = FileSchema.builder("schema")
                .list("a b", RepetitionType.REQUIRED, element -> element
                        .primitive(PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 8))
                .list("a-b", RepetitionType.REQUIRED, element -> element
                        .primitive(PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 4));
        Path secondFile = write(Files.createDirectories(tempDir.resolve("second")), secondBuilder.build());

        Cli.Result first = Cli.launch("schema", "-f", firstFile.toString(), "--format", "AVRO");
        Cli.Result second = Cli.launch("schema", "-f", secondFile.toString(), "--format", "AVRO");

        assertThat(first.exitCode()).isZero();
        assertThat(second.exitCode()).isZero();
        for (Cli.Result result : List.of(first, second)) {
            assertThat(result.output())
                    .contains("{\"type\": \"fixed\", \"name\": \"Element\", \"namespace\": \"Schema.a_b\", \"size\": 8}")
                    .contains("{\"type\": \"fixed\", \"name\": \"Element\", \"namespace\": \"Schema.a_b_2\", \"size\": 4}");
            parseAndCreateFileHeader(result.output());
        }
    }

    @Test
    void displaysProtoSchemaForNestedFile() {
        Cli.Result result = Cli.launch("schema", "-f", NESTED_FILE, "--format", "PROTO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains("syntax = \"proto3\"")
                .contains("message");
    }

    @Test
    void sanitizesNamesInProtoSchema(@TempDir Path tempDir) throws Exception {
        Path parquetFile = write(tempDir, FileSchema.builder("root \"schema\"")
                .addColumn("total (usd)", PhysicalType.DOUBLE, RepetitionType.OPTIONAL)
                .addColumn("bell\u0007field", PhysicalType.INT32, RepetitionType.REQUIRED)
                .addColumn("plain", PhysicalType.INT32, RepetitionType.REQUIRED)
                .build());

        Cli.Result result = Cli.launch("schema", "-f", parquetFile.toString(), "--format", "PROTO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("""
                syntax = "proto3";

                message Root__schema_ {
                  // Parquet name: total (usd)
                  optional double total__usd_ = 1;
                  // Parquet name: bell\\u0007field
                  int32 bell_field = 2;
                  int32 plain = 3;
                }""");
    }

    @Test
    void disambiguatesCollidingFieldNamesInProtoSchema(@TempDir Path tempDir) throws Exception {
        Path parquetFile = write(tempDir, collidingNames());

        Cli.Result result = Cli.launch("schema", "-f", parquetFile.toString(), "--format", "PROTO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains("int32 a_b = 1;")
                .contains("int32 a_b_2 = 2;")
                .contains("int32 a_b_3 = 3;");
    }

    @Test
    void leavesLegalNamesUncommentedInProtoSchema() {
        Cli.Result result = Cli.launch("schema", "-f", NESTED_FILE, "--format", "PROTO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).doesNotContain("//");
    }

    @Test
    void rejectsRemoteUri() {
        Cli.Result result = Cli.launch("schema", "-f", "gs://bucket/data.parquet");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.errorOutput()).contains("not implemented yet");
    }

    @Test
    void displaysVariantAnnotation() {
        Cli.Result result = Cli.launch("schema", "-f", VARIANT_FILE);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("""
                message schema {
                  required int32 id;
                  optional group var (VARIANT(1)) {
                    required byte_array metadata;
                    required byte_array value;
                  }
                }""");
    }

    @Test
    void displaysShreddedVariantAnnotationWithTypedValueChild() {
        Cli.Result result = Cli.launch("schema", "-f", VARIANT_SHREDDED_FILE);

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).isEqualTo("""
                message schema {
                  required int32 id;
                  optional group var (VARIANT(1)) {
                    required byte_array metadata;
                    optional byte_array value;
                    optional int64 typed_value;
                  }
                }""");
    }

    /// Three names that all sanitize to `a_b`.
    private static FileSchema collidingNames() {
        return FileSchema.builder("schema")
                .addColumn("a b", PhysicalType.INT32, RepetitionType.REQUIRED)
                .addColumn("a-b", PhysicalType.INT32, RepetitionType.REQUIRED)
                .addColumn("a.b", PhysicalType.INT32, RepetitionType.REQUIRED)
                .build();
    }

    /// Known limitation, repaired by #953 PR 3: ordinary nested records are still inlined
    /// with bare local names, so two same-named records under different parents emit two
    /// `Address` definitions and `Schema.Parser` rejects the redefinition. This test pins
    /// the limitation so PR 3's diff flips it into distinct full-name assertions.
    @Test
    void knownLimitationDuplicateNestedRecordNamesStillCollide(@TempDir Path tempDir) throws Exception {
        Path parquetFile = write(tempDir, FileSchema.builder("schema")
                .struct("home", RepetitionType.REQUIRED, home -> home
                        .struct("address", RepetitionType.REQUIRED, address -> address
                                .addColumn("city", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED,
                                        new LogicalType.StringType())))
                .struct("work", RepetitionType.REQUIRED, work -> work
                        .struct("address", RepetitionType.REQUIRED, address -> address
                                .addColumn("zip", PhysicalType.BYTE_ARRAY, RepetitionType.REQUIRED,
                                        new LogicalType.StringType())))
                .build());

        Cli.Result result = Cli.launch("schema", "-f", parquetFile.toString(), "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output()).contains("\"name\": \"Address\"").contains("\"name\": \"Address\"");
        SchemaParseException rejection = null;
        try {
            new Schema.Parser().parse(result.output());
        }
        catch (SchemaParseException e) {
            rejection = e;
        }
        assertThat(rejection).isNotNull();
        assertThat(rejection.getMessage()).contains("Can't redefine: Address");
    }

    @Test
    void resolvesFixedNamesInContainerPositions(@TempDir Path tempDir) throws Exception {
        Path parquetFile = write(tempDir, FileSchema.builder("schema")
                .list("hashes", RepetitionType.REQUIRED, element -> element
                        .primitive(PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 16))
                .map("digests", RepetitionType.REQUIRED, PhysicalType.BYTE_ARRAY, new LogicalType.StringType(),
                        value -> value.primitive(PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.OPTIONAL, 8))
                .build());

        Cli.Result result = Cli.launch("schema", "-f", parquetFile.toString(), "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains("{\"type\": \"fixed\", \"name\": \"Element\", \"namespace\": \"Schema.hashes\", \"size\": 16}")
                .contains("{\"type\": \"fixed\", \"name\": \"Value\", \"namespace\": \"Schema.digests\", \"size\": 8}");
        parseAndCreateFileHeader(result.output());
    }

    @Test
    void disambiguatesCollidingFixedCandidatesInOneScope(@TempDir Path tempDir) throws Exception {
        Path parquetFile = write(tempDir, FileSchema.builder("schema")
                .addColumn("md5", PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 16)
                .addColumn("Md5", PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 16)
                .build());

        Cli.Result result = Cli.launch("schema", "-f", parquetFile.toString(), "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains("{\"type\": \"fixed\", \"name\": \"Md5\", \"namespace\": \"Schema\", \"size\": 16}")
                .contains("\"name\": \"Md5_2\"");
        parseAndCreateFileHeader(result.output());
    }

    @Test
    void rendersNestedListElementsRecursively(@TempDir Path tempDir) throws Exception {
        Path parquetFile = write(tempDir, FileSchema.builder("schema")
                .list("matrix", RepetitionType.REQUIRED, element -> element
                        .list(RepetitionType.REQUIRED, inner -> inner
                                .primitive(PhysicalType.INT32, RepetitionType.REQUIRED)))
                .build());

        Cli.Result result = Cli.launch("schema", "-f", parquetFile.toString(), "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        assertThat(result.output())
                .contains("{\"type\": \"array\", \"items\": {\"type\": \"array\", \"items\": \"int\"}}");
        Schema parsed = parseAndCreateFileHeader(result.output());
        Schema outer = nonNullBranch(parsed.getField("matrix").schema());
        assertThat(outer.getElementType().getType()).isEqualTo(Schema.Type.ARRAY);
        assertThat(outer.getElementType().getElementType().getType()).isEqualTo(Schema.Type.INT);
    }

    @Test
    void acceptsEnumAndJsonMapKeys(@TempDir Path tempDir) throws Exception {
        Path parquetFile = write(tempDir, FileSchema.builder("schema")
                .map("by_role", RepetitionType.REQUIRED, PhysicalType.BYTE_ARRAY, new LogicalType.EnumType(),
                        value -> value.primitive(PhysicalType.INT32, RepetitionType.OPTIONAL))
                .map("by_doc", RepetitionType.REQUIRED, PhysicalType.BYTE_ARRAY, new LogicalType.JsonType(),
                        value -> value.primitive(PhysicalType.INT32, RepetitionType.OPTIONAL))
                .build());

        Cli.Result result = Cli.launch("schema", "-f", parquetFile.toString(), "--format", "AVRO");

        assertThat(result.exitCode()).isZero();
        parseAndCreateFileHeader(result.output());
    }

    /// The emitter's malformed-container and missing-`type_length` rejections are not
    /// reachable through [ParquetFileWriter]: the writer refuses to create such files in
    /// the first place (`WriterSchemaShape`, `ValueEncoder`), so the negative paths only
    /// trigger for third-party files. Raw hand-written Parquet fixtures are out of scope
    /// for this PR.
    ///
    /// A key-only map cannot be built through [FileSchema.Builder], whose `map` verbs
    /// require a value, so the schema elements are declared directly: a MAP group whose
    /// `key_value` carries only the required `key`.
    private static FileSchema keyOnlyMapSchema() {
        List<SchemaElement> elements = List.of(
                SchemaElement.group("schema", RepetitionType.REQUIRED, 1),
                new SchemaElement("tags", null, null, RepetitionType.OPTIONAL, 1, ConvertedType.MAP,
                        null, null, null, new LogicalType.MapType()),
                SchemaElement.group("key_value", RepetitionType.REPEATED, 1),
                new SchemaElement("key", PhysicalType.BYTE_ARRAY, null, RepetitionType.REQUIRED, null,
                        ConvertedType.UTF8, null, null, null, new LogicalType.StringType()));
        return FileSchema.fromSchemaElements(elements);
    }

    private static Schema nonNullBranch(Schema schema) {
        if (schema.getType() != Schema.Type.UNION) {
            return schema;
        }
        return schema.getTypes().stream()
                .filter(type -> type.getType() != Schema.Type.NULL)
                .findFirst()
                .orElseThrow();
    }

    private static Schema parseAndCreateFileHeader(String output) throws Exception {
        Schema parsed = new Schema.Parser().parse(output);
        try (DataFileWriter<Object> writer = new DataFileWriter<>(new GenericDatumWriter<>())) {
            writer.create(parsed, new ByteArrayOutputStream());
        }
        return parsed;
    }

    /// Three container fields sanitizing to one segment exercise the loser tier: only
    /// `a_b` is a legal raw name (the hyphen and space are illegal), so it keeps the bare
    /// segment, and the losers receive `_2`/`_3` by raw-name order (`a b` sorts before
    /// `a-b`), not by declaration order — the first file declares the losers in the
    /// opposite order, so a declaration-order implementation fails this test.
    @Test
    void ordersCollidingContainerLoserSegmentsByRawName(@TempDir Path tempDir) throws Exception {
        FileSchema.Builder firstBuilder = FileSchema.builder("schema")
                .list("a_b", RepetitionType.REQUIRED, element -> element
                        .primitive(PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 16))
                .list("a-b", RepetitionType.REQUIRED, element -> element
                        .primitive(PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 4))
                .list("a b", RepetitionType.REQUIRED, element -> element
                        .primitive(PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 8));
        Path firstFile = write(Files.createDirectories(tempDir.resolve("first")), firstBuilder.build());

        FileSchema.Builder secondBuilder = FileSchema.builder("schema")
                .list("a b", RepetitionType.REQUIRED, element -> element
                        .primitive(PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 8))
                .list("a-b", RepetitionType.REQUIRED, element -> element
                        .primitive(PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 4))
                .list("a_b", RepetitionType.REQUIRED, element -> element
                        .primitive(PhysicalType.FIXED_LEN_BYTE_ARRAY, RepetitionType.REQUIRED, 16));
        Path secondFile = write(Files.createDirectories(tempDir.resolve("second")), secondBuilder.build());

        Cli.Result first = Cli.launch("schema", "-f", firstFile.toString(), "--format", "AVRO");
        Cli.Result second = Cli.launch("schema", "-f", secondFile.toString(), "--format", "AVRO");

        assertThat(first.exitCode()).isZero();
        assertThat(second.exitCode()).isZero();
        for (Cli.Result result : List.of(first, second)) {
            assertThat(result.output())
                    .contains("\"name\": \"Element\", \"namespace\": \"Schema.a_b\", \"size\": 16")
                    .contains("\"name\": \"Element\", \"namespace\": \"Schema.a_b_2\", \"size\": 8")
                    .contains("\"name\": \"Element\", \"namespace\": \"Schema.a_b_3\", \"size\": 4");
            parseAndCreateFileHeader(result.output());
        }
    }

    private static Path write(Path tempDir, FileSchema schema) throws Exception {
        Path parquetFile = tempDir.resolve("names.parquet");
        try (ParquetFileWriter ignored = ParquetFileWriter.create(OutputFile.of(parquetFile), schema)) {
        }
        return parquetFile;
    }
}

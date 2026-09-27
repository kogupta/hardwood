/*
 *  SPDX-License-Identifier: Apache-2.0
 *
 *  Copyright The original authors
 *
 *  Licensed under the Apache Software License version 2.0, available at http://www.apache.org/licenses/LICENSE-2.0
 */
package dev.hardwood.internal.reader;

import org.checkerframework.checker.index.qual.IndexFor;
import org.checkerframework.checker.index.qual.IndexOrLow;
import org.checkerframework.checker.index.qual.SameLen;
import org.checkerframework.checker.nullness.qual.Nullable;

import dev.hardwood.internal.ExceptionContext;
import dev.hardwood.internal.schema.ProjectedSchema;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.schema.ColumnSchema;
import dev.hardwood.schema.SchemaNode;

/// Pre-computed batch-level index for all projected columns.
///
/// Computed once per `setBatchData()` call. Holds multi-level offset arrays,
/// null bitmaps, and raw value arrays that enable flyweight cursors to
/// navigate directly over column data without per-row tree assembly.
final class NestedBatchIndex {

    /// The outer arrays are one `@SameLen("valueCounts")` family: every
    /// projected-column index is valid for all of them, which
    /// [NestedBatchIndex#buildFromBatches] establishes with a length check at
    /// the construction boundary. The inner (jagged) dimensions stay
    /// runtime-validated construction invariants.
    final Object @SameLen("valueCounts") [] valueArrays;    // [projectedCol] -> typed value array (int[], long[], etc.)
    final int @SameLen("valueCounts") [][] defLevels;       // [projectedCol] -> definition levels
    final ColumnSchema @SameLen("valueCounts") @Nullable [] columnSchemas; // [projectedCol] -> column schema; null where no schema is bound
    final int @SameLen("valueCounts") [] valueCounts;       // [projectedCol] -> number of values
    final int @SameLen("valueCounts") [] recordCounts;      // [projectedCol] -> number of records
    final int @SameLen("valueCounts") [][] offsets;         // [projectedCol] -> record-level offsets
    /// `[projectedCol] -> int[repCount][]`, **rep-level-indexed** offsets
    /// compacted from the layer-indexed [NestedBatch#multiLevelOffsets]
    /// produced by the worker. `STRUCT`-layer slots (`null`) are dropped so
    /// the remaining `REPEATED`-layer offsets are addressable by the
    /// 0-indexed rep level used by internal consumers
    /// ([PqListImpl] / [PqMapImpl] / [PqStructImpl]). Each per-rep-level
    /// `int[]` is sentinel-suffixed (length `count + 1`).
    final int @SameLen("valueCounts") [][] @SameLen({})[] multiOffsets;
    final long @SameLen("valueCounts") [][] elementValidity; // [projectedCol] -> leaf validity bitmap (set bit = present)
    final ProjectedSchema projectedSchema;
    /// The file these batches came from, for the failures that name one. Batches never
    /// straddle files.
    final String fileName;

    private NestedBatchIndex(NestedBatch[] batches, ColumnSchema @Nullable [] columnSchemas,
                             ProjectedSchema projectedSchema) {
        // Every outer array is created from `batches.length` and assigned straight to
        // its field from a local, so the SameLen checker unifies the family the field
        // declarations declare: one projectedCol index is valid across all of them.
        // The schema array is optional; when present, the length check ties it to the
        // same family — without it a short array would surface later as an
        // access-time ArrayIndexOutOfBoundsException.
        int[] valueCounts = new int[batches.length];
        Object[] valueArrays = new Object[batches.length];
        int[][] defLevels = new int[batches.length][];
        int[] recordCounts = new int[batches.length];
        int[][] offsets = new int[batches.length][];
        int[][][] multiOffsets = new int[batches.length][][];
        long[][] elementValidity = new long[batches.length][];
        if (columnSchemas != null && columnSchemas.length != valueCounts.length) {
            throw new IllegalArgumentException("Column schema count " + columnSchemas.length
                    + " does not match the " + valueCounts.length + " batch columns");
        }
        for (int col = 0; col < valueCounts.length; col++) {
            NestedBatch batch = batches[col];
            valueArrays[col] = batch.values;
            defLevels[col] = batch.definitionLevels;
            valueCounts[col] = batch.valueCount;
            recordCounts[col] = batch.recordCount;
            offsets[col] = batch.recordOffsets;
            multiOffsets[col] = compactToRepLevelOffsets(batch.multiLevelOffsets);
            elementValidity[col] = batch.elementValidity;
        }
        this.valueCounts = valueCounts;
        this.valueArrays = valueArrays;
        this.defLevels = defLevels;
        this.recordCounts = recordCounts;
        this.offsets = offsets;
        this.multiOffsets = multiOffsets;
        this.elementValidity = elementValidity;
        this.columnSchemas = columnSchemas;
        this.projectedSchema = projectedSchema;
        this.fileName = batches.length > 0 ? batches[0].fileName : null;
    }

    /// Fails when the caller has asked a column for a float it does not hold.
    ///
    /// Reached only once the `FLOAT` fast path has been ruled out, so a column that holds
    /// floats never arrives here. Shared by every nested accessor that reads a `FLOAT16`,
    /// so all of them answer a caller the same way. The column is named by its leaf name,
    /// as every other message this reader composes names it. A column whose annotation its
    /// width cannot carry arrives unannotated, so it reaches here as what it physically is
    /// rather than as a broken `FLOAT16`.
    void requireFloatAccess(SchemaNode.PrimitiveNode column) {
        LogicalType logicalType = column.logicalType();
        if (logicalType instanceof LogicalType.Float16Type) {
            return;
        }
        throw new IllegalArgumentException(ExceptionContext.filePrefix(fileName)
                + "Column '" + column.name() + "' is " + column.type()
                + (logicalType == null ? "" : " annotated " + logicalType)
                + ", which cannot be read as a float");
    }

    /// Build the batch index from [NestedBatch] objects whose index fields
    /// have been pre-computed by the drain thread.
    static NestedBatchIndex buildFromBatches(NestedBatch[] batches, ColumnSchema @Nullable [] columnSchemas,
                                             ProjectedSchema projectedSchema) {
        return new NestedBatchIndex(batches, columnSchemas, projectedSchema);
    }
    /// Compact a layer-indexed offsets array (length `layerCount`, with
    /// `null` at `STRUCT`-layer positions) into a rep-level-indexed array
    /// (length `repCount`) by dropping the `null` slots while preserving
    /// order. `null` input passes through unchanged.
    private static int[][] compactToRepLevelOffsets(int[][] layerIndexed) {
        if (layerIndexed == null) {
            return null;
        }
        int repCount = 0;
        for (int[] o : layerIndexed) {
            if (o != null) {
                repCount++;
            }
        }
        if (repCount == layerIndexed.length) {
            return layerIndexed;
        }
        int[][] compact = new int[repCount][];
        int j = 0;
        for (int[] o : layerIndexed) {
            if (o != null) {
                compact[j++] = o;
            }
        }
        return compact;
    }

    // ==================== Value Access ====================

    /// Refines a schema-derived projected column against this batch's columns.
    ///
    /// Descriptors ([TopLevelFieldMap.FieldDesc]) carry column indices built
    /// from the schema; this batch is per-`setBatchData` state. The two are
    /// guaranteed to agree by construction, but that guarantee crosses object
    /// boundaries the type system cannot see, so this check — the one place a
    /// descriptor meets a batch — establishes it at runtime once per call and
    /// mints the `@IndexFor("valueCounts")` type the accessors require. It
    /// never fires for a descriptor built against the same projected schema.
    @IndexFor("valueCounts") int refineProjCol(int col) {
        if (col < 0 || col >= valueCounts.length) {
            throw new IllegalStateException("Projected column " + col + " is outside the "
                    + valueCounts.length + " columns of this batch");
        }
        return col;
    }

    /// Get the definition level at the given value index.
    int getDefLevel(@IndexFor("valueCounts") int projectedCol, int valueIndex) {
        int[] dl = defLevels[projectedCol];
        return dl != null ? dl[valueIndex] : columnSchemas[projectedCol].maxDefinitionLevel();
    }

    /// Get the maximum repetition level for a column.
    int getMaxRepLevel(@IndexFor("valueCounts") int projectedCol) {
        return columnSchemas[projectedCol].maxRepetitionLevel();
    }

    /// Get the boxed value at the given index (for generic access paths).
    /// For byte-array physical types this materialises a fresh `byte[]`
    /// copy out of [BinaryBatchValues].
    Object getValue(@IndexFor("valueCounts") int projectedCol, int valueIndex) {
        Object arr = valueArrays[projectedCol];
        return switch (arr) {
            case int[] a -> a[valueIndex];
            case long[] a -> a[valueIndex];
            case float[] a -> a[valueIndex];
            case double[] a -> a[valueIndex];
            case boolean[] a -> a[valueIndex];
            case BinaryBatchValues bbv -> bbv.byteArrayAt(valueIndex);
            default -> throw new IllegalStateException("Unexpected array type: " + arr.getClass());
        };
    }

    /// Get a fresh byte[] copy of value `valueIndex` for a varlength column.
    byte[] getBinary(@IndexFor("valueCounts") int projectedCol, int valueIndex) {
        return ((BinaryBatchValues) valueArrays[projectedCol]).byteArrayAt(valueIndex);
    }

    /// Get a UTF-8 decoded string for value `valueIndex` of a varlength column.
    String getString(@IndexFor("valueCounts") int projectedCol, int valueIndex) {
        return ((BinaryBatchValues) valueArrays[projectedCol]).stringAt(valueIndex);
    }

    /// Decode value `valueIndex` of `projectedCol` to its boxed Java value: an
    /// interned `String` for a `UTF8` / `JSON` leaf, otherwise the converted raw
    /// value. The element must be known non-null.
    Object decodeLeaf(@IndexFor("valueCounts") int projectedCol, int valueIndex, SchemaNode schema) {
        return LeafKind.of(schema) == LeafKind.STRING
                ? getString(projectedCol, valueIndex)
                : NestedLeafDecoder.decode(getValue(projectedCol, valueIndex), schema);
    }

    // ==================== Index Navigation ====================

    /// Get the value index for a non-repeated column at the given record.
    int getValueIndex(@IndexFor("valueCounts") int projectedCol, int recordIndex) {
        int[] recordOffsets = offsets[projectedCol];
        return recordOffsets != null ? recordOffsets[recordIndex] : recordIndex;
    }

    /// Get the start value index for a repeated column's list at the given record.
    int getListStart(@IndexFor("valueCounts") int projectedCol, int recordIndex) {
        int[][] ml = multiOffsets[projectedCol];
        if (ml == null) {
            int[] recordOffsets = offsets[projectedCol];
            return recordOffsets != null ? recordOffsets[recordIndex] : recordIndex;
        }
        return ml[0][recordIndex];
    }

    /// Get the end index (exclusive) for a repeated column's list at the
    /// given record. With sentinel-suffixed `multiOffsets[k]` (length
    /// `count + 1`) the last record's end is just `ml[0][recordIndex + 1]`.
    int getListEnd(@IndexFor("valueCounts") int projectedCol, int recordIndex) {
        int[][] ml = multiOffsets[projectedCol];
        if (ml == null) {
            int[] recordOffsets = offsets[projectedCol];
            if (recordOffsets == null) {
                return recordIndex + 1;
            }
            return (recordIndex + 1 < recordCounts[projectedCol])
                    ? recordOffsets[recordIndex + 1]
                    : valueCounts[projectedCol];
        }
        return ml[0][recordIndex + 1];
    }

    /// Get the start index at a given multi-level offset level.
    int getLevelStart(@IndexFor("valueCounts") int projectedCol, int level, int itemIndex) {
        return multiOffsets[projectedCol][level][itemIndex];
    }

    /// Get the end index (exclusive) at a given multi-level offset level.
    /// `multiOffsets[level]` is sentinel-suffixed (length `count + 1`), so
    /// the next slot is always available.
    int getLevelEnd(@IndexFor("valueCounts") int projectedCol, int level, int itemIndex) {
        return multiOffsets[projectedCol][level][itemIndex + 1];
    }

    /// Check if a value at the given position is null at the leaf level.
    /// Validity polarity is **set bit = present**, so a null leaf is
    /// indicated by a clear bit (or a `null` validity reference means every
    /// leaf in the batch is present).
    /// `projectedCol` is `@IndexOrLow`: a negative index means "no projected
    /// column", which the `projectedCol < 0` guard refines to `@IndexFor`
    /// before the validity bitmap is indexed.
    boolean isElementNull(@IndexOrLow("valueCounts") int projectedCol, int valueIndex) {
        if (projectedCol < 0) {
            return true;
        }
        long[] validity = elementValidity[projectedCol];
        return validity != null && (validity[valueIndex >>> 6] & (1L << valueIndex)) == 0L;
    }
}

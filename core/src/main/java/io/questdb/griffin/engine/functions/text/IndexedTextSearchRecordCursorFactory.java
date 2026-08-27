/*******************************************************************************
 * Copyright (c) 2014-2019 Appsicle
 * Copyright (c) 2019-2026 QuestDB
 * Licensed under the Apache License, Version 2.0.
 ******************************************************************************/

package io.questdb.griffin.engine.functions.text;

import io.questdb.cairo.AbstractRecordCursorFactory;
import io.questdb.cairo.CairoException;
import io.questdb.cairo.ColumnType;
import io.questdb.cairo.GenericRecordMetadata;
import io.questdb.cairo.TableColumnMetadata;
import io.questdb.cairo.TableReader;
import io.questdb.cairo.TableReaderMetadata;
import io.questdb.cairo.TableToken;
import io.questdb.cairo.TableUtils;
import io.questdb.cairo.VarcharTypeDriver;
import io.questdb.cairo.idx.OfflineTextIndexBuilder;
import io.questdb.cairo.idx.PartitionTextIndexReader;
import io.questdb.cairo.idx.PartitionTextIndexSearcher;
import io.questdb.cairo.sql.PartitionFormat;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.vm.api.MemoryCR;
import io.questdb.griffin.PlanSink;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.std.Misc;
import io.questdb.std.ObjList;
import io.questdb.std.Rows;
import io.questdb.std.str.Path;
import io.questdb.std.str.Utf8Sequence;
import io.questdb.std.str.Utf8String;

/** Experimental append-only index cursor. Returns {@code null} when safe scan fallback is required. */
final class IndexedTextSearchRecordCursorFactory extends AbstractRecordCursorFactory {
    private final int columnIndex;
    private final long limit;
    private final long timestampHi;
    private final int timestampIndex;
    private final long timestampLo;
    private final TableToken tableToken;
    private final Utf8String query;

    IndexedTextSearchRecordCursorFactory(
            TableToken tableToken,
            int columnIndex,
            int timestampIndex,
            int timestampType,
            Utf8String query,
            long timestampLo,
            long timestampHi,
            long limit
    ) {
        super(createMetadata(timestampType));
        this.tableToken = tableToken;
        this.columnIndex = columnIndex;
        this.timestampIndex = timestampIndex;
        this.query = query;
        this.timestampLo = timestampLo;
        this.timestampHi = timestampHi;
        this.limit = limit;
    }

    @Override
    public RecordCursor getCursor(SqlExecutionContext executionContext) throws SqlException {
        final TableReader reader = executionContext.getCairoEngine().getReader(tableToken);
        try {
            final IndexedCursor cursor = new IndexedCursor(
                    reader,
                    tableToken,
                    columnIndex,
                    timestampIndex,
                    timestampLo,
                    timestampHi
            );
            if (!cursor.load(query, limit)) {
                cursor.close();
                return null;
            }
            return cursor;
        } catch (CairoException e) {
            Misc.free(reader);
            return null;
        }
    }

    @Override
    public boolean recordCursorSupportsRandomAccess() {
        return true;
    }

    @Override
    public void toPlan(PlanSink sink) {
        sink.type("text_search partition index");
        sink.meta("table").val(tableToken.getTableName());
        sink.meta("limit").val(limit);
    }

    @Override
    public boolean usesIndex() {
        return true;
    }

    private static GenericRecordMetadata createMetadata(int timestampType) {
        final GenericRecordMetadata metadata = new GenericRecordMetadata();
        metadata.add(new TableColumnMetadata("ts", timestampType));
        metadata.add(new TableColumnMetadata("value", ColumnType.VARCHAR));
        metadata.add(new TableColumnMetadata("score", ColumnType.DOUBLE));
        metadata.setTimestampIndex(0);
        return metadata;
    }

    private static final class Candidate {
        private final int partitionIndex;
        private final long rowId;
        private final double score;

        private Candidate(int partitionIndex, long rowId, double score) {
            this.partitionIndex = partitionIndex;
            this.rowId = rowId;
            this.score = score;
        }
    }

    private static final class IndexedCursor implements RecordCursor {
        private final ObjList<Candidate> candidates = new ObjList<>();
        private final int columnIndex;
        private int index;
        private final TableReader reader;
        private final IndexedRecord recordA = new IndexedRecord();
        private final IndexedRecord recordB = new IndexedRecord();
        private final PartitionTextIndexSearcher searcher = new PartitionTextIndexSearcher();
        private final long timestampHi;
        private final int timestampIndex;
        private final long timestampLo;
        private final TableToken tableToken;

        private IndexedCursor(
                TableReader reader,
                TableToken tableToken,
                int columnIndex,
                int timestampIndex,
                long timestampLo,
                long timestampHi
        ) {
            this.reader = reader;
            this.tableToken = tableToken;
            this.columnIndex = columnIndex;
            this.timestampIndex = timestampIndex;
            this.timestampLo = timestampLo;
            this.timestampHi = timestampHi;
        }

        @Override
        public void close() {
            Misc.free(reader);
        }

        @Override
        public Record getRecord() {
            return recordA;
        }

        @Override
        public Record getRecordB() {
            return recordB;
        }

        @Override
        public boolean hasNext() {
            if (index >= candidates.size()) {
                return false;
            }
            recordA.of(candidates.getQuick(index++));
            return true;
        }

        private boolean load(Utf8Sequence query, long requestedLimit) {
            final TableReaderMetadata metadata = reader.getMetadata();
            final int writerColumnIndex = metadata.getWriterIndex(columnIndex);
            try (Path path = new Path().of(reader.getConfiguration().getDbRoot()).concat(tableToken)) {
                final int rootLength = path.size();
                for (int partitionIndex = 0, n = reader.getPartitionCount(); partitionIndex < n; partitionIndex++) {
                    final long partitionTimestamp = reader.getPartitionTimestampByIndex(partitionIndex);
                    final long partitionHi;
                    if (partitionIndex + 1 < n) {
                        partitionHi = reader.getPartitionTimestampByIndex(partitionIndex + 1);
                    } else {
                        final long maxTimestamp = reader.getTxFile().getMaxTimestamp();
                        partitionHi = maxTimestamp == Long.MAX_VALUE ? Long.MAX_VALUE : maxTimestamp + 1;
                    }
                    if (partitionTimestamp >= timestampHi || partitionHi <= timestampLo) {
                        continue;
                    }
                    if (reader.getPartitionFormatFromMetadata(partitionIndex) != PartitionFormat.NATIVE) {
                        return false;
                    }
                    final long columnNameTxn = reader.getColumnVersionReader().getColumnNameTxn(
                            partitionTimestamp,
                            writerColumnIndex
                    );
                    path.trimTo(rootLength);
                    TableUtils.setPathForNativePartition(
                            path,
                            metadata.getTimestampType(),
                            metadata.getPartitionBy(),
                            partitionTimestamp,
                            reader.getTxFile().getPartitionNameTxn(partitionIndex)
                    );
                    OfflineTextIndexBuilder.textIndexFileName(path, metadata.getColumnName(columnIndex), columnNameTxn);
                    if (!reader.getConfiguration().getFilesFacade().exists(path.$())) {
                        return false;
                    }
                    try (PartitionTextIndexReader indexReader = new PartitionTextIndexReader(
                            reader.getConfiguration().getFilesFacade(),
                            path,
                            columnNameTxn,
                            partitionTimestamp
                    )) {
                        final ObjList<PartitionTextIndexSearcher.SearchResult> partitionResults = searcher.search(
                                indexReader,
                                query,
                                Integer.MAX_VALUE
                        );
                        reader.openPartition(partitionIndex);
                        final int columnBase = reader.getColumnBase(partitionIndex);
                        final int timestampPrimary = TableReader.getPrimaryColumnIndex(columnBase, timestampIndex);
                        final MemoryCR timestampMem = reader.getColumn(timestampPrimary);
                        for (int r = 0, rn = partitionResults.size(); r < rn; r++) {
                            final PartitionTextIndexSearcher.SearchResult result = partitionResults.getQuick(r);
                            final long timestamp = timestampMem.getLong(result.getRowId() * Long.BYTES);
                            if (timestamp >= timestampLo && timestamp < timestampHi) {
                                candidates.add(new Candidate(partitionIndex, result.getRowId(), result.getScore()));
                            }
                        }
                    }
                }
            }
            candidates.sort((left, right) -> {
                final int scoreCmp = Double.compare(right.score, left.score);
                if (scoreCmp != 0) {
                    return scoreCmp;
                }
                final int partitionCmp = Integer.compare(left.partitionIndex, right.partitionIndex);
                return partitionCmp != 0 ? partitionCmp : Long.compare(left.rowId, right.rowId);
            });
            candidates.setPos((int) Math.min(requestedLimit, candidates.size()));
            toTop();
            return true;
        }

        @Override
        public long preComputedStateSize() {
            return 0;
        }

        @Override
        public void recordAt(Record record, long atRowId) {
            ((IndexedRecord) record).of(candidates.getQuick((int) atRowId));
        }

        @Override
        public long size() {
            return candidates.size();
        }

        @Override
        public void toTop() {
            index = 0;
        }

        private final class IndexedRecord implements Record {
            private long columnTop;
            private MemoryCR dataMem;
            private MemoryCR auxMem;
            private Candidate candidate;
            private MemoryCR timestampMem;

            @Override
            public double getDouble(int col) {
                return candidate.score;
            }

            @Override
            public long getRowId() {
                return Rows.toRowID(candidate.partitionIndex, candidate.rowId);
            }

            @Override
            public long getTimestamp(int col) {
                return timestampMem.getLong(candidate.rowId * Long.BYTES);
            }

            @Override
            public Utf8Sequence getVarcharA(int col) {
                return VarcharTypeDriver.getSplitValue(auxMem, dataMem, candidate.rowId - columnTop, 1);
            }

            @Override
            public Utf8Sequence getVarcharB(int col) {
                return VarcharTypeDriver.getSplitValue(auxMem, dataMem, candidate.rowId - columnTop, 2);
            }

            private void of(Candidate candidate) {
                this.candidate = candidate;
                reader.openPartition(candidate.partitionIndex);
                final int columnBase = reader.getColumnBase(candidate.partitionIndex);
                final int valuePrimary = TableReader.getPrimaryColumnIndex(columnBase, columnIndex);
                final int timestampPrimary = TableReader.getPrimaryColumnIndex(columnBase, timestampIndex);
                dataMem = reader.getColumn(valuePrimary);
                auxMem = reader.getColumn(valuePrimary + 1);
                timestampMem = reader.getColumn(timestampPrimary);
                columnTop = reader.getColumnTop(columnBase, columnIndex);
            }
        }
    }
}

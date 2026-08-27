/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2026 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo.idx;

import io.questdb.cairo.CairoException;
import io.questdb.cairo.ColumnType;
import io.questdb.cairo.TableReader;
import io.questdb.cairo.TableReaderMetadata;
import io.questdb.cairo.TableUtils;
import io.questdb.cairo.TextColumnIndexer;
import io.questdb.cairo.sql.PartitionFormat;
import io.questdb.cairo.vm.api.MemoryCR;
import io.questdb.std.FilesFacade;
import io.questdb.std.str.LPSZ;
import io.questdb.std.str.Path;

/**
 * Explicit post-load builder for experimental partition-local text indexes.
 * It deliberately does not register with TableWriter, WAL, O3, or table metadata.
 */
public final class OfflineTextIndexBuilder {
    public static final String FILE_SUFFIX = ".txi";
    private final TextColumnIndexer columnIndexer = new TextColumnIndexer();

    public BuildStats build(TableReader reader, int columnIndex) {
        final TableReaderMetadata metadata = reader.getMetadata();
        if (columnIndex < 0 || columnIndex >= metadata.getColumnCount()) {
            throw CairoException.nonCritical().put("invalid text index column [columnIndex=").put(columnIndex).put(']');
        }
        if (!ColumnType.isVarchar(metadata.getColumnType(columnIndex))) {
            throw CairoException.nonCritical()
                    .put("text index column must be VARCHAR [column=")
                    .put(metadata.getColumnName(columnIndex))
                    .put(']');
        }

        final FilesFacade ff = reader.getConfiguration().getFilesFacade();
        final BuildStats stats = new BuildStats();
        final int writerColumnIndex = metadata.getWriterIndex(columnIndex);
        try (Path path = new Path().of(reader.getConfiguration().getDbRoot()).concat(reader.getTableToken())) {
            final int tableRootLength = path.size();
            for (int partitionIndex = 0, n = reader.getPartitionCount(); partitionIndex < n; partitionIndex++) {
                if (reader.getPartitionFormatFromMetadata(partitionIndex) != PartitionFormat.NATIVE) {
                    stats.skippedPartitionCount++;
                    continue;
                }

                final long partitionSize = reader.openPartition(partitionIndex);
                final long partitionTimestamp = reader.getPartitionTimestampByIndex(partitionIndex);
                final long columnNameTxn = reader.getColumnVersionReader().getColumnNameTxn(
                        partitionTimestamp,
                        writerColumnIndex
                );
                final int columnBase = reader.getColumnBase(partitionIndex);
                final int primaryIndex = TableReader.getPrimaryColumnIndex(columnBase, columnIndex);
                final MemoryCR dataMem = reader.getColumn(primaryIndex);
                final MemoryCR auxMem = reader.getColumn(primaryIndex + 1);
                final long columnTop = reader.getColumnTop(columnBase, columnIndex);

                path.trimTo(tableRootLength);
                TableUtils.setPathForNativePartition(
                        path,
                        metadata.getTimestampType(),
                        metadata.getPartitionBy(),
                        partitionTimestamp,
                        reader.getTxFile().getPartitionNameTxn(partitionIndex)
                );
                final int partitionPathLength = path.size();
                textIndexFileName(path, metadata.getColumnName(columnIndex), columnNameTxn);
                buildPartition(
                        ff,
                        auxMem,
                        dataMem,
                        columnTop,
                        partitionSize,
                        path,
                        columnNameTxn,
                        partitionTimestamp,
                        stats
                );
                path.trimTo(partitionPathLength);
            }
        }
        return stats;
    }

    public BuildStats buildPartition(
            FilesFacade ff,
            MemoryCR auxMem,
            MemoryCR dataMem,
            long columnTop,
            long partitionSize,
            Path targetPath,
            long columnNameTxn,
            long partitionTimestamp
    ) {
        final BuildStats stats = new BuildStats();
        buildPartition(
                ff,
                auxMem,
                dataMem,
                columnTop,
                partitionSize,
                targetPath,
                columnNameTxn,
                partitionTimestamp,
                stats
        );
        return stats;
    }

    public static LPSZ textIndexFileName(Path path, CharSequence columnName, long columnNameTxn) {
        path.concat(columnName).putAscii(FILE_SUFFIX);
        if (columnNameTxn > TableUtils.COLUMN_NAME_TXN_NONE) {
            path.put('.').put(columnNameTxn);
        }
        return path.$();
    }

    private void buildPartition(
            FilesFacade ff,
            MemoryCR auxMem,
            MemoryCR dataMem,
            long columnTop,
            long partitionSize,
            Path targetPath,
            long columnNameTxn,
            long partitionTimestamp,
            BuildStats stats
    ) {
        if (partitionSize < 0 || columnTop < 0 || columnTop > partitionSize) {
            throw CairoException.nonCritical()
                    .put("invalid text index partition range [columnTop=")
                    .put(columnTop)
                    .put(", partitionSize=")
                    .put(partitionSize)
                    .put(']');
        }
        final long startNanos = System.nanoTime();
        try (PartitionTextIndexWriter writer = new PartitionTextIndexWriter(
                ff,
                targetPath,
                columnNameTxn,
                partitionTimestamp
        )) {
            columnIndexer.index(auxMem, dataMem, columnTop, 0, partitionSize, writer);
            writer.commit();
            stats.documentCount += writer.getDocumentCount();
            stats.termCount += writer.getTermCount();
            stats.totalDocumentLength += writer.getTotalDocumentLength();
        }
        stats.buildNanos += System.nanoTime() - startNanos;
        stats.indexBytes += ff.length(targetPath.$());
        stats.builtPartitionCount++;
    }

    public static final class BuildStats {
        private long buildNanos;
        private int builtPartitionCount;
        private long documentCount;
        private long indexBytes;
        private int skippedPartitionCount;
        private long termCount;
        private long totalDocumentLength;

        public long getBuildNanos() {
            return buildNanos;
        }

        public int getBuiltPartitionCount() {
            return builtPartitionCount;
        }

        public long getDocumentCount() {
            return documentCount;
        }

        public long getIndexBytes() {
            return indexBytes;
        }

        public int getSkippedPartitionCount() {
            return skippedPartitionCount;
        }

        public long getTermCount() {
            return termCount;
        }

        public long getTotalDocumentLength() {
            return totalDocumentLength;
        }
    }
}

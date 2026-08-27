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

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.CairoException;
import io.questdb.cairo.TableUtils;
import io.questdb.cairo.vm.Vm;
import io.questdb.cairo.vm.api.MemoryMARW;
import io.questdb.std.FilesFacade;
import io.questdb.std.IntList;
import io.questdb.std.LongList;
import io.questdb.std.MemoryTag;
import io.questdb.std.ObjList;
import io.questdb.std.Utf8SequenceObjHashMap;
import io.questdb.std.str.Path;
import io.questdb.std.str.Utf8Sequence;
import io.questdb.std.str.Utf8String;
import io.questdb.std.str.Utf8s;
import org.jetbrains.annotations.Nullable;

/**
 * Experimental, one-shot builder for one native partition and VARCHAR column instance.
 * The complete index is written to a temporary file and becomes visible by rename on commit.
 */
public final class PartitionTextIndexWriter implements TextIndexWriter {
    private final long columnNameTxn;
    private final LongList documentRowIds = new LongList();
    private final IntList documentLengths = new IntList();
    private final FilesFacade ff;
    private final MemoryMARW mem = Vm.getCMARWInstance();
    private final long partitionTimestamp;
    private final Path targetPath = new Path();
    private final Path tempPath = new Path();
    private final Utf8SequenceObjHashMap<TermAccumulator> terms = new Utf8SequenceObjHashMap<>();
    private final AsciiTextTokenizer tokenizer = new AsciiTextTokenizer();
    private boolean committed;
    private long currentRowId;
    private long lastRowId = -1;
    private long totalDocumentLength;

    public PartitionTextIndexWriter(
            FilesFacade ff,
            Path targetPath,
            long columnNameTxn,
            long partitionTimestamp
    ) {
        this.ff = ff;
        this.columnNameTxn = columnNameTxn;
        this.partitionTimestamp = partitionTimestamp;
        this.targetPath.of(targetPath);
        this.tempPath.of(targetPath).putAscii(".tmp");
    }

    @Override
    public void add(long rowId, @Nullable Utf8Sequence value) {
        if (committed) {
            throw CairoException.nonCritical().put("cannot add to committed text index");
        }
        if (rowId < 0 || rowId <= lastRowId) {
            throw CairoException.nonCritical()
                    .put("text index row IDs must be non-negative and strictly increasing [lastRowId=")
                    .put(lastRowId)
                    .put(", rowId=")
                    .put(rowId)
                    .put(']');
        }

        currentRowId = rowId;
        final int documentLength = tokenizer.tokenize(value, this::onToken);
        documentRowIds.add(rowId);
        documentLengths.add(documentLength);
        totalDocumentLength += documentLength;
        lastRowId = rowId;
    }

    @Override
    public void clear() {
        mem.close();
        ff.removeQuiet(tempPath.$());
        documentRowIds.clear();
        documentLengths.clear();
        terms.clear();
        committed = false;
        currentRowId = 0;
        lastRowId = -1;
        totalDocumentLength = 0;
    }

    @Override
    public void close() {
        mem.close();
        if (!committed) {
            ff.removeQuiet(tempPath.$());
        }
        targetPath.close();
        tempPath.close();
    }

    @Override
    public void commit() {
        if (committed) {
            return;
        }

        final ObjList<Utf8String> sortedTerms = terms.keys();
        sortedTerms.sort(Utf8s::compare);
        final long rowCount = documentRowIds.size();
        final int termCount = sortedTerms.size();
        try {
            final long documentsOffset = TextIndexFile.HEADER_SIZE;
            final long dictionaryOffset = Math.addExact(
                    documentsOffset,
                    Math.multiplyExact(rowCount, TextIndexFile.DOCUMENT_ENTRY_SIZE)
            );
            final long termDataOffset = Math.addExact(
                    dictionaryOffset,
                    Math.multiplyExact((long) termCount, TextIndexFile.DICTIONARY_ENTRY_SIZE)
            );

            long termBytes = 0;
            long postingCount = 0;
            for (int i = 0; i < termCount; i++) {
                final Utf8String term = sortedTerms.getQuick(i);
                termBytes = Math.addExact(termBytes, term.size());
                postingCount = Math.addExact(postingCount, terms.get(term).rowIds.size());
            }
            final long postingsOffset = Math.addExact(termDataOffset, termBytes);
            Math.addExact(postingsOffset, Math.multiplyExact(postingCount, TextIndexFile.POSTING_ENTRY_SIZE));

            ff.removeQuiet(tempPath.$());
            mem.of(ff, tempPath.$(), ff.getPageSize(), MemoryTag.MMAP_DEFAULT, CairoConfiguration.O_NONE);
            mem.truncate();
            writeHeader(documentsOffset, dictionaryOffset, termDataOffset, postingsOffset, rowCount, termCount);
            writeDocuments();
            writeDictionary(sortedTerms, termDataOffset, postingsOffset);
            writeTerms(sortedTerms);
            writePostings(sortedTerms);
            mem.sync(false);
            mem.close(true, Vm.TRUNCATE_TO_POINTER);
            TableUtils.renameOrFail(ff, tempPath.$(), targetPath.$());
            committed = true;
        } catch (ArithmeticException e) {
            throw CairoException.nonCritical().put("text index is too large");
        } catch (Throwable th) {
            mem.close();
            ff.removeQuiet(tempPath.$());
            throw th;
        }
    }

    public long getDocumentCount() {
        return documentRowIds.size();
    }

    public int getTermCount() {
        return terms.size();
    }

    public long getTotalDocumentLength() {
        return totalDocumentLength;
    }

    private void onToken(Utf8Sequence token) {
        final int index = terms.keyIndex(token);
        final TermAccumulator accumulator;
        if (index < 0) {
            accumulator = terms.valueAtQuick(index);
        } else {
            accumulator = new TermAccumulator();
            terms.putAt(index, token, accumulator);
        }
        accumulator.add(currentRowId);
    }

    private void writeDictionary(
            ObjList<Utf8String> sortedTerms,
            long termDataOffset,
            long postingsOffset
    ) {
        long nextTermOffset = termDataOffset;
        long nextPostingsOffset = postingsOffset;
        for (int i = 0, n = sortedTerms.size(); i < n; i++) {
            final Utf8String term = sortedTerms.getQuick(i);
            final TermAccumulator accumulator = terms.get(term);
            final int documentFrequency = accumulator.rowIds.size();
            mem.putLong(nextTermOffset);
            mem.putInt(term.size());
            mem.putInt(documentFrequency);
            mem.putLong(nextPostingsOffset);
            mem.putLong(documentFrequency);
            nextTermOffset += term.size();
            nextPostingsOffset += (long) documentFrequency * TextIndexFile.POSTING_ENTRY_SIZE;
        }
    }

    private void writeDocuments() {
        for (int i = 0, n = documentRowIds.size(); i < n; i++) {
            mem.putLong(documentRowIds.getQuick(i));
            mem.putInt(documentLengths.getQuick(i));
            mem.putInt(0);
        }
    }

    private void writeHeader(
            long documentsOffset,
            long dictionaryOffset,
            long termDataOffset,
            long postingsOffset,
            long rowCount,
            int termCount
    ) {
        mem.putLong(TextIndexFile.MAGIC);
        mem.putInt(TextIndexFile.FORMAT_VERSION);
        mem.putInt(TextIndexFile.COMMITTED);
        mem.putLong(columnNameTxn);
        mem.putLong(partitionTimestamp);
        mem.putLong(rowCount);
        mem.putLong(rowCount == 0 ? -1 : documentRowIds.getQuick(0));
        mem.putLong(rowCount == 0 ? -1 : documentRowIds.getQuick((int) rowCount - 1));
        mem.putLong(totalDocumentLength);
        mem.putInt(termCount);
        mem.putInt(0);
        mem.putLong(documentsOffset);
        mem.putLong(dictionaryOffset);
        mem.putLong(termDataOffset);
        mem.putLong(postingsOffset);
    }

    private void writePostings(ObjList<Utf8String> sortedTerms) {
        for (int i = 0, n = sortedTerms.size(); i < n; i++) {
            final TermAccumulator accumulator = terms.get(sortedTerms.getQuick(i));
            for (int p = 0, pn = accumulator.rowIds.size(); p < pn; p++) {
                mem.putLong(accumulator.rowIds.getQuick(p));
                mem.putInt(accumulator.termFrequencies.getQuick(p));
                mem.putInt(0);
            }
        }
    }

    private void writeTerms(ObjList<Utf8String> sortedTerms) {
        for (int i = 0, n = sortedTerms.size(); i < n; i++) {
            mem.putVarchar(sortedTerms.getQuick(i));
        }
    }

    private static final class TermAccumulator {
        private final LongList rowIds = new LongList();
        private final IntList termFrequencies = new IntList();

        private void add(long rowId) {
            final int size = rowIds.size();
            if (size > 0 && rowIds.getQuick(size - 1) == rowId) {
                termFrequencies.setQuick(size - 1, termFrequencies.getQuick(size - 1) + 1);
            } else {
                rowIds.add(rowId);
                termFrequencies.add(1);
            }
        }
    }
}

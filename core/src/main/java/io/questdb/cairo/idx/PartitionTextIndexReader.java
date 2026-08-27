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
import io.questdb.cairo.vm.Vm;
import io.questdb.cairo.vm.api.MemoryCMR;
import io.questdb.std.FilesFacade;
import io.questdb.std.MemoryTag;
import io.questdb.std.str.Path;
import io.questdb.std.str.Utf8Sequence;

import java.io.Closeable;

/** Read-only lookup and validation for the experimental partition-local text index. */
public final class PartitionTextIndexReader implements Closeable {
    private final long dictionaryOffset;
    private final long documentCount;
    private final long documentsOffset;
    private final long fileSize;
    private final MemoryCMR mem = Vm.getCMRInstance();
    private final PostingCursor postingCursor = new PostingCursor();
    private final int termCount;
    private final long totalDocumentLength;

    public PartitionTextIndexReader(
            FilesFacade ff,
            Path path,
            long expectedColumnNameTxn,
            long expectedPartitionTimestamp
    ) {
        fileSize = ff.length(path.$());
        if (fileSize < TextIndexFile.HEADER_SIZE) {
            throw invalid("file is missing or shorter than header");
        }
        try {
            mem.smallFile(ff, path.$(), MemoryTag.MMAP_DEFAULT);
            if (mem.getLong(TextIndexFile.HEADER_OFFSET_MAGIC) != TextIndexFile.MAGIC) {
                throw invalid("invalid magic");
            }
            if (mem.getInt(TextIndexFile.HEADER_OFFSET_VERSION) != TextIndexFile.FORMAT_VERSION) {
                throw invalid("unsupported format version");
            }
            if (mem.getInt(TextIndexFile.HEADER_OFFSET_STATE) != TextIndexFile.COMMITTED) {
                throw invalid("index is not committed");
            }
            if (mem.getLong(TextIndexFile.HEADER_OFFSET_COLUMN_NAME_TXN) != expectedColumnNameTxn) {
                throw invalid("column-name transaction mismatch");
            }
            if (mem.getLong(TextIndexFile.HEADER_OFFSET_PARTITION_TIMESTAMP) != expectedPartitionTimestamp) {
                throw invalid("partition timestamp mismatch");
            }

            documentCount = mem.getLong(TextIndexFile.HEADER_OFFSET_ROW_COUNT);
            totalDocumentLength = mem.getLong(TextIndexFile.HEADER_OFFSET_TOTAL_DOCUMENT_LENGTH);
            termCount = mem.getInt(TextIndexFile.HEADER_OFFSET_TERM_COUNT);
            documentsOffset = mem.getLong(TextIndexFile.HEADER_OFFSET_DOCUMENTS);
            dictionaryOffset = mem.getLong(TextIndexFile.HEADER_OFFSET_DICTIONARY);
            final long termDataOffset = mem.getLong(TextIndexFile.HEADER_OFFSET_TERM_DATA);
            final long postingsOffset = mem.getLong(TextIndexFile.HEADER_OFFSET_POSTINGS);
            validateLayout(termDataOffset, postingsOffset);
            validateDocuments();
            validateDictionary(termDataOffset, postingsOffset);
        } catch (Throwable th) {
            mem.close();
            throw th;
        }
    }

    @Override
    public void close() {
        mem.close();
    }

    public long getDocumentCount() {
        return documentCount;
    }

    public int getDocumentLength(long rowId) {
        long lo = 0;
        long hi = documentCount - 1;
        while (lo <= hi) {
            final long mid = (lo + hi) >>> 1;
            final long offset = documentsOffset + mid * TextIndexFile.DOCUMENT_ENTRY_SIZE;
            final long indexedRowId = mem.getLong(offset);
            if (indexedRowId < rowId) {
                lo = mid + 1;
            } else if (indexedRowId > rowId) {
                hi = mid - 1;
            } else {
                return mem.getInt(offset + Long.BYTES);
            }
        }
        return -1;
    }

    public PostingCursor getPostings(Utf8Sequence term) {
        long lo = 0;
        long hi = termCount - 1L;
        while (lo <= hi) {
            final long mid = (lo + hi) >>> 1;
            final long entryOffset = dictionaryOffset + mid * TextIndexFile.DICTIONARY_ENTRY_SIZE;
            final int cmp = compareTerm(entryOffset, term);
            if (cmp < 0) {
                lo = mid + 1;
            } else if (cmp > 0) {
                hi = mid - 1;
            } else {
                return postingCursor.of(
                        mem.getLong(entryOffset + TextIndexFile.DICTIONARY_OFFSET_POSTINGS),
                        mem.getInt(entryOffset + TextIndexFile.DICTIONARY_OFFSET_DOCUMENT_FREQUENCY)
                );
            }
        }
        return postingCursor.of(0, 0);
    }

    public int getTermCount() {
        return termCount;
    }

    public long getTotalDocumentLength() {
        return totalDocumentLength;
    }

    private int compareTerm(long dictionaryEntryOffset, Utf8Sequence term) {
        final long termOffset = mem.getLong(dictionaryEntryOffset + TextIndexFile.DICTIONARY_OFFSET_TERM);
        final int storedLength = mem.getInt(dictionaryEntryOffset + TextIndexFile.DICTIONARY_OFFSET_TERM_LENGTH);
        final int limit = Math.min(storedLength, term.size());
        for (int i = 0; i < limit; i++) {
            final int cmp = Byte.toUnsignedInt(mem.getByte(termOffset + i)) - Byte.toUnsignedInt(term.byteAt(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(storedLength, term.size());
    }

    private CairoException invalid(CharSequence reason) {
        return CairoException.nonCritical().put("invalid text index [reason=").put(reason).put(']');
    }

    private void validateDictionary(long termDataOffset, long postingsOffset) {
        long previousEntryOffset = -1;
        for (int i = 0; i < termCount; i++) {
            final long entryOffset = dictionaryOffset + (long) i * TextIndexFile.DICTIONARY_ENTRY_SIZE;
            final long termOffset = mem.getLong(entryOffset + TextIndexFile.DICTIONARY_OFFSET_TERM);
            final int termLength = mem.getInt(entryOffset + TextIndexFile.DICTIONARY_OFFSET_TERM_LENGTH);
            final int documentFrequency = mem.getInt(entryOffset + TextIndexFile.DICTIONARY_OFFSET_DOCUMENT_FREQUENCY);
            final long entryPostingsOffset = mem.getLong(entryOffset + TextIndexFile.DICTIONARY_OFFSET_POSTINGS);
            final long postingCount = mem.getLong(entryOffset + TextIndexFile.DICTIONARY_OFFSET_POSTING_COUNT);
            if (termLength < 1 || documentFrequency < 1 || postingCount != documentFrequency
                    || !within(termOffset, termLength, postingsOffset) || termOffset < termDataOffset
                    || !within(entryPostingsOffset, postingCount * TextIndexFile.POSTING_ENTRY_SIZE, fileSize)
                    || entryPostingsOffset < postingsOffset) {
                throw invalid("invalid dictionary entry");
            }
            if (previousEntryOffset > -1 && compareStoredTerms(previousEntryOffset, entryOffset) >= 0) {
                throw invalid("terms are not strictly ordered");
            }
            long previousRowId = -1;
            for (long p = 0; p < postingCount; p++) {
                final long offset = entryPostingsOffset + p * TextIndexFile.POSTING_ENTRY_SIZE;
                final long rowId = mem.getLong(offset);
                final int termFrequency = mem.getInt(offset + Long.BYTES);
                if (rowId < 0 || rowId <= previousRowId || termFrequency < 1) {
                    throw invalid("invalid posting");
                }
                previousRowId = rowId;
            }
            previousEntryOffset = entryOffset;
        }
    }

    private int compareStoredTerms(long leftEntryOffset, long rightEntryOffset) {
        final long leftOffset = mem.getLong(leftEntryOffset + TextIndexFile.DICTIONARY_OFFSET_TERM);
        final int leftLength = mem.getInt(leftEntryOffset + TextIndexFile.DICTIONARY_OFFSET_TERM_LENGTH);
        final long rightOffset = mem.getLong(rightEntryOffset + TextIndexFile.DICTIONARY_OFFSET_TERM);
        final int rightLength = mem.getInt(rightEntryOffset + TextIndexFile.DICTIONARY_OFFSET_TERM_LENGTH);
        final int limit = Math.min(leftLength, rightLength);
        for (int i = 0; i < limit; i++) {
            final int cmp = Byte.toUnsignedInt(mem.getByte(leftOffset + i))
                    - Byte.toUnsignedInt(mem.getByte(rightOffset + i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(leftLength, rightLength);
    }

    private void validateDocuments() {
        long previousRowId = -1;
        long calculatedTotalLength = 0;
        for (long i = 0; i < documentCount; i++) {
            final long offset = documentsOffset + i * TextIndexFile.DOCUMENT_ENTRY_SIZE;
            final long rowId = mem.getLong(offset);
            final int documentLength = mem.getInt(offset + Long.BYTES);
            if (rowId < 0 || rowId <= previousRowId || documentLength < 0) {
                throw invalid("invalid document entry");
            }
            calculatedTotalLength += documentLength;
            if (calculatedTotalLength < 0) {
                throw invalid("document length overflow");
            }
            previousRowId = rowId;
        }
        if (calculatedTotalLength != totalDocumentLength) {
            throw invalid("total document length mismatch");
        }
        final long minRowId = mem.getLong(TextIndexFile.HEADER_OFFSET_MIN_ROW_ID);
        final long maxRowId = mem.getLong(TextIndexFile.HEADER_OFFSET_MAX_ROW_ID);
        if (documentCount == 0) {
            if (minRowId != -1 || maxRowId != -1) {
                throw invalid("invalid empty row range");
            }
        } else if (minRowId != mem.getLong(documentsOffset)
                || maxRowId != mem.getLong(documentsOffset + (documentCount - 1) * TextIndexFile.DOCUMENT_ENTRY_SIZE)) {
            throw invalid("row range mismatch");
        }
    }

    private void validateLayout(long termDataOffset, long postingsOffset) {
        if (documentCount < 0 || totalDocumentLength < 0 || termCount < 0
                || documentsOffset != TextIndexFile.HEADER_SIZE
                || !within(documentsOffset, documentCount * TextIndexFile.DOCUMENT_ENTRY_SIZE, fileSize)
                || dictionaryOffset != documentsOffset + documentCount * TextIndexFile.DOCUMENT_ENTRY_SIZE
                || !within(dictionaryOffset, (long) termCount * TextIndexFile.DICTIONARY_ENTRY_SIZE, fileSize)
                || termDataOffset != dictionaryOffset + (long) termCount * TextIndexFile.DICTIONARY_ENTRY_SIZE
                || postingsOffset < termDataOffset || postingsOffset > fileSize) {
            throw invalid("invalid file layout");
        }
    }

    private static boolean within(long offset, long length, long limit) {
        return offset >= 0 && length >= 0 && offset <= limit && length <= limit - offset;
    }

    public final class PostingCursor {
        private int count;
        private int index;
        private long offset;

        public int getDocumentFrequency() {
            return count;
        }

        public long getRowId() {
            ensurePositioned();
            return mem.getLong(offset + (long) (index - 1) * TextIndexFile.POSTING_ENTRY_SIZE);
        }

        public int getTermFrequency() {
            ensurePositioned();
            return mem.getInt(offset + (long) (index - 1) * TextIndexFile.POSTING_ENTRY_SIZE + Long.BYTES);
        }

        public boolean hasNext() {
            return index < count;
        }

        public void next() {
            if (index >= count) {
                throw CairoException.nonCritical().put("text posting cursor is exhausted");
            }
            index++;
        }

        private void ensurePositioned() {
            if (index < 1 || index > count) {
                throw CairoException.nonCritical().put("text posting cursor is not positioned");
            }
        }

        private PostingCursor of(long offset, int count) {
            this.offset = offset;
            this.count = count;
            this.index = 0;
            return this;
        }
    }
}

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

final class TextIndexFile {
    static final int COMMITTED = 1;
    static final int DICTIONARY_ENTRY_SIZE = 32;
    static final int DOCUMENT_ENTRY_SIZE = 16;
    static final int FORMAT_VERSION = 1;
    static final int HEADER_SIZE = 104;
    static final long MAGIC = 0x5844495458544451L; // QDTXTIDX in little-endian storage.
    static final int POSTING_ENTRY_SIZE = 16;

    static final int HEADER_OFFSET_MAGIC = 0;
    static final int HEADER_OFFSET_VERSION = 8;
    static final int HEADER_OFFSET_STATE = 12;
    static final int HEADER_OFFSET_COLUMN_NAME_TXN = 16;
    static final int HEADER_OFFSET_PARTITION_TIMESTAMP = 24;
    static final int HEADER_OFFSET_ROW_COUNT = 32;
    static final int HEADER_OFFSET_MIN_ROW_ID = 40;
    static final int HEADER_OFFSET_MAX_ROW_ID = 48;
    static final int HEADER_OFFSET_TOTAL_DOCUMENT_LENGTH = 56;
    static final int HEADER_OFFSET_TERM_COUNT = 64;
    static final int HEADER_OFFSET_DOCUMENTS = 72;
    static final int HEADER_OFFSET_DICTIONARY = 80;
    static final int HEADER_OFFSET_TERM_DATA = 88;
    static final int HEADER_OFFSET_POSTINGS = 96;

    static final int DICTIONARY_OFFSET_TERM = 0;
    static final int DICTIONARY_OFFSET_TERM_LENGTH = 8;
    static final int DICTIONARY_OFFSET_DOCUMENT_FREQUENCY = 12;
    static final int DICTIONARY_OFFSET_POSTINGS = 16;
    static final int DICTIONARY_OFFSET_POSTING_COUNT = 24;

    private TextIndexFile() {
    }
}

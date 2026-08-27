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
import io.questdb.std.str.Utf8Sequence;
import org.jetbrains.annotations.Nullable;

/** Validation-only test double for the append-only {@link TextIndexWriter} contract. */
public class ValidatingTextIndexWriter implements TextIndexWriter {
    private long committedRowCount;
    private long lastRowId = -1;
    private long rowCount;

    @Override
    public void add(long rowId, @Nullable Utf8Sequence value) {
        if (rowId <= lastRowId) {
            throw CairoException.nonCritical()
                    .put("text index row IDs must be strictly increasing [lastRowId=")
                    .put(lastRowId)
                    .put(", rowId=")
                    .put(rowId)
                    .put(']');
        }
        lastRowId = rowId;
        rowCount++;
    }

    @Override
    public void clear() {
        committedRowCount = 0;
        lastRowId = -1;
        rowCount = 0;
    }

    @Override
    public void close() {
        clear();
    }

    @Override
    public void commit() {
        committedRowCount = rowCount;
    }

    public long getCommittedRowCount() {
        return committedRowCount;
    }

    public long getLastRowId() {
        return lastRowId;
    }

    public long getRowCount() {
        return rowCount;
    }
}

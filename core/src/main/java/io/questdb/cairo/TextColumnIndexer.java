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

package io.questdb.cairo;

import io.questdb.cairo.idx.TextIndexWriter;
import io.questdb.cairo.vm.api.MemoryCR;

/**
 * Feeds a committed range of one native VARCHAR column to a text-index writer.
 *
 * <p>This is an offline append-only PoC seam. It deliberately has no TableWriter,
 * WAL, O3, metadata, or SQL registration.</p>
 */
public final class TextColumnIndexer {

    public void index(
            MemoryCR auxMem,
            MemoryCR dataMem,
            long columnTop,
            long loRow,
            long hiRow,
            TextIndexWriter writer
    ) {
        if (columnTop < 0 || loRow < 0 || hiRow < loRow) {
            throw CairoException.nonCritical()
                    .put("invalid text index row range [columnTop=")
                    .put(columnTop)
                    .put(", loRow=")
                    .put(loRow)
                    .put(", hiRow=")
                    .put(hiRow)
                    .put(']');
        }

        final long lo = Math.max(columnTop, loRow);
        for (long rowId = lo; rowId < hiRow; rowId++) {
            writer.add(rowId, VarcharTypeDriver.getSplitValue(auxMem, dataMem, rowId - columnTop, 1));
        }
    }
}

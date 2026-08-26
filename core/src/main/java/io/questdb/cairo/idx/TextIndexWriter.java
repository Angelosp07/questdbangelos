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

import io.questdb.std.Mutable;
import io.questdb.std.str.Utf8Sequence;
import org.jetbrains.annotations.Nullable;

import java.io.Closeable;

/**
 * Append-only boundary between a VARCHAR column scan and a future text index.
 *
 * <p>The writer is scoped to one native partition and column instance. Row IDs
 * are partition-local and must be supplied in strictly increasing order. The
 * value is a flyweight owned by the caller and must not be retained.</p>
 *
 * <p>This interface is intentionally separate from {@link IndexWriter}: the
 * existing index API accepts an integer SYMBOL key, while text indexing first
 * needs to turn a VARCHAR document into multiple terms and payloads.</p>
 */
public interface TextIndexWriter extends Closeable, Mutable {

    void add(long rowId, @Nullable Utf8Sequence value);

    void commit();
}

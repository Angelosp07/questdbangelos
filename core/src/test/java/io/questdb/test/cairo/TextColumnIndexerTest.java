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

package io.questdb.test.cairo;

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.CairoException;
import io.questdb.cairo.TextColumnIndexer;
import io.questdb.cairo.VarcharTypeDriver;
import io.questdb.cairo.idx.TextIndexWriter;
import io.questdb.cairo.idx.ValidatingTextIndexWriter;
import io.questdb.cairo.vm.Vm;
import io.questdb.cairo.vm.api.MemoryCMARW;
import io.questdb.std.LongList;
import io.questdb.std.MemoryTag;
import io.questdb.std.ObjList;
import io.questdb.std.str.Path;
import io.questdb.std.str.Utf8Sequence;
import io.questdb.std.str.Utf8String;
import io.questdb.std.str.Utf8s;
import io.questdb.test.AbstractTest;
import io.questdb.test.std.TestFilesFacadeImpl;
import io.questdb.test.tools.TestUtils;
import org.jetbrains.annotations.Nullable;
import org.junit.Assert;
import org.junit.Test;

public class TextColumnIndexerTest extends AbstractTest {

    @Test
    public void testNoOpWriterValidatesAppendOnlyContract() {
        try (ValidatingTextIndexWriter writer = new ValidatingTextIndexWriter()) {
            writer.add(2, new Utf8String("first"));
            writer.add(5, null);
            Assert.assertEquals(2, writer.getRowCount());
            Assert.assertEquals(5, writer.getLastRowId());
            Assert.assertEquals(0, writer.getCommittedRowCount());

            writer.commit();
            Assert.assertEquals(2, writer.getCommittedRowCount());

            try {
                writer.add(5, new Utf8String("duplicate"));
                Assert.fail("expected append-order validation failure");
            } catch (CairoException e) {
                TestUtils.assertContains(e.getFlyweightMessage(), "text index row IDs must be strictly increasing");
            }
        }
    }

    @Test
    public void testReadsCommittedVarcharRangeWithPartitionRowIds() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (
                    Path auxPath = new Path().of(temp.newFile().getAbsolutePath());
                    Path dataPath = new Path().of(temp.newFile().getAbsolutePath());
                    MemoryCMARW auxMem = Vm.getSmallCMARWInstance(
                            TestFilesFacadeImpl.INSTANCE,
                            auxPath.$(),
                            MemoryTag.MMAP_DEFAULT,
                            CairoConfiguration.O_NONE
                    );
                    MemoryCMARW dataMem = Vm.getSmallCMARWInstance(
                            TestFilesFacadeImpl.INSTANCE,
                            dataPath.$(),
                            MemoryTag.MMAP_DEFAULT,
                            CairoConfiguration.O_NONE
                    );
                    RecordingTextIndexWriter writer = new RecordingTextIndexWriter()
            ) {
                VarcharTypeDriver.appendValue(auxMem, dataMem, new Utf8String("inline"));
                VarcharTypeDriver.appendValue(auxMem, dataMem, new Utf8String("a split VARCHAR value"));
                VarcharTypeDriver.appendValue(auxMem, dataMem, null);
                VarcharTypeDriver.appendValue(auxMem, dataMem, new Utf8String("last"));

                new TextColumnIndexer().index(auxMem, dataMem, 7, 6, 11, writer);

                Assert.assertEquals(4, writer.rowIds.size());
                Assert.assertEquals(7, writer.rowIds.getQuick(0));
                Assert.assertEquals(8, writer.rowIds.getQuick(1));
                Assert.assertEquals(9, writer.rowIds.getQuick(2));
                Assert.assertEquals(10, writer.rowIds.getQuick(3));
                Assert.assertEquals("inline", writer.values.getQuick(0));
                Assert.assertEquals("a split VARCHAR value", writer.values.getQuick(1));
                Assert.assertNull(writer.values.getQuick(2));
                Assert.assertEquals("last", writer.values.getQuick(3));
            }
        });
    }

    private static class RecordingTextIndexWriter implements TextIndexWriter {
        private final LongList rowIds = new LongList();
        private final ObjList<String> values = new ObjList<>();

        @Override
        public void add(long rowId, @Nullable Utf8Sequence value) {
            rowIds.add(rowId);
            values.add(value == null ? null : Utf8s.stringFromUtf8Bytes(value));
        }

        @Override
        public void clear() {
            rowIds.clear();
            values.clear();
        }

        @Override
        public void close() {
            clear();
        }

        @Override
        public void commit() {
        }
    }
}

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
import io.questdb.cairo.VarcharTypeDriver;
import io.questdb.cairo.idx.OfflineTextIndexBuilder;
import io.questdb.cairo.idx.PartitionTextIndexReader;
import io.questdb.cairo.idx.PartitionTextIndexSearcher;
import io.questdb.cairo.idx.PartitionTextIndexWriter;
import io.questdb.cairo.vm.Vm;
import io.questdb.cairo.vm.api.MemoryCMARW;
import io.questdb.std.MemoryTag;
import io.questdb.std.ObjList;
import io.questdb.std.str.Path;
import io.questdb.std.str.Utf8String;
import io.questdb.test.std.TestFilesFacadeImpl;
import io.questdb.test.tools.TestUtils;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class PartitionTextIndexTest {
    private static final long COLUMN_NAME_TXN = 17;
    private static final long PARTITION_TIMESTAMP = 42_000_000L;
    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void testEmptyIndexRoundTrip() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (Path path = newIndexPath()) {
                try (PartitionTextIndexWriter writer = new PartitionTextIndexWriter(
                        TestFilesFacadeImpl.INSTANCE,
                        path,
                        COLUMN_NAME_TXN,
                        PARTITION_TIMESTAMP
                )) {
                    writer.commit();
                }

                try (PartitionTextIndexReader reader = new PartitionTextIndexReader(
                        TestFilesFacadeImpl.INSTANCE,
                        path,
                        COLUMN_NAME_TXN,
                        PARTITION_TIMESTAMP
                )) {
                    Assert.assertEquals(0, reader.getDocumentCount());
                    Assert.assertEquals(0, reader.getTermCount());
                    Assert.assertEquals(0, reader.getTotalDocumentLength());
                    Assert.assertEquals(-1, reader.getDocumentLength(0));
                    Assert.assertEquals(0, reader.getPostings(new Utf8String("missing")).getDocumentFrequency());
                }
            }
        });
    }

    @Test
    public void testOfflineBuilderWritesVarcharColumnToPersistentPostings() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (
                    Path auxPath = new Path().of(temp.newFile().getAbsolutePath());
                    Path dataPath = new Path().of(temp.newFile().getAbsolutePath());
                    Path indexPath = newIndexPath();
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
                    )
            ) {
                VarcharTypeDriver.appendValue(auxMem, dataMem, new Utf8String("Timeout timeout disk"));
                VarcharTypeDriver.appendValue(auxMem, dataMem, null);
                VarcharTypeDriver.appendValue(auxMem, dataMem, new Utf8String("panic42 timeout"));

                OfflineTextIndexBuilder.BuildStats stats = new OfflineTextIndexBuilder().buildPartition(
                        TestFilesFacadeImpl.INSTANCE,
                        auxMem,
                        dataMem,
                        4,
                        7,
                        indexPath,
                        COLUMN_NAME_TXN,
                        PARTITION_TIMESTAMP
                );

                Assert.assertEquals(1, stats.getBuiltPartitionCount());
                Assert.assertEquals(3, stats.getDocumentCount());
                Assert.assertEquals(3, stats.getTermCount());
                Assert.assertEquals(5, stats.getTotalDocumentLength());
                Assert.assertTrue(stats.getBuildNanos() > 0);
                Assert.assertTrue(stats.getIndexBytes() > 0);

                try (PartitionTextIndexReader reader = new PartitionTextIndexReader(
                        TestFilesFacadeImpl.INSTANCE,
                        indexPath,
                        COLUMN_NAME_TXN,
                        PARTITION_TIMESTAMP
                )) {
                    Assert.assertEquals(3, reader.getDocumentLength(4));
                    Assert.assertEquals(0, reader.getDocumentLength(5));
                    Assert.assertEquals(2, reader.getDocumentLength(6));
                    PartitionTextIndexReader.PostingCursor cursor = reader.getPostings(new Utf8String("timeout"));
                    Assert.assertEquals(2, cursor.getDocumentFrequency());
                    cursor.next();
                    Assert.assertEquals(4, cursor.getRowId());
                    Assert.assertEquals(2, cursor.getTermFrequency());
                    cursor.next();
                    Assert.assertEquals(6, cursor.getRowId());
                    Assert.assertEquals(1, cursor.getTermFrequency());
                }
            }
        });
    }

    @Test
    public void testRejectsCorruptAndStaleIndex() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (Path path = newIndexPath()) {
                writeIndex(path);

                assertReaderFails(path, COLUMN_NAME_TXN + 1, PARTITION_TIMESTAMP, "column-name transaction mismatch");
                assertReaderFails(path, COLUMN_NAME_TXN, PARTITION_TIMESTAMP + 1, "partition timestamp mismatch");

                try (MemoryCMARW mem = Vm.getSmallCMARWInstance(
                        TestFilesFacadeImpl.INSTANCE,
                        path.$(),
                        MemoryTag.MMAP_DEFAULT,
                        CairoConfiguration.O_NONE
                )) {
                    final long fileSize = TestFilesFacadeImpl.INSTANCE.length(path.$());
                    mem.putInt(8, 999);
                    mem.jumpTo(fileSize);
                    mem.sync(false);
                }
                assertReaderFails(path, COLUMN_NAME_TXN, PARTITION_TIMESTAMP, "unsupported format version");
            }
        });
    }

    @Test
    public void testSearcherRanksBm25FromPersistentPostings() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (Path path = newIndexPath()) {
                writeIndex(path);
                try (PartitionTextIndexReader reader = new PartitionTextIndexReader(
                        TestFilesFacadeImpl.INSTANCE,
                        path,
                        COLUMN_NAME_TXN,
                        PARTITION_TIMESTAMP
                )) {
                    PartitionTextIndexSearcher searcher = new PartitionTextIndexSearcher();
                    ObjList<PartitionTextIndexSearcher.SearchResult> results = searcher.search(
                            reader,
                            new Utf8String("TIMEOUT panic42 timeout"),
                            10
                    );
                    Assert.assertEquals(2, results.size());
                    Assert.assertEquals(10, results.getQuick(0).getRowId());
                    Assert.assertEquals(3, results.getQuick(1).getRowId());

                    // Independent BM25 oracle: N=3, average dl=2, timeout df=2, panic42 df=1.
                    final double timeoutIdf = Math.log(1.0 + 1.5 / 2.5);
                    final double panicIdf = Math.log(1.0 + 2.5 / 1.5);
                    final double row3 = timeoutIdf * 2.0 * 2.2 / (2.0 + 1.2 * 1.375);
                    final double row10 = timeoutIdf * 2.2 / (1.0 + 1.2 * 1.375)
                            + panicIdf * 2.0 * 2.2 / (2.0 + 1.2 * 1.375);
                    Assert.assertEquals(row10, results.getQuick(0).getScore(), 0.000000001);
                    Assert.assertEquals(row3, results.getQuick(1).getScore(), 0.000000001);

                    results = searcher.search(reader, new Utf8String("missing"), 10);
                    Assert.assertEquals(0, results.size());
                    results = searcher.search(reader, new Utf8String("timeout"), 1);
                    Assert.assertEquals(1, results.size());
                    Assert.assertEquals(3, results.getQuick(0).getRowId());
                }
            }
        });
    }

    @Test
    public void testRoundTripTermsPostingsAndStatistics() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (Path path = newIndexPath()) {
                writeIndex(path);

                try (PartitionTextIndexReader reader = new PartitionTextIndexReader(
                        TestFilesFacadeImpl.INSTANCE,
                        path,
                        COLUMN_NAME_TXN,
                        PARTITION_TIMESTAMP
                )) {
                    Assert.assertEquals(3, reader.getDocumentCount());
                    Assert.assertEquals(3, reader.getTermCount());
                    Assert.assertEquals(6, reader.getTotalDocumentLength());
                    Assert.assertEquals(3, reader.getDocumentLength(3));
                    Assert.assertEquals(0, reader.getDocumentLength(8));
                    Assert.assertEquals(3, reader.getDocumentLength(10));
                    Assert.assertEquals(-1, reader.getDocumentLength(9));

                    PartitionTextIndexReader.PostingCursor cursor = reader.getPostings(new Utf8String("timeout"));
                    Assert.assertEquals(2, cursor.getDocumentFrequency());
                    Assert.assertTrue(cursor.hasNext());
                    cursor.next();
                    Assert.assertEquals(3, cursor.getRowId());
                    Assert.assertEquals(2, cursor.getTermFrequency());
                    Assert.assertTrue(cursor.hasNext());
                    cursor.next();
                    Assert.assertEquals(10, cursor.getRowId());
                    Assert.assertEquals(1, cursor.getTermFrequency());
                    Assert.assertFalse(cursor.hasNext());

                    cursor = reader.getPostings(new Utf8String("panic42"));
                    Assert.assertEquals(1, cursor.getDocumentFrequency());
                    cursor.next();
                    Assert.assertEquals(10, cursor.getRowId());
                    Assert.assertEquals(2, cursor.getTermFrequency());

                    cursor = reader.getPostings(new Utf8String("disk"));
                    Assert.assertEquals(1, cursor.getDocumentFrequency());
                    cursor.next();
                    Assert.assertEquals(3, cursor.getRowId());
                    Assert.assertEquals(1, cursor.getTermFrequency());

                    Assert.assertEquals(0, reader.getPostings(new Utf8String("missing")).getDocumentFrequency());
                }
            }
        });
    }

    @Test
    public void testWriterRejectsInvalidAppendOrderAndPostCommitAdds() throws Exception {
        TestUtils.assertMemoryLeak(() -> {
            try (
                    Path path = newIndexPath();
                    PartitionTextIndexWriter writer = new PartitionTextIndexWriter(
                            TestFilesFacadeImpl.INSTANCE,
                            path,
                            COLUMN_NAME_TXN,
                            PARTITION_TIMESTAMP
                    )
            ) {
                writer.add(1, new Utf8String("first"));
                try {
                    writer.add(1, new Utf8String("duplicate"));
                    Assert.fail("expected append-order failure");
                } catch (CairoException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "strictly increasing");
                }

                writer.commit();
                try {
                    writer.add(2, new Utf8String("after commit"));
                    Assert.fail("expected committed writer failure");
                } catch (CairoException e) {
                    TestUtils.assertContains(e.getFlyweightMessage(), "cannot add to committed text index");
                }
            }
        });
    }

    private static void assertReaderFails(
            Path path,
            long columnNameTxn,
            long partitionTimestamp,
            CharSequence expectedMessage
    ) {
        try (PartitionTextIndexReader ignored = new PartitionTextIndexReader(
                TestFilesFacadeImpl.INSTANCE,
                path,
                columnNameTxn,
                partitionTimestamp
        )) {
            Assert.fail("expected reader validation failure");
        } catch (CairoException e) {
            TestUtils.assertContains(e.getFlyweightMessage(), expectedMessage);
        }
    }

    private Path newIndexPath() throws Exception {
        return new Path().of(temp.newFolder().getAbsolutePath()).concat("message.txtidx");
    }

    private static void writeIndex(Path path) {
        try (PartitionTextIndexWriter writer = new PartitionTextIndexWriter(
                TestFilesFacadeImpl.INSTANCE,
                path,
                COLUMN_NAME_TXN,
                PARTITION_TIMESTAMP
        )) {
            writer.add(3, new Utf8String("Timeout timeout disk"));
            writer.add(8, null);
            writer.add(10, new Utf8String("panic42 timeout panic42"));
            Assert.assertEquals(3, writer.getDocumentCount());
            Assert.assertEquals(3, writer.getTermCount());
            Assert.assertEquals(6, writer.getTotalDocumentLength());
            writer.commit();
        }
    }
}

/*+*****************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|_| |_|____/
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

package io.questdb.test.griffin.engine.functions.text;

import io.questdb.cairo.TableReader;
import io.questdb.cairo.idx.OfflineTextIndexBuilder;
import io.questdb.test.AbstractCairoTest;
import org.junit.Test;

public class TextSearchFunctionFactoryTest extends AbstractCairoTest {

    @Test
    public void testRejectsInvalidSources() throws Exception {
        assertMemoryLeak(() -> {
            assertQuery("select * from text_search('missing', 'message', 'timeout', '2026-08-24', '2026-08-26', 10)")
                    .fails(26, "table does not exist [table=missing]");

            execute("create table no_ts (message varchar)");
            assertQuery("select * from text_search('no_ts', 'message', 'timeout', '2026-08-24', '2026-08-26', 10)")
                    .fails(26, "table must have a designated timestamp [table=no_ts]");

            execute("create table logs (ts timestamp, message varchar, code int) timestamp(ts)");
            assertQuery("select * from text_search('logs', 'missing', 'timeout', '2026-08-24', '2026-08-26', 10)")
                    .fails(34, "column does not exist [column=missing]");
            assertQuery("select * from text_search('logs', 'code', 'timeout', '2026-08-24', '2026-08-26', 10)")
                    .fails(34, "column must be VARCHAR [column=code, type=INT]");
            assertQuery("select * from text_search('logs', 'message', 'timeout', '2026-08-24', '2026-08-26', 0)")
                    .fails(84, "limit must be positive");
        });
    }

    @Test
    public void testReturnsBoundedScanMatchesWithPlaceholderScore() throws Exception {
        assertMemoryLeak(() -> {
            execute("create table logs (id long, ts timestamp, message varchar) timestamp(ts) partition by day");
            execute("insert into logs values (1, '2026-08-23T12:00:00Z', 'timeout before range')");
            execute("insert into logs values (2, '2026-08-24T12:00:00Z', 'request timeout')");
            execute("insert into logs values (3, '2026-08-25T12:00:00Z', 'network outage')");
            execute("insert into logs values (4, '2026-08-25T13:00:00Z', 'another timeout')");
            execute("insert into logs values (5, '2026-08-26T00:00:00Z', 'timeout at exclusive edge')");

            assertQuery("select * from text_search('logs', 'message', 'timeout', '2026-08-24', '2026-08-26', 10)")
                    .timestamp("ts")
                    .returns(
                            "ts\tvalue\tscore\n" +
                                    "2026-08-24T12:00:00.000000Z\trequest timeout\t1.0\n" +
                                    "2026-08-25T13:00:00.000000Z\tanother timeout\t1.0\n"
                    );
            assertQuery("select * from text_search('logs', 'message', 'timeout', '2026-08-24', '2026-08-26', 1)")
                    .timestamp("ts")
                    .returns("ts\tvalue\tscore\n2026-08-24T12:00:00.000000Z\trequest timeout\t1.0\n");
            assertQuery("select * from text_search('logs', 'message', null, '2026-08-24', '2026-08-26', 10)")
                    .timestamp("ts")
                    .returns("ts\tvalue\tscore\n");
        });
    }

    @Test
    public void testQuotesIdentifiersAndQueryText() throws Exception {
        assertMemoryLeak(() -> {
            execute("create table \"select\" (ts timestamp, \"from\" varchar) timestamp(ts)");
            execute("insert into \"select\" values ('2026-08-25T12:00:00Z', 'can''t connect')");

            assertQuery("select * from text_search('select', 'from', 'can''t', '2026-08-25', '2026-08-26', 10)")
                    .timestamp("ts")
                    .returns("ts\tvalue\tscore\n2026-08-25T12:00:00.000000Z\tcan't connect\t1.0\n");
        });
    }

    @Test
    public void testPlanMakesFallbackExplicit() throws Exception {
        assertMemoryLeak(() -> {
            execute("create table logs (ts timestamp, message varchar) timestamp(ts) partition by day");

            assertQuery("select * from text_search('logs', 'message', 'timeout', '2026-08-24', '2026-08-26', 10)")
                    .assertsPlanContaining(
                            "text_search partition index with scan fallback",
                            "text_match(message",
                            "Interval forward scan on: logs"
                    );
        });
    }

    @Test
    public void testUsesPersistentPartitionIndexesForRankedTopK() throws Exception {
        assertMemoryLeak(() -> {
            execute("create table logs (ts timestamp, message varchar) timestamp(ts) partition by day");
            execute("insert into logs values ('2026-08-24T12:00:00Z', 'request timeout')");
            execute("insert into logs values ('2026-08-25T12:00:00Z', 'network outage')");
            execute("insert into logs values ('2026-08-25T13:00:00Z', 'another timeout')");

            try (TableReader reader = engine.getReader("logs")) {
                final int columnIndex = reader.getMetadata().getColumnIndexQuiet("message");
                OfflineTextIndexBuilder.BuildStats stats = new OfflineTextIndexBuilder().build(reader, columnIndex);
                org.junit.Assert.assertEquals(2, stats.getBuiltPartitionCount());
                org.junit.Assert.assertEquals(3, stats.getDocumentCount());
            }

            assertQuery("select * from text_search('logs', 'message', 'timeout', '2026-08-24', '2026-08-26', 10)")
                    .timestamp("ts")
                    .returns(
                            "ts\tvalue\tscore\n" +
                                    "2026-08-25T13:00:00.000000Z\tanother timeout\t0.6931471805599453\n" +
                                    "2026-08-24T12:00:00.000000Z\trequest timeout\t0.28768207245178085\n"
                    );
        });
    }
}

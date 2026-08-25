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

import io.questdb.test.AbstractCairoTest;
import org.junit.Test;

public class TextMatchVarcharFunctionFactoryTest extends AbstractCairoTest {

    @Test
    public void testConstantQueryScansVarcharRows() throws Exception {
        assertMemoryLeak(() -> {
            execute("create table docs (body varchar)");
            execute("insert into docs values ('disk failure'), ('Disk failure'), ('network outage'), (null)");

            assertQuery("select body from docs where text_match(body, 'failure')")
                    .returns("body\ndisk failure\nDisk failure\n");
            assertQuery("select body from docs where text_match(body, 'Failure')")
                    .returns("body\n");
            assertQuery("select body from docs where text_match(body, '')")
                    .returns("body\ndisk failure\nDisk failure\nnetwork outage\n");
        });
    }

    @Test
    public void testRuntimeConstantQueryAndUnicode() throws Exception {
        assertMemoryLeak(() -> {
            execute("create table docs (body varchar)");
            execute("insert into docs values ('naïve café'), ('plain text')");
            bindVariableService.setStr("query", "café");

            assertQuery("select body from docs where text_match(body, :query)")
                    .returns("body\nnaïve café\n");
        });
    }

    @Test
    public void testRejectsNonConstantQuery() throws Exception {
        assertMemoryLeak(() -> {
            execute("create table docs (body varchar, query string)");
            assertQuery("select * from docs where text_match(body, query)")
                    .fails(42, "query must be a constant or bind variable");
        });
    }
}

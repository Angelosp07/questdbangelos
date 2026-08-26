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

import io.questdb.cairo.idx.AsciiTextTokenizer;
import io.questdb.std.ObjList;
import io.questdb.std.str.Utf8Sequence;
import io.questdb.std.str.Utf8String;
import io.questdb.std.str.Utf8s;
import org.junit.Assert;
import org.junit.Test;

public class AsciiTextTokenizerTest {

    @Test
    public void testEmptyAndNullHaveNoTokens() {
        final AsciiTextTokenizer tokenizer = new AsciiTextTokenizer();
        final TokenCollector collector = new TokenCollector();

        Assert.assertEquals(0, tokenizer.tokenize(null, collector));
        Assert.assertEquals(0, tokenizer.tokenize(new Utf8String(""), collector));
        Assert.assertEquals(0, tokenizer.tokenize(new Utf8String("---"), collector));
        Assert.assertEquals(0, collector.tokens.size());
    }

    @Test
    public void testLowercasesAndSplitsOnNonAsciiAlphanumericBytes() {
        final AsciiTextTokenizer tokenizer = new AsciiTextTokenizer();
        final TokenCollector collector = new TokenCollector();

        Assert.assertEquals(6, tokenizer.tokenize(new Utf8String("ERROR_error-42 can't café"), collector));
        Assert.assertEquals("error", collector.tokens.getQuick(0));
        Assert.assertEquals("error", collector.tokens.getQuick(1));
        Assert.assertEquals("42", collector.tokens.getQuick(2));
        Assert.assertEquals("can", collector.tokens.getQuick(3));
        Assert.assertEquals("t", collector.tokens.getQuick(4));
        Assert.assertEquals("caf", collector.tokens.getQuick(5));
    }

    @Test
    public void testRepeatedTermsArePreserved() {
        final AsciiTextTokenizer tokenizer = new AsciiTextTokenizer();
        final TokenCollector collector = new TokenCollector();

        Assert.assertEquals(3, tokenizer.tokenize(new Utf8String("Timeout timeout TIMEOUT"), collector));
        Assert.assertEquals("timeout", collector.tokens.getQuick(0));
        Assert.assertEquals("timeout", collector.tokens.getQuick(1));
        Assert.assertEquals("timeout", collector.tokens.getQuick(2));
    }

    private static class TokenCollector implements AsciiTextTokenizer.TokenSink {
        private final ObjList<String> tokens = new ObjList<>();

        @Override
        public void onToken(Utf8Sequence token) {
            tokens.add(Utf8s.stringFromUtf8Bytes(token));
        }
    }
}

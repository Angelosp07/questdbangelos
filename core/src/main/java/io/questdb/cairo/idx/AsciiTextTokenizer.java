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

import io.questdb.std.str.Utf8Sequence;
import io.questdb.std.str.Utf8StringSink;
import org.jetbrains.annotations.Nullable;

/**
 * Minimal tokenizer for the append-only text-index PoC.
 *
 * <p>A token is a non-empty run of ASCII letters or digits. Letters are emitted
 * in lower case. Every other byte, including underscore and non-ASCII UTF-8,
 * is a delimiter. Repeated terms are emitted repeatedly so a later writer can
 * calculate term frequency.</p>
 *
 * <p>The emitted sequence is a reused flyweight and must not be retained by the
 * sink. This class is not thread-safe.</p>
 */
public final class AsciiTextTokenizer {
    private final Utf8StringSink token = new Utf8StringSink();

    public int tokenize(@Nullable Utf8Sequence value, TokenSink sink) {
        if (value == null) {
            return 0;
        }

        int tokenCount = 0;
        token.clear();
        for (int i = 0, n = value.size(); i < n; i++) {
            final byte b = value.byteAt(i);
            if (b >= 'A' && b <= 'Z') {
                token.putAscii((char) (b + ('a' - 'A')));
            } else if ((b >= 'a' && b <= 'z') || (b >= '0' && b <= '9')) {
                token.putAscii((char) b);
            } else if (token.size() > 0) {
                sink.onToken(token);
                tokenCount++;
                token.clear();
            }
        }
        if (token.size() > 0) {
            sink.onToken(token);
            tokenCount++;
            token.clear();
        }
        return tokenCount;
    }

    @FunctionalInterface
    public interface TokenSink {
        void onToken(Utf8Sequence token);
    }
}

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

import io.questdb.std.LongObjHashMap;
import io.questdb.std.ObjList;
import io.questdb.std.str.Utf8Sequence;
import io.questdb.std.str.Utf8String;
import io.questdb.std.str.Utf8s;

/** Minimal OR-query BM25 search over one persistent partition text index. */
public final class PartitionTextIndexSearcher {
    public static final double B = 0.75;
    public static final double K1 = 1.2;
    private final LongObjHashMap<SearchResult> matches = new LongObjHashMap<>();
    private final ObjList<SearchResult> results = new ObjList<>();
    private final ObjList<Utf8String> terms = new ObjList<>();
    private final AsciiTextTokenizer tokenizer = new AsciiTextTokenizer();

    public ObjList<SearchResult> search(PartitionTextIndexReader reader, Utf8Sequence query, int limit) {
        matches.clear();
        results.clear();
        terms.clear();
        if (limit < 1 || reader.getDocumentCount() == 0) {
            return results;
        }

        tokenizer.tokenize(query, this::addQueryTerm);
        final double averageDocumentLength = (double) reader.getTotalDocumentLength() / reader.getDocumentCount();
        for (int i = 0, n = terms.size(); i < n; i++) {
            final PartitionTextIndexReader.PostingCursor cursor = reader.getPostings(terms.getQuick(i));
            final int documentFrequency = cursor.getDocumentFrequency();
            if (documentFrequency == 0) {
                continue;
            }
            final double inverseDocumentFrequency = Math.log(
                    1.0 + (reader.getDocumentCount() - documentFrequency + 0.5) / (documentFrequency + 0.5)
            );
            while (cursor.hasNext()) {
                cursor.next();
                final long rowId = cursor.getRowId();
                final int documentLength = reader.getDocumentLength(rowId);
                final int termFrequency = cursor.getTermFrequency();
                final double normalizer = averageDocumentLength == 0
                        ? 1.0
                        : 1.0 - B + B * documentLength / averageDocumentLength;
                final double score = inverseDocumentFrequency
                        * termFrequency * (K1 + 1.0)
                        / (termFrequency + K1 * normalizer);
                final int index = matches.keyIndex(rowId);
                if (index < 0) {
                    matches.valueAtQuick(index).score += score;
                } else {
                    matches.putAt(index, rowId, new SearchResult(rowId, score));
                }
            }
        }

        matches.forEach((rowId, result) -> results.add(result));
        results.sort((left, right) -> {
            final int scoreComparison = Double.compare(right.score, left.score);
            return scoreComparison != 0 ? scoreComparison : Long.compare(left.rowId, right.rowId);
        });
        results.setPos(Math.min(limit, results.size()));
        return results;
    }

    private void addQueryTerm(Utf8Sequence term) {
        for (int i = 0, n = terms.size(); i < n; i++) {
            if (Utf8s.equals(terms.getQuick(i), term)) {
                return;
            }
        }
        terms.add(Utf8String.newInstance(term));
    }

    public static final class SearchResult {
        private final long rowId;
        private double score;

        private SearchResult(long rowId, double score) {
            this.rowId = rowId;
            this.score = score;
        }

        public long getRowId() {
            return rowId;
        }

        public double getScore() {
            return score;
        }
    }
}

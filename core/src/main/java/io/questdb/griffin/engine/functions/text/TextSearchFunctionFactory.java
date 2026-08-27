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

package io.questdb.griffin.engine.functions.text;

import io.questdb.cairo.AbstractRecordCursorFactory;
import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.ColumnType;
import io.questdb.cairo.TableToken;
import io.questdb.cairo.TimestampDriver;
import io.questdb.cairo.sql.Function;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordCursorFactory;
import io.questdb.cairo.sql.TableMetadata;
import io.questdb.griffin.CompiledQuery;
import io.questdb.griffin.FunctionFactory;
import io.questdb.griffin.PlanSink;
import io.questdb.griffin.SqlCompiler;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.engine.functions.CursorFunction;
import io.questdb.std.Chars;
import io.questdb.std.IntList;
import io.questdb.std.Misc;
import io.questdb.std.NumericException;
import io.questdb.std.ObjList;
import io.questdb.std.str.StringSink;
import io.questdb.std.str.Utf8String;

/**
 * Scan-backed text-search table function for the pre-index PoC.
 *
 * <p>The time range is half-open: {@code [from, to)}. Matches are emitted in the
 * table's natural timestamp order with a placeholder score of {@code 1.0}. This
 * function deliberately delegates matching to {@link TextMatchVarcharFunctionFactory};
 * it does not implement ranking or an index.</p>
 */
public class TextSearchFunctionFactory implements FunctionFactory {
    public static final String NAME = "text_search";

    @Override
    public String getSignature() {
        return NAME + "(sssssv)";
    }

    @Override
    public boolean isCursor() {
        return true;
    }

    @Override
    public Function newInstance(
            int position,
            ObjList<Function> args,
            IntList argPositions,
            CairoConfiguration configuration,
            SqlExecutionContext sqlExecutionContext
    ) throws SqlException {
        final String tableName = getRequiredString(args, argPositions, 0, "table name");
        final String columnName = getRequiredString(args, argPositions, 1, "column name");
        final CharSequence query = args.getQuick(2).getStrA(null);
        final String timestampLo = getRequiredString(args, argPositions, 3, "from timestamp");
        final String timestampHi = getRequiredString(args, argPositions, 4, "to timestamp");
        final long limit = args.getQuick(5).getLong(null);
        if (limit < 1) {
            throw SqlException.$(argPositions.getQuick(5), "limit must be positive");
        }

        final CairoEngine engine = sqlExecutionContext.getCairoEngine();
        final TableToken tableToken = engine.getTableTokenIfExists(tableName);
        if (tableToken == null) {
            throw SqlException.$(argPositions.getQuick(0), "table does not exist [table=").put(tableName).put(']');
        }

        final String timestampColumnName;
        final int columnIndex;
        final int timestampIndex;
        final int timestampType;
        try (TableMetadata metadata = engine.getTableMetadata(tableToken)) {
            columnIndex = metadata.getColumnIndexQuiet(columnName);
            if (columnIndex < 0) {
                throw SqlException.$(argPositions.getQuick(1), "column does not exist [column=").put(columnName).put(']');
            }
            if (!ColumnType.isVarchar(metadata.getColumnType(columnIndex))) {
                throw SqlException.$(argPositions.getQuick(1), "column must be VARCHAR [column=")
                        .put(columnName)
                        .put(", type=")
                        .put(ColumnType.nameOf(metadata.getColumnType(columnIndex)))
                        .put(']');
            }
            timestampIndex = metadata.getTimestampIndex();
            if (timestampIndex < 0) {
                throw SqlException.$(argPositions.getQuick(0), "table must have a designated timestamp [table=")
                        .put(tableName)
                        .put(']');
            }
            timestampColumnName = Chars.toString(metadata.getColumnName(timestampIndex));
            timestampType = metadata.getColumnType(timestampIndex);
        }

        final long timestampLoValue;
        final long timestampHiValue;
        try {
            final TimestampDriver timestampDriver = ColumnType.getTimestampDriver(timestampType);
            timestampLoValue = timestampDriver.parseFloorLiteral(timestampLo);
            timestampHiValue = timestampDriver.parseFloorLiteral(timestampHi);
        } catch (NumericException e) {
            throw SqlException.$(argPositions.getQuick(3), "invalid timestamp range");
        }

        final StringSink sql = new StringSink();
        sql.put("select ");
        putIdentifier(sql, timestampColumnName);
        sql.put(" ts, ");
        putIdentifier(sql, columnName);
        sql.put(" value, 1.0 score from ");
        putIdentifier(sql, tableToken.getTableName());
        sql.put(" where ");
        putIdentifier(sql, timestampColumnName);
        sql.put(" >= ");
        putStringLiteral(sql, timestampLo);
        sql.put(" and ");
        putIdentifier(sql, timestampColumnName);
        sql.put(" < ");
        putStringLiteral(sql, timestampHi);
        sql.put(" and ").put(TextMatchVarcharFunctionFactory.NAME).put('(');
        putIdentifier(sql, columnName);
        sql.put(", ");
        putStringLiteral(sql, query);
        sql.put(") limit ").put(limit);

        RecordCursorFactory base = null;
        try (SqlCompiler compiler = engine.getSqlCompiler()) {
            final CompiledQuery compiledQuery = compiler.compile(sql, sqlExecutionContext);
            base = compiledQuery.getRecordCursorFactory();
            final IndexedTextSearchRecordCursorFactory indexed = query == null ? null : new IndexedTextSearchRecordCursorFactory(
                    tableToken,
                    columnIndex,
                    timestampIndex,
                    timestampType,
                    new Utf8String(query),
                    timestampLoValue,
                    timestampHiValue,
                    limit
            );
            return new CursorFunction(new TextSearchRecordCursorFactory(
                    base,
                    indexed,
                    tableToken.getTableName(),
                    columnName,
                    limit
            ));
        } catch (Throwable th) {
            Misc.free(base);
            throw th;
        }
    }

    private static String getRequiredString(
            ObjList<Function> args,
            IntList argPositions,
            int index,
            CharSequence name
    ) throws SqlException {
        final CharSequence value = args.getQuick(index).getStrA(null);
        if (value == null) {
            throw SqlException.$(argPositions.getQuick(index), name).put(" must not be null");
        }
        return Chars.toString(value);
    }

    private static void putIdentifier(StringSink sink, CharSequence identifier) {
        sink.put('"');
        for (int i = 0, n = identifier.length(); i < n; i++) {
            final char c = identifier.charAt(i);
            if (c == '"') {
                sink.put('"');
            }
            sink.put(c);
        }
        sink.put('"');
    }

    private static void putStringLiteral(StringSink sink, CharSequence value) {
        if (value == null) {
            sink.put("null");
            return;
        }
        sink.put('\'');
        for (int i = 0, n = value.length(); i < n; i++) {
            final char c = value.charAt(i);
            if (c == '\'') {
                sink.put('\'');
            }
            sink.put(c);
        }
        sink.put('\'');
    }

    private static class TextSearchRecordCursorFactory extends AbstractRecordCursorFactory {
        private final RecordCursorFactory base;
        private final String columnName;
        private final IndexedTextSearchRecordCursorFactory indexed;
        private final long limit;
        private final String tableName;

        private TextSearchRecordCursorFactory(
                RecordCursorFactory base,
                IndexedTextSearchRecordCursorFactory indexed,
                CharSequence tableName,
                CharSequence columnName,
                long limit
        ) {
            super(base.getMetadata());
            this.base = base;
            this.indexed = indexed;
            this.tableName = Chars.toString(tableName);
            this.columnName = Chars.toString(columnName);
            this.limit = limit;
        }

        @Override
        public RecordCursor getCursor(SqlExecutionContext executionContext) throws SqlException {
            if (indexed != null) {
                final RecordCursor indexedCursor = indexed.getCursor(executionContext);
                if (indexedCursor != null) {
                    return indexedCursor;
                }
            }
            return base.getCursor(executionContext);
        }

        @Override
        public boolean isNonDeterministic() {
            return base.isNonDeterministic();
        }

        @Override
        public boolean isStableWithinExecution() {
            return base.isStableWithinExecution();
        }

        @Override
        public boolean recordCursorSupportsRandomAccess() {
            return base.recordCursorSupportsRandomAccess();
        }

        @Override
        public void toPlan(PlanSink sink) {
            sink.type(indexed == null ? "text_search scan fallback" : "text_search partition index with scan fallback");
            sink.meta("table").val(tableName);
            sink.meta("column").val(columnName);
            sink.meta("limit").val(limit);
            sink.child(base);
        }

        @Override
        public boolean usesCompiledFilter() {
            return base.usesCompiledFilter();
        }

        @Override
        public boolean usesIndex() {
            return indexed != null;
        }

        @Override
        protected void _close() {
            base.close();
            Misc.free(indexed);
        }
    }
}

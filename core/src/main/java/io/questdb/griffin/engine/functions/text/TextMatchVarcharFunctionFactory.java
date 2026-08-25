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

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.sql.Function;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.SymbolTableSource;
import io.questdb.griffin.FunctionFactory;
import io.questdb.griffin.PlanSink;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.engine.functions.BinaryFunction;
import io.questdb.griffin.engine.functions.BooleanFunction;
import io.questdb.griffin.engine.functions.UnaryFunction;
import io.questdb.std.IntList;
import io.questdb.std.ObjList;
import io.questdb.std.str.Utf8Sequence;
import io.questdb.std.str.Utf8String;
import io.questdb.std.str.Utf8s;

/**
 * Minimal text-search placeholder. It deliberately performs a case-sensitive literal
 * substring scan, equivalent to {@code value LIKE '%' || query || '%'}.
 */
public class TextMatchVarcharFunctionFactory implements FunctionFactory {
    public static final String NAME = "text_match";

    @Override
    public String getSignature() {
        return NAME + "(ØS)";
    }

    @Override
    public boolean isBoolean() {
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
        final Function value = args.getQuick(0);
        final Function query = args.getQuick(1);
        if (query.isConstant()) {
            return new ConstFunc(value, query.getStrA(null));
        }
        if (query.isRuntimeConstant()) {
            return new RuntimeConstFunc(value, query);
        }
        throw SqlException.$(argPositions.getQuick(1), "query must be a constant or bind variable");
    }

    private static class ConstFunc extends BooleanFunction implements UnaryFunction {
        private final Utf8String query;
        private final Function value;

        private ConstFunc(Function value, CharSequence query) {
            this.value = value;
            this.query = query == null ? null : new Utf8String(query);
        }

        @Override
        public Function getArg() {
            return value;
        }

        @Override
        public boolean getBool(Record rec) {
            final Utf8Sequence text = value.getVarcharA(rec);
            return text != null && query != null && Utf8s.contains(text, query);
        }

        @Override
        public void toPlan(PlanSink sink) {
            sink.val(NAME).val('(').val(value).val(", ").val(query).val(')');
        }
    }

    private static class RuntimeConstFunc extends BooleanFunction implements BinaryFunction {
        private Utf8String query;
        private final Function queryFunc;
        private final Function value;

        private RuntimeConstFunc(Function value, Function queryFunc) {
            this.value = value;
            this.queryFunc = queryFunc;
        }

        @Override
        public boolean getBool(Record rec) {
            final Utf8Sequence text = value.getVarcharA(rec);
            return text != null && query != null && Utf8s.contains(text, query);
        }

        @Override
        public void init(SymbolTableSource symbolTableSource, SqlExecutionContext executionContext) throws SqlException {
            BinaryFunction.super.init(symbolTableSource, executionContext);
            final CharSequence queryValue = queryFunc.getStrA(null);
            query = queryValue == null ? null : new Utf8String(queryValue);
        }

        @Override
        public Function getLeft() {
            return value;
        }

        @Override
        public Function getRight() {
            return queryFunc;
        }

        @Override
        public boolean isThreadSafe() {
            return false;
        }

        @Override
        public void toPlan(PlanSink sink) {
            sink.val(NAME).val('(').val(value).val(", ").val(queryFunc).val(')');
        }
    }
}

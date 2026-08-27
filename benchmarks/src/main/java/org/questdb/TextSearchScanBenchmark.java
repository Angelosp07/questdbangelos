/*+*****************************************************************************
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

package org.questdb;

import io.questdb.cairo.CairoConfiguration;
import io.questdb.cairo.CairoEngine;
import io.questdb.cairo.DefaultCairoConfiguration;
import io.questdb.cairo.TableToken;
import io.questdb.cairo.TableReader;
import io.questdb.cairo.idx.OfflineTextIndexBuilder;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.cairo.sql.RecordCursorFactory;
import io.questdb.griffin.SqlCompiler;
import io.questdb.griffin.SqlException;
import io.questdb.griffin.SqlExecutionContext;
import io.questdb.griffin.SqlExecutionContextImpl;
import io.questdb.log.LogFactory;
import io.questdb.std.Files;
import io.questdb.std.Misc;
import io.questdb.std.str.Path;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.io.File;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Query-time scan baseline for the minimal {@code text_match(VARCHAR, STRING)} PoC.
 *
 * <p>The generated append-only corpus spans 16 daily partitions. {@code timeout} occurs in about
 * 10% of rows and {@code panic42} in 0.01%. All three operators use case-sensitive substring
 * semantics for these literal alphanumeric terms.</p>
 *
 * <p>Build and run via this class's {@link #main(String[])} so the corpus is created before JMH:
 * <pre>
 * mvn -pl benchmarks -am package -DskipTests
 * java -cp benchmarks/target/benchmarks.jar org.questdb.TextSearchScanBenchmark
 * </pre>
 * Set {@code -Dtext.search.bench.rows=N} to change the default 2 million rows. Pass JMH CLI
 * arguments to select a smaller matrix, for example:
 * <pre>
 * TextSearchScanBenchmark -p operator=text_match,like -p cache=warm -wi 2 -i 5
 * </pre>
 * {@code cache=cold_linux} releases QuestDB readers and asks Linux to evict the database files from
 * the page cache before every invocation. QuestDB's {@link Files#fadvise(long, long, long, int)} is
 * intentionally a no-op on other operating systems, so cold numbers are meaningful on Linux only.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 2)
@Measurement(iterations = 5)
@Fork(0)
public class TextSearchScanBenchmark {
    private static final String COLD_LINUX = "cold_linux";
    private static final int PARTITION_COUNT = 16;
    private static final int POSIX_FADV_DONTNEED = 4;
    private static final long ROWS = Long.getLong("text.search.bench.rows", 2_000_000L);
    private static final String ROOT = System.getProperty("java.io.tmpdir")
            + File.separator
            + "text-search-scan-bench";
    private static final String TABLE_NAME = "logs";
    private static final CairoConfiguration CONFIGURATION = new DefaultCairoConfiguration(ROOT);

    private static CairoEngine engine;
    private static SqlCompiler compiler;
    private static SqlExecutionContext context;

    @Param({"warm", COLD_LINUX})
    public String cache;

    @Param({"common", "rare"})
    public String frequency;

    @Param({"text_match", "like", "regex", "text_search"})
    public String operator;

    @Param({"wide", "one_day"})
    public String window;

    private RecordCursorFactory factory;

    public static void main(String[] args) throws Exception {
        java.nio.file.Files.createDirectories(Paths.get(ROOT));
        buildCorpus();

        final Options options = args.length > 0
                ? new org.openjdk.jmh.runner.options.CommandLineOptions(args)
                : new OptionsBuilder().include(TextSearchScanBenchmark.class.getSimpleName()).build();
        new Runner(options).run();
        LogFactory.haltInstance();
    }

    @Benchmark
    public long countMatches() throws SqlException {
        try (RecordCursor cursor = factory.getCursor(context)) {
            if (!cursor.hasNext()) {
                throw new IllegalStateException("count query returned no row");
            }
            return cursor.getRecord().getLong(0);
        }
    }

    @Setup(Level.Invocation)
    public void evictBeforeColdInvocation() {
        if (COLD_LINUX.equals(cache)) {
            engine.releaseAllReaders();
            evictPageCache();
        }
    }

    @Setup(Level.Trial)
    public void setUpTrial() throws SqlException {
        ensureEngine();
        factory = compiler.compile(query(operator, frequency, window), context).getRecordCursorFactory();
    }

    @TearDown(Level.Trial)
    public void tearDownTrial() {
        factory = Misc.free(factory);
    }

    private static void buildCorpus() throws Exception {
        final long spacingUs = Math.max(1L, PARTITION_COUNT * 86_400_000_000L / ROWS);
        try (CairoEngine buildEngine = new CairoEngine(CONFIGURATION)) {
            final SqlExecutionContext buildContext = newContext(buildEngine);
            buildEngine.execute("DROP TABLE IF EXISTS " + TABLE_NAME, buildContext);
            buildEngine.execute(
                    "CREATE TABLE " + TABLE_NAME + " (" +
                            "id LONG, " +
                            "ts TIMESTAMP, " +
                            "level SYMBOL, " +
                            "message VARCHAR" +
                            ") TIMESTAMP(ts) PARTITION BY DAY BYPASS WAL",
                    buildContext
            );

            final String generatedRows =
                    "SELECT " +
                            "x, " +
                            "('2024-01-01'::TIMESTAMP + (x - 1) * " + spacingUs + "L)::TIMESTAMP, " +
                            "(CASE WHEN x % 10000 = 0 THEN 'ERROR' " +
                            "WHEN x % 10 = 0 THEN 'WARN' ELSE 'INFO' END)::SYMBOL, " +
                            "(CASE WHEN x % 10000 = 0 " +
                            "THEN 'panic42 shard recovery failed after checkpoint rotation request=' || x " +
                            "WHEN x % 10 = 0 " +
                            "THEN 'request timeout while contacting upstream cache retry=' || x " +
                            "ELSE 'request completed status=200 service=api shard=' || (x % 128) END)::VARCHAR " +
                            "FROM long_sequence(" + ROWS + ")";
            final long insertStartNanos = System.nanoTime();
            buildEngine.execute("INSERT INTO " + TABLE_NAME + " " + generatedRows, buildContext);
            buildEngine.releaseAllWriters();
            final long insertNanos = System.nanoTime() - insertStartNanos;

            verifyEquivalentResults(buildEngine, buildContext);

            final OfflineTextIndexBuilder.BuildStats indexStats;
            try (TableReader reader = buildEngine.getReader(TABLE_NAME)) {
                indexStats = new OfflineTextIndexBuilder().build(
                        reader,
                        reader.getMetadata().getColumnIndexQuiet("message")
                );
            }
            verifyIndexedResults(buildEngine, buildContext);

            final TableToken tableToken = buildEngine.getTableTokenIfExists(TABLE_NAME);
            final java.nio.file.Path tableRoot = Paths.get(CONFIGURATION.getDbRoot(), tableToken.getDirName());
            final long tableBytes = directoryBytes(tableRoot);
            final long messageBytes = messageColumnBytes(tableRoot);
            final double insertSeconds = insertNanos / 1_000_000_000.0;
            System.out.printf(
                    "text-search corpus: %,d rows, append=%.1f rows/s, table=%.2f MiB, message=%.2f MiB, " +
                            "index=%.2f MiB (%.1f%% of message), build=%.3f s%n",
                    ROWS,
                    ROWS / insertSeconds,
                    tableBytes / 1_048_576.0,
                    messageBytes / 1_048_576.0,
                    indexStats.getIndexBytes() / 1_048_576.0,
                    100.0 * indexStats.getIndexBytes() / messageBytes,
                    indexStats.getBuildNanos() / 1_000_000_000.0
            );
        }
    }

    private static long directoryBytes(java.nio.file.Path root) throws Exception {
        try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(root)) {
            return paths.filter(java.nio.file.Files::isRegularFile).mapToLong(TextSearchScanBenchmark::fileSize).sum();
        }
    }

    private static void ensureEngine() {
        if (engine != null) {
            return;
        }
        engine = new CairoEngine(CONFIGURATION);
        compiler = engine.getSqlCompiler();
        context = newContext(engine);
    }

    private static void evictPageCache() {
        try (Path path = new Path();
             java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(Paths.get(CONFIGURATION.getDbRoot()))) {
            paths.filter(java.nio.file.Files::isRegularFile).forEach(file -> {
                path.of(file.toString()).$();
                final long fd = Files.openRO(path.$());
                if (fd > 0) {
                    final long length = Files.length(fd);
                    if (length > 0) {
                        Files.fadvise(fd, 0, length, POSIX_FADV_DONTNEED);
                    }
                    Files.close(fd);
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static long fileSize(java.nio.file.Path file) {
        try {
            return java.nio.file.Files.size(file);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static long messageColumnBytes(java.nio.file.Path root) throws Exception {
        try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(root)) {
            return paths
                    .filter(java.nio.file.Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().startsWith("message."))
                    .mapToLong(TextSearchScanBenchmark::fileSize)
                    .sum();
        }
    }

    private static SqlExecutionContext newContext(CairoEngine cairoEngine) {
        return new SqlExecutionContextImpl(cairoEngine, 1) {
            @Override
            public boolean shouldLogSql() {
                return false;
            }
        }.with(
                CONFIGURATION.getFactoryProvider().getSecurityContextFactory().getRootContext(),
                null,
                null,
                -1,
                null
        );
    }

    private static long queryCount(
            SqlCompiler sqlCompiler,
            SqlExecutionContext sqlContext,
            String sql
    ) throws SqlException {
        try (RecordCursorFactory countFactory = sqlCompiler.compile(sql, sqlContext).getRecordCursorFactory();
             RecordCursor cursor = countFactory.getCursor(sqlContext)) {
            if (!cursor.hasNext()) {
                throw new IllegalStateException("count query returned no row [sql=" + sql + ']');
            }
            return cursor.getRecord().getLong(0);
        }
    }

    private static String query(String operator, String frequency, String window) {
        final String term;
        switch (frequency) {
            case "common":
                term = "timeout";
                break;
            case "rare":
                term = "panic42";
                break;
            default:
                throw new IllegalArgumentException("unknown frequency: " + frequency);
        }

        final String predicate;
        switch (operator) {
            case "text_match":
                predicate = "text_match(message, '" + term + "')";
                break;
            case "like":
                predicate = "message LIKE '%" + term + "%'";
                break;
            case "regex":
                predicate = "message ~ '" + term + "'";
                break;
            case "text_search":
                switch (window) {
                    case "wide":
                        return "SELECT count() FROM text_search('" + TABLE_NAME +
                                "', 'message', '" + term + "', '2024-01-01', '2024-02-01', " + ROWS + ")";
                    case "one_day":
                        return "SELECT count() FROM text_search('" + TABLE_NAME +
                                "', 'message', '" + term + "', '2024-01-16', '2024-01-17', " + ROWS + ")";
                    default:
                        throw new IllegalArgumentException("unknown window: " + window);
                }
            default:
                throw new IllegalArgumentException("unknown operator: " + operator);
        }

        switch (window) {
            case "wide":
                return "SELECT count() FROM " + TABLE_NAME + " WHERE " + predicate;
            case "one_day":
                return "SELECT count() FROM " + TABLE_NAME + " WHERE ts IN '2024-01-16' AND " + predicate;
            default:
                throw new IllegalArgumentException("unknown window: " + window);
        }
    }

    private static void verifyEquivalentResults(CairoEngine cairoEngine, SqlExecutionContext sqlContext) throws SqlException {
        try (SqlCompiler sqlCompiler = cairoEngine.getSqlCompiler()) {
            final String[] frequencies = {"common", "rare"};
            final String[] windows = {"wide", "one_day"};
            for (int frequencyIndex = 0; frequencyIndex < frequencies.length; frequencyIndex++) {
                for (int windowIndex = 0; windowIndex < windows.length; windowIndex++) {
                    final String frequency = frequencies[frequencyIndex];
                    final String window = windows[windowIndex];
                    final long textMatchCount = queryCount(
                            sqlCompiler,
                            sqlContext,
                            query("text_match", frequency, window)
                    );
                    final long likeCount = queryCount(sqlCompiler, sqlContext, query("like", frequency, window));
                    final long regexCount = queryCount(sqlCompiler, sqlContext, query("regex", frequency, window));
                    if (textMatchCount <= 0 || textMatchCount != likeCount || textMatchCount != regexCount) {
                        throw new IllegalStateException(
                                "scan baselines disagree [frequency=" + frequency +
                                        ", window=" + window +
                                        ", text_match=" + textMatchCount +
                                        ", like=" + likeCount +
                                        ", regex=" + regexCount + ']'
                        );
                    }
                }
            }
        }
    }

    private static void verifyIndexedResults(CairoEngine cairoEngine, SqlExecutionContext sqlContext) throws SqlException {
        try (SqlCompiler sqlCompiler = cairoEngine.getSqlCompiler()) {
            final String[] frequencies = {"common", "rare"};
            final String[] windows = {"wide", "one_day"};
            for (int frequencyIndex = 0; frequencyIndex < frequencies.length; frequencyIndex++) {
                for (int windowIndex = 0; windowIndex < windows.length; windowIndex++) {
                    final String frequency = frequencies[frequencyIndex];
                    final String window = windows[windowIndex];
                    final long scanCount = queryCount(sqlCompiler, sqlContext, query("text_match", frequency, window));
                    final long indexCount = queryCount(sqlCompiler, sqlContext, query("text_search", frequency, window));
                    if (scanCount != indexCount) {
                        throw new IllegalStateException(
                                "indexed results disagree with scan [frequency=" + frequency +
                                        ", window=" + window +
                                        ", scan=" + scanCount +
                                        ", index=" + indexCount + ']'
                        );
                    }
                }
            }
        }
    }
}

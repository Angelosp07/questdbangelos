# Text-search PoC: pre-BM25 decision record

Status: scan skeleton, no-op append-only writer seam, and fixed tokenizer contract implemented. Persistent postings, scoring, and RRF are not implemented.

## Recommendation

Keep the first public surface deliberately small:

```sql
SELECT ts, message
FROM logs
WHERE ts IN '2026-08-25'
  AND text_match(message, 'timeout');
```

`text_match(VARCHAR, STRING) -> BOOLEAN` currently means a case-sensitive literal substring scan. It is equivalent to `message LIKE '%timeout%'`, returns `false` for a null value or query, accepts a constant or bind-variable query, and rejects a per-row query expression.

Do not add BM25 semantics to this boolean function. If the later design gate selects BM25, add a separate scoring or top-k surface so this simple predicate does not silently change meaning.

The PoC also has a scan-backed top-k-shaped surface:

```sql
SELECT *
FROM text_search('logs', 'message', 'timeout', '2026-08-24', '2026-08-26', 10);
```

It returns `ts`, `value`, and a placeholder `score = 1.0`, applies the half-open timestamp range `[from, to)`, and limits rows in natural timestamp order. Its plan is labelled `text_search scan fallback`; it does not rank or read an index.

## Smallest end-to-end path

The scalar-function route is smaller than new grammar or a table function:

1. The existing expression parser recognizes an ordinary function call; no `SqlParser` change is required.
2. Classpath factory discovery finds `TextMatchVarcharFunctionFactory`; `function_list.txt` does not need an entry because it only pins conflicting overload order.
3. `FunctionParser` resolves signature `text_match(ØS)` to the factory.
4. The normal filter planner embeds the returned boolean function in the existing table-scan cursor factory. Timestamp predicates are still extracted into an interval partition scan.
5. The existing table cursor returns matching rows. No dedicated text-search cursor is needed for the scan version.

Current files:

- `core/src/main/java/io/questdb/griffin/engine/functions/text/TextMatchVarcharFunctionFactory.java`: signature, validation, constant/bind handling, scan predicate, and plan rendering.
- `core/src/test/java/io/questdb/test/griffin/engine/functions/text/TextMatchVarcharFunctionFactoryTest.java`: literal behavior, case sensitivity, null/empty values, Unicode, bind variables, cursor reopen, rejection of row-varying queries, and interval-plan coverage.
- `core/src/main/java/io/questdb/griffin/engine/functions/text/TextSearchFunctionFactory.java`: table-function validation, safe SQL construction, scan-backed cursor factory, timestamp range, limit, and placeholder score.
- `core/src/test/java/io/questdb/test/griffin/engine/functions/text/TextSearchFunctionFactoryTest.java`: rows, range boundaries, limit, validation, quoting, cursor reopen, and fallback plan coverage.
- `benchmarks/src/main/java/org/questdb/TextSearchScanBenchmark.java`: deterministic corpus, semantic equivalence checks, append/storage measurements, and the JMH scan matrix.

There is intentionally no parser, `SqlCodeGenerator`, table metadata, `TableWriter`, or persistent index change in this version. The table function wraps the ordinary compiled scan cursor rather than adding an index-selected planner path.

## Existing patterns worth copying

### Scalar scan predicates

- `LikeVarcharFunctionFactory` uses signature `like(ØS)` and is the semantic baseline.
- `MatchVarcharFunctionFactory` uses signature `~(ØS)` and supplies the regex baseline.
- `TextMatchVarcharFunctionFactory` follows the same factory-discovery and ordinary-filter route, but calls `Utf8s.contains` directly.

### A table function that owns a cursor

`LongSequenceFunctionFactory` is the smallest complete example if a later top-k design needs a table function. Its `newInstance` returns `CursorFunction`, its `LongSequenceCursorFactory` supplies metadata and a `RecordCursor`, and `LongSequenceRecordCursor.hasNext()` emits rows. `LongSequenceTest` is the focused factory test.

Relevant files:

- `core/src/main/java/io/questdb/griffin/engine/functions/rnd/LongSequenceFunctionFactory.java`
- `core/src/main/java/io/questdb/griffin/engine/functions/CursorFunction.java`
- `core/src/test/java/io/questdb/test/griffin/engine/functions/rnd/LongSequenceTest.java`

## Append-only write seam and tokenizer

The first write path is deliberately offline and has no index output yet:

1. `TextColumnIndexer` reads a committed `[loRow, hiRow)` range from native VARCHAR aux/data memory using `VarcharTypeDriver`.
2. It converts physical offsets using `columnTop`, while preserving partition-local row IDs.
3. It calls `TextIndexWriter.add(rowId, value)` once per stored row, including null values.
4. `NoOpTextIndexWriter` retains no values and writes no files; it only validates strictly increasing row IDs and exposes a commit watermark for tests.

This is the minimal replacement point for the later partition-local file writer. It is intentionally not registered in `TableWriter`: an explicit offline build can open each committed native partition and use this range feeder without implying WAL, O3, deduplication, or metadata support.

The tokenizer contract is also fixed:

- tokens are non-empty runs of ASCII letters or digits (`[A-Za-z0-9]+`);
- ASCII letters are lowercased;
- punctuation, underscore, and every non-ASCII UTF-8 byte are delimiters;
- repeated tokens are preserved for later term-frequency calculation;
- null and empty documents emit no tokens;
- emitted UTF-8 is a reused flyweight and cannot be retained by the consumer.

Evidence files:

- `core/src/main/java/io/questdb/cairo/TextColumnIndexer.java`
- `core/src/main/java/io/questdb/cairo/idx/TextIndexWriter.java`
- `core/src/main/java/io/questdb/cairo/idx/NoOpTextIndexWriter.java`
- `core/src/main/java/io/questdb/cairo/idx/AsciiTextTokenizer.java`
- `core/src/test/java/io/questdb/test/cairo/TextColumnIndexerTest.java`
- `core/src/test/java/io/questdb/test/cairo/AsciiTextTokenizerTest.java`

The five focused writer/tokenizer tests pass. They cover inline and split VARCHAR storage, nulls, `columnTop` row-ID mapping, append-order rejection, null/empty input, normalization, delimiter behavior, and repeated terms.

The complete focused MVP regression is 14 passing tests: five scalar predicate tests, four scan-backed table-function tests, two writer-seam tests, and three tokenizer tests.

### A planner-selected posting-index cursor

The current POSTING path is the more relevant model for a future native index:

- `SqlParser` parses `INDEX TYPE POSTING` and `INCLUDE (...)` for SYMBOL columns.
- `SqlCodeGenerator` can replace a qualifying `DISTINCT symbol` scan with `PostingIndexDistinctRecordCursorFactory`, preserving timestamp interval frames.
- `PostingIndexDistinctRecordCursorFactory` walks partition frames, gets the partition's `IndexReader`, and emits rows through a `RecordCursor`.
- `TableReader` asks `IndexFactory` for a bitmap or posting reader using the partition timestamp, column-name transaction, column top, and pinned table transaction.

This is a complete syntax -> metadata -> planner -> cursor example, but it is not itself a text index.

## Reproducible scan baseline

The benchmark creates an append-only `logs(id LONG, ts TIMESTAMP, level SYMBOL, message VARCHAR)` table spread evenly over 16 daily partitions. `timeout` occurs in about 10% of rows and `panic42` in 0.01%. Before timing, it asserts that `text_match`, `LIKE`, and regex return identical counts for both terms and both time windows.

The first local evidence run used 2,000,000 rows, one query thread, warm page cache, JDK 25 on macOS arm64, one 1-second warm-up iteration and three 1-second measurement iterations. It is a development-machine baseline, not a release claim.

- Append: 2.79 million rows/s.
- Whole table: 165.61 MiB.
- `message` VARCHAR data and aux files: 126.73 MiB.

| Frequency | Window | `text_match` | `LIKE` | regex |
|---|---:|---:|---:|---:|
| common | 16 days | 66.3 ms | 42.8 ms | 74.9 ms |
| common | 1 day | 4.11 ms | 2.70 ms | 4.68 ms |
| rare | 16 days | 68.7 ms | 38.2 ms | 79.8 ms |
| rare | 1 day | 4.30 ms | 2.41 ms | 5.03 ms |

Interpretation:

- Partition pruning reduced all scans to roughly 1/16 of the wide cost. Keeping the timestamp predicate independent of text search is important.
- Match rarity barely changes scan cost, as expected without an index.
- The skeleton is faster than regex but slower than the existing `LIKE` implementation. It is a useful API seam, not a performance feature.
- The benchmark supports `cache=cold_linux`, which releases readers and uses `POSIX_FADV_DONTNEED`. QuestDB's `Files.fadvise` is Linux-only, so cold-cache evidence still needs a Linux run.

## What the existing index lifecycle tells us

### Storage and visibility

`ColumnVersionReader` resolves a column-name transaction and column top per `(partition timestamp, column index)`. A future index must be tied to the same column instance, not merely to the logical column name. `ColumnVersionWriter` copy, upsert, squash, truncate, and remove operations show the lifecycle events that invalidate or relocate sidecars.

POSTING already stores partition-local immutable/sealed files and selects readers through `IndexFactory`. Its public writer shape is still `add(int key, long rowId)`: it indexes SYMBOL integer keys and row IDs. BM25 additionally needs a term dictionary, term frequency, document frequency, document length, and corpus/partition aggregation. Those are data-model additions, not a small flag on the current writer.

The reusable part is therefore the lifecycle shape--partition-local files, committed row-range backfill, seal/publish, and partition-aware readers--rather than the current integer-key writer API or on-disk payload.

### WAL apply and ingestion

`ApplyWal2TableJob` hands data commits to `TableWriter.commitWalInsertTransactions`. `TableWriter` updates indexes in the WAL/O3 paths and commits buffered posting generations before publishing the table transaction. A synchronous tokenizer/index writer would therefore sit on the WAL apply hot path unless deliberately separated.

For a production direction, prefer an asynchronous per-sealed-partition build with an explicit refresh watermark. For the append-only PoC, a synchronous build command after loading the corpus is simpler and makes build time measurable without pretending to solve zero-GC WAL maintenance.

### O3 and deduplication

The current posting implementation has explicit fast append, rollback, discard/rebuild, reseal, and O3 paths because row IDs and covered values can change. Text postings have the same problem, plus term statistics. Supporting O3 or dedup correctly is high-cost and remains out of PoC scope.

### Parquet partitions

The current SYMBOL index path links or rebuilds index files while converting partitions to Parquet, and can decode Parquet to rebuild a posting index. A future text index has two viable policies:

1. carry/rebuild its sidecars beside the Parquet file; or
2. mark Parquet partitions unindexed and fall back to `text_match` scanning.

The second is the appropriate PoC policy. The first requires wiring conversion, switch, restore, and purge paths.

### Snapshot, detach/attach, and partition drop

Snapshot restore explicitly removes and rebuilds posting sidecars. Detach recursively links/copies the partition directory and copies `_meta`, `_cv`, and `_txn`; attach validates and pins column versions. Partition drop removes the column-version partition record and later purges the directory.

Partition-local text files would travel automatically during recursive detach, but correctness still requires validation, version pinning, snapshot cleanup/rebuild, and orphan purge logic. These are acknowledged production costs, not PoC work.

## Options at the pre-BM25 gate

| Route | PoC simplicity | QuestDB fit | Main risk | Recommendation now |
|---|---|---|---|---|
| Query-time scan | Highest | Uses existing VARCHAR and interval scans; no lifecycle work | O(rows), no ranking | Keep as baseline and fallback |
| Native per-partition inverted index | Medium | Can reuse POSTING lifecycle concepts and partition-local readers | New tokenizer, dictionary, BM25 payload/stats, WAL/O3 integration | Best candidate for a later dedicated PoC |
| Embedded Lucene/Tantivy | Medium for isolated demo, low for integration | Mature search semantics | Separate transaction/lifecycle model, heap/FFI, duplicate partition management | Do not choose before measuring native design cost |
| Query-time BM25 | Medium | No persistent index | At least one expensive token/statistics pass; poor top-k scaling | Useful only as a correctness oracle |

## Proposed next gate

The next bounded milestone is one experimental partition-local file writer behind `TextIndexWriter`, still with no BM25 and no SQL reader. It should:

1. consume the fixed ASCII tokenizer;
2. build a term dictionary for one native partition;
3. store postings carrying partition row ID and term frequency;
4. store per-row document length and per-term document frequency, but calculate no score;
5. run only through an explicit offline build harness, not `TableWriter` or WAL;
6. publish files only on `commit`, with a format version and column-name transaction in the header;
7. have a small reader used only by tests to verify exact terms, postings, and statistics;
8. add a JMH build-throughput benchmark before connecting the query engine.

Only after the format and build cost are measured should the existing `text_search(...)` cursor gain an index-backed path and a scan-based scoring oracle.

Still excluded: O3, dedup, automatic WAL maintenance, live refresh, Parquet indexing, snapshot integration, attach validation, stemming, language analyzers, phrase search, highlighting, vector search, and RRF.

## Likely files for that separate spike

Only after approval:

- SQL/DDL: `SqlParser.java`, create/alter table models and operations, table metadata encoding, and parser tests if index syntax is introduced.
- Planner: `SqlCodeGenerator.java` and a new text-search cursor factory if the planner selects an index; a table-function-only spike can avoid bespoke grammar.
- Storage: new text index reader/writer and file utilities under `io.questdb.cairo.idx`, plus `IndexFactory` only if promoted into the generic index type system.
- Writer lifecycle: selected `TableWriter` build/rebuild hooks; WAL/O3 paths remain explicitly unsupported in the spike.
- Query cursor: a partition-frame-aware top-k `RecordCursorFactory`, modeled on existing posting cursor factories.
- Tests: focused parser/factory/cursor tests, interval pruning, native vs Parquet fallback, corrupt/missing index fallback, and benchmark equivalence against the scan oracle.

No production lifecycle changes should be made until the index format and query evidence justify them.

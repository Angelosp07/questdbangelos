# Track A RFC: append-only native text search MVP

> Status: MVP evidence complete; production lifecycle work is intentionally excluded.

## Decision

Adopt a native immutable inverted-index sidecar per `(partition, VARCHAR column instance)` as the MVP for ranked text search on native `VARCHAR` data.

Keep `text_match(VARCHAR, STRING)` as the existing boolean literal-substring scan predicate. Expose ranked retrieval through a separate `text_search(...)` table function:

```sql
SELECT *
FROM text_search('logs', 'message', 'timeout', '2024-01-01', '2024-02-01', 10);
```

`text_search` returns `(ts, value, score)`, applies the half-open range `[from, to)`, prunes partitions by timestamp, calculates partition-local BM25, merges candidates by descending score, and returns global top-k.

If any selected partition is Parquet or has a missing, stale, or corrupt sidecar, the whole query falls back safely to the existing scan path with placeholder `score = 1.0`.

## Why This Direction

The realistic options reduce to three:

1. `Brute-force scan baseline.` This is the simplest path and already works, but it is still `O(rows)` and does not provide ranked retrieval at production scale. Keep it as the correctness baseline and safe fallback, not as the primary solution.
2. `Native per-partition inverted index.` This matches QuestDB's storage model directly: immutable partition-local sidecars, partition-aware readers, explicit offline build, and narrow integration with existing lifecycle patterns. It preserves the zero-GC ingest path because the MVP keeps index maintenance outside `TableWriter` and WAL apply.
3. `Embedded library route (Lucene/Tantivy).` This offers mature search features, but adds a separate transaction and storage model, plus either JVM heap pressure or FFI complexity. It does not align naturally with QuestDB's existing partition lifecycle.

The native route wins because it fits known QuestDB patterns, keeps the integration surface narrow, and already shows measurable speedups for selective terms while making the next optimization gate explicit for common terms.

## Storage And Build

`OfflineTextIndexBuilder` reads committed native `VARCHAR` aux/data mappings through `TextColumnIndexer`. `PartitionTextIndexWriter` tokenizes ASCII alphanumeric runs, lowercases ASCII, and atomically publishes a versioned sidecar. The file contains:

- document records: partition row ID and document length;
- a sorted term dictionary: term bytes, document frequency, and postings location;
- postings: partition row ID and term frequency;
- identity: format version, column-name transaction, and partition timestamp.

`PartitionTextIndexReader` validates identity and layout before exposing binary-search term lookup and posting cursors. The MVP is an explicit post-load append-only build. It is intentionally not production-ready and makes no `TableWriter`, WAL, O3, deduplication, snapshot, attach, or Parquet lifecycle claim.

## Evidence

Corpus: 2,000,000 log rows, 16 daily partitions; `timeout` in 10% and `panic42` in 0.01%. Local JDK 25/macOS arm64 warm-cache JMH used one 1-second warm-up and three 1-second measurements.

| Metric | Result |
| --- | ---: |
| Append throughput | 3.44M rows/s |
| Offline index build | 1.313 s / 1.52M rows/s |
| Raw message VARCHAR | 408.81 MiB |
| Text index | 282.07 MiB / 69.0% of VARCHAR |
| Rare, 16 days: scan → index | 53.39 → 30.97 ms (1.72× faster) |
| Rare, 1 day: scan → index | 3.46 → 1.44 ms (2.40× faster) |
| Common, 16 days: scan → index | 51.27 → 70.63 ms (0.73×) |
| Common, 1 day: scan → index | 3.27 → 4.13 ms (0.79×) |

These results show two things clearly: the index improves selective-term retrieval and timestamp partition pruning works as intended. Common terms regress because the MVP materializes and sorts every matching posting. A bounded heap or WAND-style top-k path is therefore the next optimization gate, not a reason to reject the append-only MVP direction.

Six standalone partition-index tests pass, covering persistent round trips, `VARCHAR`-memory builds, corruption/staleness rejection, writer lifecycle checks, and BM25 scores against an independent numeric oracle. Core and benchmark sources compile. The indexed SQL integration executes through the benchmark and includes scan/index count-equivalence checks; its `AbstractCairoTest` regression is compiled but local execution is blocked before the test by the existing unrelated native bootstrap failure.

## Limits And Non-Goals

The MVP explicitly does not provide production lifecycle support for:

- WAL / O3 ingestion
- Deduplication
- Parquet / cold partitions
- Snapshot
- Detach / attach

These are known production gaps, not surprises. The current implementation is an offline, append-only index build with safe query fallback semantics.

## Recommendation

Accept Track A as the preferred MVP architecture for further development, with two gates before production consideration:

1. Replace full posting materialization with bounded top-k or WAND-style execution, then repeat warm/cold Linux measurements.
2. Design asynchronous sealed-partition maintenance plus explicit freshness metadata for WAL/O3 and lifecycle events.

Do not add DDL or synchronous ingestion maintenance until those gates demonstrate acceptable common-term cost and write-path overhead.

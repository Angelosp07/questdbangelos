# Track A RFC: append-only native text search MVP

Status: MVP evidence complete; production lifecycle work is intentionally excluded.

## Decision

Use a native immutable inverted-index sidecar per `(partition, VARCHAR column instance)`. Keep `text_match(VARCHAR, STRING)` as the literal substring scan predicate and use the separate table function for ranked retrieval:

```sql
SELECT *
FROM text_search('logs', 'message', 'timeout', '2024-01-01', '2024-02-01', 10);
```

`text_search` returns `(ts, value, score)`, applies the half-open range `[from,to)`, prunes partitions, calculates partition-local BM25 (`k1=1.2`, `b=0.75`), merges candidates by descending score, and returns global top-k. If any selected partition is Parquet or has a missing, stale, or corrupt sidecar, the whole query safely uses the existing scan fallback.

## Storage and build

`OfflineTextIndexBuilder` reads committed native VARCHAR aux/data mappings through `TextColumnIndexer`. `PartitionTextIndexWriter` tokenizes ASCII alphanumeric runs, lowercases ASCII, and atomically publishes a versioned sidecar. The file contains:

- document records: partition row ID and document length;
- a sorted term dictionary: term bytes, document frequency, and postings location;
- postings: partition row ID and term frequency;
- identity: format version, column-name transaction, and partition timestamp.

`PartitionTextIndexReader` validates identity and layout before exposing binary-search term lookup and posting cursors. The MVP is an explicit post-load append-only build; it makes no `TableWriter`, WAL, O3, deduplication, snapshot, attach, or Parquet lifecycle claim.

## Evidence

Corpus: 2,000,000 log rows, 16 daily partitions; `timeout` in 10% and `panic42` in 0.01%. Local JDK 25/macOS arm64 warm-cache JMH used one 1-second warm-up and three 1-second measurements.

| Metric | Result |
|---|---:|
| Append throughput | 3.44M rows/s |
| Offline index build | 1.313 s / 1.52M rows/s |
| Raw message VARCHAR | 408.81 MiB |
| Text index | 282.07 MiB / 69.0% of VARCHAR |
| Rare, 16 days: scan → index | 53.39 → 30.97 ms (1.72× faster) |
| Rare, 1 day: scan → index | 3.46 → 1.44 ms (2.40× faster) |
| Common, 16 days: scan → index | 51.27 → 70.63 ms (0.73×) |
| Common, 1 day: scan → index | 3.27 → 4.13 ms (0.79×) |

The index proves a selective-term speedup and effective timestamp pruning. Common terms regress because the MVP materializes and sorts every matching posting. A bounded heap or WAND-style top-k is the next query optimization, not a prerequisite for the append-only MVP conclusion.

Six standalone partition-index tests pass, covering persistent round trips, VARCHAR-memory builds, corruption/staleness rejection, writer lifecycle checks, and BM25 scores against an independent numeric oracle. Core and benchmark sources compile. The indexed SQL integration executes through the benchmark and includes scan/index count-equivalence checks; its `AbstractCairoTest` regression is compiled but local execution is blocked before the test by the existing unrelated native bootstrap failure.

## Recommendation

Accept Track A as the chosen technical direction for a larger spike, with two gates before production consideration:

1. replace full posting materialization with bounded top-k and repeat warm/cold Linux measurements;
2. design asynchronous sealed-partition maintenance plus explicit freshness metadata for WAL/O3 and lifecycle events.

Do not add DDL or synchronous ingestion maintenance until those gates demonstrate acceptable common-term cost and write-path overhead.

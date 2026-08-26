# Virtual threads: does `@RunOnVirtualThread` help this workload?

Follow-up to `stress-run-report.md`, which found the registry app pod crash-loops under CPU
starvation (not thread-pool exhaustion) at ~300 concurrent REST clients on a 1-CPU limit. Since
virtual threads primarily help when a service is bottlenecked by a *limited number of platform
threads* blocking on I/O - not when it's bottlenecked by raw CPU - the expectation going in was
that virtual threads would **not** meaningfully change that result. This experiment tests that
expectation directly, on the `feat/virtual-threads` branch (based on `feat/perf-main-workflow`).

## Change made

- Added `quarkus-virtual-threads` to `app/pom.xml`.
- Annotated all eight `app/src/main/java/io/apicurio/registry/rest/v3/impl/*ResourceImpl.java`
  classes with `@io.smallrye.common.annotation.RunOnVirtualThread` (class-level), so every REST
  request across the v3 API is dispatched onto a virtual thread instead of the platform worker
  thread pool.

  Note: the annotation is `io.smallrye.common.annotation.RunOnVirtualThread` (from
  `smallrye-common-annotation`, already a transitive dependency via `quarkus-vertx`) - **not**
  `io.quarkus.virtual.threads.RunOnVirtualThread`, which doesn't exist. That package only contains
  the executor/config classes backing the feature. This was confirmed by inspecting the actual
  jar contents of both artifacts before settling on the import, rather than assuming.

## Method

Three load levels were run against **the same topology** (operator + PostgreSQL + Kafka +
Keycloak, default 1 CPU / 1Gi app pod limits), each run twice - once with the virtual-threads
build, once with a build from the same commit *minus* the virtual-threads change (built via
`git stash` to get a true apples-to-apples binary diff) - to isolate the effect of the change
itself from environment noise:

1. **10 users / 60s** (the "everything is fine" baseline level)
2. **100 users / 60s** (a moderate level, chosen specifically because this is roughly where a
   thread-pool-bound service would start showing virtual threads' benefit, if any)
3. **300 users / 180s** (the level that reliably crash-loops the pod - see `stress-run-report.md`)

## Results

### 10 users / 60s - both builds: perfect, no meaningful difference

| | Non-VT (baseline) | Virtual threads |
| --- | --- | --- |
| Failed requests | 0% | 0% |
| Mean response time | 7 ms | 9 ms |
| p95 | 21 ms | 26 ms |
| p99 | 27 ms | 34 ms |
| App pod restarts | 0 | 0 |

Difference here is noise (single-digit millisecond deltas at very low load).

### 100 users / 60s - non-VT was actually *faster*

| | Non-VT | Virtual threads |
| --- | --- | --- |
| Failed requests | 0% | 0% |
| Mean response time | **106 ms** | **177 ms** |
| p95 | **288 ms** | **865 ms** |
| p99 | 3,479 ms | 4,030 ms |
| App pod restarts | 0 | 0 |

Both builds handled 100 concurrent users without a single failure or restart, but the
platform-thread build had noticeably better latency at p95 (288ms vs 865ms - roughly 3x).

### 300 users / 180s - both crash-loop; virtual threads did not prevent it

| | Non-VT (original stress run) | Non-VT (OAuth-fix re-run) | Virtual threads |
| --- | --- | --- | --- |
| Failed requests | 52.77% | 76.34% | 68.21% |
| Mean response time | 9,795 ms | 13,044 ms | 14,254 ms |
| p95 | 26,608 ms | 28,791 ms | 25,384 ms |
| p99 | 30,972 ms | 34,344 ms | 30,636 ms |
| App pod restarts | 5 | 4 | 4 |

All three numbers here (52.77% / 76.34% / 68.21%) should be read as "the pod is in a crash loop
and everything is on fire" rather than as precisely comparable percentages - at this load level,
results are dominated by exactly how many restarts happened to land mid-request, which is highly
run-to-run variable. The important, consistent finding across all three runs: **the app pod
crash-loops under CPU starvation regardless of which threading model is in use** - virtual
threads neither prevented nor meaningfully worsened the crash-loop (4 restarts vs. 4-5 for
non-VT, well within noise).

Kafka/serde path (unaffected by this change, included as a sanity check that the topology itself
was equivalent across runs): 10,093-10,502 produced, 2-4 failures, consistently near-perfect
across all conditions.

## Conclusion

**Virtual threads did not help this workload, and at moderate load may have added measurable
overhead.** This is the expected outcome given the earlier root-cause finding
(`stress-run-report.md`): the registry's failure mode under load is **CPU starvation** (exit 143,
SIGTERM from failed liveness probes; never OOMKilled), not platform-thread-pool exhaustion.
Virtual threads solve the latter problem - letting a service hold open many more *concurrently
blocked* I/O-bound requests than the platform thread pool would otherwise allow - by making
blocking cheap. They do not add CPU capacity, and this workload's REST endpoints (short-lived
Postgres queries against a local, single-node DB, JSON (de)serialization, canonicalization) are
CPU-bound rather than I/O-wait-bound: threads here aren't parked waiting on a slow downstream
call for a long time, they're actively consuming CPU. Under those conditions, virtual threads add
their own scheduling/carrier-thread bookkeeping overhead without buying back anything, which is
consistent with the moderate-load result being slightly worse, not better.

This matches general guidance on when virtual threads help: workloads with high *concurrency* and
long *I/O wait* per request (e.g., calling a slow remote service, or a DB under heavy contention
with long query queues) benefit most. A registry backed by a fast local Postgres instance, doing
short queries, is not that workload - at least not at the request patterns exercised here (single
artifact create/get/search calls, not e.g. artifacts with many versions/references requiring
multiple sequential DB round-trips per request, which might change this calculus).

### Caveats / what this does *not* show

- This was tested against a **single-node PostgreSQL instance with no real network latency**
  (same Minikube node). Virtual threads are more likely to show a benefit in topologies with
  actual I/O wait - e.g. a remote/higher-latency database, or when many requests are waiting on
  the registry's own downstream calls (Kafka lookups, compatibility rule webhooks configured via
  `contracts-rules`, etc.) rather than just Postgres round-trips on localhost-equivalent
  latency.
- Only the default operator resource limits (1 CPU / 1Gi) were tested. Virtual threads' overhead
  is proportionally larger on very small CPU budgets; the calculus could differ with more CPU
  headroom.
- This tested REST-only load; the KafkaSQL storage variant and the Kafka consumer/producer serde
  path (already async/non-blocking-adjacent by construction) weren't part of this comparison.

**Recommendation:** don't merge `@RunOnVirtualThread` onto the hot REST path based on this data
alone - it showed no benefit and a possible moderate-load regression for the workload actually
tested. If virtual threads are revisited, it would be worth targeting a scenario with genuine I/O
wait (e.g. a remote DB with added network latency, or artifact operations that fan out to
multiple downstream calls) rather than the local-Postgres, single-round-trip requests used here.

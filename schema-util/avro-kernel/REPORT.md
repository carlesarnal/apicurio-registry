# Shared schema-registry kernel: Avro feasibility report

## Executive assessment

**A shared, in-process Avro rules kernel is technically feasible without merging the registries.**
The useful boundary is a small Java library for schema parsing/validity and compatibility policy,
with independent product adapters. Storage, registry identity/versioning, APIs and operational
behavior should remain outside that boundary.

This is a technical experiment prompted by the consolidation concern, **not an assessment of
acquisition status, IBM product strategy, or a recommendation to retire either product**. A common
owner does not itself settle upstream governance, contribution rights, support or licensing.

## What was built

The Maven reactor now contains:

1. **`io.apicurio:apicurio-registry-avro-kernel`**: Java 17, no Quarkus/CDI, Kafka, storage, REST,
   Apicurio DTOs or Confluent dependencies. Its only direct production dependency is Apache Avro.
2. **Apicurio adapter**: the existing `AvroContentValidator` and `AvroCompatibilityChecker` now call
   the kernel. Native validity levels and rule exceptions are retained.
3. **`io.apicurio:apicurio-registry-avro-kernel-confluent`**: Confluent's supported `SchemaProvider`
   extension overrides its built-in AVRO provider. Parsing first invokes the kernel; compatibility
   invokes the shared policy engine. The client SPI dependency is `provided`.
4. **An executable two-server demonstration**: Docker Compose starts a real Apicurio server, real
   Confluent Schema Registry and Kafka. Python drives Apicurio **v3** and Confluent **native** REST
   endpoints independently. No reverse proxy, mock server or Apicurio compatibility facade is used.

All seven modes are implemented once: `NONE`, `BACKWARD`, `FORWARD`, `FULL` and the three transitive
variants. The kernel returns message/location differences; each host translates them into its own
exception/response model. Validity is independent of compatibility: `NONE` does not permit invalid
schemas when validity checking is enabled.

### Acceptance criteria

| Criterion | Verification |
|---|---|
| Both server processes load the same kernel implementation | Compare every kernel `.class` SHA-256 extracted from the running containers; require kernel class-load records from both JVMs |
| Avro validity can be shared without forcing one product's default policy | Strict/lax and normalization unit tests; strict-default live demonstration |
| Compatibility direction and all seven modes work | Parameterized unit tests and native registration matrix in both products |
| Transitive history differs from latest-only checking | Old-incompatible/latest-compatible histories, including negative registrations |
| Host boundaries remain separate | Independent native API calls, native errors and persistence checks after rejection |

## Which code was chosen, and why

**Choice: extract Apicurio's small Avro-rule approach into a neutral kernel, backed by Apache Avro;
do not import Confluent's server implementation.** This is an extraction/adaptation, not a verbatim
copy of either product's whole rules subsystem. There is deliberately no new compatibility algorithm:
Apache Avro's reader/writer checker remains the authority.

Inspection showed that both existing implementations already call
`SchemaCompatibility.checkReaderWriterCompatibility(reader, writer)`. Consequently, importing a
large product subsystem would add coupling without improving the actual Avro decision procedure.

| Candidate | Advantages | Disadvantages | Decision |
|---|---|---|---|
| Apicurio rules approach | Small checker; structured message/location differences; explicit validity levels; Apache-2.0 project; no Confluent server dependency needed | Current interfaces refer to Apicurio content/DTO types; reference/parser quirks must be separated | **Use as extraction basis**, remove product types from kernel |
| Confluent `AvroSchema`/client rules | Already supports Confluent representations, metadata, normalization and provider SPI; relevant client source files carry Apache-2.0 headers | Broad `ParsedSchema` surface includes message transformation, tags, metadata and rule sets; unnecessary as kernel API | **Retain in Confluent adapter**, not kernel |
| Confluent server rules/storage classes | Already integrated into Confluent's runtime | Kafka/REST/storage coupling; inspected server file carries Confluent Community License rather than Apache-2.0 | **Do not copy or depend on server code** |
| Apache Avro alone | Neutral, already used by both products, authoritative schema model/checker | Does not define either host's rule activation, version visibility or REST contract | **Shared engine dependency**, with a small explicit policy layer |

The kernel package is currently `io.apicurio.schema.kernel` for repository ownership, not because
its API requires Apicurio. A jointly governed artifact/package can replace it before publication.
The kernel remains under this repository's Apache-2.0 license. Confluent's client dependency and
the separately run Confluent container are not thereby relicensed. This source-level inspection is
not a complete transitive license audit or legal approval.

## What went well

* **The computational overlap is real.** Both products already share the hard Avro algorithm
  upstream. Centralizing parsing policy and seven-mode orchestration required little framework code.
* **No shared service is necessary.** A JAR in each JVM avoids an extra network dependency,
  deployment, consistency model or availability bottleneck.
* **Confluent has an appropriate extension point.** Its server registers custom schema providers
  after defaults, so an AVRO provider can be replaced without editing server sources.
* **Apicurio already separates schema utilities from persistence.** The adapter changes are below
  storage and REST. No SQL, KafkaSQL, GitOps or KubernetesOps implementations were changed.
* **Host differences need not contaminate the kernel.** A boolean default-validation policy and
  host-owned error translation preserve deliberate differences rather than pretending APIs match.
* **Parser state is isolated.** New parsers are created per document/reference attempt. Failed
  reference parses cannot poison retries, and unrelated versions/requests do not share name tables.
* **Confluent's copy paths are handled explicitly.** `copy`, versioned/metadata/tag copies and
  `normalize` retain the kernel-aware subtype; otherwise the first copy could silently revert to
  Confluent's original rules. Lazy history holders are selected and cleared by the adapter.
* **The test setup produces inspectable evidence.** It checks bytecode identity and class provenance,
  not merely two servers returning similar answers. It is connected to the existing non-app shard
  and Verification Gate, rather than a standalone non-blocking CI workflow.

## What went badly / required compromises

### 1. Same Avro algorithm does not mean identical products

Apicurio distinguishes `VALIDITY` activation (`NONE`, `SYNTAX_ONLY`, `FULL`) from compatibility.
Confluent parses schemas as part of its provider lifecycle and independently controls validation of
field defaults. The demo explicitly configures strict defaults on Confluent; it does not change the
adapter's default or claim those native defaults were already equivalent.

Error contracts also differ. The aim is shared decisions, not identical JSON bodies or status codes.
The selected Apicurio 3.3.3 server returns **400** for a validity/compatibility rule violation;
Confluent 8.0.0 returns **409** for incompatibility and **422** for invalid schemas. The live test
asserts these separately rather than accepting a generic “some 4xx” outcome.
Confluent's original rich compatibility diagnostics are not reproduced byte-for-byte: this POC
formats the kernel's location/message pairs rather than its `Difference` rendering. Clients that
parse diagnostic text require an explicit compatibility plan.

### 2. Dependency alignment is part of the work

The chosen released Apicurio 3.3.3 image uses Avro **1.12.2**. Confluent 8.0.0 ships **1.11.4**.
For this controlled experiment the Confluent classpath is prepended with Avro 1.12.2, matching
Apicurio and the Maven kernel build. That is an experimental distribution change, not a supported
Confluent upgrade or proof that all serializers, plugins and workloads tolerate it.

The public kernel API currently exposes `org.apache.avro.Schema`. This is simple and avoids
reparsing compatibility inputs, but ties callers to a common Avro ABI. A production library needs
an agreed Avro release/support policy, or an opaque parsed-schema API and deliberate isolation.
Shading Avro is not a free fix because the Confluent adapter exchanges native Avro schemas.

### 3. Packaging differs substantially

The working branch contains unrelated Quarkus migration work. Instead of making that migration a
prerequisite, the live experiment uses released Apicurio **3.3.3-mutable**, replacing its Avro
utility JAR at the original path. It installs the ordinary kernel JAR in the providers directory
and runs the image's supported re-augmentation command. Confluent consumes the identical kernel
JAR plus the adapter through its classpath/provider config.

This proves reuse but is intentionally **not** release packaging: it combines a released server
with the modified utility compiled from this branch. Normal source builds consume a regular Maven
dependency. A production implementation should rebuild both supported distributions, not replace
JARs inside a released image. An initial fast-jar-only injection failed because its precomputed
classpath index did not know the kernel package; this was resolved with the mutable image's
supported re-augmentation mechanism. Root `.dockerignore` excludes `target/`, so the runner stages a minimal
build context instead of sending the repository to Docker.

### 4. Reference semantics are the next difficult boundary

The kernel accepts **already resolved schema content**, never URLs or registry clients. It resolves
dependencies in local reference maps without depending on their iteration order.

It fails closed if any supplied reference cannot be parsed/resolved. The existing Apicurio accessor
can silently drop invalid/conflicting reference definitions (documented in its tests and #10766).
The kernel therefore intentionally tightens that edge case for validity/compatibility; other
Apicurio parsing/canonicalization paths still use the existing accessor. This is not a complete
behavior-preserving migration or a repository-wide parser unification.

More importantly, Apicurio's current checker supplies one resolved-reference map across the
compatibility operation. That interface does not preserve independently versioned reference graphs
for every historical schema. Confluent's historical parsed schemas retain their own resolved
references. The kernel can compare independently parsed schemas, but cannot repair missing
historical reference context at an adapter boundary. This must be resolved before claiming full
reference-evolution parity.

### 5. Upstream parser exceptions are not a stable invalid-input contract

Narrowing the tests exposed Avro 1.12.2 throwing `NullPointerException` for an unresolved top-level
name (`"MissingType"`), rather than an Avro exception. The kernel now normalizes parser runtime
failures to a specific `InvalidSchemaException` with a stable, non-internal message. Both adapters
can translate it consistently. This finding came from an executed test, not an assumed API contract.

### 6. This is not all of Confluent's “rules”

Here, “schema rules” means **schema validity and schema evolution compatibility**. Confluent
data-contract domain/migration rules, CEL, encryption and message transformation are **not**
implemented by this kernel. Nor are Apicurio integrity rules, canonicalization, IDs, serializers,
authorization, metadata policy or storage. Those require separate overlap studies.

### 7. The adapter favors a safe seam over optimal parsing cost

Confluent's provider first validates through the kernel, then constructs its native `AvroSchema`
and the kernel-aware subtype. Those constructors/canonicalization paths parse again. This avoids
reimplementing Confluent's private representation and metadata handling, but duplicates work.
No throughput/latency claim is made. A production integration should expose a parsed-schema
constructor/delegation seam upstream or otherwise remove redundant parsing after benchmarking.

## Verification results

Original experiment environment: repository baseline `e861b8091e`, branch `feat/quarkus-4-migration`, macOS/aarch64,
OpenJDK 25.0.2 running Maven; new library bytecode targets Java 17. Servers run in their container
JDKs. The pre-existing untracked `SIMPLIFICATION-PLAN.md` was left untouched.

For review, only the POC changes were subsequently transplanted onto `origin/main` at
`c930757fe3` in branch `poc/shared-avro-kernel`; none of the Quarkus migration commits are included.
The focused Maven tests and Checkstyle were rerun there before committing. The live-run fingerprint
below identifies the original tested artifact; it is not a claim that a later rebuild has the same
whole-JAR hash (ZIP metadata can change).

Executed successfully:

```bash
./mvnw -B -T1 package -pl schema-util/avro,schema-util/avro-kernel-confluent -am -Dlocal
./mvnw -B -T1 checkstyle:check -pl schema-util/avro-kernel,schema-util/avro-kernel-confluent,schema-util/avro
./mvnw -B -T1 dependency:tree -pl schema-util/avro-kernel -Dscope=compile
```

* **144 Maven tests passed** across the selected modules and their tested upstream dependencies:
  common 43, schema-util-common 24, kernel 18, Avro utility 56 and Confluent adapter 3. The 26 new
  cases are kernel 18, Apicurio adapter 5 and Confluent adapter 3. Existing tests were not removed
  or weakened. The existing Avro compatibility fixture suite passed as part of this run.
* **Checkstyle passed** for all three changed Java modules (zero enforced violations).
* The first live incompatible registration exposed Apicurio's `LazyContentList` rejecting both
  `subList` and `stream`. The adapter now uses indexed access and has a test double that throws
  for those operations, with exact latest-only/transitive read-count and decision assertions.
* The kernel compile dependency tree contains Apache Avro and its Jackson/Commons Compress
  and SLF4J dependencies, with no Apicurio/Confluent server, Kafka or Quarkus runtime dependency.
* Required test-quality review ran four concurrent detector sweeps and refreshed them against the
  final exception assertions: **10.0/10 pattern score**, all four categories 10.0. This is a
  static pattern-quality result, not an execution result or complete behavioral proof. The review
  identified coverage opportunities: non-empty Confluent tags/rule sets/references, `isNew=false`
  validity policy, distinct lazy-history holder identities and more symmetrical unit matrices.
  The native REST matrix separately exercises addition/removal and every compatibility mode.

### Native two-server result: PASS

Executed on 2026-11-18 after building both POC images:

```bash
python3 schema-util/avro-kernel/poc/run.py --skip-build --skip-image-build
```

**30 scenarios passed against each server: 60 final registration outcomes**, plus setup, rule
configuration and persistence checks. The reuse flags only avoid repeating already completed Maven
and image builds; neither server was mocked or bypassed. The documented command without reuse flags
performs those builds automatically.

| Scenario family | Scenarios per server | Observed outcome |
|---|---:|---|
| Add required field, seven modes | 7 | Accepted only for NONE/FORWARD/FORWARD_TRANSITIVE |
| Remove required field, seven modes | 7 | Accepted only for NONE/BACKWARD/BACKWARD_TRANSITIVE |
| Add field with default, seven modes | 7 | Accepted in every mode |
| Older incompatible/latest compatible history | 6 | Latest-only BACKWARD/FORWARD/FULL accepted; all transitive counterparts rejected |
| Malformed, invalid default, unknown type with compatibility NONE | 3 | Both rejected every invalid candidate; persisted version count stayed exactly 1 |

Both running containers yielded the **same complete kernel JAR SHA-256**:

```text
a97a88911b5db9e9130816f508d7cd02fd5e85bf4bfc26194502479229b19d23
```

All four kernel class hashes also matched exactly. The JVMs reported these class sources:

```text
Apicurio kernel:  /deployments/quarkus-app/providers/kernel.jar
Apicurio Avro:    /deployments/quarkus-app/lib/main/org.apache.avro.avro-1.12.2.jar
Confluent kernel: /opt/kernel/kernel.jar
Confluent Avro:   /opt/kernel/avro.jar
```

Confluent's startup log explicitly registered
`io.apicurio.schema.kernel.confluent.KernelAvroSchemaProvider` after the built-in AVRO provider.
Byte identity and class loading demonstrate deployed wiring; the adapter source/unit tests and
native behavioral checks establish the tested rule paths, not an instrumented invocation trace
for every possible workflow.

Full machine-readable outcomes, hashes, class-loading traces and server logs are saved under
`schema-util/avro-kernel/target/poc/`. That generated directory is gitignored. The runner removed
its containers, network and volumes successfully after the passing run; built images remain local.

### Evidence gaps

Full CI, the complete application suite,
native-image builds, other storage variants, unmodified-server differential baselines and
performance benchmarks have **not** been run. No before/after bug-fix regression claim is made:
this is an extraction/feasibility feature, not a claimed fix for an existing issue.

## Feasibility limits and next steps

**Proceed with a shared library and a jointly maintained conformance suite; do not start with a
merged server.** Confidence is high for the narrow Avro compute boundary, not for unifying whole
registries or claiming production readiness.

Before production adoption:

1. Agree on a small versioned API, governance, release ownership, supported JDK/Avro versions and
   contribution/license policy. Decide which parsing differences are configurable profiles and
   which are conformance bugs.
2. Run an independent differential corpus against **unmodified** supported releases and the
   adapters: archived real schemas, aliases, enum defaults, logical types, recursive unions,
   deeply nested/oversized input, invalid legacy schemas and reference graphs. Record intentional
   changes rather than silently accepting them. Current tests are not that exhaustive corpus.
3. Preserve version-specific resolved references and host history selection (deletion, disabled
   versions, contexts, metadata/version selection and import modes). Extend native REST tests to
   references and Confluent tag/rule-set workflows.
4. Agree on diagnostic stability and ensure API clients cannot depend on internal exception text.
   Benchmark large transitive histories and reference DAGs: this POC holds selected parsed history
   in memory and may retry reference parsing quadratically in unfavorable orders.
5. Rebuild both distributions normally; test their supported runtimes and plugins. Validate
   Apicurio native-image packaging and every supported storage-backed deployment. Storage was not
   changed here, but variant testing is still needed before product adoption.
6. Only then consider JSON Schema/Protobuf kernels. Do not assume the Avro result automatically
   transfers: library differences and compatibility definitions need fresh comparison.

Sharing this core can prevent duplicated bug fixes and compatibility drift while preserving both
products' APIs, ecosystems and deployment choices. It cannot itself guarantee an organizational
decision against consolidation, and it does not justify promising a large maintenance saving:
the common Avro checker is already small; the larger value is one policy contract and test corpus.

## Inspected sources

* Apicurio baseline `e861b8091e`: `schema-util/avro/.../AvroCompatibilityChecker.java`,
  `AvroContentValidator.java`, `AvroParserAccessor.java`, and
  `schema-util/common/.../AbstractCompatibilityChecker.java`.
* [Confluent 8.0.0 AvroSchema](https://github.com/confluentinc/schema-registry/blob/v8.0.0/client/src/main/java/io/confluent/kafka/schemaregistry/avro/AvroSchema.java):
  parser/default policy, pairwise compatibility and copy/normalization surface; Apache-2.0 header.
* [Confluent 8.0.0 AvroSchemaProvider](https://github.com/confluentinc/schema-registry/blob/v8.0.0/client/src/main/java/io/confluent/kafka/schemaregistry/avro/AvroSchemaProvider.java):
  `avro.validate.defaults`, reference resolution and new-schema/normalization behavior.
* [Confluent 8.0.0 ParsedSchema](https://github.com/confluentinc/schema-registry/blob/v8.0.0/client/src/main/java/io/confluent/kafka/schemaregistry/ParsedSchema.java)
  and [CompatibilityChecker](https://github.com/confluentinc/schema-registry/blob/v8.0.0/client/src/main/java/io/confluent/kafka/schemaregistry/CompatibilityChecker.java):
  history order, holders and rule SPI.
* [Confluent 8.0.0 KafkaSchemaRegistry](https://github.com/confluentinc/schema-registry/blob/v8.0.0/core/src/main/java/io/confluent/kafka/schemaregistry/storage/KafkaSchemaRegistry.java):
  custom providers override defaults; inspected source carries Confluent Community License.

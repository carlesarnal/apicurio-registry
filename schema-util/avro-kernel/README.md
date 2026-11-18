# Shared Avro schema-registry kernel — proof of concept

This experiment runs **an Apicurio Registry server and a Confluent Schema Registry server** with
the same Avro validity/compatibility implementation. It is not Apicurio's Confluent-compatible REST
API pretending to be a second product, and there is no remote validation service.

## Modules and boundary

* `schema-util/avro-kernel`: plain Java 17 JAR; Apache Avro is its only direct production dependency.
  Parses schemas with explicitly chosen default validation; resolves already-fetched references;
  computes structured incompatibilities and all seven compatibility modes.
* `schema-util/avro`: existing Apicurio utility module, now adapting validity/compatibility calls to
  the kernel. Keeps Apicurio rule levels, exceptions, reference mapping checks and REST behavior.
* `schema-util/avro-kernel-confluent`: thin Confluent 8.0.0 `SchemaProvider` adapter. Keeps Confluent's
  `AvroSchema` representation, references, metadata, normalization and copy methods. The Confluent
  client SPI is a **provided** dependency, not a dependency of the kernel.

```text
Apicurio v3 REST → Apicurio rules adapter ──┐
                                         ├→ same AvroSchemaKernel → Apache Avro
Confluent REST → custom AVRO provider ─────┘
```

History is chronological, oldest first. `BACKWARD` means the proposed reader can read existing
writer data; `FORWARD` reverses that relation; `FULL` checks both. Transitive variants check every
supplied version. `NONE` disables compatibility, **not validity**. Hosts still decide which versions
are visible (including deletion/state handling), fetch references and translate errors.

## Run

Requirements: JDK 21+, Maven wrapper prerequisites, Python 3, Docker with Compose v2, network access
to Maven repositories and container registries. Run from the repository root:

```bash
./mvnw -B -T1 test -pl schema-util/avro,schema-util/avro-kernel-confluent -am -Dlocal
python3 schema-util/avro-kernel/poc/run.py
```

The runner packages the Maven modules, starts an isolated Kafka broker and both real registries,
checks their native REST APIs, saves evidence, and removes its Compose containers/network/volumes
even after a test failure. It does not touch existing Docker projects. Do not run two copies with
the same Compose project/ports concurrently.

* `--skip-build`: reuse already packaged Maven JARs, not just compiled classes.
* `--skip-image-build`: reuse previously built POC container images (only when Java artifacts are unchanged).
* `--keep`: retain the test environment for inspection, including after failure.
* `KERNEL_APICURIO_PORT` / `KERNEL_CONFLUENT_PORT`: change loopback ports (defaults 18080/18081).

```bash
python3 schema-util/avro-kernel/poc/run.py --keep
docker compose -p avro-kernel-poc -f schema-util/avro-kernel/poc/compose.yaml logs
docker compose -p avro-kernel-poc -f schema-util/avro-kernel/poc/compose.yaml down --volumes
```

The demo explicitly enables strict defaults on Confluent so its demonstration policy matches
Apicurio. The adapter otherwise preserves Confluent's permissive default and its stricter
normalization path. This does **not** change either product's configuration defaults.

## Evidence

`target/poc/` (inside this module, gitignored) contains:

* `results.json`: native REST outcomes and SHA-256 hashes of the kernel JAR and each kernel class,
  extracted from the actual running containers; the runner requires both JAR and class maps to match.
* `apicurio-classes.log` and `confluent-classes.log`: JVM class-loading provenance. The runner
  requires both JVMs to have loaded the kernel and the aligned Avro runtime; inspect the `source:` entries.
* `containers.log`: startup/provider registration and failure diagnostics.

The live matrix covers required-field addition/removal and defaulted-field addition under every
compatibility mode, older-history/transitive differences, malformed schemas, unknown types and
invalid defaults. Rejected invalid versions must not enter persisted history. Unit tests add
numeric promotion direction, aliases, unions, recursive records, reference ordering/parser isolation,
and adapter copy/normalization and lazy-history behavior.

The modules are ordinary reactor members under `schema-util/**`, already classified as Java by
Decide and exercised by the existing non-app test shard. That shard also runs the live test and
uploads its evidence; a failure blocks the existing Verification Gate, not a separate advisory workflow.

## Important POC packaging constraints

The live test uses Apicurio **3.3.3-mutable**, Confluent **8.0.0**, and Avro **1.12.2** in both JVMs.
The branch contains unrelated Quarkus migration work, so the demo intentionally uses a released
Apicurio server. The POC replaces its Avro utility JAR in place with the rebuilt utility, adds the
ordinary kernel JAR to its providers directory, and runs the image's supported `build.sh --prune`
re-augmentation step. Confluent loads the same kernel JAR plus the adapter through its supported
`schema.providers` extension point. No Confluent server sources are patched.

Confluent 8.0.0 normally bundles Avro **1.11.4**. The POC prepends 1.12.2 to its classpath to make
the experiment's engine version identical. This is not a supported Confluent distribution or a
certified Avro upgrade. A normal Apicurio source build consumes the kernel as a regular dependency;
the released-server utility replacement is only for this isolated demonstration.

See [REPORT.md](REPORT.md) for the feasibility assessment, implementation choice, results and gaps.

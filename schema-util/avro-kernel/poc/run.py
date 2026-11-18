#!/usr/bin/env python3
"""Build and exercise two actual registries. No third-party Python packages required."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import urllib.error
import urllib.request
import uuid
import zipfile

ROOT = Path(__file__).resolve().parents[3]
POC = Path(__file__).resolve().parent
TARGET = POC.parent / "target" / "poc"
COMPOSE = ["docker", "compose", "-p", "avro-kernel-poc", "-f", str(POC / "compose.yaml")]


def command(args, **kwargs):
    return subprocess.run(args, cwd=ROOT, check=True, **kwargs)


def request(base, method, path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(base + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            payload = response.read()
            return response.status, json.loads(payload) if payload else None
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())


def expect(actual, status):
    if actual[0] != status:
        raise AssertionError(f"Expected HTTP {status}, got {actual}")
    return actual[1]


def ready(base, path):
    deadline = time.monotonic() + 180
    last = None
    while time.monotonic() < deadline:
        try:
            last = request(base, "GET", path)
            if last[0] == 200:
                return
        except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
            last = str(error)
        time.sleep(1)
    raise RuntimeError(f"Registry did not become ready: {base}: {last}")


def exercise(apicurio, confluent):
    results = []
    prefix = "kernel-" + uuid.uuid4().hex[:12]
    av3 = "/apis/registry/v3"

    def schema(fields):
        return json.dumps({"type": "record", "name": "R", "fields": fields})

    empty = schema([])
    required = schema([{"name": "x", "type": "int"}])
    optional = schema([{"name": "x", "type": "int", "default": 0}])
    invalid_default = schema([{"name": "x", "type": "int", "default": "bad"}])

    def registration(host, name, content, first=False):
        if host == "confluent":
            return request(confluent, "POST", f"/subjects/{name}/versions",
                           {"schemaType": "AVRO", "schema": content})
        version = {"content": {"content": content, "contentType": "application/json"}}
        if first:
            return request(apicurio, "POST", av3 + "/groups/default/artifacts",
                           {"artifactId": name, "artifactType": "AVRO", "firstVersion": version})
        return request(apicurio, "POST", av3 + f"/groups/default/artifacts/{name}/versions", version)

    def config(host, name, level):
        if host == "confluent":
            expect(request(confluent, "PUT", f"/config/{name}", {"compatibility": level}), 200)
        else:
            expect(request(apicurio, "POST", av3 + f"/groups/default/artifacts/{name}/rules",
                           {"ruleType": "VALIDITY", "config": "FULL"}), 204)
            expect(request(apicurio, "POST", av3 + f"/groups/default/artifacts/{name}/rules",
                           {"ruleType": "COMPATIBILITY", "config": level}), 204)

    # Required field addition: backward fails, forward succeeds. Removal reverses direction.
    cases = [("add-required", empty, required, {"NONE", "FORWARD", "FORWARD_TRANSITIVE"}),
             ("remove-required", required, empty, {"NONE", "BACKWARD", "BACKWARD_TRANSITIVE"}),
             ("add-default", empty, optional, {"NONE", "BACKWARD", "BACKWARD_TRANSITIVE",
                                               "FORWARD", "FORWARD_TRANSITIVE", "FULL", "FULL_TRANSITIVE"})]
    levels = ["NONE", "BACKWARD", "BACKWARD_TRANSITIVE", "FORWARD", "FORWARD_TRANSITIVE",
              "FULL", "FULL_TRANSITIVE"]
    for case, old, new, allowed in cases:
        for level in levels:
            row = {"case": case, "level": level, "compatible": level in allowed}
            for host in ("apicurio", "confluent"):
                name = f"{prefix}-{case}-{level.lower()}"
                expect(registration(host, name, old, True), 200)
                config(host, name, level)
                expected = 200 if level in allowed else (400 if host == "apicurio" else 409)
                response = registration(host, name, new)
                print(f"{case} {level} {host}: HTTP {response[0]}", flush=True)
                expect(response, expected)
                row[host] = response[0]
            results.append(row)

    # Force a history whose oldest version is incompatible but latest is compatible.
    history_old = schema([{"name": "x", "type": "int"}])
    history_latest = schema([{"name": "x", "type": "string"}])
    history_proposed = json.loads(history_latest)
    history_proposed["doc"] = "new version"
    for level in levels[1:]:
        row = {"case": "older-history", "level": level}
        for host in ("apicurio", "confluent"):
            name = f"{prefix}-history-{level.lower()}"
            expect(registration(host, name, history_old, True), 200)
            config(host, name, "NONE")
            expect(registration(host, name, history_latest), 200)
            if host == "confluent":
                expect(request(confluent, "PUT", f"/config/{name}", {"compatibility": level}), 200)
            else:
                expect(request(apicurio, "PUT", av3 + f"/groups/default/artifacts/{name}/rules/COMPATIBILITY",
                               {"ruleType": "COMPATIBILITY", "config": level}), 200)
            # Different doc avoids the duplicate-schema shortcut while preserving Avro type.
            response = registration(host, name, json.dumps(history_proposed))
            expected = (400 if host == "apicurio" else 409) if "TRANSITIVE" in level else 200
            expect(response, expected)
            row[host] = response[0]
        results.append(row)

    for label, content in (("malformed", "{"), ("invalid-default", invalid_default),
                           ("unknown-type", '"MissingType"')):
        row = {"case": label, "level": "NONE"}
        for host in ("apicurio", "confluent"):
            name = f"{prefix}-{label}"
            expect(registration(host, name, empty, True), 200)
            config(host, name, "NONE")
            response = registration(host, name, content)
            expect(response, 400 if host == "apicurio" else 422)
            # Rejected versions must not enter persisted history.
            path = (av3 + f"/groups/default/artifacts/{name}/versions" if host == "apicurio"
                    else f"/subjects/{name}/versions")
            versions = expect(request(apicurio if host == "apicurio" else confluent, "GET", path), 200)
            count = versions["count"] if host == "apicurio" else len(versions)
            if count != 1:
                raise AssertionError(f"Invalid schema persisted: {host}: {versions}")
            row[host] = response[0]
        results.append(row)
    return results


def evidence():
    hashes = {}
    jar_hashes = {}
    sources = {}
    for service, path in (("apicurio", "/deployments/quarkus-app/providers/kernel.jar"),
                          ("confluent", "/opt/kernel/kernel.jar")):
        destination = TARGET / f"{service}.jar"
        command(COMPOSE + ["cp", f"{service}:{path}", str(destination)])
        command(COMPOSE + ["cp", f"{service}:/tmp/kernel-classes.log", str(TARGET / f"{service}-classes.log")])
        with zipfile.ZipFile(destination) as jar:
            hashes[service] = {name: hashlib.sha256(jar.read(name)).hexdigest()
                               for name in jar.namelist()
                               if name.startswith("io/apicurio/schema/kernel/") and name.endswith(".class")}
        jar_hashes[service] = hashlib.sha256(destination.read_bytes()).hexdigest()
        log = (TARGET / f"{service}-classes.log").read_text()
        if "io.apicurio.schema.kernel.AvroSchemaKernel source:" not in log:
            raise AssertionError(f"Shared kernel not loaded in {service}")
        if "org.apache.avro.Schema source:" not in log:
            raise AssertionError(f"Avro runtime not traced in {service}")
        sources[service] = [line for line in log.splitlines()
                            if "io.apicurio.schema.kernel.AvroSchemaKernel source:" in line
                            or "org.apache.avro.Schema source:" in line]
        avro_path = "org.apache.avro.avro-1.12.2.jar" if service == "apicurio" else "/opt/kernel/avro.jar"
        if not any("org.apache.avro.Schema source:" in line and avro_path in line for line in sources[service]):
            raise AssertionError(f"Expected aligned Avro runtime not loaded in {service}: {sources[service]}")
    if not hashes["apicurio"] or hashes["apicurio"] != hashes["confluent"]:
        raise AssertionError("Registries did not load identical kernel bytecode")
    if jar_hashes["apicurio"] != jar_hashes["confluent"]:
        raise AssertionError("Registries did not receive the identical kernel JAR")
    return {"kernel_class_sha256": hashes, "kernel_jar_sha256": jar_hashes, "class_sources": sources}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--skip-build", action="store_true", help="Use already packaged Maven artifacts")
    parser.add_argument("--skip-image-build", action="store_true", help="Reuse previously built POC images")
    parser.add_argument("--keep", action="store_true", help="Leave the isolated Compose project running")
    args = parser.parse_args()
    TARGET.mkdir(parents=True, exist_ok=True)
    (TARGET / "results.json").unlink(missing_ok=True)
    if not args.skip_build:
        command(["./mvnw", "-B", "-T1", "package", "-pl", "schema-util/avro,schema-util/avro-kernel-confluent",
                 "-am", "-Dlocal", "-DskipTests"])
    command(["./mvnw", "-B", "-T1", "-pl", "schema-util/avro-kernel", "dependency:copy",
             "-Dartifact=org.apache.avro:avro:1.12.2", "-DoutputDirectory=target/runtime"])
    # Stage only the experimental artifacts: repository .dockerignore excludes target/.
    context = TARGET / "context"
    context.mkdir(exist_ok=True)
    for source, name in (
            (ROOT / "schema-util/avro/target/apicurio-registry-schema-util-avro-3.4.0-SNAPSHOT.jar", "util.jar"),
            (POC.parent / "target/apicurio-registry-avro-kernel-3.4.0-SNAPSHOT.jar", "kernel.jar"),
            (ROOT / "schema-util/avro-kernel-confluent/target/apicurio-registry-avro-kernel-confluent-3.4.0-SNAPSHOT.jar", "adapter.jar"),
            (POC.parent / "target/runtime/avro-1.12.2.jar", "avro.jar"),
            (POC / "Dockerfile.apicurio", "Dockerfile.apicurio"),
            (POC / "Dockerfile.confluent", "Dockerfile.confluent")):
        shutil.copyfile(source, context / name)
    try:
        command(COMPOSE + ["up", "-d"] + ([] if args.skip_image_build else ["--build"]))
        apicurio = "http://127.0.0.1:" + os.environ.get("KERNEL_APICURIO_PORT", "18080")
        confluent = "http://127.0.0.1:" + os.environ.get("KERNEL_CONFLUENT_PORT", "18081")
        ready(apicurio, "/apis/registry/v3/system/info")
        ready(confluent, "/subjects")
        results = exercise(apicurio, confluent)
        report = {"results": results, **evidence()}
        (TARGET / "results.json").write_text(json.dumps(report, indent=2) + "\n")
        print(f"PASS: {len(results)} scenarios through both registry instances. Evidence: {TARGET}")
    finally:
        with (TARGET / "containers.log").open("w") as log:
            subprocess.run(COMPOSE + ["logs", "--no-color"], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
        if not args.keep:
            command(COMPOSE + ["down", "--volumes", "--remove-orphans"])


if __name__ == "__main__":
    main()

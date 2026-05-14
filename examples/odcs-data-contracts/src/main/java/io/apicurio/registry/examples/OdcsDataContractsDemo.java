package io.apicurio.registry.examples;

import io.apicurio.registry.client.RegistryClientFactory;
import io.apicurio.registry.client.common.DefaultVertxInstance;
import io.apicurio.registry.client.common.RegistryClientOptions;
import io.apicurio.registry.rest.client.RegistryClient;
import io.apicurio.registry.rest.client.models.CreateArtifact;
import io.apicurio.registry.rest.client.models.CreateVersion;
import io.apicurio.registry.rest.client.models.OdcsContractSummary;
import io.apicurio.registry.rest.client.models.VersionContent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Demonstrates ODCS Data Contracts with Apicurio Registry.
 *
 * Prerequisites:
 *   1. Start Apicurio Registry with contracts enabled:
 *      docker run -p 8080:8080 \
 *        -e APICURIO_FEATURES_EXPERIMENTAL_ENABLED=true \
 *        -e APICURIO_CONTRACTS_ENABLED=true \
 *        quay.io/apicurio/apicurio-registry:latest-snapshot
 *
 *   2. Run this example:
 *      mvn exec:java -Dexec.mainClass="io.apicurio.registry.examples.OdcsDataContractsDemo"
 *
 * This example walks through the complete ODCS data contract lifecycle:
 *   1. Register an Avro schema artifact
 *   2. Submit an ODCS v3.1 data contract referencing the schema
 *   3. Verify the contract was projected (labels, rules, tags)
 *   4. List contracts in the group
 *   5. Export the contract back as ODCS YAML
 *   6. Check the quality score
 *   7. Promote through deployment stages (DEV -> STAGE)
 *   8. Clean up
 */
public class OdcsDataContractsDemo {

    private static final String REGISTRY_URL = "http://localhost:8080/apis/registry/v3";
    private static final String GROUP_ID = "odcs-example";
    private static final String ARTIFACT_ID = "OrderEvent";

    private static final String AVRO_SCHEMA = """
            {
              "type": "record",
              "name": "OrderEvent",
              "namespace": "com.example.orders",
              "fields": [
                {"name": "orderId", "type": "string"},
                {"name": "customerEmail", "type": "string", "tags": ["PII", "EMAIL"]},
                {"name": "totalAmount", "type": "double"}
              ]
            }
            """;

    public static void main(String[] args) {
        RegistryClient client = RegistryClientFactory.create(
                RegistryClientOptions.create(REGISTRY_URL));

        try {
            System.out.println("=== ODCS Data Contracts Demo ===\n");

            // Step 1: Register the schema artifact
            System.out.println("1. Registering Avro schema artifact...");
            registerSchema(client);
            System.out.println("   Schema registered: " + GROUP_ID + "/" + ARTIFACT_ID);

            // Step 2: Submit the ODCS contract
            System.out.println("\n2. Submitting ODCS data contract...");
            String contractYaml = loadContractYaml();
            String submitResponse = httpPost(
                    REGISTRY_URL + "/groups/" + GROUP_ID + "/contracts",
                    "application/x-yaml",
                    contractYaml);
            System.out.println("   Contract submitted. Response:");
            System.out.println("   " + submitResponse);

            // Step 3: List contracts in the group
            System.out.println("\n3. Listing contracts in group '" + GROUP_ID + "'...");
            List<OdcsContractSummary> contracts = client.groups()
                    .byGroupId(GROUP_ID)
                    .contracts()
                    .get();
            if (contracts != null) {
                for (OdcsContractSummary summary : contracts) {
                    System.out.println("   - Contract: " + summary.getContractId()
                            + " (" + summary.getName() + ")");
                }
            }

            // Step 4: Get the contract metadata
            System.out.println("\n4. Getting contract metadata...");
            String metadata = httpGet(
                    REGISTRY_URL + "/groups/" + GROUP_ID + "/artifacts/" + ARTIFACT_ID
                            + "/contract/metadata");
            System.out.println("   Metadata: " + metadata);

            // Step 5: Export the contract as ODCS YAML
            System.out.println("\n5. Exporting contract as ODCS YAML...");
            String exported = httpGet(
                    REGISTRY_URL + "/groups/" + GROUP_ID + "/artifacts/" + ARTIFACT_ID
                            + "/contract/export");
            System.out.println("   Exported YAML (first 200 chars):");
            System.out.println("   " + exported.substring(0, Math.min(200, exported.length())) + "...");

            // Step 6: Check quality score
            System.out.println("\n6. Checking quality score...");
            String quality = httpGet(
                    REGISTRY_URL + "/groups/" + GROUP_ID + "/artifacts/" + ARTIFACT_ID
                            + "/contract/quality?contractId=orders-contract");
            System.out.println("   Quality: " + quality);

            // Step 7: Promote through stages
            System.out.println("\n7. Promoting contract through stages...");
            String promoteDevResponse = httpPost(
                    REGISTRY_URL + "/groups/" + GROUP_ID + "/artifacts/" + ARTIFACT_ID
                            + "/contract/promote",
                    "application/json",
                    "{\"contractId\":\"orders-contract\",\"targetStage\":\"DEV\"}");
            System.out.println("   Promoted to DEV: " + promoteDevResponse);

            String promoteStageResponse = httpPost(
                    REGISTRY_URL + "/groups/" + GROUP_ID + "/artifacts/" + ARTIFACT_ID
                            + "/contract/promote",
                    "application/json",
                    "{\"contractId\":\"orders-contract\",\"targetStage\":\"STAGE\"}");
            System.out.println("   Promoted to STAGE: " + promoteStageResponse);

            // Step 8: Clean up
            System.out.println("\n8. Cleaning up...");
            httpDelete(REGISTRY_URL + "/groups/" + GROUP_ID + "/contracts/orders-contract");
            client.groups().byGroupId(GROUP_ID).artifacts().byArtifactId(ARTIFACT_ID).delete();
            System.out.println("   Cleaned up contract and schema artifact.");

            System.out.println("\n=== Demo complete! ===");

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            DefaultVertxInstance.close();
        }
    }

    private static void registerSchema(RegistryClient client) {
        CreateArtifact createArtifact = new CreateArtifact();
        createArtifact.setArtifactId(ARTIFACT_ID);
        createArtifact.setArtifactType("AVRO");
        CreateVersion firstVersion = new CreateVersion();
        VersionContent content = new VersionContent();
        content.setContent(AVRO_SCHEMA);
        content.setContentType("application/json");
        firstVersion.setContent(content);
        createArtifact.setFirstVersion(firstVersion);
        client.groups().byGroupId(GROUP_ID).artifacts().post(createArtifact);
    }

    private static String loadContractYaml() throws IOException {
        try (var stream = OdcsDataContractsDemo.class.getResourceAsStream("/order-contract.yaml")) {
            if (stream == null) {
                throw new IOException("order-contract.yaml not found in classpath");
            }
            String yaml = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .lines().collect(Collectors.joining("\n"));
            return yaml
                    .replace("${GROUP_ID}", GROUP_ID)
                    .replace("${ARTIFACT_ID}", ARTIFACT_ID);
        }
    }

    private static String httpPost(String url, String contentType, String body) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", contentType);
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn);
    }

    private static String httpGet(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("GET");
        return readResponse(conn);
    }

    private static void httpDelete(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("DELETE");
        conn.getResponseCode();
        conn.disconnect();
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        var stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String response = reader.lines().collect(Collectors.joining("\n"));
            if (code >= 400) {
                throw new IOException("HTTP " + code + ": " + response);
            }
            return response;
        } finally {
            conn.disconnect();
        }
    }
}

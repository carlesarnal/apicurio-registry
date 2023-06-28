/*
 * Copyright 2021 Red Hat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.apicurio.tests.dbupgrade;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.apicurio.registry.rest.client.RegistryClient;
import io.apicurio.registry.rest.client.RegistryClientFactory;
import io.apicurio.registry.rest.v2.beans.ArtifactMetaData;
import io.apicurio.registry.rest.v2.beans.ArtifactReference;
import io.apicurio.registry.rest.v2.beans.Rule;
import io.apicurio.registry.rest.v2.beans.VersionMetaData;
import io.apicurio.registry.types.ArtifactType;
import io.apicurio.registry.types.RuleType;
import io.apicurio.registry.utils.IoUtil;
import io.apicurio.registry.utils.tests.SimpleDisplayName;
import io.apicurio.tests.ApicurioV2BaseIT;
import io.apicurio.tests.common.Constants;
import io.apicurio.tests.common.RegistryFacade;
import io.apicurio.tests.common.RegistryStorageType;
import io.apicurio.tests.common.interfaces.TestSeparator;
import io.apicurio.tests.common.utils.RegistryUtils;
import io.apicurio.tests.multitenancy.MultitenancySupport;
import io.apicurio.tests.utils.CustomTestsUtils.ArtifactData;
import org.apache.commons.codec.digest.DigestUtils;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestInstance.Lifecycle;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.RemoteDockerImage;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.apicurio.tests.utils.CustomTestsUtils.createArtifact;
import static io.apicurio.tests.utils.CustomTestsUtils.createArtifactWithReferences;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Note this test does not extend any base class
 *
 * @author Fabian Martinez
 */
@DisplayNameGeneration(SimpleDisplayName.class)
@TestInstance(Lifecycle.PER_CLASS)
@Tag(Constants.DB_UPGRADE)
@Tag(Constants.SQL)
public class SqlStorageUpgradeIT implements TestSeparator, Constants {

    private static final String ARTIFACT_CONTENT = "{\"name\":\"redhat\"}";
    private static final String REFERENCE_CONTENT = "{\"name\":\"ibm\"}";

    private static final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void testStorageUpgrade() throws Exception {

        Path logsPath = RegistryUtils.getLogsPath(getClass(), "testStorageUpgrade");
        RegistryFacade facade = RegistryFacade.getInstance();

        try {

            Map<String, String> appEnv = facade.initRegistryAppEnv();

            //runs all required infra except for the registry
            facade.runMultitenancyInfra(appEnv);

            appEnv.put("QUARKUS_HTTP_PORT", "8081");

            String oldRegistryName = "registry-sql-dbv2";
            var container = new GenericContainer<>(new RemoteDockerImage(DockerImageName.parse("quay.io/apicurio/apicurio-registry-sql:2.1.0.Final")));
            container.setNetworkMode("host");
            facade.runContainer(appEnv, oldRegistryName, container);
            facade.waitForRegistryReady();

            MultitenancySupport mt = new MultitenancySupport();

            List<TenantData> data = loadData(mt);

            verifyData(data);

            facade.stopProcess(logsPath, oldRegistryName);

            facade.runRegistry(appEnv, "sql-dblatest", "8081");
            facade.waitForRegistryReady();

            verifyData(data);

            createMoreArtifacts(data);

            verifyData(data);

        } finally {
            facade.stopAndCollectLogs(logsPath);
        }

    }

    @Test
    public void testStorageUpgradeProtobufUpgraderSql() throws Exception {
        testStorageUpgradeProtobufUpgrader("protobufCanonicalHashSql", RegistryStorageType.sql);
    }

    public void testStorageUpgradeProtobufUpgrader(String testName, RegistryStorageType storage) throws Exception {

        RegistryStorageType previousStorageValue = RegistryUtils.REGISTRY_STORAGE;
        RegistryUtils.REGISTRY_STORAGE = storage;

        Path logsPath = RegistryUtils.getLogsPath(getClass(), testName);
        RegistryFacade facade = RegistryFacade.getInstance();

        try {

            Map<String, String> appEnv = facade.initRegistryAppEnv();

            //runs all required infra except for the registry
            facade.deployStorage(appEnv, storage);

            appEnv.put("QUARKUS_HTTP_PORT", "8081");

            String oldRegistryName = "registry-dbv4";
            String image = "quay.io/apicurio/apicurio-registry-sql:2.1.2.Final";
            if (storage == RegistryStorageType.kafkasql) {
                image = "quay.io/apicurio/apicurio-registry-kafkasql:2.1.2.Final";
            }
            var container = new GenericContainer<>(new RemoteDockerImage(DockerImageName.parse(image)));
            container.setNetworkMode("host");
            facade.runContainer(appEnv, oldRegistryName, container);
            facade.waitForRegistryReady();

            //

            var registryClient = RegistryClientFactory.create("http://localhost:8081/");

            createArtifact(registryClient, ArtifactType.AVRO, ApicurioV2BaseIT.resourceToString("artifactTypes/" + "avro/multi-field_v1.json"));
            createArtifact(registryClient, ArtifactType.JSON, ApicurioV2BaseIT.resourceToString("artifactTypes/" + "jsonSchema/person_v1.json"));

            String test1content = ApicurioV2BaseIT.resourceToString("artifactTypes/" + "protobuf/tutorial_v1.proto");
            ArtifactData protoData = createArtifact(registryClient, ArtifactType.PROTOBUF, test1content);

            //verify search with canonicalize returns the expected artifact metadata
            var versionMetadata = registryClient.getArtifactVersionMetaDataByContent(null, protoData.meta.getId(), true, null, IoUtil.toStream(test1content));
            assertEquals(protoData.meta.getContentId(), versionMetadata.getContentId());

            assertEquals(3, registryClient.listArtifactsInGroup(null).getCount());

            //

            facade.stopProcess(logsPath, oldRegistryName);

            facade.runRegistry(appEnv, "registry-dblatest", "8081");
            facade.waitForRegistryReady();

            //

            var searchResults = registryClient.listArtifactsInGroup(null);
            assertEquals(3, searchResults.getCount());

            var protobufs = searchResults.getArtifacts().stream()
                    .filter(ar -> ar.getType().equals(ArtifactType.PROTOBUF))
                    .collect(Collectors.toList());

            assertEquals(1, protobufs.size());
            var protoMetadata = registryClient.getArtifactMetaData(protobufs.get(0).getGroupId(), protobufs.get(0).getId());
            var content = registryClient.getContentByGlobalId(protoMetadata.getGlobalId());

            //search with canonicalize
            versionMetadata = registryClient.getArtifactVersionMetaDataByContent(protobufs.get(0).getGroupId(), protobufs.get(0).getId(), true, null, content);
            assertEquals(protoData.meta.getContentId(), versionMetadata.getContentId());

            //search with canonicalize
            versionMetadata = registryClient.getArtifactVersionMetaDataByContent(protobufs.get(0).getGroupId(), protobufs.get(0).getId(), true, null, IoUtil.toStream(test1content));
            assertEquals(protoData.meta.getContentId(), versionMetadata.getContentId());

            //search without canonicalize
            versionMetadata = registryClient.getArtifactVersionMetaDataByContent(protobufs.get(0).getGroupId(), protobufs.get(0).getId(), false, null, IoUtil.toStream(test1content));
            assertEquals(protoData.meta.getContentId(), versionMetadata.getContentId());

            //create one more protobuf artifact and verify
            String test2content = ApicurioV2BaseIT.resourceToString("artifactTypes/" + "protobuf/tutorial_v2.proto");
            protoData = createArtifact(registryClient, ArtifactType.PROTOBUF, test2content);
            versionMetadata = registryClient.getArtifactVersionMetaDataByContent(null, protoData.meta.getId(), true, null, IoUtil.toStream(test2content));
            assertEquals(protoData.meta.getContentId(), versionMetadata.getContentId());

            //assert total num of artifacts
            assertEquals(4, registryClient.listArtifactsInGroup(null).getCount());


        } finally {
            try {
                facade.stopAndCollectLogs(logsPath);
            } finally {
                RegistryUtils.REGISTRY_STORAGE = previousStorageValue;
            }
        }

    }

    @Test
    public void testStorageUpgradeReferencesContentHash() throws Exception {
        testStorageUpgradeReferencesContentHashUpgrader("referencesContentHash", RegistryStorageType.sql);
    }

    public void testStorageUpgradeReferencesContentHashUpgrader(String testName, RegistryStorageType storage) throws Exception {

        RegistryStorageType previousStorageValue = RegistryUtils.REGISTRY_STORAGE;
        RegistryUtils.REGISTRY_STORAGE = storage;

        Path logsPath = RegistryUtils.getLogsPath(getClass(), testName);
        RegistryFacade facade = RegistryFacade.getInstance();

        try {

            Map<String, String> appEnv = facade.initRegistryAppEnv();

            //runs all required infra except for the registry
            facade.deployStorage(appEnv, storage);

            appEnv.put("QUARKUS_HTTP_PORT", "8081");

            String oldRegistryName = "registry-dbv4";
            String image = "quay.io/apicurio/apicurio-registry-sql:2.4.1.Final";
            if (storage == RegistryStorageType.kafkasql) {
                image = "quay.io/apicurio/apicurio-registry-kafkasql:2.4.1.Final";
            }
            var container = new GenericContainer<>(new RemoteDockerImage(DockerImageName.parse(image)));
            container.setNetworkMode("host");
            facade.runContainer(appEnv, oldRegistryName, container);
            facade.waitForRegistryReady();

            //
            var registryClient = RegistryClientFactory.create("http://localhost:8081/");


            final ArtifactData artifact = createArtifact(registryClient, ArtifactType.JSON, REFERENCE_CONTENT);

            //Create a second artifact referencing the first one, the hash will be the same using version 2.4.1.Final.
            var artifactReference = new ArtifactReference();

            artifactReference.setName("testReference");
            artifactReference.setArtifactId(artifact.meta.getId());
            artifactReference.setGroupId(artifact.meta.getGroupId());
            artifactReference.setVersion(artifact.meta.getVersion());

            var artifactReferences = List.of(artifactReference);

            String artifactId = UUID.randomUUID().toString();

            final ArtifactData artifactWithReferences = createArtifactWithReferences(artifactId, registryClient, ArtifactType.AVRO, ARTIFACT_CONTENT, artifactReferences);

            String calculatedHash = DigestUtils.sha256Hex(ARTIFACT_CONTENT);

            //Assertions
            //The artifact hash is calculated without using references
            assertEquals(calculatedHash, artifactWithReferences.contentHash);

            facade.stopProcess(logsPath, oldRegistryName);

            facade.runRegistry(appEnv, "registry-dblatest", "8081");
            facade.waitForRegistryReady();

            //Finally, if we try to create the same artifact with the same references, no new version will be created and the same ids are used.
            ArtifactData upgradedArtifact = createArtifactWithReferences(artifactId, registryClient, ArtifactType.AVRO, ARTIFACT_CONTENT, artifactReferences);
            assertEquals(artifactWithReferences.meta.getGlobalId(), upgradedArtifact.meta.getGlobalId());
            assertEquals(artifactWithReferences.meta.getContentId(), upgradedArtifact.meta.getContentId());

        } finally {
            try {
                facade.stopAndCollectLogs(logsPath);
            } finally {
                RegistryUtils.REGISTRY_STORAGE = previousStorageValue;
            }
        }

    }
}

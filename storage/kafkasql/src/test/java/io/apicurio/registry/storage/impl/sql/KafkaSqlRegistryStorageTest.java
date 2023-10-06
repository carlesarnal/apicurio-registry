/*
 * Copyright 2020 Red Hat
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

package io.apicurio.registry.storage.impl.sql;

import javax.inject.Inject;

import io.apicurio.registry.noprofile.storage.AbstractRegistryStorageTest;
import io.apicurio.registry.rest.client.RegistryClient;
import io.apicurio.registry.rest.client.RegistryClientFactory;
import io.apicurio.registry.rest.v2.beans.ArtifactMetaData;
import io.apicurio.registry.rest.v2.beans.ArtifactReference;
import io.apicurio.registry.rest.v2.beans.IfExists;
import io.apicurio.registry.storage.RegistryStorage;
import io.apicurio.registry.storage.impl.kafkasql.KafkaSqlRegistryStorage;
import io.apicurio.registry.storage.impl.kafkasql.KafkaSqlUpgrader;
import io.apicurio.registry.types.ArtifactType;
import io.apicurio.registry.types.ContentTypes;
import io.apicurio.registry.utils.IoUtil;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * @author eric.wittmann@gmail.com
 */
@QuarkusTest
public class KafkaSqlRegistryStorageTest extends AbstractRegistryStorageTest {

    @TestHTTPResource
    public String testEndpoint;

    private static String schema1 = "syntax = \"proto3\";\n" +
            "package test.mma;\n" +
            "\n" +
            "import \"test.mma2.proto\";\n" +
            "\n" +
            "message OtherRecord1 {\n" +
            "  string f1 = 1;\n" +
            "  OtherRecord2 f2 = 2;\n" +
            "}";

    private static String schema2 = "syntax = \"proto3\";\n" +
            "package test.mma;\n" +
            "\n" +
            "import \"test.mma3.proto\";\n" +
            "\n" +
            "message OtherRecord2 {\n" +
            "  string f1 = 1;\n" +
            "  OtherRecord3 f2 = 2;\n" +
            "  OtherRecord4 f1 = 1;\n" +
            "}";

    private static String schema3 = "syntax = \"proto3\";\n" +
            "package test.mma;\n" +
            "\n" +
            "import \"test.mma4.proto\";\n" +
            "\n" +
            "message OtherRecord3 {\n" +
            "  string f1 = 1;\n" +
            "  OtherRecord4 f2 = 2;\n" +
            "}";

    private static String schema4 = "syntax = \"proto3\";\n" +
            "package test.mma;\n" +
            "\n" +
            "import \"test.mma5.proto\";\n" +
            "\n" +
            "message OtherRecord4 {\n" +
            "  string f1 = 1;\n" +
            "  OtherRecord5 f2 = 2;\n" +
            "}";

    private static String schema5 = "syntax = \"proto3\";\n" +
            "\n" +
            "package test.mma;\n" +
            "\n" +
            "message OtherRecord5 {\n" +
            "\tint32 other_id = 1;\n" +
            "}";
    
    @Inject
    KafkaSqlRegistryStorage storage;

    @Inject
    KafkaSqlUpgrader upgrader;
    
    /**
     * @see AbstractRegistryStorageTest#storage()
     */
    @Override
    protected RegistryStorage storage() {
        return storage;
    }

    @Test
    public void testUpgraderTime() {

        prepareData();

        upgrader.upgrade();

    }

    private void prepareData() {
        ArtifactMetaData schema5Meta = createArtifactWithReferences(schema5, "protoTest", UUID.randomUUID().toString(), Collections.emptyList());

        ArtifactReference artifactReference = new ArtifactReference();
        artifactReference.setName("test.mma.OtherRecord5");
        artifactReference.setArtifactId(schema5Meta.getId());
        artifactReference.setGroupId(schema5Meta.getGroupId());
        artifactReference.setVersion(schema5Meta.getVersion());

        ArtifactMetaData schema4Meta = createArtifactWithReferences(schema4, "protoTest", UUID.randomUUID().toString(), List.of(artifactReference));

        artifactReference = new ArtifactReference();
        artifactReference.setName("test.mma.OtherRecord4");
        artifactReference.setArtifactId(schema4Meta.getId());
        artifactReference.setGroupId(schema4Meta.getGroupId());
        artifactReference.setVersion(schema4Meta.getVersion());

        ArtifactMetaData schema3Meta = createArtifactWithReferences(schema3, "protoTest", UUID.randomUUID().toString(), List.of(artifactReference));

        artifactReference = new ArtifactReference();
        artifactReference.setName("test.mma.OtherRecord3");
        artifactReference.setArtifactId(schema3Meta.getId());
        artifactReference.setGroupId(schema3Meta.getGroupId());
        artifactReference.setVersion(schema3Meta.getVersion());

        ArtifactReference artifactReference2 = new ArtifactReference();
        artifactReference2.setName("test.mma.OtherRecord4");
        artifactReference2.setArtifactId(schema4Meta.getId());
        artifactReference2.setGroupId(schema4Meta.getGroupId());
        artifactReference2.setVersion(schema4Meta.getVersion());

        ArtifactMetaData schema2Meta = createArtifactWithReferences(schema2, "protoTest", UUID.randomUUID().toString(), List.of(artifactReference, artifactReference2));

        artifactReference = new ArtifactReference();
        artifactReference.setName("test.mma.OtherRecord2");
        artifactReference.setArtifactId(schema2Meta.getId());
        artifactReference.setGroupId(schema2Meta.getGroupId());
        artifactReference.setVersion(schema2Meta.getVersion());

        ArtifactMetaData schema1Meta = createArtifactWithReferences(schema1, "protoTest", UUID.randomUUID().toString(), List.of(artifactReference));

    }

    private ArtifactMetaData createArtifactWithReferences(String content, String groupId, String artifactId, List<ArtifactReference> artifactReferences) {
        RegistryClient client = RegistryClientFactory.create(testEndpoint);

        final InputStream stream = IoUtil.toStream(content.getBytes(StandardCharsets.UTF_8));
        final ArtifactMetaData created = client.createArtifact(groupId, artifactId, null,
                ArtifactType.PROTOBUF, IfExists.UPDATE, false, null, null, ContentTypes.APPLICATION_CREATE_EXTENDED, null, null, stream, artifactReferences);
        return created;
    }

}

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

package io.apicurio.registry.serde.jsonschema;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import org.apache.kafka.common.header.Headers;
import org.apache.kafka.common.serialization.Serializer;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.worldturner.medeia.api.StreamSchemaSource;
import com.worldturner.medeia.api.jackson.MedeiaJacksonApi;
import com.worldturner.medeia.schema.validation.SchemaValidator;

import io.apicurio.registry.rest.client.RegistryClient;
import io.apicurio.registry.serde.AbstractKafkaSerializer;
import io.apicurio.registry.serde.ParsedSchema;
import io.apicurio.registry.serde.SchemaResolver;
import io.apicurio.registry.serde.SerdeConfigKeys;
import io.apicurio.registry.serde.strategy.ArtifactResolverStrategy;
import io.apicurio.registry.serde.utils.HeaderUtils;
import io.apicurio.registry.serde.utils.Utils;
import io.apicurio.registry.types.ArtifactType;
import io.apicurio.registry.utils.IoUtil;

/**
 * An implementation of the Kafka Serializer for JSON Schema use-cases. This serializer assumes that the
 * user's application needs to serialize a Java Bean to JSON data using Jackson. In addition to standard
 * serialization of the bean, this implementation can also optionally validate it against a JSON schema.
 *
 * @author eric.wittmann@gmail.com
 * @author Ales Justin
 * @author Fabian Martinez
 */
public class JsonSchemaKafkaSerializer<T> extends AbstractKafkaSerializer<SchemaValidator, T> implements Serializer<T> {

    protected static MedeiaJacksonApi api = new MedeiaJacksonApi();
    protected static ObjectMapper mapper = new ObjectMapper();
    private Boolean validationEnabled;

    public JsonSchemaKafkaSerializer() {
        super();
    }

    public JsonSchemaKafkaSerializer(RegistryClient client,
            ArtifactResolverStrategy<SchemaValidator> artifactResolverStrategy,
            SchemaResolver<SchemaValidator, T> schemaResolver) {
        super(client, artifactResolverStrategy, schemaResolver);
    }

    public JsonSchemaKafkaSerializer(RegistryClient client) {
        super(client);
    }

    public JsonSchemaKafkaSerializer(SchemaResolver<SchemaValidator, T> schemaResolver) {
        super(schemaResolver);
    }

    public JsonSchemaKafkaSerializer(RegistryClient client, Boolean validationEnabled) {
        this(client);
        this.validationEnabled = validationEnabled;
    }

    /**
     * @see io.apicurio.registry.serde.AbstractKafkaSerializer#configure(java.util.Map, boolean)
     */
    @SuppressWarnings("unchecked")
    @Override
    public void configure(Map<String, ?> configs, boolean isKey) {
        super.configure(configs, isKey);

        if (validationEnabled == null) {
            Object ve = configs.get(SerdeConfigKeys.VALIDATION_ENABLED);
            this.validationEnabled = Utils.isTrue(ve);
        }

        //headers funcionality is always enabled for jsonschema
        headerUtils = new HeaderUtils((Map<String, Object>) configs, isKey);

        // TODO allow the schema to be configured here
    }

    public boolean isValidationEnabled() {
        return validationEnabled != null && validationEnabled;
    }

    /**
     * @param validationEnabled the validationEnabled to set
     */
    public void setValidationEnabled(Boolean validationEnabled) {
        this.validationEnabled = validationEnabled;
    }

    /**
     * @see io.apicurio.registry.serde.SchemaParser#artifactType()
     */
    @Override
    public ArtifactType artifactType() {
        return ArtifactType.JSON;
    }

    /**
     * @see io.apicurio.registry.serde.SchemaParser#parseSchema(byte[])
     */
    @Override
    public SchemaValidator parseSchema(byte[] rawSchema) {
        return api.loadSchema(new StreamSchemaSource(IoUtil.toStream(rawSchema)));
    }

    //TODO we could implement some way of providing the jsonschema beforehand:
    // - via annotation in the object being serialized
    // - via config property
    //if we do this users will be able to automatically registering the schema when using this serde
//    /**
//     * @see io.apicurio.registry.serde.AbstractKafkaSerializer#getSchemaFromData(java.lang.Object)
//     */
//    @Override
//    protected ParsedSchema<SchemaValidator> getSchemaFromData(T data) {
//        // TODO Auto-generated method stub
//        return super.getSchemaFromData(data);
//    }

    /**
     * @see io.apicurio.registry.serde.AbstractKafkaSerializer#serializeData(io.apicurio.registry.serde.ParsedSchema, java.lang.Object, java.io.OutputStream)
     */
    @Override
    protected void serializeData(ParsedSchema<SchemaValidator> schema, T data, OutputStream out) throws IOException {
        // note, if no headers to pass the messageType (javaType) the user is responsible for putting the javaType in the jsonschema, so the deserializer can know the java class to use.
        JsonNode jsonSchema = mapper.readTree(schema.getRawSchema());

        JsonNode javaType = jsonSchema.get("javaType");
        if (javaType == null || javaType.isNull()) {
            throw new IllegalStateException("Missing javaType info in jsonschema, unable to deserialize.");
        }

        serializeData(null, schema, data, out);
    }

    /**
     * @see io.apicurio.registry.serde.AbstractKafkaSerializer#serializeData(org.apache.kafka.common.header.Headers, io.apicurio.registry.serde.ParsedSchema, java.lang.Object, java.io.OutputStream)
     */
    @Override
    protected void serializeData(Headers headers, ParsedSchema<SchemaValidator> schema, T data, OutputStream out) throws IOException {
        JsonGenerator generator = mapper.getFactory().createGenerator(out);
        if (isValidationEnabled()) {
            generator = api.decorateJsonGenerator(schema.getParsedSchema(), generator);
        }
        if (headers != null) {
            //TODO add logic to override the messageType via config property?
            headerUtils.addMessageTypeHeader(headers, data.getClass().getName());
        }
        mapper.writeValue(generator, data);
    }

}

package io.apicurio.registry.serde.jsonschema.nats.client.streaming.producers;

import io.apicurio.registry.serde.jsonschema.JsonSchemaDeserializerConfig;
import io.apicurio.registry.serde.jsonschema.JsonSchemaSerializer;
import io.apicurio.registry.serde.jsonschema.nats.client.exceptions.ApicurioNatsException;
import io.nats.client.Connection;
import io.nats.client.JetStream;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class NatsProducerImpl<DATA> implements NatsProducer<DATA> {

    private final Connection connection;

    private final JetStream jetStream;

    private final JsonSchemaSerializer<DATA> serializer;

    private final String subject;

    public NatsProducerImpl(Connection connection, String subject, Properties config) throws Exception {
        this.connection = connection;
        this.subject = subject;
        Map<String, Object> configs = new HashMap<>();
        config.forEach((key, value) -> configs.put(key.toString(), value));
        JsonSchemaDeserializerConfig deserializerConfig = new JsonSchemaDeserializerConfig(configs);
        serializer = new JsonSchemaSerializer<>();
        serializer.configure(deserializerConfig, false);
        //config.get(NatsProducerConfig.SERIALIZER_CLASS_CONFIG)

        jetStream = connection.jetStream();
    }

    @Override
    public void send(DATA message) throws ApicurioNatsException {
        byte[] data = serializer.serializeData(subject, message);

        try {
            jetStream.publish(subject, data);
        }
        catch (Exception ex) {
            throw new ApicurioNatsException(ex);
        }
    }

    @Override
    public void close() throws Exception {
        connection.close();
    }
}

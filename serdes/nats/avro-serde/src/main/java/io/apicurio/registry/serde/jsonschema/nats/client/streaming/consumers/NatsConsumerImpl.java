package io.apicurio.registry.serde.jsonschema.nats.client.streaming.consumers;

import io.apicurio.registry.serde.jsonschema.JsonSchemaDeserializer;
import io.apicurio.registry.serde.jsonschema.JsonSchemaSerializerConfig;
import io.nats.client.Connection;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class NatsConsumerImpl<DATA> implements NatsConsumer<DATA> {

    private final JsonSchemaDeserializer<DATA> deserializer;

    private final Connection connection;

    private final String subject;

    private final PullSubscribeOptions subscribeOptions;

    private JetStreamSubscription subscription;

    private static final Logger logger = LoggerFactory.getLogger(NatsConsumerImpl.class);

    public NatsConsumerImpl(Connection connection, String subject, PullSubscribeOptions subscribeOptions,
                            Properties config) {
        this.connection = connection;
        this.subject = subject;
        this.subscribeOptions = subscribeOptions;
        Map<String, Object> configs = new HashMap<>();
        config.forEach((key, value) -> configs.put(key.toString(), value));

        JsonSchemaSerializerConfig serializerConfig = new JsonSchemaSerializerConfig(configs);
        deserializer = new JsonSchemaDeserializer<>();

        deserializer.configure(serializerConfig, false);
    }

    private JetStreamSubscription getLazySubscription() throws Exception {
        if (subscription == null) {
            subscription = connection.jetStream().subscribe(subject, subscribeOptions);
        }
        return subscription;
    }

    @Override
    public String getSubject() {
        return subject;
    }

    @Override
    public NatsConsumerRecord<DATA> receive() throws Exception {
        return receive(Duration.ofSeconds(3));
    }

    @Override
    public NatsConsumerRecord<DATA> receive(Duration timeout) throws Exception {
        List<NatsConsumerRecord<DATA>> messages = receive(1, timeout);
        return messages == null ? null : messages.get(0); // TODO
    }

    @Override
    public List<NatsConsumerRecord<DATA>> receive(int batchSize, Duration timeout) throws Exception {

        List<Message> messages = getLazySubscription().fetch(batchSize, timeout);

        if (messages == null || messages.isEmpty()) {
            logger.info("Receive timeout ({} ms)", timeout.toMillis());
            return null; // TODO
        }

        List<NatsConsumerRecord<DATA>> records = new ArrayList<>();
        for (Message message : messages) {
            DATA payload = deserializer.deserializeData(subject, message.getData());
            records.add(new NatsConsumerRecordImpl<>(message, payload));
        }
        return records;
    }

    @Override
    public void close() throws Exception {
        if (subscription != null) {
            subscription.unsubscribe();
        }
    }
}

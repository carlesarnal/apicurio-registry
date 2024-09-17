package io.apicurio.registry.serde.jsonschema.nats.client.streaming.producers;

import io.apicurio.registry.serde.avro.nats.platform.nats.client.exceptions.ApicurioNatsException;

public interface NatsProducer<T> extends AutoCloseable {

    void send(T message) throws ApicurioNatsException;
}

package io.apicurio.registry.serde.jsonschema.nats.client.exceptions;

public class ApicurioNatsException extends RuntimeException {

    public ApicurioNatsException(Throwable e) {
        super(e);
    }
}

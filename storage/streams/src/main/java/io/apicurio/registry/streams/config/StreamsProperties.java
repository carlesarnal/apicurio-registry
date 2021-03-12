package io.apicurio.registry.streams.config;

import java.util.Properties;

/**
 * @author Ales Justin
 */
public interface StreamsProperties {
    Properties getProperties();
    long toGlobalId(long offset, int partition);
    long getBaseOffset();
    String getStorageStoreName();
    String getGlobalIdStoreName();
    String getContentStoreName();
    String getStorageTopic();
    String getContentTopic();
    String getApplicationServer();
    boolean ignoreAutoCreate();
}

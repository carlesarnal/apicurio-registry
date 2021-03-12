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

package io.apicurio.registry.streams.config;

import org.apache.kafka.streams.StreamsConfig;

import java.util.Properties;

/**
 * @author Ales Justin
 */
public class StreamsPropertiesImpl implements StreamsProperties {
    private final Properties properties;

    public StreamsPropertiesImpl(Properties properties) {
        this.properties = properties;
    }

    public Properties getProperties() {
        return properties;
    }

    public long toGlobalId(long offset, int partition) {
        return getBaseOffset() + (offset << 16) + partition;
    }

    // just to make sure we can always move the whole system
    // and not get duplicates; e.g. after move baseOffset = max(globalId) + 1
    public long getBaseOffset() {
        return Long.parseLong(properties.getProperty("storage.base.offset", "0"));
    }

    public String getStorageStoreName() {
        return properties.getProperty("storage.store", "storage-store");
    }

    public String getGlobalIdStoreName() {
        return properties.getProperty("global.id.store", "global-id-store");
    }

    public String getContentStoreName() {
        return properties.getProperty("content.store", "content-store");
    }

    public String getStorageTopic() {
        return properties.getProperty("storage.topic", "storage-topic");
    }

    public String getContentTopic() {
        return properties.getProperty("content.topic", "content-topic");
    }

    public String getApplicationServer() {
        return properties.getProperty(StreamsConfig.APPLICATION_SERVER_CONFIG, "localhost:9000");
    }

    public boolean ignoreAutoCreate() {
        return Boolean.parseBoolean(properties.getProperty("ignore.auto-create", "false"));
    }
}

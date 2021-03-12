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

import com.google.common.collect.ImmutableMap;
import io.apicurio.registry.storage.proto.Str;
import io.apicurio.registry.streams.utils.ArtifactKeySerde;
import io.apicurio.registry.types.provider.ArtifactTypeUtilProviderFactory;
import io.apicurio.registry.utils.kafka.ProtoSerde;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.ForeachAction;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.state.KeyValueStore;
import org.apache.kafka.streams.state.StoreBuilder;
import org.apache.kafka.streams.state.Stores;

import java.util.function.Supplier;


/**
 * Request --> Storage (topic / store) --> GlobalId (topic / store)
 *
 * @author Ales Justin
 */
public class StreamsTopologyProvider implements Supplier<Topology> {
    private final StreamsProperties properties;
    private final ForeachAction<? super Str.ArtifactKey, ? super Str.Data> dataDispatcher;
    private final ForeachAction<? super Long, ? super Str.ContentData> contentDataDispatcher;
    private final ArtifactTypeUtilProviderFactory factory;

    public StreamsTopologyProvider(
            StreamsProperties properties,
            ForeachAction<? super Str.ArtifactKey, ? super Str.Data> dataDispatcher,
            ForeachAction<? super Long, ? super Str.ContentData> contentDataDispatcher,
        ArtifactTypeUtilProviderFactory factory
    ) {
        this.properties = properties;
        this.dataDispatcher = dataDispatcher;
        this.contentDataDispatcher = contentDataDispatcher;
        this.factory = factory;
    }

    @Override
    public Topology get() {
        StreamsBuilder builder = new StreamsBuilder();

        // Simple defaults
        ImmutableMap<String, String> configuration = ImmutableMap.of(
            TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_COMPACT,
            TopicConfig.MIN_COMPACTION_LAG_MS_CONFIG, "0",
            TopicConfig.SEGMENT_BYTES_CONFIG, String.valueOf(64 * 1024 * 1024)
        );

        // Input topic -- artifactStore topic
        // This is where we handle "http" requests
        // Key is artifactId -- which is also used for KeyValue store key
        KStream<Str.ArtifactKey, Str.StorageValue> storageRequest = builder.stream(
                properties.getStorageTopic(),
                Consumed.with(new ArtifactKeySerde(), ProtoSerde.parsedWith(Str.StorageValue.parser()))
        );

        KStream<Long, Str.ContentValue> contentStorageRequest = builder.stream(
                properties.getContentTopic(),
                Consumed.with(new Serdes.LongSerde(), ProtoSerde.parsedWith(Str.ContentValue.parser()))
        );

        // Data structure holds all artifact information
        // Global rules are Data as well, with constant artifactId (GLOBAL_RULES variable)
        String storageStoreName = properties.getStorageStoreName();
        StoreBuilder<KeyValueStore<Str.ArtifactKey /* artifactId */, Str.Data>> storageStoreBuilder =
                Stores
                        .keyValueStoreBuilder(
                                Stores.inMemoryKeyValueStore(storageStoreName),
                                new ArtifactKeySerde(), ProtoSerde.parsedWith(Str.Data.parser())
                        )
                        .withCachingEnabled()
                        .withLoggingEnabled(configuration);

        builder.addStateStore(storageStoreBuilder);

        String globalIdStoreName = properties.getGlobalIdStoreName();
        StoreBuilder<KeyValueStore<Long /* globalId */, Str.TupleValue>> globalIdStoreBuilder =
                Stores
                        .keyValueStoreBuilder(
                                Stores.inMemoryKeyValueStore(globalIdStoreName),
                                Serdes.Long(), ProtoSerde.parsedWith(Str.TupleValue.parser())
                        )
                        .withCachingEnabled()
                        .withLoggingEnabled(configuration);

        builder.addStateStore(globalIdStoreBuilder);

        String contentStoreName = properties.getContentStoreName();
        StoreBuilder<KeyValueStore<Long /* globalId */, Str.ContentData>> contentStoreBuilder =
                Stores
                        .keyValueStoreBuilder(
                                Stores.inMemoryKeyValueStore(contentStoreName),
                                Serdes.Long(), ProtoSerde.parsedWith(Str.ContentData.parser())
                        )
                        .withCachingEnabled()
                        .withLoggingEnabled(configuration);

        builder.addStateStore(contentStoreBuilder);

        // We process <artifactId, Data> into simple mapping <globalId, <artifactId, version>>
        storageRequest.process(
                () -> new StorageProcessor(properties, dataDispatcher, factory),
                storageStoreName, globalIdStoreName
        );

        contentStorageRequest.process(
                () -> new ContentProcessor(properties, contentDataDispatcher, factory),
                storageStoreName, globalIdStoreName
        );

        return builder.build(properties.getProperties());
    }
}

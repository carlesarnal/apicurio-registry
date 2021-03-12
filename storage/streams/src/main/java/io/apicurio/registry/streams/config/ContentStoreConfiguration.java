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

import io.apicurio.registry.storage.proto.Str;
import io.apicurio.registry.streams.StreamsRegistryStorage;
import io.apicurio.registry.streams.utils.WaitForContentDataService;
import io.apicurio.registry.types.Current;
import io.apicurio.registry.utils.RegistryProperties;
import io.apicurio.registry.utils.kafka.AsyncProducer;
import io.apicurio.registry.utils.kafka.ProducerActions;
import io.apicurio.registry.utils.kafka.ProtoSerde;
import io.apicurio.registry.utils.streams.diservice.AsyncBiFunctionService;
import io.apicurio.registry.utils.streams.diservice.DefaultGrpcChannelProvider;
import io.apicurio.registry.utils.streams.diservice.DistributedAsyncBiFunctionService;
import io.apicurio.registry.utils.streams.diservice.LocalService;
import io.apicurio.registry.utils.streams.distore.DistributedReadOnlyKeyValueStore;
import io.apicurio.registry.utils.streams.distore.ExtReadOnlyKeyValueStore;
import io.apicurio.registry.utils.streams.distore.FilterPredicate;
import io.apicurio.registry.utils.streams.ext.ForeachActionDispatcher;
import io.quarkus.runtime.ShutdownEvent;
import org.apache.kafka.common.serialization.LongSerializer;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.state.HostInfo;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Disposes;
import javax.enterprise.inject.Produces;
import javax.inject.Singleton;
import java.util.Properties;

public class ContentStoreConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ContentStoreConfiguration.class);

    private static void close(Object service) {
        if (service instanceof AutoCloseable) {
            try {
                ((AutoCloseable) service).close();
            } catch (Exception ignored) {
            }
        }
    }

    public void stopContentProducer(@Disposes ProducerActions<Long, Str.ContentValue> producer) throws Exception {
        producer.close();
    }

    @Produces
    @ApplicationScoped
    public FilterPredicate<Long, Str.ContentData> contentFilterPredicate() {
        return StreamsRegistryStorage.createContentFilterPredicate();
    }

    @Produces
    @ApplicationScoped
    public ExtReadOnlyKeyValueStore<Long, Str.ContentData> contentStorageKeyValueStore(
            KafkaStreams streams,
            HostInfo storageLocalHost,
            StreamsProperties properties,
            FilterPredicate<Long, Str.ContentData> filterPredicate
    ) {
        return new DistributedReadOnlyKeyValueStore<>(
                streams,
                storageLocalHost,
                properties.getStorageStoreName(),
                new Serdes.LongSerde(), ProtoSerde.parsedWith(Str.ContentData.parser()),
                new DefaultGrpcChannelProvider(),
                true,
                filterPredicate
        );
    }

    @SuppressWarnings("resource")
    @Produces
    @ApplicationScoped
    public ProducerActions<Long, Str.ContentValue> storageProducer(
            @RegistryProperties(
                    value = {"registry.streams.common", "registry.streams.storage-producer"},
                    empties = {"ssl.endpoint.identification.algorithm="}
            ) Properties properties
    ) {
        return new AsyncProducer<>(
                properties,
                new LongSerializer(),
                ProtoSerde.parsedWith(Str.ContentValue.parser())
        );
    }

    public void destroyContentStore(@Observes ShutdownEvent event, ExtReadOnlyKeyValueStore<Long, Str.ContentData> store) {
        close(store);
    }

    @Produces
    @Singleton
    public ForeachActionDispatcher<Long, Str.ContentData> contentDataDispatcher() {
        return new ForeachActionDispatcher<>();
    }

    @Produces
    @Singleton
    public WaitForContentDataService waitForContentDataServiceImpl(
            ReadOnlyKeyValueStore<Long, Str.ContentData> storageKeyValueStore,
            ForeachActionDispatcher<Long, Str.ContentData> storageDispatcher
    ) {
        return new WaitForContentDataService(storageKeyValueStore, storageDispatcher);
    }

    @Produces
    @Singleton
    public LocalService<AsyncBiFunctionService.WithSerdes<Long, Long, Str.ContentData>> localWaitForContentDataService(
            WaitForContentDataService localService
    ) {
        return new LocalService<>(
                WaitForContentDataService.NAME,
                localService
        );
    }

    @Produces
    @ApplicationScoped
    @Current
    public AsyncBiFunctionService<Long, Long, Str.ContentData> waitForContentDataUpdateService(
            StreamsProperties properties,
            KafkaStreams streams,
            HostInfo storageLocalHost,
            LocalService<AsyncBiFunctionService.WithSerdes<Long, Long, Str.ContentData>> localWaitForDataUpdateService
    ) {
        return new DistributedAsyncBiFunctionService<>(
                streams,
                storageLocalHost,
                properties.getStorageStoreName(),
                localWaitForDataUpdateService,
                new DefaultGrpcChannelProvider()
        );
    }

    public void destroyWaitForDataUpdateService(@Observes ShutdownEvent event, @Current AsyncBiFunctionService<Long, Long, Str.ContentData> service) {
        close(service);
    }
}

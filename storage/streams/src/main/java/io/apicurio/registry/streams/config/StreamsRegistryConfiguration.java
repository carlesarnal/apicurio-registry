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
import io.apicurio.registry.streams.utils.StateService;
import io.apicurio.registry.types.Current;
import io.apicurio.registry.types.provider.ArtifactTypeUtilProviderFactory;
import io.apicurio.registry.utils.ConcurrentUtil;
import io.apicurio.registry.utils.RegistryProperties;
import io.apicurio.registry.utils.kafka.KafkaUtil;
import io.apicurio.registry.utils.kafka.ProtoSerde;
import io.apicurio.registry.utils.streams.diservice.AsyncBiFunctionService;
import io.apicurio.registry.utils.streams.diservice.AsyncBiFunctionServiceGrpcLocalDispatcher;
import io.apicurio.registry.utils.streams.diservice.DefaultGrpcChannelProvider;
import io.apicurio.registry.utils.streams.diservice.DistributedAsyncBiFunctionService;
import io.apicurio.registry.utils.streams.diservice.LocalService;
import io.apicurio.registry.utils.streams.diservice.proto.AsyncBiFunctionServiceGrpc;
import io.apicurio.registry.utils.streams.distore.DistributedReadOnlyKeyValueStore;
import io.apicurio.registry.utils.streams.distore.FilterPredicate;
import io.apicurio.registry.utils.streams.distore.KeyValueSerde;
import io.apicurio.registry.utils.streams.distore.KeyValueStoreGrpcImplLocalDispatcher;
import io.apicurio.registry.utils.streams.distore.UnknownStatusDescriptionInterceptor;
import io.apicurio.registry.utils.streams.distore.proto.KeyValueStoreGrpc;
import io.apicurio.registry.utils.streams.ext.Lifecycle;
import io.apicurio.registry.utils.streams.ext.LoggingStateRestoreListener;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KafkaClientSupplier;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.Topology;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.kstream.ForeachAction;
import org.apache.kafka.streams.processor.internals.DefaultKafkaClientSupplier;
import org.apache.kafka.streams.state.HostInfo;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.enterprise.inject.Produces;
import javax.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * @author Ales Justin
 */
@ApplicationScoped
public class StreamsRegistryConfiguration {
    private static final Logger log = LoggerFactory.getLogger(StreamsRegistryConfiguration.class);

    private static void close(Object service) {
        if (service instanceof AutoCloseable) {
            try {
                ((AutoCloseable) service).close();
            } catch (Exception ignored) {
            }
        }
    }

    @Produces
    @ApplicationScoped
    public StreamsProperties streamsProperties(
            @RegistryProperties(
                    value = {"registry.streams.common", "registry.streams.topology"},
                    empties = {"ssl.endpoint.identification.algorithm="}
            ) Properties properties
    ) {
        return new StreamsPropertiesImpl(properties);
    }


    @SuppressWarnings({"unchecked", "rawtypes"})
    @Produces
    @Singleton // required (cannot be ApplicationScoped), as we don't want proxy
    public KafkaClientSupplier kafkaClientSupplier(StreamsProperties properties) {
        KafkaClientSupplier kcs = new DefaultKafkaClientSupplier();
        if (!properties.ignoreAutoCreate()) {
            Map<String, Object> configMap = new HashMap(properties.getProperties());
            try (Admin admin = kcs.getAdmin(configMap)) {
                KafkaUtil.createTopics(admin, Set.of(properties.getStorageTopic(), properties.getContentTopic()));
            }
        }
        return kcs;
    }

    @Produces
    @Singleton
    public KafkaStreams storageStreams(
            StreamsProperties properties,
            KafkaClientSupplier kafkaClientSupplier, // this injection is here to create a dependency on previous auto-create topics code
            ForeachAction<? super Str.ArtifactKey, ? super Str.Data> dataDispatcher,
            ForeachAction<? super Long, ? super Str.ContentData> contentDataDispatcher,
            ArtifactTypeUtilProviderFactory factory
    ) {
        Topology topology = new StreamsTopologyProvider(properties, dataDispatcher, contentDataDispatcher, factory).get();

        KafkaStreams streams = new KafkaStreams(topology, properties.getProperties(), kafkaClientSupplier);
        streams.setGlobalStateRestoreListener(new LoggingStateRestoreListener());

        return streams;
    }

    public void init(@Observes StartupEvent event, KafkaStreams streams) {
        streams.start();
    }

    public void destroy(@Observes ShutdownEvent event, KafkaStreams streams) {
        streams.close();
    }

    @Produces
    @Singleton
    public HostInfo storageLocalHost(StreamsProperties props) {
        // Remove brackets in case we have an ipv6 address of the form [2001:0db8:85a3:0000:0000:8a2e:0370:7334]:8080.
        String appServer = props.getApplicationServer().replaceAll("\\[", "")
                .replaceAll("\\]", "");

        // In all cases we assume there is a :{port} included.  So we split on the last : and take the rest as the host.
        int lastIndexOf = appServer.lastIndexOf(":");
        String host = appServer.substring(0, lastIndexOf);
        String port = appServer.substring(lastIndexOf + 1);
        log.info("Application server gRPC: '{}'", appServer);
        return new HostInfo(host, Integer.parseInt(port));
    }

    @Produces
    @Singleton
    public KeyValueStoreGrpc.KeyValueStoreImplBase streamsKeyValueStoreGrpcImpl(
            KafkaStreams streams,
            StreamsProperties props,
            FilterPredicate<Str.ArtifactKey, Str.Data> filterPredicate,
            FilterPredicate<Long, Str.ContentData> contentFilterPredicate
    ) {
        return new KeyValueStoreGrpcImplLocalDispatcher(
                streams,
                KeyValueSerde
                        .newRegistry()
                        .register(
                                props.getStorageStoreName(),
                                new ArtifactKeySerde(), ProtoSerde.parsedWith(Str.Data.parser())
                        )
                        .register(
                                props.getGlobalIdStoreName(),
                                Serdes.Long(), ProtoSerde.parsedWith(Str.TupleValue.parser())
                        ).register(
                        props.getContentStoreName(),
                        Serdes.Long(), ProtoSerde.parsedWith(Str.ContentData.parser())
                ),
                filterPredicate
        );
    }

    @Produces
    @ApplicationScoped
    public ReadOnlyKeyValueStore<Long, Str.TupleValue> globalIdKeyValueStore(
            KafkaStreams streams,
            HostInfo storageLocalHost,
            StreamsProperties properties
    ) {
        return new DistributedReadOnlyKeyValueStore<>(
                streams,
                storageLocalHost,
                properties.getGlobalIdStoreName(),
                Serdes.Long(), ProtoSerde.parsedWith(Str.TupleValue.parser()),
                new DefaultGrpcChannelProvider(),
                true,
                (filter, id, tuple) -> true
        );
    }

    public void destroyGlobalIdStore(@Observes ShutdownEvent event, ReadOnlyKeyValueStore<Long, Str.TupleValue> store) {
        close(store);
    }


    @Produces
    @Singleton
    public StateService stateServiceImpl(KafkaStreams streams) {
        return new StateService(streams);
    }

    @Produces
    @Singleton
    public LocalService<AsyncBiFunctionService.WithSerdes<Void, Void, KafkaStreams.State>> localStateService(
            StateService localService
    ) {
        return new LocalService<>(
                StateService.NAME,
                localService
        );
    }

    @Produces
    @ApplicationScoped
    @Current
    public AsyncBiFunctionService<Void, Void, KafkaStreams.State> stateService(
            KafkaStreams streams,
            HostInfo storageLocalHost,
            LocalService<AsyncBiFunctionService.WithSerdes<Void, Void, KafkaStreams.State>> localStateService
    ) {
        return new DistributedAsyncBiFunctionService<>(
                streams,
                storageLocalHost,
                "stateStore",
                localStateService,
                new DefaultGrpcChannelProvider()
        );
    }

    public void destroyStateService(@Observes ShutdownEvent event, @Current AsyncBiFunctionService<Void, Void, KafkaStreams.State> service) {
        close(service);
    }

    // gRPC server

    @Produces
    @ApplicationScoped
    public Lifecycle storageGrpcServer(
            HostInfo storageLocalHost,
            KeyValueStoreGrpc.KeyValueStoreImplBase storageStoreGrpcImpl,
            AsyncBiFunctionServiceGrpc.AsyncBiFunctionServiceImplBase storageAsyncBiFunctionServiceGrpcImpl
    ) {

        UnknownStatusDescriptionInterceptor unknownStatusDescriptionInterceptor =
                new UnknownStatusDescriptionInterceptor(
                        ImmutableMap.of(
                                IllegalArgumentException.class, Status.INVALID_ARGUMENT,
                                IllegalStateException.class, Status.FAILED_PRECONDITION,
                                InvalidStateStoreException.class, Status.FAILED_PRECONDITION,
                                Throwable.class, Status.INTERNAL
                        )
                );

        Server server = ServerBuilder
                .forPort(storageLocalHost.port())
                .addService(
                        ServerInterceptors.intercept(
                                storageStoreGrpcImpl,
                                unknownStatusDescriptionInterceptor
                        )
                )
                .addService(
                        ServerInterceptors.intercept(
                                storageAsyncBiFunctionServiceGrpcImpl,
                                unknownStatusDescriptionInterceptor
                        )
                )
                .build();

        return new Lifecycle() {
            @Override
            public void start() {
                try {
                    server.start();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public void stop() {
                ConcurrentUtil
                        .<Server>consumer(Server::awaitTermination)
                        .accept(server.shutdown());
            }

            @Override
            public boolean isRunning() {
                return !(server.isShutdown() || server.isTerminated());
            }
        };
    }

    public void init(@Observes StartupEvent event, Lifecycle lifecycle) {
        lifecycle.start();
    }

    public void destroy(@Observes ShutdownEvent event, Lifecycle lifecycle) {
        lifecycle.stop();
    }


    @Produces
    @Singleton
    public AsyncBiFunctionServiceGrpc.AsyncBiFunctionServiceImplBase storageContentAsyncBiFunctionServiceGrpcImpl(
            LocalService<AsyncBiFunctionService.WithSerdes<Long, Long, Str.ContentData>> localWaitForContentDataService,
            LocalService<AsyncBiFunctionService.WithSerdes<Str.ArtifactKey, Long, Str.Data>> localWaitForDataService,
            LocalService<AsyncBiFunctionService.WithSerdes<Void, Void, KafkaStreams.State>> localStateService
    ) {
        return new AsyncBiFunctionServiceGrpcLocalDispatcher(
                Arrays.asList(localWaitForContentDataService, localWaitForDataService, localStateService)
        );
    }

}

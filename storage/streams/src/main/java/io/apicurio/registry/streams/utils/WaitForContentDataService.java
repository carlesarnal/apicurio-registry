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

package io.apicurio.registry.streams.utils;

import io.apicurio.registry.storage.proto.Str;
import io.apicurio.registry.utils.kafka.ProtoSerde;
import io.apicurio.registry.utils.streams.diservice.AsyncBiFunctionService;
import io.apicurio.registry.utils.streams.ext.ForeachActionDispatcher;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;

import java.util.Iterator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

public class WaitForContentDataService implements AsyncBiFunctionService.WithSerdes<Long, Long, Str.ContentData> {

    public static final String NAME = "WaitForContentDataService";

    private final Map<Long, NavigableMap<Long, ResultCF>> waitingResults = new ConcurrentHashMap<>();

    private final ReadOnlyKeyValueStore<Long, Str.ContentData> storageKeyValueStore;

    public WaitForContentDataService(
            ReadOnlyKeyValueStore<Long, Str.ContentData> storageKeyValueStore,
            ForeachActionDispatcher<Long, Str.ContentData> storageDispatcher
    ) {
        this.storageKeyValueStore = Objects.requireNonNull(storageKeyValueStore);
        storageDispatcher.register(this::dataUpdated);
    }

    /**
     * Notification (from transformer)
     */
    private void dataUpdated(Long key, Str.ContentData data) {
        if (data == null) {
            return;
        }
        // fast-path check if there are any registered futures
        if (waitingResults.containsKey(key)) {
            // re-check under lock (performed by CHM on the bucket-level)
            waitingResults.compute(
                    key,
                    (_artifactId, cfMap) -> {
                        if (cfMap == null) {
                            // might have been de-registered after fast-path check above
                            return null;
                        }

                        NavigableMap<Long, ResultCF> map = cfMap.headMap(data.getLastProcessedOffset(), true);
                        Iterator<Map.Entry<Long, ResultCF>> iter = map.entrySet().iterator();
                        while (iter.hasNext()) {
                            Map.Entry<Long, ResultCF> next = iter.next();
                            next.getValue().complete(data);
                            iter.remove();
                        }

                        return cfMap.isEmpty() ? null : cfMap;
                    }
            );
        }
    }

    @Override
    public void close() {
    }

    @Override
    public Serde<Long> keySerde() {
        return new Serdes.LongSerde();
    }

    @Override
    public Serde<Long> reqSerde() {
        return Serdes.Long();
    }

    @Override
    public Serde<Str.ContentData> resSerde() {
        return ProtoSerde.parsedWith(Str.ContentData.parser());
    }

    @Override
    public CompletionStage<Str.ContentData> apply(Long key, Long offset) {
        // 1st register the future
        ResultCF cf = new ResultCF(offset);
        register(key, cf);
        // 2nd check the store if it contains data for a contentId
        try {
            dataUpdated(key, storageKeyValueStore.get(key));
        } catch (Throwable e) {
            // exception looking up the store is propagated to cf...
            deregister(key, cf);
            cf.completeExceptionally(e);
        }
        return cf;
    }

    private void register(Long key, ResultCF cf) {
        waitingResults.compute(
                key,
                (_artifactId, cfMap) -> {
                    if (cfMap == null) {
                        cfMap = new TreeMap<>();
                    }
                    cfMap.put(cf.offset, cf);
                    return cfMap;
                }
        );
    }

    private void deregister(Long key, ResultCF cf) {
        waitingResults.compute(
                key,
                (_artifactId, cfMap) -> {
                    if (cfMap == null) {
                        return null;
                    } else {
                        cfMap.remove(cf.offset);
                        // remove empty queue from map
                        return cfMap.isEmpty() ? null : cfMap;
                    }
                }
        );
    }

    private static class ResultCF extends CompletableFuture<Str.ContentData> {
        final long offset;

        public ResultCF(long offset) {
            this.offset = offset;
        }
    }
}

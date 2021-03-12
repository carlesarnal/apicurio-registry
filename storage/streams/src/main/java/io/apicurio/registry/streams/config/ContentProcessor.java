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
import io.apicurio.registry.types.provider.ArtifactTypeUtilProviderFactory;
import org.apache.kafka.streams.kstream.ForeachAction;
import org.apache.kafka.streams.processor.AbstractProcessor;
import org.apache.kafka.streams.processor.ProcessorContext;
import org.apache.kafka.streams.state.KeyValueStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContentProcessor extends AbstractProcessor<Long, Str.ContentValue> {

    private static final Logger log = LoggerFactory.getLogger(ContentProcessor.class);

    private final StreamsProperties properties;
    private final ForeachAction<? super Long, ? super Str.ContentData> dispatcher;

    private ProcessorContext context;
    private KeyValueStore<Long, Str.ContentData> dataStore;

    public ContentProcessor(
            StreamsProperties properties,
            ForeachAction<? super Long, ? super Str.ContentData> dispatcher,
            ArtifactTypeUtilProviderFactory factory
    ) {
        this.properties = properties;
        this.dispatcher = dispatcher;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void init(ProcessorContext context) {
        this.context = context;
        //noinspection unchecked
        dataStore = (KeyValueStore<Long, Str.ContentData>) context.getStateStore(properties.getContentStoreName());
    }

    @Override
    public void process(Long contentId, Str.ContentValue contentValue) {
        Str.ContentData data = dataStore.get(contentId);
        if (data == null) {
            // initial value can be "empty" default
            data = Str.ContentData.getDefaultInstance();
        }

        long offset = context.offset();
        // should be unique enough
        long globalId = properties.toGlobalId(offset, context.partition());

        // apply / update data
        data = apply(contentValue, data, globalId, offset);

        //assume content creation for now
        if (data != null) {
            dataStore.put(globalId, data);
            // dispatch
            dispatcher.apply(globalId, data);
        } else {
            dataStore.delete(globalId);
        }
    }

    @Override
    public void close() {
    }

    // handle StorageValue to build appropriate Data representation
    private Str.ContentData apply(Str.ContentValue value, Str.ContentData aggregate, long globalId, long offset) {
        return consumeContent(aggregate, value, offset, globalId);
    }

    private Str.ContentData consumeContent(Str.ContentData data, Str.ContentValue contentValue, long offset, long globalId) {
        Str.ContentData.Builder builder = Str.ContentData.newBuilder(data).setLastProcessedOffset(offset);
        boolean notFound = builder.getContentsList().stream().noneMatch(c -> c.getContentHash().equals(contentValue.getContentHash()));
        if (notFound) {
            builder.addContents(Str.ContentValue.newBuilder().setContentHash(contentValue.getContentHash())
                    .setCanonicalHash(contentValue.getCanonicalHash())
                    .setContent(contentValue.getContent())
                    .setId(globalId));
        }
        return builder.build();
    }
}

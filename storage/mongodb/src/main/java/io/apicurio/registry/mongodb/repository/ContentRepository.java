/*
 * Copyright 2020 Red Hat
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

package io.apicurio.registry.mongodb.repository;

import io.apicurio.registry.mongodb.entity.Content;
import io.quarkus.mongodb.panache.PanacheMongoRepository;

import javax.enterprise.context.ApplicationScoped;
import java.util.Optional;

@ApplicationScoped
public class ContentRepository implements PanacheMongoRepository<Content> {

    public Content upsertContentByHash(String contentHash, byte[] contentBytes, String canonicalContentHash) {

        return findByHash(contentHash)
                .map(foundContent -> {
                    foundContent.setCanonicalHash(canonicalContentHash);
                    foundContent.setContent(contentBytes);
                    foundContent.setContentHash(contentHash);
                    update(foundContent);

                    return foundContent;
                }).orElseGet(() -> {

                    final Content content = new Content(canonicalContentHash, contentHash, contentBytes);
                    persist(content);
                    return content;
                });
    }


    public Optional<Content> findByHash(String contentHash) {
        return find("contentHash", contentHash)
                .firstResultOptional();
    }
}

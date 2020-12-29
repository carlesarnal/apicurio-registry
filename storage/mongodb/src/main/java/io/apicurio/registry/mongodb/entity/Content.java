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

package io.apicurio.registry.mongodb.entity;

import io.quarkus.mongodb.panache.MongoEntity;
import io.quarkus.mongodb.panache.PanacheMongoEntity;

@MongoEntity(collection = "content")
public class Content extends PanacheMongoEntity {

    private String canonicalHash;

    private String contentHash;

    private byte[] content;

    public Content() {
    }

    public Content(String canonicalHash, String contentHash, byte[] content) {
        this.canonicalHash = canonicalHash;
        this.contentHash = contentHash;
        this.content = content;
    }

    public String getCanonicalHash() {
        return canonicalHash;
    }

    public void setCanonicalHash(String canonicalHash) {
        this.canonicalHash = canonicalHash;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public byte[] getContent() {
        return content;
    }

    public void setContent(byte[] content) {
        this.content = content;
    }
}

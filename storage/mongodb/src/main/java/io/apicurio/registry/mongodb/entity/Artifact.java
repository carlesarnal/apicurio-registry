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

import io.quarkus.mongodb.panache.PanacheMongoEntityBase;
import org.bson.codecs.pojo.annotations.BsonId;

import java.time.LocalDate;

public class Artifact extends PanacheMongoEntityBase {

    @BsonId
    public String artifactId;

    public String artifactType;

    public String createdBy;

    public LocalDate createdOn;

    public Long latest;

    public Artifact(String artifactId, String artifactType, String createdBy, LocalDate createdOn, Long latest) {
        this.artifactId = artifactId;
        this.artifactType = artifactType;
        this.createdBy = createdBy;
        this.createdOn = createdOn;
        this.latest = latest;
    }
}


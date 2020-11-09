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
import org.bson.types.ObjectId;

@MongoEntity(collection = "Artifacts")
public class Property extends PanacheMongoEntity {

    public ObjectId versionId;

    public String pkey;

    public String pvalue;

    public Property() {
    }

    public Property(ObjectId versionId, String pkey, String pvalue) {
        this.versionId = versionId;
        this.pkey = pkey;
        this.pvalue = pvalue;
    }

    public ObjectId getVersionId() {
        return versionId;
    }

    public void setVersionId(ObjectId versionId) {
        this.versionId = versionId;
    }

    public String getPkey() {
        return pkey;
    }

    public void setPkey(String pkey) {
        this.pkey = pkey;
    }

    public String getPvalue() {
        return pvalue;
    }

    public void setPvalue(String pvalue) {
        this.pvalue = pvalue;
    }
}

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

@MongoEntity(collection = "version")
public class Version extends PanacheMongoEntity {

    private Long globalId;

    private ObjectId contentId;

    private String artifactId;

    private Long version;

    private String state;

    private String name;

    private String description;

    private String createdBy;

    private Long createdOn;

    private String labelsStr;

    private String propertiesStr;

    private ObjectId referencedArtifact;

    public Version() {
    }

    public Version(Long globalId, ObjectId contentId, String artifactId, Long version, String state, String name, String description, String createdBy, Long createdOn, String labelsStr, String propertiesStr, ObjectId referencedArtifact) {
        this.globalId = globalId;
        this.contentId = contentId;
        this.artifactId = artifactId;
        this.version = version;
        this.state = state;
        this.name = name;
        this.description = description;
        this.createdBy = createdBy;
        this.createdOn = createdOn;
        this.labelsStr = labelsStr;
        this.propertiesStr = propertiesStr;
        this.referencedArtifact = referencedArtifact;
    }

    public Long getGlobalId() {
        return globalId;
    }

    public void setGlobalId(Long globalId) {
        this.globalId = globalId;
    }

    public ObjectId getContentId() {
        return contentId;
    }

    public void setContentId(ObjectId contentId) {
        this.contentId = contentId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public Long getCreatedOn() {
        return createdOn;
    }

    public void setCreatedOn(Long createdOn) {
        this.createdOn = createdOn;
    }

    public String getLabelsStr() {
        return labelsStr;
    }

    public void setLabelsStr(String labelsStr) {
        this.labelsStr = labelsStr;
    }

    public String getPropertiesStr() {
        return propertiesStr;
    }

    public void setPropertiesStr(String propertiesStr) {
        this.propertiesStr = propertiesStr;
    }

    public ObjectId getReferencedArtifact() {
        return referencedArtifact;
    }

    public void setReferencedArtifact(ObjectId referencedArtifact) {
        this.referencedArtifact = referencedArtifact;
    }
}

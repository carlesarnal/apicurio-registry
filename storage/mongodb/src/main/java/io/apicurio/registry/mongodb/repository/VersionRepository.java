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

import io.apicurio.registry.mongodb.entity.Version;
import io.apicurio.registry.types.ArtifactState;
import io.quarkus.mongodb.panache.PanacheMongoRepository;
import io.quarkus.panache.common.Parameters;
import org.bson.types.ObjectId;

import javax.enterprise.context.ApplicationScoped;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class VersionRepository implements PanacheMongoRepository<Version> {


    public Long fetchMaxVersion(ObjectId artifactId) {

        return find("artifactId = ?1 where version = max(version)", artifactId)
                .firstResult()
                .getVersion();
    }

    public Version createVersion(boolean firstVersion, String artifactId, String name, String description, ArtifactState state,
                                 String createdBy, Date createdOn, String labelsStr, String propertiesStr, ObjectId contentId, ObjectId referencedArtifact) {

        //TODO generate globalId
        Long numVersion = 1L;
        if (!firstVersion) {
            numVersion = fetchMaxVersion(referencedArtifact);
        }

        final Version version = new Version(0L, contentId, artifactId, numVersion, state.name(), name, description, createdBy, createdOn.toInstant().toEpochMilli(), labelsStr, propertiesStr, referencedArtifact);
        persist(version);
        return version;
    }

    public Version getVersion(String artifactId, Long version) {
        return find("artifactId = ?1 and version = ?2", artifactId, version)
                .firstResult();
    }

    public Version findByGlobalId(long globalId) {
        return find("globalId = ?1", globalId).firstResult();
    }

    public List<Long> getArtifactVersions(String artifactId) {
        return list("artifactId = ?1", artifactId)
                .stream()
                .map(Version::getGlobalId)
                .collect(Collectors.toList());
    }
}
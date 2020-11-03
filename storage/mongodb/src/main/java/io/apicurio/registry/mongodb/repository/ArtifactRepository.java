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

import io.apicurio.registry.mongodb.entity.Artifact;
import io.apicurio.registry.rest.beans.ArtifactMetaData;
import io.quarkus.mongodb.panache.PanacheMongoRepositoryBase;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

@ApplicationScoped
public class ArtifactRepository implements PanacheMongoRepositoryBase<Artifact, String> {

    @Inject
    private VersionRepository versionRepository;

    public Artifact findById(String artifactId) {
        return find("artifactId", artifactId).firstResult();
    }

    public ArtifactMetaData updateLatestVersion(String artifactId, long latest) {
        update("latest = ?1 where artifactId = ?2", latest, artifactId);
        return versionRepository.getArtifactMetadata(artifactId);
    }
}

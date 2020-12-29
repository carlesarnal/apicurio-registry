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
import io.apicurio.registry.mongodb.entity.Version;
import io.apicurio.registry.mongodb.util.SerializationUtil;
import io.apicurio.registry.rest.beans.ArtifactMetaData;
import io.apicurio.registry.rest.beans.ArtifactSearchResults;
import io.apicurio.registry.rest.beans.SearchOver;
import io.apicurio.registry.rest.beans.SearchedArtifact;
import io.apicurio.registry.rest.beans.SortOrder;
import io.apicurio.registry.types.ArtifactState;
import io.apicurio.registry.types.ArtifactType;
import io.apicurio.registry.utils.StringUtil;
import io.quarkus.mongodb.panache.PanacheMongoRepository;
import io.quarkus.mongodb.panache.PanacheQuery;
import io.quarkus.panache.common.Parameters;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.List;
import java.util.stream.Collectors;

@ApplicationScoped
public class ArtifactRepository implements PanacheMongoRepository<Artifact> {

    @Inject
    private VersionRepository versionRepository;

    public Artifact findByArtifactId(String artifactId) {
        return find("artifactId", artifactId).firstResult();
    }

    public ArtifactMetaData updateLatestVersion(Artifact artifact, long latest) {
        artifact.setLatest(latest);
        update(artifact);
        return getArtifactMetadata(artifact);
    }

    public ArtifactMetaData getArtifactMetadata(Artifact artifact) {

        final Version version = versionRepository.find("globalId", artifact.getLatest())
                .firstResult();

        final ArtifactMetaData artifactMetaData = new ArtifactMetaData();

        artifactMetaData.setGlobalId(version.getGlobalId());
        artifactMetaData.setId(artifact.getArtifactId());
        artifactMetaData.setModifiedBy(version.getCreatedBy());
        artifactMetaData.setModifiedOn(version.getCreatedOn());
        artifactMetaData.setState(ArtifactState.fromValue(version.getState()));
        artifactMetaData.setName(version.getName());
        artifactMetaData.setDescription(version.getDescription());
        artifactMetaData.setType(ArtifactType.fromValue(artifact.getArtifactType()));
        artifactMetaData.setVersion(version.getVersion().intValue());
        artifactMetaData.setCreatedBy(artifact.getCreatedBy());
        artifactMetaData.setCreatedOn(artifact.getCreatedOn());

        return artifactMetaData;
    }

    public ArtifactSearchResults searchArtifacts(String search, int offset, int limit, SearchOver searchOver, SortOrder sortOrder) {

        if (!StringUtil.isEmpty(search)) {
            switch (searchOver) {
                case description:
                    return findByDescription(search, offset, limit, sortOrder);
                case everything:
                    return searchEverything(search, offset, limit, sortOrder);
                case labels:
                    return findByLabels(search, offset, limit, sortOrder);
                case name:
                    return findByName(search, offset, limit, sortOrder);
                default:
                    throw new IllegalStateException("No valid search over value");
            }
        } else {
            final PanacheQuery<Version> findAll = versionRepository.find("globalId in (a.latest from Artifact a)")
                    .range(offset, limit - 1);
            return buildSearchResult(findAll.list(), Long.valueOf(findAll.count()).intValue());
        }
    }

    private ArtifactSearchResults findByName(String search, int offset, int limit, SortOrder sortOrder) {

        final Parameters searchParams = Parameters.with("nameSearch", "%" + search + "%");

        final PanacheQuery<Version> nameSearch = versionRepository.find(SearchQueries.searchName + sortOrder.value(), searchParams)
                .range(offset, limit - 1);

        return buildSearchResult(nameSearch.list(), Long.valueOf(versionRepository.count(SearchQueries.searchNameCount, searchParams)).intValue());
    }

    private ArtifactSearchResults findByDescription(String search, int offset, int limit, SortOrder sortOrder) {

        final Parameters searchParams = Parameters.with("descriptionSearch", "%" + search + "%");

        final PanacheQuery<Version> descriptionSearch = versionRepository.find(SearchQueries.searchDescription + sortOrder.value(), searchParams)
                .range(offset, limit - 1);

        return buildSearchResult(descriptionSearch.list(), Long.valueOf(versionRepository.count(SearchQueries.searchDescriptionCount, searchParams)).intValue());
    }

    private ArtifactSearchResults findByLabels(String search, int offset, int limit, SortOrder sortOrder) {

        final Parameters searchParams = Parameters.with("labelSearch", "%" + search + "%");

        final PanacheQuery<Version> labelSearch = versionRepository.find(SearchQueries.searchLabels + sortOrder.value(), searchParams)
                .range(offset, limit - 1);

        return buildSearchResult(labelSearch.list(), Long.valueOf(versionRepository.count(SearchQueries.searchLabelsCount, searchParams)).intValue());
    }

    private ArtifactSearchResults searchEverything(String search, int offset, int limit, SortOrder sortOrder) {

        final Parameters searchParams = Parameters.with("nameSearch", "%" + search + "%")
                .and("descriptionSearch", "%" + search + "%")
                .and("labelSearch", "%" + search + "%");

        final PanacheQuery<Version> matchedVersions = versionRepository.find(SearchQueries.searchEverything + sortOrder.value(), searchParams)
                .range(offset, limit - 1);

        return buildSearchResult(matchedVersions.list(), Long.valueOf(versionRepository.count(SearchQueries.searchEverythingCount, searchParams)).intValue());
    }

    private ArtifactSearchResults buildSearchResult(List<Version> matchedVersions, int count) {

        final List<SearchedArtifact> searchedArtifacts = buildFromResult(matchedVersions);

        final ArtifactSearchResults artifactSearchResults = new ArtifactSearchResults();
        artifactSearchResults.setCount(count);
        artifactSearchResults.setArtifacts(searchedArtifacts);

        return artifactSearchResults;
    }

    private List<SearchedArtifact> buildFromResult(List<Version> matchedVersions) {

        return matchedVersions.stream()
                .map(this::buildFromVersion)
                .collect(Collectors.toList());
    }

    private SearchedArtifact buildFromVersion(Version version) {

        final Artifact artifact = findByArtifactId(version.getArtifactId());

        final SearchedArtifact searchedArtifact = new SearchedArtifact();
        searchedArtifact.setCreatedBy(artifact.getCreatedBy());
        searchedArtifact.setCreatedOn(artifact.getCreatedOn());
        searchedArtifact.setDescription(version.getDescription());
        searchedArtifact.setId(artifact.getArtifactId());
        searchedArtifact.setLabels(SerializationUtil.deserializeLabels(version.getLabelsStr()));
        searchedArtifact.setModifiedBy(version.getCreatedBy());
        searchedArtifact.setModifiedOn(version.getCreatedOn());
        searchedArtifact.setState(ArtifactState.fromValue(version.getState()));
        searchedArtifact.setType(ArtifactType.fromValue(artifact.getArtifactType()));
        searchedArtifact.setName(version.getName());
        return searchedArtifact;
    }

    public Version getArtifactLatestVersion(String artifactId) {

            return versionRepository.find("artifactId = :artifactId and globalId = :globalId", Parameters.with("artifactId", artifactId).and("globalId", findByArtifactId(artifactId).getLatest()))
                    .firstResult();
    }

    private static class SearchQueries {

        protected static final String searchNameCount = "name like :nameSearch and v.globalId IN (a.latest from Artifact a)";

        protected static final String searchName = "name like :nameSearch and v.globalId IN (a.latest from Artifact a)" +
                " group by v.artifact, v.globalId" +
                " order by(COALESCE(v.artifact, v.name)) ";

        protected static final String searchDescriptionCount = "description like :descriptionSearch and v.globalId IN (a.latest from Artifact a)";

        protected static final String searchDescription = "description like :descriptionSearch and v.globalId IN (a.latest from Artifact a)" +
                " group by v.artifact, v.globalId" +
                " order by(COALESCE(v.artifact, v.name))";

        protected static final String searchLabels = "labelsStr like :labelSearch and v.globalId IN (a.latest from Artifact a)" +
                " group by v.artifact, v.globalId" +
                " order by(COALESCE(v.artifact, v.name)) ";

        protected static final String searchLabelsCount = "labelsStr like :labelSearch and v.globalId IN (a.latest from Artifact a)";

        protected static final String searchEverything = "(v.name like :nameSearch" +
                " OR v.description like :descriptionSearch" +
                " OR v.labelsStr like :labelSearch" +
                ")" +
                " AND v.globalId IN (a.latest from Artifact a)" +
                " group by v.artifact, v.globalId" +
                " order by(COALESCE(v.artifact, v.name)) ";

        protected static final String searchEverythingCount = "from Version v where (v.name like :nameSearch" +
                " OR v.description like :descriptionSearch" +
                " OR v.labelsStr like :labelSearch" +
                ")" +
                " AND v.globalId IN (a.latest from Artifact a)";
    }
}

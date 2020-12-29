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

package io.apicurio.registry.mongodb;

import io.apicurio.registry.content.ContentHandle;
import io.apicurio.registry.content.canon.ContentCanonicalizer;
import io.apicurio.registry.content.extract.ContentExtractor;
import io.apicurio.registry.logging.Logged;
import io.apicurio.registry.metrics.PersistenceExceptionLivenessApply;
import io.apicurio.registry.metrics.PersistenceTimeoutReadinessApply;
import io.apicurio.registry.mongodb.entity.Artifact;
import io.apicurio.registry.mongodb.entity.Content;
import io.apicurio.registry.mongodb.entity.Version;
import io.apicurio.registry.mongodb.repository.ArtifactRepository;
import io.apicurio.registry.mongodb.repository.ContentRepository;
import io.apicurio.registry.mongodb.repository.LabelRepository;
import io.apicurio.registry.mongodb.repository.PropertyRepository;
import io.apicurio.registry.mongodb.repository.VersionRepository;
import io.apicurio.registry.mongodb.util.SerializationUtil;
import io.apicurio.registry.rest.beans.ArtifactMetaData;
import io.apicurio.registry.rest.beans.ArtifactSearchResults;
import io.apicurio.registry.rest.beans.EditableMetaData;
import io.apicurio.registry.rest.beans.SearchOver;
import io.apicurio.registry.rest.beans.SortOrder;
import io.apicurio.registry.rest.beans.VersionSearchResults;
import io.apicurio.registry.storage.ArtifactAlreadyExistsException;
import io.apicurio.registry.storage.ArtifactMetaDataDto;
import io.apicurio.registry.storage.ArtifactNotFoundException;
import io.apicurio.registry.storage.ArtifactVersionMetaDataDto;
import io.apicurio.registry.storage.EditableArtifactMetaDataDto;
import io.apicurio.registry.storage.RegistryStorageException;
import io.apicurio.registry.storage.RuleAlreadyExistsException;
import io.apicurio.registry.storage.RuleConfigurationDto;
import io.apicurio.registry.storage.RuleNotFoundException;
import io.apicurio.registry.storage.StoredArtifact;
import io.apicurio.registry.storage.VersionNotFoundException;
import io.apicurio.registry.storage.impl.AbstractRegistryStorage;
import io.apicurio.registry.types.ArtifactState;
import io.apicurio.registry.types.ArtifactType;
import io.apicurio.registry.types.RuleType;
import io.apicurio.registry.types.provider.ArtifactTypeUtilProvider;
import io.apicurio.registry.types.provider.ArtifactTypeUtilProviderFactory;
import org.apache.commons.codec.digest.DigestUtils;
import org.eclipse.microprofile.metrics.annotation.ConcurrentGauge;
import org.eclipse.microprofile.metrics.annotation.Counted;
import org.eclipse.microprofile.metrics.annotation.Timed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static io.apicurio.registry.metrics.MetricIDs.STORAGE_CONCURRENT_OPERATION_COUNT;
import static io.apicurio.registry.metrics.MetricIDs.STORAGE_CONCURRENT_OPERATION_COUNT_DESC;
import static io.apicurio.registry.metrics.MetricIDs.STORAGE_GROUP_TAG;
import static io.apicurio.registry.metrics.MetricIDs.STORAGE_OPERATION_COUNT;
import static io.apicurio.registry.metrics.MetricIDs.STORAGE_OPERATION_COUNT_DESC;
import static io.apicurio.registry.metrics.MetricIDs.STORAGE_OPERATION_TIME;
import static io.apicurio.registry.metrics.MetricIDs.STORAGE_OPERATION_TIME_DESC;
import static org.eclipse.microprofile.metrics.MetricUnits.MILLISECONDS;

@ApplicationScoped
@PersistenceExceptionLivenessApply
@PersistenceTimeoutReadinessApply
@Counted(name = STORAGE_OPERATION_COUNT, description = STORAGE_OPERATION_COUNT_DESC, tags = {"group=" + STORAGE_GROUP_TAG, "metric=" + STORAGE_OPERATION_COUNT})
@ConcurrentGauge(name = STORAGE_CONCURRENT_OPERATION_COUNT, description = STORAGE_CONCURRENT_OPERATION_COUNT_DESC, tags = {"group=" + STORAGE_GROUP_TAG, "metric=" + STORAGE_CONCURRENT_OPERATION_COUNT})
@Timed(name = STORAGE_OPERATION_TIME, description = STORAGE_OPERATION_TIME_DESC, tags = {"group=" + STORAGE_GROUP_TAG, "metric=" + STORAGE_OPERATION_TIME}, unit = MILLISECONDS)
@Logged
@SuppressWarnings("unchecked")
public class MongoDbRegistryStorage extends AbstractRegistryStorage {

    private static final Logger logger = LoggerFactory.getLogger(MongoDbRegistryStorage.class);

    @Inject
    ArtifactRepository artifactRepository;

    @Inject
    ContentRepository contentRepository;

    @Inject
    VersionRepository versionRepository;

    @Inject
    PropertyRepository propertyRepository;

    @Inject
    LabelRepository labelRepository;

    @Inject
    ArtifactTypeUtilProviderFactory factory;

    @Override
    public void updateArtifactState(String artifactId, ArtifactState state) {
        logger.debug("Updating the state of artifact {} to {}", artifactId, state.name());
    }

    @Override
    public void updateArtifactState(String artifactId, ArtifactState state, Integer version) {
        logger.debug("Updating the state of artifact {}, version {} to {}", artifactId, version, state.name());
    }

    @Override
    public CompletionStage<ArtifactMetaDataDto> createArtifact(String artifactId, ArtifactType artifactType, ContentHandle content) throws ArtifactAlreadyExistsException, RegistryStorageException {
        logger.debug("Inserting an artifact row for: {}", artifactId);
        String createdBy = null;
        Date createdOn = new Date();

        try {
            // Extract meta-data from the content
            ArtifactTypeUtilProvider provider = factory.getArtifactTypeProvider(artifactType);
            ContentExtractor extractor = provider.getContentExtractor();
            EditableMetaData emd = extractor.extract(content);
            EditableArtifactMetaDataDto metaData = null;
            if (emd != null) {
                 metaData = new EditableArtifactMetaDataDto(emd.getName(), emd.getDescription(), emd.getLabels(), emd.getProperties());
            }

            ArtifactMetaDataDto amdd = createArtifactInternal(artifactId, artifactType, content,
                    createdBy, createdOn, metaData);

            return CompletableFuture.completedFuture(amdd);

        } catch (Exception e) {
            throw new RegistryStorageException(e);
        }
    }

    /**
     * Creates an artifact version by storing content in the versions table.
     *
     * @param firstVersion
     * @param artifact
     * @param contentHandle
     */
    private ArtifactVersionMetaDataDto createArtifactVersion(ArtifactType artifactType,
                                                             boolean firstVersion, Artifact artifact, String name, String description, List<String> labels,
                                                             Map<String, String> properties, ContentHandle contentHandle) {

        final ArtifactState state = ArtifactState.ENABLED;
        String createdBy = null;
        final Date createdOn = new Date();
        final byte[] contentBytes = contentHandle.bytes();
        final String contentHash = DigestUtils.sha256Hex(contentBytes);
        final String labelsStr = SerializationUtil.serializeLabels(labels);
        final String propertiesStr = SerializationUtil.serializeProperties(properties);

        ArtifactTypeUtilProvider provider = factory.getArtifactTypeProvider(artifactType);
        ContentCanonicalizer canonicalizer = provider.getContentCanonicalizer();
        ContentHandle canonicalContent = canonicalizer.canonicalize(contentHandle);
        byte[] canonicalContentBytes = canonicalContent.bytes();
        String canonicalContentHash = DigestUtils.sha256Hex(canonicalContentBytes);

        final Content content = contentRepository.upsertContentByHash(contentHash, contentBytes, canonicalContentHash);
        final Version version = versionRepository.createVersion(firstVersion, artifact.getArtifactId(), name, description, state, createdBy, createdOn, labelsStr, propertiesStr, content.id, artifact.id);

        labelRepository.persistLabels(labels, version.id.toHexString());
        propertyRepository.persistProperties(properties, version);

        //TODO extract
        final ArtifactVersionMetaDataDto artifactVersionMetaDataDto = new ArtifactVersionMetaDataDto();
        artifactVersionMetaDataDto.setVersion(version.getVersion().intValue());
        artifactVersionMetaDataDto.setCreatedBy(version.getCreatedBy());
        artifactVersionMetaDataDto.setCreatedOn(version.getCreatedOn());
        artifactVersionMetaDataDto.setDescription(version.getDescription());
        artifactVersionMetaDataDto.setGlobalId(version.getGlobalId());
        artifactVersionMetaDataDto.setName(version.getName());
        artifactVersionMetaDataDto.setState(ArtifactState.fromValue(version.getState()));
        artifactVersionMetaDataDto.setType(artifactType);

        return artifactVersionMetaDataDto;
    }

    @Override
    public CompletionStage<ArtifactMetaDataDto> createArtifactWithMetadata(String artifactId, ArtifactType artifactType, ContentHandle content, EditableArtifactMetaDataDto metaData) throws ArtifactAlreadyExistsException, RegistryStorageException {
        logger.debug("Inserting an artifact (with meta-data) row for: {}", artifactId);
        String createdBy = null;
        Date createdOn = new Date();
        ArtifactMetaDataDto amdd = createArtifactInternal(artifactId, artifactType, content,
                createdBy, createdOn, metaData);

        return CompletableFuture.completedFuture(amdd);
    }

    @Override
    public SortedSet<Long> deleteArtifact(String artifactId) throws ArtifactNotFoundException, RegistryStorageException {
        logger.debug("Deleting an artifact: {}", artifactId);
        return null;
    }

    @Override
    public StoredArtifact getArtifact(String artifactId) throws ArtifactNotFoundException, RegistryStorageException {
        logger.debug("Selecting a single artifact (latest version) by artifactId: {}", artifactId);

        final Version version = artifactRepository.getArtifactLatestVersion(artifactId);
        final Content content = contentRepository.findById(version.getContentId());

        return StoredArtifact.builder()
                .content(ContentHandle.create(content.getContent()))
                .globalId(version.getGlobalId())
                .version(version.getVersion())
                .build();
    }

    @Override
    public CompletionStage<ArtifactMetaDataDto> updateArtifact(String artifactId, ArtifactType artifactType, ContentHandle content) throws ArtifactNotFoundException, RegistryStorageException {
        logger.debug("Updating artifact {} with a new version (content).", artifactId);
        return null;
    }

    @Override
    public CompletionStage<ArtifactMetaDataDto> updateArtifactWithMetadata(String artifactId, ArtifactType artifactType, ContentHandle content, EditableArtifactMetaDataDto metaData) throws ArtifactNotFoundException, RegistryStorageException {
        return null;
    }

    @Override
    public Set<String> getArtifactIds(Integer limit) {
        return null;
    }

    @Override
    public ArtifactSearchResults searchArtifacts(String search, int offset, int limit, SearchOver searchOver, SortOrder sortOrder) {

        logger.debug("Searching for artifacts: {} over {} with {} ordering", search, searchOver, sortOrder);

        try {
            return artifactRepository.searchArtifacts(search, offset, limit, searchOver, sortOrder);
        } catch (Exception e) {
            throw new RegistryStorageException(e);
        }
    }

    @Override
    public ArtifactMetaDataDto getArtifactMetaData(String artifactId) throws ArtifactNotFoundException, RegistryStorageException {
        logger.debug("Selecting artifact (latest version) meta-data: {}", artifactId);

        return this.getLatestArtifactMetaDataInternal(artifactId);
    }

    @Override
    public ArtifactVersionMetaDataDto getArtifactVersionMetaData(String artifactId, boolean canonical, ContentHandle content) throws ArtifactNotFoundException, RegistryStorageException {
        return null;
    }

    /**
     * Internal method to retrieve the meta-data of the latest version of the given artifact.
     *
     * @param artifactId
     */
    private ArtifactMetaDataDto getLatestArtifactMetaDataInternal(String artifactId) {

        final Artifact artifact = artifactRepository.findByArtifactId(artifactId);
        final ArtifactMetaData metaData = artifactRepository.getArtifactMetadata(artifact);
        final ArtifactMetaDataDto dto = new ArtifactMetaDataDto();

        dto.setDescription(metaData.getDescription());
        dto.setGlobalId(metaData.getGlobalId());
        dto.setId(metaData.getId());
        dto.setLabels(metaData.getLabels());
        dto.setModifiedBy(metaData.getModifiedBy());
        dto.setModifiedOn(metaData.getModifiedOn());
        dto.setName(metaData.getName());
        dto.setType(metaData.getType());
        dto.setState(metaData.getState());
        dto.setProperties(metaData.getProperties());
        dto.setVersion(metaData.getVersion());

        return dto;
    }

    /**
     * Internal method to retrieve the meta-data of the given version of the given artifact.
     *
     * @param artifactId
     * @param version
     */
    private ArtifactVersionMetaDataDto getArtifactVersionMetaDataInternal(String artifactId, Long version) {

        final Version metaData = versionRepository.getVersion(artifactId, version);
        final ArtifactVersionMetaDataDto dto = new ArtifactVersionMetaDataDto();

        dto.setDescription(metaData.getDescription());
        dto.setGlobalId(metaData.getGlobalId());
        dto.setName(metaData.getName());
        dto.setState(ArtifactState.fromValue(metaData.getState()));
        dto.setVersion(metaData.getVersion().intValue());

        return dto;
    }

    @Override
    public ArtifactMetaDataDto getArtifactMetaData(long id) throws ArtifactNotFoundException, RegistryStorageException {
        logger.debug("Getting meta-data for globalId: {}", id);

        final Version metaData = versionRepository.findByGlobalId(id);
        final ArtifactMetaDataDto dto = new ArtifactMetaDataDto();

        dto.setDescription(metaData.getDescription());
        dto.setGlobalId(metaData.getGlobalId());
        dto.setName(metaData.getName());
        dto.setState(ArtifactState.fromValue(metaData.getState()));
        dto.setVersion(metaData.getVersion().intValue());

        return dto;
    }

    @Override
    public void updateArtifactMetaData(String artifactId, EditableArtifactMetaDataDto metaData) throws ArtifactNotFoundException, RegistryStorageException {

    }

    @Override
    public List<RuleType> getArtifactRules(String artifactId) throws ArtifactNotFoundException, RegistryStorageException {
        return null;
    }

    @Override
    public void createArtifactRule(String artifactId, RuleType rule, RuleConfigurationDto config) throws ArtifactNotFoundException, RuleAlreadyExistsException, RegistryStorageException {

    }

    @Override
    public CompletionStage<Void> createArtifactRuleAsync(String artifactId, RuleType rule, RuleConfigurationDto config) throws ArtifactNotFoundException, RuleAlreadyExistsException, RegistryStorageException {
        return null;
    }

    @Override
    public void deleteArtifactRules(String artifactId) throws ArtifactNotFoundException, RegistryStorageException {

    }

    @Override
    public RuleConfigurationDto getArtifactRule(String artifactId, RuleType rule) throws ArtifactNotFoundException, RuleNotFoundException, RegistryStorageException {
        return null;
    }

    @Override
    public void updateArtifactRule(String artifactId, RuleType rule, RuleConfigurationDto config) throws ArtifactNotFoundException, RuleNotFoundException, RegistryStorageException {

    }

    @Override
    public void deleteArtifactRule(String artifactId, RuleType rule) throws ArtifactNotFoundException, RuleNotFoundException, RegistryStorageException {

    }

    @Override
    public SortedSet<Long> getArtifactVersions(String artifactId) throws ArtifactNotFoundException, RegistryStorageException {
        logger.debug("Getting a list of versions for an artifact");
        return new TreeSet<>(versionRepository.getArtifactVersions(artifactId));
    }

    @Override
    public VersionSearchResults searchVersions(String artifactId, int offset, int limit) {
        return null;
    }

    @Override
    public StoredArtifact getArtifactVersion(long id) throws ArtifactNotFoundException, RegistryStorageException {
        logger.debug("Selecting a single artifact version by globalId: {}", id);

        final Version version = versionRepository.findByGlobalId(id);
        final Content content = contentRepository.findById(version.getContentId());

        return StoredArtifact.builder()
                .content(ContentHandle.create(content.getContent()))
                .globalId(id)
                .version(version.getVersion())
                .build();
    }

    @Override
    public StoredArtifact getArtifactVersion(String artifactId, long numVersion) throws ArtifactNotFoundException, VersionNotFoundException, RegistryStorageException {

        logger.debug("Selecting a single artifact version by artifactId: {} and version {}", artifactId, numVersion);

        final Version version = versionRepository.getVersion(artifactId, numVersion);
        final Content content = contentRepository.findById(version.getContentId());

        return StoredArtifact.builder()
                .content(ContentHandle.create(content.getContent()))
                .globalId(version.getGlobalId())
                .version(version.getVersion())
                .build();
    }

    @Override
    public void deleteArtifactVersion(String artifactId, long version) throws ArtifactNotFoundException, VersionNotFoundException, RegistryStorageException {

    }

    @Override
    public ArtifactVersionMetaDataDto getArtifactVersionMetaData(String artifactId, long version) throws ArtifactNotFoundException, VersionNotFoundException, RegistryStorageException {
        logger.debug("Selecting artifact version meta-data: {} version {}", artifactId, version);
        try {
            return this.getArtifactVersionMetaDataInternal(artifactId, version);
        } catch (IllegalStateException e) {
            throw new VersionNotFoundException(artifactId, version);
        } catch (Exception e) {
            throw new RegistryStorageException(e);
        }
    }

    @Override
    public void updateArtifactVersionMetaData(String artifactId, long version, EditableArtifactMetaDataDto metaData) throws ArtifactNotFoundException, VersionNotFoundException, RegistryStorageException {

    }

    @Override
    public void deleteArtifactVersionMetaData(String artifactId, long version) throws ArtifactNotFoundException, VersionNotFoundException, RegistryStorageException {

    }

    @Override
    public List<RuleType> getGlobalRules() throws RegistryStorageException {
        return Collections.EMPTY_LIST;
    }

    @Override
    public void createGlobalRule(RuleType rule, RuleConfigurationDto config) throws RuleAlreadyExistsException, RegistryStorageException {

    }

    @Override
    public void deleteGlobalRules() throws RegistryStorageException {

    }

    @Override
    public RuleConfigurationDto getGlobalRule(RuleType rule) throws RuleNotFoundException, RegistryStorageException {
        return null;
    }

    @Override
    public void updateGlobalRule(RuleType rule, RuleConfigurationDto config) throws RuleNotFoundException, RegistryStorageException {

    }

    @Override
    public void deleteGlobalRule(RuleType rule) throws RuleNotFoundException, RegistryStorageException {

    }


    /**
     * Internal method to create a new artifact.
     *
     * @param artifactId
     * @param artifactType
     * @param content
     * @param createdBy
     * @param createdOn
     * @param metaData
     */
    private ArtifactMetaDataDto createArtifactInternal(String artifactId, ArtifactType artifactType,
                                                       ContentHandle content, String createdBy, Date createdOn, EditableArtifactMetaDataDto metaData) {

        final Artifact artifact = new Artifact(artifactId, artifactType.name(), null, Instant.now().toEpochMilli(), null);

        artifactRepository.persist(artifact);

        ArtifactVersionMetaDataDto vmdd = null;
        if (metaData != null) {
            // Then create a row in the content and versions tables (for the content and version meta-data)
            vmdd = this.createArtifactVersion(artifactType, true, artifact,
                    metaData.getName(), metaData.getDescription(), metaData.getLabels(), metaData.getProperties(), content);
        } else {
            vmdd = this.createArtifactVersion(artifactType, true, artifact,
                    null, null, null, null, content);
        }

        // Update the "latest" column in the artifacts collection with the globalId of the new version
        artifactRepository.updateLatestVersion(artifact, vmdd.getGlobalId());

        // Return the new artifact meta-data
        ArtifactMetaDataDto amdd = versionToArtifactDto(artifactId, vmdd);
        amdd.setCreatedBy(createdBy);
        amdd.setCreatedOn(createdOn.getTime());
        return amdd;
    }

    /**
     * Converts a version DTO to an artifact DTO.
     *
     * @param artifactId
     * @param vmdd
     */
    private ArtifactMetaDataDto versionToArtifactDto(String artifactId, ArtifactVersionMetaDataDto vmdd) {
        ArtifactMetaDataDto amdd = new ArtifactMetaDataDto();
        amdd.setGlobalId(vmdd.getGlobalId());
        amdd.setId(artifactId);
        amdd.setModifiedBy(vmdd.getCreatedBy());
        amdd.setModifiedOn(vmdd.getCreatedOn());
        amdd.setState(vmdd.getState());
        amdd.setName(vmdd.getName());
        amdd.setDescription(vmdd.getDescription());
        amdd.setType(vmdd.getType());
        amdd.setVersion(vmdd.getVersion());
        return amdd;
    }
}

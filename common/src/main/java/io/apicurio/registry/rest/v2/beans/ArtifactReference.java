
package io.apicurio.registry.rest.v2.beans;

import javax.annotation.processing.Generated;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;


/**
 * Root Type for ArtifactReference
 * <p>
 * A reference to a different artifact. Typically used with artifact types that can have dependencies like Protobuf.
 * 
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
    "groupId",
    "artifactId",
    "version",
    "name"
})
@Generated("jsonschema2pojo")
@io.quarkus.runtime.annotations.RegisterForReflection
@lombok.ToString
public class ArtifactReference {

    /**
     * 
     * (Required)
     * 
     */
    @JsonProperty("groupId")
    private String groupId;
    /**
     * 
     * (Required)
     * 
     */
    @JsonProperty("artifactId")
    private String artifactId;
    @JsonProperty("version")
    private String version;
    /**
     * 
     * (Required)
     * 
     */
    @JsonProperty("name")
    private String name;

    /**
     * 
     * (Required)
     * 
     */
    @JsonProperty("groupId")
    public String getGroupId() {
        return groupId;
    }

    /**
     * 
     * (Required)
     * 
     */
    @JsonProperty("groupId")
    public void setGroupId(String groupId) {
        this.groupId = groupId;
    }

    /**
     * 
     * (Required)
     * 
     */
    @JsonProperty("artifactId")
    public String getArtifactId() {
        return artifactId;
    }

    /**
     * 
     * (Required)
     * 
     */
    @JsonProperty("artifactId")
    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    @JsonProperty("version")
    public String getVersion() {
        return version;
    }

    @JsonProperty("version")
    public void setVersion(String version) {
        this.version = version;
    }

    /**
     * 
     * (Required)
     * 
     */
    @JsonProperty("name")
    public String getName() {
        return name;
    }

    /**
     * 
     * (Required)
     * 
     */
    @JsonProperty("name")
    public void setName(String name) {
        this.name = name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ArtifactReference reference = (ArtifactReference) o;

        if (groupId != null ? !groupId.equals(reference.groupId) : reference.groupId != null) return false;
        if (artifactId != null ? !artifactId.equals(reference.artifactId) : reference.artifactId != null) return false;
        if (version != null ? !version.equals(reference.version) : reference.version != null) return false;
        if (name != null ? !name.equals(reference.name) : reference.name != null) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = groupId != null ? groupId.hashCode() : 0;
        result = 31 * result + (artifactId != null ? artifactId.hashCode() : 0);
        result = 31 * result + (version != null ? version.hashCode() : 0);
        result = 31 * result + (name != null ? name.hashCode() : 0);
        return result;
    }
}

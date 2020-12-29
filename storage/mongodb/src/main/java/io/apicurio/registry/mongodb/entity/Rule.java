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
import org.bson.types.ObjectId;

@MongoEntity(collection = "rule")
public class Rule {

	private String artifactId;

	private ObjectId referencedArtifact;

	private String type;

	private String configuration;

	public Rule(String artifactId, ObjectId referencedArtifact, String type, String configuration) {
		this.artifactId = artifactId;
		this.referencedArtifact = referencedArtifact;
		this.type = type;
		this.configuration = configuration;
	}

	public String getArtifactId() {
		return artifactId;
	}

	public void setArtifactId(String artifactId) {
		this.artifactId = artifactId;
	}

	public ObjectId getReferencedArtifact() {
		return referencedArtifact;
	}

	public void setReferencedArtifact(ObjectId referencedArtifact) {
		this.referencedArtifact = referencedArtifact;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getConfiguration() {
		return configuration;
	}

	public void setConfiguration(String configuration) {
		this.configuration = configuration;
	}
}

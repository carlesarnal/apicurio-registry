/*
 * Copyright 2022 Red Hat
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

package io.apicurio.registry.rest.v1;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Response;

import io.apicurio.registry.rest.v1.beans.ArtifactMetaData;

/**
 * A JAX-RS interface.  An implementation of this interface must be provided.
 */
@Path("/apis/registry/v1/ids")
public interface IdsResource {
  /**
   * Gets the content for an artifact version in the registry using its globally unique
   * identifier.
   *
   * This operation may fail for one of the following reasons:
   *
   * * No artifact version with this `globalId` exists (HTTP error `404`)
   * * A server error occurred (HTTP error `500`)
   *
   */
  @Path("/{globalId}")
  @GET
  @Produces({"application/json", "application/x-protobuf", "application/x-protobuffer"})
  Response getArtifactByGlobalId(@PathParam("globalId") long globalId);

  /**
   * Gets the metadata for an artifact version in the registry using its globally unique
   * identifier.  The returned metadata includes both generated (read-only) and editable
   * metadata (such as name and description).
   *
   * This operation may fail for one of the following reasons:
   *
   * * No artifact version with this `globalId` exists (HTTP error `404`)
   * * A server error occurred (HTTP error `500`)
   *
   */
  @Path("/{globalId}/meta")
  @GET
  @Produces("application/json")
  ArtifactMetaData getArtifactMetaDataByGlobalId(@PathParam("globalId") long globalId);
}

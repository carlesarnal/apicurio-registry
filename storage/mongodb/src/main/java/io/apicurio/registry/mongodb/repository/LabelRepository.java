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

import io.apicurio.registry.mongodb.entity.Label;
import io.apicurio.registry.mongodb.entity.Version;
import io.quarkus.mongodb.panache.PanacheMongoRepository;

import java.util.List;

public class LabelRepository implements PanacheMongoRepository<Label> {

    public void persistLabels(List<String> labels, Version version) {
        if (labels != null && !labels.isEmpty()) {
            labels.forEach(labelStr -> {

                Label label = new Label();
                label.label = labelStr;
                label.version = version;
                persist(label);
            });
        }
    }
}

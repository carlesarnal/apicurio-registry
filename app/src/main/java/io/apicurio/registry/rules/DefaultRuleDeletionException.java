/*
 * Copyright 2020 IBM
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

package io.apicurio.registry.rules;

import io.apicurio.registry.exception.RegistryException;
import io.apicurio.registry.types.RuleType;
import lombok.Getter;

/**
 * Exception thrown when attempting to delete a configured default rule.
 */
public class DefaultRuleDeletionException extends RegistryException {

    private static final long serialVersionUID = 1599508405950159L;

    @Getter
    private final RuleType ruleType;

    /**
     * Constructor.
     */
    public DefaultRuleDeletionException(RuleType ruleType) {
        this.ruleType = ruleType;
    }

    /**
     * @see java.lang.Throwable#getMessage()
     */
    @Override
    public String getMessage() {
        return "Default rule '" + this.ruleType.name() + "' cannot be deleted.";
    }
}

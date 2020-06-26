/**
 * Copyright (C) 2011-2020 Red Hat, Inc. (https://github.com/Commonjava/indy)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.indy.model.core;

import java.util.Map;

/**
 * Represents different types of package content (eg. maven, NPM, etc.). This is part of the data represented by
 * {@link org.commonjava.indy.model.core.StoreKey}, but rather than using an enum we need this to be extensible. So, this
 * interface offers a way to define new package types and also load the available package types, using
 * {@link java.util.ServiceLoader}.
 *
 * Created by jdcasey on 5/10/17.
 *
 * @see PackageTypes
 */
public interface PackageTypeDescriptor
{
    String getKey();

    /**
     * The base-path for accessing content of this package type. For example, for 'maven' it should be:
     * <pre>
     *     /api/content/maven
     * </pre>
     * @return
     */
    String getContentRestBasePath();

    /**
     * The base-path for accessing {@link ArtifactStore} definitions with this packageType. For example, for 'maven' it
     * should be:
     * <pre>
     *     /api/admin/maven
     * </pre>
     * @return
     */
    String getAdminRestBasePath();

    /**
     * If the core repository functions use browsing, as with PyPI, then this should be true. For other types of packages,
     * this should probably be <code>false</code>, in order to redirect browsing users to the browsing API.
     *
     * @return false by default, but true if content browsing (directory rendering) is a core part of the repository function.
     */
    default boolean isBrowsingIntegrated(){ return false; }
}

/**
 * Copyright (C) 2011-2018 Red Hat, Inc. (https://github.com/Commonjava/indy)
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
package org.commonjava.indy.pkg.maven.content.group;

import org.apache.maven.artifact.repository.metadata.Metadata;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.model.core.StoreKey;

/**
 * Created by jdcasey on 11/1/16.
 *
 * Interface for add-ons that contribute "virtual" metadata that originates from potential repositories that haven't
 * been created yet, such as the Koji add-on.
 */
public interface MavenMetadataProvider
{
    /**
     * Retrieve the list of versions available for the given Maven GA (groupId:artifactId), which should be included
     * in the target store.
     * @param targetStore Usually a group, this is the target where metadata aggregation is happening.
     * @param path path of the maven-metadata.xml that was requested. Useful to determine what kind of metadata to retrieve.
     * @return a {@link Metadata} instance containing the "virtual" versions that could become available.
     * @throws IndyWorkflowException
     */
    Metadata getMetadata( StoreKey targetStore, String path )
            throws IndyWorkflowException;

    void clearMetadata( StoreKey key, String path );
}

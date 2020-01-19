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
package org.commonjava.indy.pkg.maven.content;

import org.commonjava.indy.content.DirectContentAccess;
import org.commonjava.indy.content.MergedContentAction;
import org.commonjava.propulsor.metrics.annotation.Measure;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.Set;

/**
 * This listener will do these tasks:
 * <ul>
 *     <li>When the metadata file changed of a member in a group, delete correspond cache of that file path of the member and group (cascaded)</li>
 * </ul>
 */
@ApplicationScoped
public class MetadataMergeListener
        implements MergedContentAction
{
    final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private DirectContentAccess fileManager;

    @Inject
    private MetadataCacheManager cacheManager;

    /**
     * Will clear the both merge path and merge info file of member and group contains that member(cascaded)
     * if that path of file changed in the member of #originatingStore
     */
    @Override
    @Measure
    public void clearMergedPath( ArtifactStore originatingStore, Set<Group> affectedGroups, String path )
    {
        logger.debug( "Clear merged path {}, origin: {}, affected: {}", path, originatingStore, affectedGroups );
        cacheManager.remove( new MetadataKey( originatingStore.getKey(), path ) );
        affectedGroups.forEach( group -> {
            cacheManager.remove( new MetadataKey( group.getKey(), path ) );
        } );
    }

}

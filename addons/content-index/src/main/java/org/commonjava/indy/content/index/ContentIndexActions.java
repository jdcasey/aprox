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
package org.commonjava.indy.content.index;

import org.commonjava.cdi.util.weft.ThreadContext;
import org.commonjava.indy.content.MergedContentAction;
import org.commonjava.indy.content.StoreContentAction;
import org.commonjava.propulsor.metrics.annotation.Measure;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.StoreKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.Set;

/**
 * <p>Maintain the content index in response to events propagating through the system.</p>
 * <p><b>TODO:</b> This observer breaks things subtly.</p>
 * <p>When it removes a metadata file from the index, it also cleans up the Transfers (files) associated with merged
 * content as it propagates index removals up the group inclusion chain. If we roll a distribution that doesn't include
 * this, some merged-metadata problems may come back...</p>
 * <br/>
 * Created by jdcasey on 3/15/16.
 */
@ApplicationScoped
public class ContentIndexActions
        implements MergedContentAction, StoreContentAction
{
    private static final String ORIGIN_KEY = "ContentIndex:originKey";

    @Inject
    private ContentIndexManager indexManager;

    protected ContentIndexActions()
    {
    }

    public ContentIndexActions( final ContentIndexManager indexManager )
    {
        this.indexManager = indexManager;
    }

    @Measure
    public void clearMergedPath( ArtifactStore originatingStore, Set<Group> groups, String path )
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.debug( "Clearing merged path: {} from indexes of: {} (triggered by: {})", path, groups, originatingStore );

        StoreKey key = originatingStore.getKey();

        ThreadContext context = ThreadContext.getContext( true );
        context.put( ORIGIN_KEY, key );
        try
        {
            // the only time a group will have local storage of the path is when it has been merged
            // ...in which case we should try to delete it.
            indexManager.clearIndexedPathFrom( path, groups, null );
        }
        finally
        {
            context.remove( ORIGIN_KEY );
        }
    }

    @Override
    @Measure
    public void clearStoreContent( String path, ArtifactStore store, Set<Group> affectedGroups,
                                   boolean clearOriginPath )
    {
        if ( clearOriginPath )
        {
            indexManager.deIndexStorePath( store.getKey(), path );
        }

        indexManager.clearIndexedPathFrom( path, affectedGroups, null );
    }

}

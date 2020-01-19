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
package org.commonjava.indy.core.content;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import javax.annotation.PostConstruct;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;

import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.content.AbstractContentGenerator;
import org.commonjava.indy.content.DirectContentAccess;
import org.commonjava.indy.content.MergedContentAction;
import org.commonjava.indy.core.content.group.GroupMergeHelper;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.propulsor.metrics.annotation.Measure;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.spi.nfc.NotFoundCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractMergedContentGenerator
    extends AbstractContentGenerator
{
    protected final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    protected DirectContentAccess fileManager;

    @Inject
    protected StoreDataManager storeManager;

    @Inject
    protected GroupMergeHelper helper;

    @Inject
    private NotFoundCache nfc;

    @Inject
    private Instance<MergedContentAction> mergedContentActionInjected;

    private Iterable<MergedContentAction> mergedContentActions;

    protected AbstractMergedContentGenerator()
    {
    }

    protected AbstractMergedContentGenerator( final DirectContentAccess fileManager, final StoreDataManager storeManager,
                                              final GroupMergeHelper helper, final NotFoundCache nfc, final MergedContentAction...mergedContentActions )
    {
        this.fileManager = fileManager;
        this.storeManager = storeManager;
        this.helper = helper;
        this.nfc = nfc;
        this.mergedContentActions = Arrays.asList( mergedContentActions );
    }

    @PostConstruct
    public void cdiInit()
    {
        this.mergedContentActions = mergedContentActionInjected;
    }

    @Override
    public final void handleContentDeletion( final ArtifactStore store, final String path,
                                             final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        if ( path.endsWith( getMergedMetadataName() ) )
        {
            clearAllMerged( store, path );
        }
    }

    @Override
    public final void handleContentStorage( final ArtifactStore store, final String path, final Transfer result,
                                            final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        if ( path.endsWith( getMergedMetadataName() ) )
        {
            clearAllMerged( store, path );
        }
    }

    protected abstract String getMergedMetadataName();

    @Measure
    protected void clearAllMerged( final ArtifactStore store, final String... paths )
    {
        final Set<Group> groups = new HashSet<>();

        if ( StoreType.group == store.getKey()
                                     .getType() )
        {
            groups.add( (Group) store );
        }

        try
        {
            groups.addAll(
                    storeManager.query().packageType( store.getPackageType() ).getGroupsAffectedBy( store.getKey() ) );
        }
        catch ( IndyDataException e )
        {
            logger.error( String.format( "Failed to retrieve groups affected by: %s. Reason: %s", store.getKey(),
                                         e.getMessage() ), e );

        }

        groups.forEach( group -> Stream.of(paths).forEach( path->{
            logger.trace( "Clearing: '{}' in: {}", path, group );
            clearMergedFile( group, path );
        } ));

        if ( mergedContentActions != null )
        {
            StreamSupport.stream( mergedContentActions.spliterator(), false )
                         .forEach( action -> Stream.of(paths).forEach( path->{
                             logger.trace( "Executing clearMergedPath on action: {} for group: {} and path: {}", action, groups, path );
                             action.clearMergedPath( store, groups, path );
                         } ) );
        }
    }

    protected void clearMergedFile( final Group group, final String path )
    {
        try
        {
            // delete so it'll be recomputed.
            final Transfer target = fileManager.getTransfer( group, path );

            if ( target.exists() )
            {
                logger.debug( "Deleting merged file: {}", target );
                target.delete( false );
                if ( target.exists() )
                {
                    logger.error( "\n\n\n\nDID NOT DELETE merged metadata file at: {} in group: {}\n\n\n\n", path,
                                  group.getName() );
                }
                helper.deleteChecksumsAndMergeInfo( group, path );
            }
            else
            {
                ConcreteResource resource = new ConcreteResource( LocationUtils.toLocation( group ), path );
                nfc.clearMissing( resource );
            }

            // make sure we delete these, even if they're left over.
            helper.deleteChecksumsAndMergeInfo( group, path );
        }
        catch ( final IndyWorkflowException | IOException e )
        {
            logger.error( "Failed to delete generated file (to allow re-generation on demand: {}/{}. Error: {}", e,
                          group.getKey(), path, e.getMessage() );
        }
    }

}

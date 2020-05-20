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
package org.commonjava.indy.pkg.npm.change;

import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.content.DownloadManager;
import org.commonjava.indy.core.change.event.IndyFileEventManager;
import org.commonjava.indy.core.content.group.GroupMergeHelper;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.pkg.PackageTypeConstants;
import org.commonjava.indy.pkg.npm.content.group.PackageMetadataMerger;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.event.FileDeletionEvent;
import org.commonjava.maven.galley.event.FileStorageEvent;
import org.commonjava.maven.galley.model.Transfer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import java.io.IOException;
import java.util.Set;

import static org.commonjava.indy.model.core.StoreType.hosted;
import static org.commonjava.indy.util.LocationUtils.getKey;
import static org.commonjava.maven.galley.util.PathUtils.normalize;
import static org.commonjava.maven.galley.util.PathUtils.parentPath;

@ApplicationScoped
public class PackageStoreListener
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private static final String PACKAGE_TARBALL_EXTENSION = ".tgz";

    @Inject
    private StoreDataManager dataManager;

    @Inject
    private DownloadManager fileManager;

    @Inject
    private IndyFileEventManager fileEvent;

    /**
     * this listener observes {@link org.commonjava.maven.galley.event.FileStorageEvent}
     * for a tarball file, which means package.json will be cleared when a npm package
     * is uploaded.
     */
    public void onPackageStorageEvent( @Observes FileStorageEvent event )
    {
        if ( !event.getTransfer().getPath().endsWith( PACKAGE_TARBALL_EXTENSION ) )
        {
            return;
        }

        logger.info( "Package storage: {}", event.getTransfer() );

        final StoreKey storeKey = getKey( event );

        final String pkgPath = normalize( parentPath( event.getTransfer().getParent().getPath() ) );
        final String pkgMetadataPath = normalize( pkgPath, PackageMetadataMerger.METADATA_NAME ) ;

        logger.info( "Package metadata: store:{} and path: {}", storeKey.getName(), pkgMetadataPath );

        try
        {
            if ( hosted == storeKey.getType() )
            {
                HostedRepository hosted = dataManager.query().packageType( PackageTypeConstants.PKG_TYPE_NPM ).getHostedRepository( storeKey.getName() );
                try
                {
                    doClear( hosted, pkgMetadataPath );
                }
                catch ( final IOException e )
                {
                    logger.error( String.format(
                                    "Failed to delete: %s from hosted: %s when npm package changed. Error: %s", pkgMetadataPath,
                                    hosted, e.getMessage() ), e );
                }

                final Set<Group> groups = dataManager.query().packageType( PackageTypeConstants.PKG_TYPE_NPM ).getGroupsAffectedBy( storeKey );
                if ( groups != null )
                {
                    for ( final Group group : groups )
                    {
                        try
                        {
                            doClear( group, pkgMetadataPath );
                        }
                        catch ( final IOException e )
                        {
                            logger.error( String.format(
                                            "Failed to delete: %s from its group: %s when npm package changed. Error: %s",
                                            pkgMetadataPath, group, e.getMessage() ), e );
                        }
                    }
                }
            }
        }
        catch ( final IndyDataException e )
        {
            logger.warn( "Failed to regenerate package.json for npm packages after deployment to: {}"
                                         + "\nCannot retrieve associated groups: {}", pkgMetadataPath, e.getMessage() );
        }
    }

        private boolean doClear( final ArtifactStore store, final String path )
            throws IOException
        {
            boolean isCleared = false;
            logger.info( "Updating merged package metadata file: {} in store: {}", path, store );

            final Transfer[] toDelete = { fileManager.getStorageReference( store, path ),
                            fileManager.getStorageReference( store, path + GroupMergeHelper.MERGEINFO_SUFFIX ) };

            for ( final Transfer item : toDelete )
            {
                logger.info( "Attempting to delete: {}", item );

                if ( item.exists() )
                {
                    boolean result = false;
                    try
                    {
                        result = fileManager.delete( store, item.getPath(),
                                                     new EventMetadata().set( StoreDataManager.IGNORE_READONLY, true ) );
                    }
                    catch ( IndyWorkflowException e )
                    {
                        logger.warn( "Deletion failed for package metadata clear, transfer is {}, failed reason:{}", item,
                                     e.getMessage() );
                    }

                    logger.info( "Deleted: {} (success? {})", item, result );

                    if ( item.getPath().endsWith( PackageMetadataMerger.METADATA_NAME ) )
                    {
                        isCleared = result;
                    }
                    if ( fileEvent != null )
                    {
                        logger.trace( "Firing deletion event for: {}", item );
                        fileEvent.fire( new FileDeletionEvent( item, new EventMetadata() ) );
                    }
                }
                else if ( item.getPath().endsWith( PackageMetadataMerger.METADATA_NAME ) )
                {
                    // we should return true here to trigger cache cleaning, because file not exists in store does not mean
                    // metadata not exists in cache.
                    logger.debug(
                                    "Package metadata clean for {}: metadata not existed in store, so skipped deletion and mark as deleted",
                                    item );
                    return true;
                }
            }
            return isCleared;
        }
}

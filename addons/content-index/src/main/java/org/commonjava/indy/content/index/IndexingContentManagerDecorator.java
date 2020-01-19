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

import org.commonjava.cdi.util.weft.ExecutorConfig;
import org.commonjava.cdi.util.weft.WeftManaged;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.content.ContentManager;
import org.commonjava.indy.content.index.conf.ContentIndexConfig;
import org.commonjava.indy.core.content.PathMaskChecker;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.propulsor.metrics.annotation.Measure;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.model.galley.KeyedLocation;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.SpecialPathInfo;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.spi.io.SpecialPathManager;
import org.commonjava.maven.galley.spi.nfc.NotFoundCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.decorator.Decorator;
import javax.decorator.Delegate;
import javax.enterprise.inject.Any;
import javax.inject.Inject;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;

import static org.commonjava.indy.core.content.group.GroupMergeHelper.GROUP_METADATA_EXISTS;
import static org.commonjava.indy.core.content.group.GroupMergeHelper.GROUP_METADATA_GENERATED;

/**
 * Decorator for ContentManager which uses Infinispan to index content to avoid having to iterate all members of large
 * groups looking for a file.
 *
 * Created by jdcasey on 3/15/16.
 */
@Decorator
public abstract class IndexingContentManagerDecorator
        implements ContentManager
{
    private final Logger logger = LoggerFactory.getLogger( IndexingContentManagerDecorator.class.getName() );

    @Inject
    private StoreDataManager storeDataManager;

    @Inject
    private SpecialPathManager specialPathManager;

    @Delegate
    @Any
    @Inject
    private ContentManager delegate;

    @Inject
    private ContentIndexManager indexManager;

    @Inject
    private NotFoundCache nfc;

    @Inject
    private ContentIndexConfig indexCfg;

    @Inject
    @WeftManaged
    @ExecutorConfig( named = "content-index-store-deindex", priority = 4, threads = 10 )
    private Executor deIndexExecutor;

    protected IndexingContentManagerDecorator()
    {
    }

    protected IndexingContentManagerDecorator( final ContentManager delegate, final StoreDataManager storeDataManager,
                                               final SpecialPathManager specialPathManager,
                                               final ContentIndexManager indexManager, final NotFoundCache nfc )
    {
        this.delegate = delegate;
        this.storeDataManager = storeDataManager;
        this.specialPathManager = specialPathManager;
        this.indexManager = indexManager;
        this.nfc = nfc;
    }

    protected IndexingContentManagerDecorator( final ContentManager delegate, final StoreDataManager storeDataManager,
                                               final SpecialPathManager specialPathManager,
                                               final ContentIndexManager indexManager, final NotFoundCache nfc,
                                               final ContentIndexConfig indexCfg)
    {
        this(delegate, storeDataManager, specialPathManager, indexManager, nfc);
        this.indexCfg = indexCfg;
    }

    protected IndexingContentManagerDecorator( final ContentManager delegate, final StoreDataManager storeDataManager,
                                               final SpecialPathManager specialPathManager,
                                               final ContentIndexManager indexManager, final NotFoundCache nfc,
                                               final ContentIndexConfig indexCfg, final Executor deIndexExecutor)
    {
        this(delegate, storeDataManager, specialPathManager, indexManager, nfc, indexCfg);
        this.deIndexExecutor = deIndexExecutor;
    }

    @Override
    public Transfer retrieveFirst( final List<? extends ArtifactStore> stores, final String path )
            throws IndyWorkflowException
    {
        return retrieveFirst( stores, path, new EventMetadata() );
    }

    @Override
    public Transfer retrieveFirst( final List<? extends ArtifactStore> stores, final String path,
                                   final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        Transfer transfer = null;
        for ( ArtifactStore store : stores )
        {
            transfer = retrieve( store, path, eventMetadata );
            if ( transfer != null )
            {
                break;
            }
        }

        return transfer;
    }

    @Override
    public List<Transfer> retrieveAll( final List<? extends ArtifactStore> stores, final String path )
            throws IndyWorkflowException
    {
        return retrieveAll( stores, path, new EventMetadata() );
    }

    @Override
    public List<Transfer> retrieveAll( final List<? extends ArtifactStore> stores, final String path,
                                       final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        List<Transfer> results = new ArrayList<>();
        stores.stream().map( ( store ) -> {
            try
            {
                return retrieve( store, path, eventMetadata );
            }
            catch ( IndyWorkflowException e )
            {
                logger.error(
                        String.format( "Failed to retrieve indexed content: %s:%s. Reason: %s", store.getKey(), path,
                                       e.getMessage() ), e );
            }

            return null;
        } ).filter( Objects::nonNull ).forEachOrdered( results::add );

        return results;
    }

    @Override
    public Transfer retrieve( final ArtifactStore store, final String path )
            throws IndyWorkflowException
    {
        return retrieve( store, path, new EventMetadata() );
    }

    @Override
    public Transfer retrieve( final ArtifactStore store, final String path, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        if ( store == null )
        {
            return null;
        }

        logger.trace( "Looking for indexed path: {} in: {}", path, store.getKey() );

        Transfer transfer = getIndexedTransfer( store.getKey(), null, path, TransferOperation.DOWNLOAD, eventMetadata );
        if ( transfer != null )
        {
            logger.debug( "Found indexed transfer: {}. Returning.", transfer );
            return transfer;
        }
        else if ( isAuthoritativelyMissing( store ) )
        {
            logger.debug(
                    "Not found indexed transfer: {} and authoritative index switched on. Considering not found and return null.", transfer );
            return null;
        }

        StoreType type = store.getKey().getType();

        // NOTE: This will make the index cache non-disposable, which will mean that we have to use more reliable
        // (slower) disk to store it...which will be BAD for performance.
        // Ironically, this change will speed things up in the short term but slow them way down in the larger
        // context.
//        if ( StoreType.hosted == type )
//        {
//            // hosted repos are completely indexed, since the store() method maintains the index
//            // So, if it wasn't found in the index (above), and we're looking at a hosted repo, it's not here.
//            logger.debug( "HOSTED / Not-Indexed: {}/{}", store.getKey(), path );
//            return null;
//        }
//        else if ( StoreType.group == type )

        if ( StoreType.group == type )
        {
            ConcreteResource resource = new ConcreteResource( LocationUtils.toLocation( store ), path );
            if ( nfc.isMissing( resource ) )
            {
                logger.debug( "{} is marked as missing. Returning null.", resource );
                return null;
            }

            logger.debug( "No group index hits. Devolving to member store indexes." );

            KeyedLocation location = LocationUtils.toLocation( store );
            SpecialPathInfo specialPathInfo =
                    specialPathManager.getSpecialPathInfo( location, path, store.getPackageType() );

            if ( specialPathInfo == null || !specialPathInfo.isMergable() )
            {
                if ( PathMaskChecker.checkMask( store, path ) )
                {
                    transfer = getTransferFromConstituents( ( (Group) store ).getConstituents(), resource, path, store,
                                                            memberKey -> {
                                                                try
                                                                {
                                                                    ArtifactStore member =
                                                                            storeDataManager.getArtifactStore( memberKey );

                                                                    if ( member == null )
                                                                    {
                                                                        logger.trace( "Cannot find store for key: {}",
                                                                                      memberKey );
                                                                    }
                                                                    else
                                                                    {
                                                                        return retrieve( member, path, eventMetadata );
                                                                    }
                                                                }
                                                                catch ( IndyDataException e )
                                                                {
                                                                    logger.error( String.format(
                                                                            "Failed to lookup store: %s (in membership of: %s). Reason: %s",
                                                                            memberKey, store.getKey(), e.getMessage() ),
                                                                                  e );
                                                                }

                                                                return null;
                                                            } );
                    nfcForGroup( store, transfer, resource );
                    return transfer;
                }
                else
                {
                    return null;
                }
            }
            else
            {
                logger.debug( "Merged content. Delegating to main content manager for: {} in: {}", path, store );
                transfer = delegate.retrieve( store, path, eventMetadata );
                if ( !exists( transfer ) )
                {
                    Boolean metadataGenerated = (Boolean) eventMetadata.get( GROUP_METADATA_GENERATED );
                    Boolean metadataExists = (Boolean) eventMetadata.get( GROUP_METADATA_EXISTS );

                    if ( Boolean.TRUE.equals( metadataGenerated ) || Boolean.TRUE.equals( metadataExists ) )
                    {
                        ; // metadata generated/exists but missing due to membership change, not add to nfc so next req can retry
                    }
                    else // don't track NFC for hosted repos
                    {
                        nfc.addMissing( resource );
                    }
                }

                return transfer;
            }
        }

        logger.trace( "Delegating retrieve call for concrete repository: {}/{}", store, path );
        transfer = delegate.retrieve( store, path, eventMetadata );

        if ( exists( transfer ) && indexCfg.isEnabled() )
        {
            logger.debug( "Got transfer from delegate: {} (will index)", transfer );
            indexManager.indexTransferIn( transfer, store.getKey() );
        }

        logger.debug( "Returning transfer: {}", transfer );
        return transfer;
    }

    private boolean isAuthoritativelyMissing( final ArtifactStore store )
    {
        return indexCfg.isAuthoritativeIndex() && ( store.isAuthoritativeIndex()
                || ( ( store instanceof HostedRepository ) && ( (HostedRepository) store ).isReadonly() ) )
                && !store.isRescanInProgress();
    }

    /**
     * Recursively fetching the transfer from group constituents, and only
     * indexing the transfer for first found repo and its parent groups.
     */
    private Transfer getTransferFromConstituents( Collection<StoreKey> constituents, ConcreteResource resource, String path,
                                                  ArtifactStore parentStore, TransferSupplier<Transfer> transferSupplier )
    {
        List<StoreKey> members = new ArrayList<>( constituents );
        Transfer transfer = null;
        for ( StoreKey memberKey : members )
        {
            try
            {
                transfer = transferSupplier.get( memberKey );
            }
            catch ( IndyWorkflowException e )
            {
                logger.error( String.format( "Failed to retrieve() for member path: %s:%s. Reason: %s", memberKey, path,
                                             e.getMessage() ), e );
            }
            if ( exists( transfer ) )
            {
                nfc.clearMissing( resource );

                if ( indexCfg.isEnabled() )
                {
                    logger.debug( "Got transfer from constituent: {} (will index)", transfer );
                    indexManager.indexTransferIn( transfer, parentStore.getKey() );
                }

                return transfer;
            }
        }

        return transfer;
    }

    private interface TransferSupplier<T extends Transfer>
    {
        T get( final StoreKey memberKey )
                throws IndyWorkflowException;
    }

    private boolean exists( final Transfer transfer )
    {
        return transfer != null && transfer.exists();
    }

    @Measure
    public Transfer getIndexedTransfer( final StoreKey storeKey, final StoreKey topKey, final String path,
                                        final TransferOperation op, final EventMetadata metadata )
            throws IndyWorkflowException
    {
        if ( !indexCfg.isEnabled() )
        {
            logger.debug( "Content indexing is disabled. Returning null for indexedTransfer of: {}/{}", storeKey, path );
            return null;
        }

        logger.debug( "Looking for indexed path: {} in: {} (entry point: {})", path, storeKey, topKey );

        try
        {
            ArtifactStore store = storeDataManager.getArtifactStore( storeKey );
            if ( store.isDisabled() )
            {
                logger.debug( "Content not available in index caching layer due to store disabled for {} in group {}",
                             storeKey, topKey );
                return null;
            }
        }
        catch ( IndyDataException e )
        {
            logger.error(
                    String.format( "Failed to lookup store: %s (in membership of: %s). Reason: %s", storeKey, topKey,
                                   e.getMessage() ), e );
            //TODO: Need further check if it is suitable to throw a IndyWorkflowException here.
            return null;
        }

        StoreKey indexedStoreKey = indexManager.getIndexedStoreKey( storeKey, path );

        if ( indexedStoreKey != null )
        {
            Transfer transfer = delegate.getTransfer( indexedStoreKey, path, op );
            if ( transfer == null || !transfer.exists() )
            {
                if ( indexedStoreKey.getType() == StoreType.remote )
                {
                    // Transfer not existing may be caused by not cached for remote repo, so we should trigger downloading
                    // immediately to check if it really exists.
                    logger.debug( "Will trigger downloading of path {} from store {} from content index level", path,
                                  indexedStoreKey );
                    try
                    {
                        transfer =
                                delegate.retrieve( storeDataManager.getArtifactStore( indexedStoreKey ), path,
                                                   metadata );
                        if ( transfer != null && transfer.exists() )
                        {
                            logger.debug( "Downloaded and found it: {}", transfer );
                            return transfer;
                        }
                    }
                    catch ( IndyDataException e )
                    {
                        logger.warn( "Error to get store {} caused by {}", indexedStoreKey, e.getMessage() );
                    }
                }

                logger.trace( "Found obsolete index entry: {},{}. De-indexing from: {} and {}", indexedStoreKey, path, storeKey,
                              topKey );
                // something happened to the underlying Transfer...de-index it, and don't return it.
                indexManager.deIndexStorePath( storeKey, path );
                if ( topKey != null )
                {
                    logger.debug( "{} Not found in: {}. De-indexing from: {} (topKey)", path, storeKey, topKey );
                    indexManager.deIndexStorePath( topKey, path );
                }
            }
            else
            {
                logger.debug( "Found it: {}", transfer );
                return transfer;
            }
        }

        return null;
    }

    @Override
    public Transfer getTransfer( final ArtifactStore store, final String path, final TransferOperation op )
            throws IndyWorkflowException
    {
        Transfer transfer = getIndexedTransfer( store.getKey(), null, path, TransferOperation.DOWNLOAD, new EventMetadata(  ) );
        if ( exists( transfer ) )
        {
            return transfer;
        }
        else if ( isAuthoritativelyMissing( store ) )
        {
            logger.info(
                    "Not found indexed transfer: {} and authoritative index switched on. Considering not found and return null.", transfer );
            return null;
        }

        ConcreteResource resource = new ConcreteResource( LocationUtils.toLocation( store ), path );
        StoreType type = store.getKey().getType();

        if ( StoreType.group == type )
        {
            if ( !nfc.isMissing( resource ) )
            {
                logger.debug( "No group index hits. Devolving to member store indexes." );
                transfer = getTransferFromConstituents( ( (Group) store ).getConstituents(), resource, path, store,
                                                        memberKey -> {
                                                            try
                                                            {
                                                                ArtifactStore member =
                                                                        storeDataManager.getArtifactStore( memberKey );
                                                                return getTransfer( member, path, op );
                                                            }
                                                            catch ( IndyDataException e )
                                                            {
                                                                logger.error( String.format(
                                                                        "Failed to lookup store: %s (in membership of: %s). Reason: %s",
                                                                        memberKey, store.getKey(), e.getMessage() ),
                                                                              e );
                                                                return null;
                                                            }
                                                        } );

                nfcForGroup( store, transfer, resource );

                return transfer;
            }
            else
            {
                logger.debug( "NFC marks {} as missing. Returning null.", resource );
                return null;
            }
        }

        transfer = delegate.getTransfer( store, path, op );
        // index the transfer only if it exists, it cannot be null at this point
        if ( exists( transfer ) && indexCfg.isEnabled() )
        {
            indexManager.indexTransferIn( transfer, store.getKey() );
        }

        logger.debug( "Returning transfer: {}", transfer );
        return transfer;
    }

    @Measure
    @Deprecated
    public Transfer getIndexedMemberTransfer( final Group group, final String path, TransferOperation op,
                                               ContentManagementFunction func, final EventMetadata metadata )
            throws IndyWorkflowException
    {
        StoreKey topKey = group.getKey();

        List<StoreKey> toProcess = new ArrayList<>( group.getConstituents() );
        Set<StoreKey> seen = new HashSet<>();

        while ( !toProcess.isEmpty() )
        {
            StoreKey key = toProcess.remove( 0 );

            seen.add( key );

            final ArtifactStore member;
            try
            {
                member = storeDataManager.getArtifactStore( key );
                if ( member == null )
                {
                    continue;
                }
            }
            catch ( IndyDataException e )
            {
                logger.error(
                        String.format( "Failed to lookup store: %s (in membership of: %s). Reason: %s", key, topKey,
                                       e.getMessage() ), e );
                continue;
            }

            Transfer transfer = getIndexedTransfer( key, topKey, path, op, metadata );
            if ( transfer == null && StoreType.group != key.getType() )
            {
                // don't call this for constituents that are groups...we'll manually traverse the membership below...
                transfer = func.apply( member );
            }

            if ( transfer != null )
            {
                if ( indexCfg.isEnabled() )
                {
                    indexManager.indexTransferIn( transfer, key, topKey );
                }

                return transfer;
            }
            else if ( StoreType.group == key.getType() )
            {
                int i = 0;
                for ( StoreKey memberKey : ( (Group) member ).getConstituents() )
                {
                    if ( !seen.contains( memberKey ) )
                    {
                        toProcess.add( i, memberKey );
                        i++;
                    }
                }
            }

        }

        return null;
    }

    @Override
    public Transfer getTransfer( final StoreKey storeKey, final String path, final TransferOperation op )
            throws IndyWorkflowException
    {
        Transfer transfer = getIndexedTransfer( storeKey, null, path, TransferOperation.DOWNLOAD, new EventMetadata(  ) );
        if ( exists( transfer ) )
        {
            logger.debug( "Returning indexed transfer: {}", transfer );
            return transfer;
        }

        ArtifactStore store;
        try
        {
            store = storeDataManager.getArtifactStore( storeKey );
        }
        catch ( IndyDataException e )
        {
            throw new IndyWorkflowException( "Failed to lookup ArtifactStore: %s for NFC handling. Reason: %s", e,
                                             storeKey, e.getMessage() );
        }

        if ( isAuthoritativelyMissing( store ) )
        {
            logger.debug( "Not found indexed transfer: {} and authoritative index switched on. Return null.", transfer );
            return null;
        }

        ConcreteResource resource = new ConcreteResource( LocationUtils.toLocation( store ), path );
        StoreType type = storeKey.getType();

        if ( StoreType.group == type )
        {
            Group g= (Group) store;

            if ( g == null )
            {
                throw new IndyWorkflowException( "Cannot find requested group: %s", storeKey );
            }

            if ( nfc.isMissing( resource ) )
            {
                logger.debug( "NFC / MISSING: {}", resource );
                return null;
            }

            logger.debug( "No group index hits. Devolving to member store indexes." );
            transfer = getTransferFromConstituents( g.getConstituents(), resource, path, g,
                                                    memberKey -> getTransfer( memberKey, path, op ) );

            nfcForGroup( store, transfer, resource );

            return transfer;
        }

        transfer = delegate.getTransfer( storeKey, path, op );
        if ( exists( transfer ) && indexCfg.isEnabled() )
        {
            logger.debug( "Indexing transfer: {}", transfer );
            indexManager.indexTransferIn( transfer, storeKey );
        }

        return transfer;
    }

    private void nfcForGroup( final ArtifactStore store, final Transfer transfer, final ConcreteResource resource )
    {
        if ( StoreType.group == store.getType() )
        {
            if ( exists( transfer ) )
            {
                nfc.clearMissing( resource );
            }
            else
            {
                logger.debug( "No index hits. Delegating to main content manager for: {} in: {}", resource.getPath(),
                              store );
                logger.debug( "No transfer hit at group level of group {}, will add to NFC for this group resource",
                              store );
                nfc.addMissing( resource );
            }
        }
    }


    @Override
    public Transfer getTransfer( final List<ArtifactStore> stores, final String path, final TransferOperation op )
            throws IndyWorkflowException
    {
        Transfer transfer = null;
        for ( ArtifactStore store : stores )
        {
            transfer = getTransfer( store, path, op );
            if ( transfer != null )
            {
                break;
            }
        }

        return transfer;
    }

    @Override
    public Transfer store( final ArtifactStore store, final String path, final InputStream stream,
                           final TransferOperation op )
            throws IndyWorkflowException
    {
        return store( store, path, stream, op, new EventMetadata() );
    }

    @Override
    @Measure
    public Transfer store( final ArtifactStore store, final String path, final InputStream stream,
                           final TransferOperation op, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.trace( "Storing: {} in: {} from indexing level", path, store.getKey() );
        Transfer transfer = delegate.store( store, path, stream, op, eventMetadata );
        if ( transfer != null )
        {
            if ( indexCfg.isEnabled() )
            {
                logger.trace( "Indexing: {} in: {}", transfer, store.getKey() );
                indexManager.indexTransferIn( transfer, store.getKey() );
            }

            if ( store instanceof Group )
            {
                nfc.clearMissing( new ConcreteResource( LocationUtils.toLocation( store ), path ) );
            }
            // We should deIndex the path for all parent groups because the new content of the path
            // may change the content index sequence based on the constituents sequence in parent groups
            if ( store.getType() == StoreType.hosted )
            {
                //FIXME: One potential problem here: The fixed thread pool is using a blocking queue to
                // cache runnables, which could cause OOM if there are bunch of uploading happened in
                // a short time period. We need to monitor if this could happen.
                deIndexExecutor.execute( () -> {
                    try
                    {
                        Set<Group> groups = storeDataManager.query().getGroupsAffectedBy( store.getKey() );
                        if ( groups != null && !groups.isEmpty() && indexCfg.isEnabled() )
                        {
                            groups.forEach( g -> indexManager.deIndexStorePath( g.getKey(), path ) );
                        }
                    }
                    catch ( IndyDataException e )
                    {
                        logger.error(
                                String.format( "Failed to get groups which contains: %s for NFC handling. Reason: %s",
                                               store.getKey(), e.getMessage() ), e );
                    }
                } );
            }
        }
//        nfcClearByContaining( store, path );

        return transfer;
    }

    //    @Override
    //    public Transfer store( final List<? extends ArtifactStore> stores, final String path, final InputStream stream, final TransferOperation op )
    //            throws IndyWorkflowException
    //    {
    //        return store( stores, path, stream, op, new EventMetadata() );
    //    }

    @Override
    public Transfer store( final List<? extends ArtifactStore> stores, final StoreKey topKey, final String path,
                           final InputStream stream, final TransferOperation op, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        Transfer transfer = delegate.store( stores, topKey, path, stream, op, eventMetadata );
        if ( transfer != null )
        {
            if ( indexCfg.isEnabled() )
            {
                indexManager.indexTransferIn( transfer, topKey );
            }

            try
            {
                ArtifactStore topStore = storeDataManager.getArtifactStore( topKey );
                nfc.clearMissing( new ConcreteResource( LocationUtils.toLocation( topStore ), path ) );

                if ( indexCfg.isEnabled() )
                {
                    // We should deIndex the path for all parent groups because the new content of the path
                    // may change the content index sequence based on the constituents sequence in parent groups
                    indexManager.deIndexStorePath( topKey, path );
                }
            }
            catch ( IndyDataException e )
            {
                Logger logger = LoggerFactory.getLogger( getClass() );
                logger.error( String.format( "Failed to retrieve top store: %s for NFC management. Reason: %s",
                                             topKey, e.getMessage()), e );
            }
        }

        return transfer;
    }

    @Override
    public boolean delete( final ArtifactStore store, final String path )
            throws IndyWorkflowException
    {
        return delete( store, path, new EventMetadata() );
    }

    @Override
    public boolean delete( final ArtifactStore store, final String path, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        boolean result = delegate.delete( store, path, eventMetadata );
        if ( result && indexCfg.isEnabled() )
        {
            indexManager.deIndexStorePath( store.getKey(), path );
        }

        return result;
    }

    @Override
    public boolean deleteAll( final List<? extends ArtifactStore> stores, final String path )
            throws IndyWorkflowException
    {
        return deleteAll( stores, path, new EventMetadata() );
    }

    @Override
    public boolean deleteAll( final List<? extends ArtifactStore> stores, final String path,
                              final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        boolean result = false;
        for ( ArtifactStore store : stores )
        {
            result = delete( store, path, eventMetadata ) || result;
        }

        return result;
    }

    private interface ContentManagementFunction
    {
        Transfer apply(ArtifactStore store);
    }

}

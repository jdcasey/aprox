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

import org.commonjava.cdi.util.weft.ExecutorConfig;
import org.commonjava.cdi.util.weft.WeftExecutorService;
import org.commonjava.cdi.util.weft.WeftManaged;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.change.event.ArtifactStorePostRescanEvent;
import org.commonjava.indy.change.event.ArtifactStorePreRescanEvent;
import org.commonjava.indy.change.event.ArtifactStoreRescanEvent;
import org.commonjava.indy.change.event.IndyStoreErrorEvent;
import org.commonjava.indy.content.DownloadManager;
import org.commonjava.indy.content.StoreResource;
import org.commonjava.indy.core.change.event.IndyFileEventManager;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.model.galley.CacheOnlyLocation;
import org.commonjava.indy.model.galley.KeyedLocation;
import org.commonjava.indy.spi.pkg.ContentAdvisor;
import org.commonjava.indy.spi.pkg.ContentQuality;
import org.commonjava.indy.util.ApplicationStatus;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.indy.util.PathUtils;
import org.commonjava.maven.galley.BadGatewayException;
import org.commonjava.maven.galley.TransferContentException;
import org.commonjava.maven.galley.TransferException;
import org.commonjava.maven.galley.TransferLocationException;
import org.commonjava.maven.galley.TransferManager;
import org.commonjava.maven.galley.TransferTimeoutException;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.event.FileAccessEvent;
import org.commonjava.maven.galley.io.SpecialPathManagerImpl;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.ListingResult;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.model.SpecialPathInfo;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.model.VirtualResource;
import org.commonjava.maven.galley.spi.io.SpecialPathManager;
import org.commonjava.maven.galley.spi.nfc.NotFoundCache;
import org.commonjava.maven.galley.spi.transport.LocationExpander;
import org.commonjava.propulsor.metrics.annotation.Measure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.event.Event;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;

import static org.commonjava.cdi.util.weft.ExecutorConfig.BooleanLiteral.TRUE;
import static org.commonjava.indy.IndyContentConstants.CHECK_CACHE_ONLY;
import static org.commonjava.indy.change.EventUtils.fireEvent;
import static org.commonjava.indy.core.ctl.PoolUtils.detectOverloadVoid;
import static org.commonjava.indy.data.StoreDataManager.IGNORE_READONLY;
import static org.commonjava.indy.model.core.StoreType.hosted;
import static org.commonjava.indy.util.ContentUtils.dedupeListing;
import static org.commonjava.maven.galley.model.TransferOperation.DOWNLOAD;
import static org.commonjava.maven.galley.model.TransferOperation.LISTING;

@javax.enterprise.context.ApplicationScoped
public class DefaultDownloadManager
        implements DownloadManager
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private Event<ArtifactStoreRescanEvent> rescanEvent;

    @Inject
    private IndyFileEventManager fileEventManager;

    // Byte, because it's small, and we really only care about the keys anyway.
    private final Map<StoreKey, Byte> rescansInProgress = new ConcurrentHashMap<>();

    @Inject
    @WeftManaged
    @ExecutorConfig( priority = 10, threads = 2, named = "rescan-manager", loadSensitive = TRUE, maxLoadFactor = 2 )
    private WeftExecutorService rescanService;

    @Inject
    private TransferManager transfers;

    @Inject
    private LocationExpander locationExpander;

    @Inject
    private StoreDataManager storeManager;

    @Inject
    private NotFoundCache nfc;

    @Inject
    private SpecialPathManager specialPathManager;

    @Inject
    @Any
    private Instance<ContentAdvisor> contentAdvisors;

    protected DefaultDownloadManager()
    {
    }

    public DefaultDownloadManager( final StoreDataManager storeManager, final TransferManager transfers,
                                   final LocationExpander locationExpander, WeftExecutorService rescanService )
    {
        this.storeManager = storeManager;
        this.transfers = transfers;
        this.locationExpander = locationExpander;
        this.fileEventManager = new IndyFileEventManager();
        this.rescanService = rescanService;
        this.specialPathManager = new SpecialPathManagerImpl();
    }

    public DefaultDownloadManager( final StoreDataManager storeManager, final TransferManager transfers,
                                   final LocationExpander locationExpander, Instance<ContentAdvisor> contentAdvisors,
                                   WeftExecutorService rescanService )
    {
        this(storeManager, transfers, locationExpander, rescanService);
        this.contentAdvisors = contentAdvisors;
    }

    public DefaultDownloadManager( final StoreDataManager storeManager, final TransferManager transfers,
                                   final LocationExpander locationExpander, Instance<ContentAdvisor> contentAdvisors,
                                   final NotFoundCache nfc, WeftExecutorService rescanService )
    {
        this(storeManager, transfers, locationExpander, contentAdvisors, rescanService);
        this.nfc = nfc;
    }
    @Override
    public List<StoreResource> list( final ArtifactStore store, final String path )
            throws IndyWorkflowException
    {
        return list(store, path, new EventMetadata() );
    }

    @Override
    @Measure
    public List<StoreResource> list( final ArtifactStore store, final String path, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        final List<StoreResource> result = new ArrayList<>();

        //        final String dir = PathUtils.dirname( path );

        if ( store.getKey().getType() == StoreType.group )
        {
            try
            {
                final List<ListingResult> results = transfers.listAll(
                        locationExpander.expand( new VirtualResource( LocationUtils.toLocations( store ), path ) ), eventMetadata );

                for ( final ListingResult lr : results )
                {
                    if ( lr != null && lr.getListing() != null )
                    {
                        for ( final String file : lr.getListing() )
                        {
                            result.add( new StoreResource( (KeyedLocation) lr.getLocation(), path, file ) );
                        }
                    }
                }
            }
            catch ( final BadGatewayException e )
            {
                fireIndyStoreErrorEvent( e );
                logger.warn( "Bad gateway: " + e.getMessage(), e );
            }
            catch ( final TransferTimeoutException e )
            {
                fireIndyStoreErrorEvent( e );
                logger.warn( "Timeout: " + e.getMessage(), e );
            }
            catch ( final TransferLocationException e )
            {
                fireIndyStoreErrorEvent( e );
                logger.warn( "Location Error: " + e.getMessage(), e );
            }
            catch ( final TransferException e )
            {
                logger.error( e.getMessage(), e );
                throw new IndyWorkflowException( "Failed to list ALL paths: {} from: {}. Reason: {}", e, path,
                                                 store.getKey(), e.getMessage() );
            }
        }
        else
        {
            if ( ! PathMaskChecker.checkListingMask( store, path ) )
            {
                return result; // if list not permitted for the path, return empty list
            }

            final KeyedLocation loc = LocationUtils.toLocation( store );
            final StoreResource res = new StoreResource( loc, path );
            if ( store instanceof RemoteRepository )
            {
                try
                {
                    final ListingResult lr = transfers.list( res, eventMetadata );
                    if ( lr != null && lr.getListing() != null )
                    {
                        for ( final String file : lr.getListing() )
                        {
                            result.add( new StoreResource( loc, path, file ) );
                        }
                    }
                }
                catch ( final BadGatewayException e )
                {
                    fireIndyStoreErrorEvent( e );
                    logger.warn( "Bad gateway: " + e.getMessage(), e );
                }
                catch ( final TransferTimeoutException e )
                {
                    fireIndyStoreErrorEvent( e );
                    logger.warn( "Timeout: " + e.getMessage(), e );
                }
                catch ( final TransferLocationException e )
                {
                    fireIndyStoreErrorEvent( e );
                    logger.warn( "Location Error: " + e.getMessage(), e );
                }
                catch ( final TransferException e )
                {
                    logger.error( e.getMessage(), e );
                    throw new IndyWorkflowException( "Failed to list path: {} from: {}. Reason: {}", e, path,
                                                     store.getKey(), e.getMessage() );
                }
            }
            else
            {
                try
                {
                    final ListingResult listing = transfers.list( res, eventMetadata );
                    if ( listing != null && listing.getListing() != null )
                    {
                        for ( final String child : listing.getListing() )
                        {
                            result.add( new StoreResource( loc, path, child ) );
                        }
                    }
                }
                catch ( final TransferLocationException e )
                {
                    fireIndyStoreErrorEvent( e );
                    logger.warn( "Timeout  / bad gateway: " + e.getMessage(), e );
                }
                catch ( final TransferException e )
                {
                    logger.error( e.getMessage(), e );
                    throw new IndyWorkflowException( "Failed to list path: {} from: {}. Reason: {}", e, path,
                                                     store.getKey(), e.getMessage() );
                }
            }
        }

        return dedupeListing( result );
    }

    @Override
    public List<StoreResource> list( final List<? extends ArtifactStore> stores, final String path )
            throws IndyWorkflowException
    {
        return list( stores, path, new EventMetadata() );
    }

    @Override
    @Measure
    public List<StoreResource> list( final List<? extends ArtifactStore> stores, final String path, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        final String dir = PathUtils.dirname( path );

        final List<StoreResource> result = new ArrayList<>();
        try
        {
            final List<ListingResult> results = transfers.listAll(
                    locationExpander.expand( new VirtualResource( LocationUtils.toLocations( stores ), path ) ), eventMetadata );

            for ( final ListingResult lr : results )
            {
                if ( lr != null && lr.getListing() != null )
                {
                    for ( final String file : lr.getListing() )
                    {
                        result.add( new StoreResource( (KeyedLocation) lr.getLocation(), dir, file ) );
                    }
                }
            }
        }
        catch ( final BadGatewayException e )
        {
            fireIndyStoreErrorEvent( e );
            logger.warn( "Bad gateway: " + e.getMessage(), e );
        }
        catch ( final TransferTimeoutException e )
        {
            fireIndyStoreErrorEvent( e );
            logger.warn( "Timeout: " + e.getMessage(), e );
        }
        catch ( final TransferLocationException e )
        {
            fireIndyStoreErrorEvent( e );
            logger.warn( "Location Error: " + e.getMessage(), e );
        }
        catch ( final TransferException e )
        {
            logger.error( e.getMessage(), e );
            throw new IndyWorkflowException( "Failed to list ALL paths: {} from: {}. Reason: {}", e, path, stores,
                                             e.getMessage() );
        }

        return dedupeListing( result );
    }

    @Override
    public Transfer retrieveFirst( final List<? extends ArtifactStore> stores, final String path )
            throws IndyWorkflowException
    {
        return retrieveFirst( stores, path, new EventMetadata() );
    }

    @Override
    @Measure
    public Transfer retrieveFirst( final List<? extends ArtifactStore> stores, final String path,
                                   final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        try
        {
            return transfers.retrieveFirst(
                    locationExpander.expand( new VirtualResource( LocationUtils.toLocations( stores ), path ) ),
                    eventMetadata );
        }
        catch ( final BadGatewayException e )
        {
            fireIndyStoreErrorEvent( e );
            logger.warn( "Bad gateway: " + e.getMessage(), e );
        }
        catch ( final TransferTimeoutException e )
        {
            fireIndyStoreErrorEvent( e );
            logger.warn( "Timeout: " + e.getMessage(), e );
        }
        catch ( final TransferLocationException e )
        {
            fireIndyStoreErrorEvent( e );
            logger.warn( "Location Error: " + e.getMessage(), e );
        }
        catch ( final TransferException e )
        {
            logger.error( e.getMessage(), e );
            throw new IndyWorkflowException( "Failed to retrieve first path: {} from: {}. Reason: {}", e, path, stores,
                                             e.getMessage() );
        }

        return null;
    }

    /*
     * (non-Javadoc)
     * @see org.commonjava.indy.core.rest.util.FileManager#downloadAll(java.util.List, java.lang.String)
     */
    @Override
    public List<Transfer> retrieveAll( final List<? extends ArtifactStore> stores, final String path )
            throws IndyWorkflowException
    {
        return retrieveAll( stores, path, new EventMetadata() );
    }

    /*
     * (non-Javadoc)
     * @see org.commonjava.indy.core.rest.util.FileManager#downloadAll(java.util.List, java.lang.String)
     */
    @Override
    @Measure
    public List<Transfer> retrieveAll( final List<? extends ArtifactStore> stores, final String path,
                                       final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        try
        {
            List<Transfer> txfrs = transfers.retrieveAll(
                    locationExpander.expand( new VirtualResource( LocationUtils.toLocations( stores ), path ) ),
                    eventMetadata );

            return txfrs;
        }
        catch ( final TransferException e )
        {
            logger.error( e.getMessage(), e );
            throw new IndyWorkflowException( "Failed to retrieve ALL paths: {} from: {}. Reason: {}", e, path, stores,
                                             e.getMessage() );
        }
    }

    /*
     * (non-Javadoc)
     * @see org.commonjava.indy.core.rest.util.FileManager#download(org.commonjava.indy.core.model.ArtifactStore,
     * java.lang.String)
     */
    @Override
    public Transfer retrieve( final ArtifactStore store, final String path )
            throws IndyWorkflowException
    {
        return retrieve( store, path, new EventMetadata() );
    }

    /*
     * (non-Javadoc)
     * @see org.commonjava.indy.core.rest.util.FileManager#download(org.commonjava.indy.core.model.ArtifactStore,
     * java.lang.String)
     */
    @Override
    @Measure
    public Transfer retrieve( final ArtifactStore store, final String path, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        return retrieve( store, path, false, eventMetadata );
    }

    private Transfer retrieve( final ArtifactStore store, final String path, final boolean suppressFailures,
                               final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        if ( store.getKey().getType() == StoreType.group )
        {
            return null;
        }

        if ( !PathMaskChecker.checkMask( store, path))
        {
            return null;
        }

        final ConcreteResource res = new ConcreteResource( LocationUtils.toLocation( store ), path );

        if ( store.getType() != hosted && nfc.isMissing( res ) )
        {
            return null;
        }

        Transfer target;
        try
        {
            if ( store instanceof RemoteRepository )
            {
                target = transfers.retrieve( res, suppressFailures, eventMetadata );
            }
            else
            {
                target = transfers.getCacheReference( res );
                if ( target == null || !target.exists() )
                {
                    target = null;
                }
            }
        }
        catch ( final TransferLocationException e )
        {
            fileEventManager.fire( new IndyStoreErrorEvent( store.getKey(), e ) );
            logger.warn( "Timeout / bad gateway: " + res + ". Reason: " + e.getMessage(), e );
            target = null;
        }
        catch ( final TransferContentException e )
        {
            logger.warn( "Content-Length mismatch: " + res + ". Reason: " + e.getMessage()
                                 + "\nNOTE: This may be a network error; will retry download on next request.", e );
            target = null;
        }
        catch ( final TransferException e )
        {
            logger.error( e.getMessage(), e );
            throw new IndyWorkflowException( "Failed to retrieve path: {} from: {}. Reason: {}", e, path, store,
                                             e.getMessage() );
        }
        
        return target;
    }

    @Override
    @Measure
    public boolean exists(final ArtifactStore store, String path)
            throws IndyWorkflowException
    {
        if ( !PathMaskChecker.checkMask( store, path ) )
        {
            return false;
        }

        final ConcreteResource res = new ConcreteResource( LocationUtils.toLocation( store ), path );
        if ( store instanceof RemoteRepository )
        {
            try {
                return transfers.exists( res );
            } catch (TransferException e) {
                logger.warn( "Existence check: " + e.getMessage(), e );
                return false;
            }
        }
        else
        {
            Transfer target = transfers.getCacheReference(res);
            if ( target != null )
            {
                return target.exists();
            }
        }
        return false;
    }

    /*
     * (non-Javadoc)
     * @see org.commonjava.indy.core.rest.util.FileManager#upload(org.commonjava.indy.core.model.DeployPoint,
     * java.lang.String, java.io.InputStream)
     */
    @Override
    public Transfer store( final ArtifactStore store, final String path, final InputStream stream,
                           final TransferOperation op )
            throws IndyWorkflowException
    {
        return store( store, path, stream, op, new EventMetadata() );
    }

    /*
     * (non-Javadoc)
     * @see org.commonjava.indy.core.rest.util.FileManager#upload(org.commonjava.indy.core.model.DeployPoint,
     * java.lang.String, java.io.InputStream)
     */
    @Override
    @Measure
    public Transfer store( final ArtifactStore store, final String path, final InputStream stream,
                           final TransferOperation op, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        if ( store.getKey().getType() == StoreType.group )
        {
            //FIXME: Why is this null? Investigate.
            return null;
        }

        if ( store.getKey().getType() != hosted )
        {
            throw new IndyWorkflowException( ApplicationStatus.BAD_REQUEST.code(),
                                             "Cannot deploy to non-deploy point artifact store: {}.", store.getKey() );
        }

        if ( !isIgnoreReadonly( eventMetadata ) && storeManager.isReadonly( store ) )
        {
            throw new IndyWorkflowException( ApplicationStatus.METHOD_NOT_ALLOWED.code(),
                                             "The store {} is readonly. If you want to store any content to this store, please modify it to non-readonly",
                                             store.getKey() );
        }


        if ( store instanceof HostedRepository )
        {
            final HostedRepository deploy = (HostedRepository) store;

//            final ArtifactPathInfo pathInfo = ArtifactPathInfo.parse( path );
            final ContentQuality quality = getQuality( path );
            if ( quality != ContentQuality.METADATA )
            {
                if ( quality == ContentQuality.SNAPSHOT )
                {
                    if ( !deploy.isAllowSnapshots() )
                    {
                        logger.error( "Cannot store snapshot in non-snapshot deploy point: {}", deploy.getName() );
                        throw new IndyWorkflowException( ApplicationStatus.BAD_REQUEST.code(),
                                                         "Cannot store snapshot in non-snapshot deploy point: {}",
                                                         deploy.getName() );
                    }
                }
                else if ( !deploy.isAllowReleases() )
                {
                    logger.error( "Cannot store release in snapshot-only deploy point: {}", deploy.getName() );
                    throw new IndyWorkflowException( ApplicationStatus.BAD_REQUEST.code(),
                                                     "Cannot store release in snapshot-only deploy point: {}",
                                                     deploy.getName() );
                }
            }
        }

        //        final Transfer target = getStorageReference( deploy, path );

        // TODO: Need some protection for released files!
        // if ( target.exists() )
        // {
        // throw new WebApplicationException(
        // Response.status( Status.BAD_REQUEST ).entity( "Deployment path already exists." ).build() );
        // }

        try
        {
            KeyedLocation loc = LocationUtils.toLocation( store );
            boolean resetReadonly = ( !loc.allowsStoring() && isIgnoreReadonly( eventMetadata ) && loc instanceof CacheOnlyLocation );
            try
            {
                if ( resetReadonly )
                {
                    ( (CacheOnlyLocation) loc ).setReadonly( false );
                }
                final ConcreteResource resource = new ConcreteResource( loc, path );

                Transfer txfr = transfers.store( resource, stream, eventMetadata );
                nfc.clearMissing( resource );
                return txfr;
            }
            finally
            {
                if ( resetReadonly )
                {
                    ( (CacheOnlyLocation) loc ).setReadonly( true );
                }
            }
        }
        catch ( final BadGatewayException e )
        {
            fireIndyStoreErrorEvent( e );
            logger.warn( "Bad gateway: " + e.getMessage(), e );
            throw new IndyWorkflowException( "Failed to store path: {} in: {}. Reason: {}", e, path, store,
                                             e.getMessage() );
        }
        catch ( final TransferTimeoutException e )
        {
            fireIndyStoreErrorEvent( e );
            logger.warn( "Timeout: " + e.getMessage(), e );
            throw new IndyWorkflowException( "Failed to store path: {} in: {}. Reason: {}", e, path, store,
                                             e.getMessage() );
        }
        catch ( final TransferLocationException e )
        {
            fireIndyStoreErrorEvent( e );
            logger.warn( "Location Error: " + e.getMessage(), e );
            throw new IndyWorkflowException( "Failed to store path: {} in: {}. Reason: {}", e, path, store,
                                             e.getMessage() );
        }
        catch ( TransferException e )
        {
            logger.error(
                    String.format( "Failed to store: %s in: %s. Reason: %s", path, store.getKey(), e.getMessage() ),
                    e );

            throw new IndyWorkflowException( "Failed to store: %s in: %s. Reason: %s", e, path, store.getKey(),
                                             e.getMessage() );
        }
    }

    private boolean isIgnoreReadonly( EventMetadata eventMetadata )
    {
        return Boolean.TRUE.equals( eventMetadata.get( IGNORE_READONLY ) );
    }

    /*
     * (non-Javadoc)
     * @see org.commonjava.indy.core.rest.util.FileManager#upload(java.util.List, java.lang.String,
     * java.io.InputStream)
     */
    @Override
    public Transfer store( final List<? extends ArtifactStore> stores, final String path, final InputStream stream,
                           final TransferOperation op )
            throws IndyWorkflowException
    {
        return store( stores, path, stream, op, new EventMetadata() );
    }

    /*
     * (non-Javadoc)
     * @see org.commonjava.indy.core.rest.util.FileManager#upload(java.util.List, java.lang.String,
     * java.io.InputStream)
     */
    @Override
    public Transfer store( final List<? extends ArtifactStore> stores, final String path, final InputStream stream,
                           final TransferOperation op, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        final ContentQuality quality = getQuality( path );

        HostedRepository selected = null;
        for ( final ArtifactStore store : stores )
        {
            if ( !isIgnoreReadonly( eventMetadata ) && storeManager.isReadonly( store ) )
            {
                logger.debug( "The store {} is readonly, store operation not allowed", store.getKey() );
                continue;
            }
            if ( storeIsSuitableFor( store, quality, op ) )
            {
                selected = (HostedRepository) store;
                break;
            }
        }

        if ( selected == null )
        {
            logger.warn( "Cannot deploy. No valid deploy points in group." );
            throw new IndyWorkflowException( ApplicationStatus.BAD_REQUEST.code(),
                                             "No deployment locations available." );
        }

        logger.info( "Storing: {} in selected: {} with event metadata: {}", path, selected, eventMetadata );
        store( selected, path, stream, op, eventMetadata );

        return getStorageReference( selected.getKey(), path );
    }

    @Override
    public Transfer getStoreRootDirectory( final StoreKey key )
            throws IndyWorkflowException
    {
        ArtifactStore store;
        try
        {
            store = storeManager.getArtifactStore( key );
        }
        catch ( final IndyDataException e )
        {
            throw new IndyWorkflowException( "Failed to retrieve ArtifactStore for: %s. Reason: %s", e, key,
                                             e.getMessage() );
        }

        if ( store == null )
        {
            throw new IndyWorkflowException( ApplicationStatus.NOT_FOUND.code(), "Cannot find store: {}", key );
        }

        return transfers.getStoreRootDirectory( LocationUtils.toLocation( store ) );
    }

    @Override
    public Transfer getStoreRootDirectory( final ArtifactStore store )
    {
        return transfers.getStoreRootDirectory( LocationUtils.toLocation( store ) );
    }

    @Override
    public Transfer getStorageReference( final List<ArtifactStore> stores, final String path,
                                         final TransferOperation op )
            throws IndyWorkflowException
    {
//        final ArtifactPathInfo pathInfo = ArtifactPathInfo.parse( path );
        final ContentQuality quality = getQuality( path );

        Transfer transfer = null;

        logger.trace( "Checking {} stores to find one suitable for {} of: {}", stores.size(), op, path );
        boolean suitableFound = false;
        for ( final ArtifactStore store : stores )
        {
            if ( storeIsSuitableFor( store, quality, op ) )
            {
                suitableFound = true;

                logger.trace( "Attempting to retrieve storage reference in: {} for: {} (operation: {})", store, path,
                              op );

                // [jdcasey]: We don't want to use NFC for hosted repos any more...consumes memory and isn't much faster than filesystem
//                if ( store.getKey().getType() == hosted && ( op == DOWNLOAD || op == LISTING ) )
//                {
//                    transfer = getStorageReferenceWithNFC( store, path );
//                }
//                else
//                {
                    transfer = getStorageReference( store, path );
//                }
                logger.trace( "Checking {} (exists? {}; file: {})", transfer, transfer != null && transfer.exists(), transfer == null ? "NONE" : transfer.getFullPath() );
                if ( transfer != null && !transfer.exists() && ( op == DOWNLOAD || op == LISTING ) )
                {
                    transfer = null;
                }

                if ( transfer != null )
                {
                    logger.debug( "Using transfer: {}", transfer );
                    break;
                }
            }
        }

        if ( !stores.isEmpty() && !suitableFound )
        {
            logger.warn( "No suitable stores in list." );
            throw new IndyWorkflowException( ApplicationStatus.BAD_REQUEST.code(), "No suitable store available." );
        }

        return transfer;
    }

    private Transfer getStorageReferenceWithNFC( final ArtifactStore store, final String... path )
    {
        ConcreteResource resource = new ConcreteResource( LocationUtils.toLocation( store ), path );
        if ( store.getType() != hosted && nfc.isMissing( resource ) )
        {
            logger.trace( "Resource {} is missing, return null", resource );
            return null;
        }

        Transfer txfr = transfers.getCacheReference( resource );
        if ( txfr == null || !txfr.exists() )
        {
            logger.trace( "Resource not found when retrieving cached reference; added to NFC: {}", resource );
            nfc.addMissing( resource );
        }

        return txfr;
    }

    private boolean storeIsSuitableFor( final ArtifactStore store, final ContentQuality pathQuality,
                                        final TransferOperation op )
    {
        if ( TransferOperation.UPLOAD == op )
        {
            if ( store instanceof HostedRepository )
            {
                //                logger.info( "Found deploy point: %s", store.getName() );
                final HostedRepository dp = (HostedRepository) store;
                if ( pathQuality == null )
                {
                    // probably not an artifact, most likely metadata instead...
                    //                    logger.info( "Selecting it for non-artifact storage: {}", path );
                    return true;
                }
                else if (  ContentQuality.SNAPSHOT == pathQuality )
                {
                    if ( dp.isAllowSnapshots() )
                    {
                        //                        logger.info( "Selecting it for snapshot storage: {}", pathInfo );
                        return true;
                    }
                    else
                    {
                        logger.trace( "Hosted repo doesn't allow snapshot uploads: {}", store.getKey() );
                    }
                }
                else if ( dp.isAllowReleases() )
                {
                    //                    logger.info( "Selecting it for release storage: {}", pathInfo );
                    return true;
                }
                else
                {
                    logger.trace( "Hosted repo doesn't allow release uploads: {}", store.getKey() );
                }
            }
            else
            {
                logger.trace( "Store not suitable for upload: {}", store.getKey() );
            }

            // TODO: Allow push-through via remote repositories too.
        }
        else
        {
            return true;
        }

        return false;
    }

    @Override
    public Transfer getStorageReference( final ArtifactStore store, final String path, final TransferOperation op )
            throws IndyWorkflowException
    {
        final ContentQuality quality = getQuality( path );
        if ( storeIsSuitableFor( store, quality, op ) )
        {
            // [jdcasey]: We don't want to use NFC for hosted repos any more...consumes memory and isn't much faster than filesystem
//            if ( store.getKey().getType() == hosted && ( op == DOWNLOAD || op == LISTING ) )
//            {
//                return getStorageReferenceWithNFC( store, path );
//            }
//            else
//            {
                return getStorageReference( store, path );
//            }
        }

        logger.warn( "Store {} not suitable for: {}", store, op );
        throw new IndyWorkflowException( ApplicationStatus.BAD_REQUEST.code(),
                                         "Store is not suitable for this operation." );
    }

    @Override
    @Measure
    public Transfer getStorageReference( final ArtifactStore store, final String... path )
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.trace( "Retrieving cache reference (Transfer) to: {} in: {}", Arrays.asList( path ), store.getKey() );

        ConcreteResource resource = new ConcreteResource( LocationUtils.toLocation( store ), path );
        return transfers.getCacheReference( resource );
    }

    @Override
    @Measure
    public Transfer getStorageReference( final StoreKey key, final String... path )
            throws IndyWorkflowException
    {
        ArtifactStore store;
        try
        {
            store = storeManager.getArtifactStore( key );
        }
        catch ( final IndyDataException e )
        {
            throw new IndyWorkflowException( "Failed to retrieve ArtifactStore for: %s. Reason: %s", e, key,
                                             e.getMessage() );
        }

        if ( store == null )
        {
            throw new IndyWorkflowException( ApplicationStatus.NOT_FOUND.code(), "Cannot find store: {}", key );
        }

        return getStorageReference( store, path );
    }

    @Override
    public boolean deleteAll( final List<? extends ArtifactStore> stores, final String path )
            throws IndyWorkflowException
    {
        boolean result = false;
        for ( final ArtifactStore store : stores )
        {
            if ( storeManager.isReadonly( store ) )
            {
                logger.warn( "The store {} is readonly, store operation not allowed", store.getKey() );
                continue;
            }

            result = delete( store, path, new EventMetadata() ) || result;
        }

        return result;
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
        if ( Boolean.TRUE.equals( eventMetadata.get( CHECK_CACHE_ONLY ) ) )
        {
            return deleteCache( store, path, eventMetadata );
        }

        if ( store.getKey().getType() == StoreType.group )
        {
            // We should allow deletion of the group level mergeable metadata here, for supporting
            // the cascading deletion from hosted member pom file deletion. See MetadataMergePomChangeListener.metaClear
            // for details
            final SpecialPathInfo pathInfo = specialPathManager.getSpecialPathInfo( path );
            if ( pathInfo == null || !pathInfo.isMetadata() || !pathInfo.isMergable() )
            {
                return false;
            }
        }

        if ( storeManager.isReadonly( store ) && !isIgnoreReadonly( eventMetadata ) )
        {
            throw new IndyWorkflowException( ApplicationStatus.METHOD_NOT_ALLOWED.code(),
                                             "The store {} is readonly. If you want to store any content to this store, please modify it to non-readonly",
                                             store.getKey() );
        }

        final Transfer item = getStorageReference( store, path == null ? ROOT_PATH : path );

        return doDelete( item, eventMetadata );
    }

    /**
     * clean just the cache (storage of groups and remote repos)
     */
    private boolean deleteCache( ArtifactStore store, String path, EventMetadata eventMetadata )
                    throws IndyWorkflowException
    {
        if ( store.getKey().getType() == hosted )
        {
            SpecialPathInfo info = specialPathManager.getSpecialPathInfo( path );
            if ( info == null || !info.isMetadata() )
            {
                return false;
            }
        }
        final Transfer item = getStorageReference( store, path == null ? ROOT_PATH : path );
        logger.trace( "Delete cache, item: {}", item );
        return doDelete( item, eventMetadata );
    }

    private Boolean doDelete( final Transfer item, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        try
        {
            Location loc = item.getLocation();
            boolean resetReadonly = ( !loc.allowsStoring() && isIgnoreReadonly( eventMetadata ) && loc instanceof CacheOnlyLocation );
            try
            {
                if ( resetReadonly )
                {
                    ( (CacheOnlyLocation) loc ).setReadonly( false );
                }
                final ConcreteResource resource = new ConcreteResource( loc, item.getPath() );
                transfers.delete( resource, eventMetadata );
            }
            finally
            {
                if ( resetReadonly )
                {
                    ( (CacheOnlyLocation) loc ).setReadonly( true );
                }
            }
        }
        catch ( final TransferException e )
        {
            throw new IndyWorkflowException( "Failed to delete: {}. Reason: {}", e, item, e.getMessage() );
        }

        return true;
    }

    @Override
    public void rescanAll( final List<? extends ArtifactStore> stores )
            throws IndyWorkflowException
    {
        rescanAll( stores, new EventMetadata() );
    }

    @Override
    public void rescanAll( final List<? extends ArtifactStore> stores, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        for ( final ArtifactStore store : stores )
        {
            rescan( store, eventMetadata );
        }
    }

    @Override
    public void rescan( final ArtifactStore store )
            throws IndyWorkflowException
    {
        rescan( store, new EventMetadata() );
    }

    @Override
    public void rescan( final ArtifactStore store, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        detectOverloadVoid( () -> rescanService.execute(
                new Rescanner( store, getStorageReference( store.getKey() ), rescansInProgress, fileEventManager,
                               rescanEvent, eventMetadata ) ) );
    }

    private static final class Rescanner
            implements Runnable
    {
        private final Logger logger = LoggerFactory.getLogger( getClass() );

        private static final Byte IN_PROGRESS_FLAG = (byte) 0x1;

        private final Map<StoreKey, Byte> rescansInProgress;

        private final Transfer start;

        private final Event<ArtifactStoreRescanEvent> rescanEvent;

        private final IndyFileEventManager fileEventManager;

        private final ArtifactStore store;

        private final EventMetadata eventMetadata;

        public Rescanner( final ArtifactStore store, final Transfer start, final Map<StoreKey, Byte> rescansInProgress,
                          final IndyFileEventManager fileEventManager,
                          final Event<ArtifactStoreRescanEvent> rescanEvent, final EventMetadata eventMetadata )
        {
            this.store = store;
            this.start = start;
            this.rescansInProgress = rescansInProgress;
            this.fileEventManager = fileEventManager;
            this.rescanEvent = rescanEvent;
            this.eventMetadata = eventMetadata;
        }

        @Override
        public void run()
        {
            final StoreKey storeKey = store.getKey();
            synchronized ( rescansInProgress )
            {
                if ( rescansInProgress.containsKey( storeKey ) )
                {
                    return;
                }

                rescansInProgress.put( storeKey, IN_PROGRESS_FLAG );
                store.setRescanInProgress( true );
            }

            try
            {
                fireEvent( rescanEvent, new ArtifactStorePreRescanEvent( eventMetadata, store ) );

                doRescan( start );

                fireEvent( rescanEvent, new ArtifactStorePostRescanEvent( eventMetadata, store ) );
            }
            finally
            {
                synchronized ( rescansInProgress )
                {
                    rescansInProgress.remove( storeKey );
                    store.setRescanInProgress( false );
                }
            }
        }

        private void doRescan( final Transfer item )
        {
            if ( !item.exists() )
            {
                return;
            }

            if ( item.isDirectory() )
            {
                try
                {
                    final String[] listing = item.list();
                    for ( final String sub : listing )
                    {
                        doRescan( item.getChild( sub ) );
                    }
                }
                catch ( final IOException e )
                {
                    logger.error(
                            String.format( "Failed to list local contents: %s. Reason: %s", item, e.getMessage() ), e );
                }
            }

            fileEventManager.fire( new FileAccessEvent( item, eventMetadata ) );
        }

    }

    @Override
    @Measure
    public List<Transfer> listRecursively( final StoreKey src, final String startPath )
            throws IndyWorkflowException
    {
        final List<Transfer> result = new ArrayList<>();
        final Transfer transfer = getStorageReference( src, startPath );
        recurseListing( transfer, result );

        return result;
    }

    private void recurseListing( final Transfer transfer, final List<Transfer> result )
            throws IndyWorkflowException
    {
        if ( transfer.isDirectory() )
        {
            try
            {
                final String[] children = transfer.list();
                for ( final String child : children )
                {
                    final Transfer childTransfer = transfer.getChild( child );
                    recurseListing( childTransfer, result );
                }
            }
            catch ( final IOException e )
            {
                throw new IndyWorkflowException( "Failed to list children of: %s. Reason: %s", e, transfer,
                                                 e.getMessage() );
            }
        }
        else if ( transfer.exists() )
        {
            SpecialPathInfo spi = specialPathManager.getSpecialPathInfo( transfer.getPath() );
            if ( spi == null || spi.isListable() )
            {
                result.add( transfer );
            }
        }
    }

    private ContentQuality getQuality( String path )
    {
        if ( contentAdvisors != null )
        {
            final ContentAdvisor advisor = StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize( contentAdvisors.iterator(), Spliterator.ORDERED ), false )
                                                        .filter( Objects::nonNull )
                                                        .findFirst()
                                                        .orElse( null );
            return advisor == null ? null : advisor.getContentQuality( path );
        }

        return null;
    }


    private void fireIndyStoreErrorEvent( TransferLocationException e )
    {
        Location location = e.getLocation();
        KeyedLocation kl = (KeyedLocation) location;

        fileEventManager.fire( new IndyStoreErrorEvent( kl.getKey(), e ) );
    }


}

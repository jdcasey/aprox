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
package org.commonjava.indy.core.content;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.conf.IndyConfiguration;
import org.commonjava.indy.content.ContentDigester;
import org.commonjava.indy.content.ContentGenerator;
import org.commonjava.indy.content.ContentManager;
import org.commonjava.indy.content.DownloadManager;
import org.commonjava.indy.content.StoreResource;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.measure.annotation.Measure;
import org.commonjava.indy.measure.annotation.MetricNamed;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.commonjava.indy.model.galley.KeyedLocation;
import org.commonjava.indy.util.ApplicationStatus;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.maven.galley.TransferManager;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.SpecialPathInfo;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.spi.io.SpecialPathManager;
import org.commonjava.maven.galley.spi.nfc.NotFoundCache;
import org.commonjava.maven.galley.transport.htcli.model.HttpExchangeMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.commonjava.indy.IndyContentConstants.CHECK_CACHE_ONLY;
import static org.commonjava.indy.measure.annotation.MetricNamed.DEFAULT;
import static org.commonjava.indy.model.core.StoreType.group;
import static org.commonjava.indy.model.core.StoreType.hosted;
import static org.commonjava.indy.util.ContentUtils.dedupeListing;
import static org.commonjava.maven.galley.io.SpecialPathConstants.HTTP_METADATA_EXT;

public class DefaultContentManager
        implements ContentManager
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private Instance<ContentGenerator> contentProducerInstances;

    private Set<ContentGenerator> contentGenerators;

    @Inject
    private StoreDataManager storeManager;

    @Inject
    private DownloadManager downloadManager;

    @Inject
    private SpecialPathManager specialPathManager;

    @Inject
    private NotFoundCache nfc;

    @Inject
    private IndyObjectMapper mapper;

    @Inject
    private ContentDigester contentDigester;

    @Inject
    private IndyConfiguration indyConfig;

    protected DefaultContentManager()
    {
    }

    public DefaultContentManager( final StoreDataManager storeManager, final DownloadManager downloadManager,
                                  final IndyObjectMapper mapper, final SpecialPathManager specialPathManager,
                                  final NotFoundCache nfc, final ContentDigester contentDigester, final Set<ContentGenerator> contentProducers )
    {
        this.storeManager = storeManager;
        this.downloadManager = downloadManager;
        this.mapper = mapper;
        this.specialPathManager = specialPathManager;
        this.nfc = nfc;
        this.contentDigester = contentDigester;
        this.contentGenerators = contentProducers == null ? new HashSet<>() : contentProducers;
    }

    @PostConstruct
    public void initialize()
    {
        contentGenerators = new HashSet<>();
        if ( contentProducerInstances != null )
        {
            for ( final ContentGenerator producer : contentProducerInstances )
            {
                contentGenerators.add( producer );
            }
        }
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
        Transfer txfr = null;
        for ( final ArtifactStore store : stores )
        {
            txfr = doRetrieve( store, path, eventMetadata );
            if ( txfr != null )
            {
                break;
            }
        }

        return txfr;
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
        final List<Transfer> txfrs = new ArrayList<>();
        for ( final ArtifactStore store : stores )
        {
            if ( group == store.getKey().getType() )
            {
                List<ArtifactStore> members;
                try
                {
                    members = storeManager.query()
                                          .packageType( store.getPackageType() )
                                          .enabledState( true )
                                          .getOrderedConcreteStoresInGroup( store.getName() );
                }
                catch ( final IndyDataException e )
                {
                    throw new IndyWorkflowException( "Failed to lookup concrete members of: %s. Reason: %s", e, store,
                                                      e.getMessage() );
                }

                final List<Transfer> storeTransfers = new ArrayList<>();
                for ( final ContentGenerator generator : contentGenerators )
                {
                    final Transfer txfr =
                            generator.generateGroupFileContent( (Group) store, members, path, eventMetadata );
                    if ( txfr != null )
                    {
                        storeTransfers.add( txfr );
                    }
                }

                // If the content was generated, don't try to retrieve it from a member store...this is the lone exception to retrieveAll
                // ...if it's generated, it's merged in this case.
                if ( storeTransfers.isEmpty() )
                {
                    for ( final ArtifactStore member : members )
                    {
                        // NOTE: This is only safe to call because we're concrete ordered stores, so anything passing through here is concrete.
                        final Transfer txfr = doRetrieve( member, path, eventMetadata );
                        if ( txfr != null )
                        {
                            storeTransfers.add( txfr );
                        }
                    }
                }

                txfrs.addAll( storeTransfers );
            }
            else
            {
                // NOTE: This is only safe to call because we're doing the group check up front, so anything passing through here is concrete.
                final Transfer txfr = doRetrieve( store, path, eventMetadata );
                if ( txfr != null )
                {
                    txfrs.add( txfr );
                }
            }
        }

        return txfrs;
    }

    @Override
    public Transfer retrieve( final ArtifactStore store, final String path )
            throws IndyWorkflowException
    {
        return retrieve( store, path, new EventMetadata() );
    }

    @Override
    @Measure( timers = @MetricNamed( DEFAULT ), exceptions = @MetricNamed( DEFAULT ) )
    public Transfer retrieve( final ArtifactStore store, final String path, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        Transfer item;
        if ( group == store.getKey().getType() )
        {
            List<ArtifactStore> members;
            try
            {
                members = storeManager.query()
                                      .packageType( store.getPackageType() )
                                      .enabledState( true )
                                      .getOrderedConcreteStoresInGroup( store.getName() );
            }
            catch ( final IndyDataException e )
            {
                throw new IndyWorkflowException( "Failed to lookup concrete members of: %s. Reason: %s", e, store,
                                                  e.getMessage() );
            }

            if ( logger.isTraceEnabled() )
            {
                logger.trace( "{} is a group. Attempting downloads from (in order):\n  {}", store.getKey(), StringUtils.join(members, "\n  ") );
            }

            item = null;
            boolean generated = false;
            for ( final ContentGenerator generator : contentGenerators )
            {
                if ( generator.canProcess( path ) )
                {
                    item = generator.generateGroupFileContent( (Group) store, members, path, eventMetadata );
                    logger.trace( "From content {}.generateGroupFileContent: {} (exists? {})",
                                  generator.getClass().getSimpleName(), item, item != null && item.exists() );
                    generated = true;
                    break;
                }
            }

            if ( !generated )
            {
                if ( PathMaskChecker.checkMask( store, path ) )
                {
                    for ( final ArtifactStore member : members )
                    {
                        try
                        {
                            item = doRetrieve( member, path, eventMetadata );
                        }
                        catch ( IndyWorkflowException e )
                        {
                            logger.error( "Failed to retrieve artifact from for path {} from {} in group {}, error is: {}",
                                          path, member, store, e.getMessage() );
                        }
                        if ( item != null )
                        {
                            // get the item from the first member store
                            break;
                        }
                    }
                }
            }
        }
        else
        {
            item = doRetrieve( store, path, eventMetadata );
        }

        logger.info( "Returning transfer {} from {}", item, store.getKey() );

        return item;
    }

    private Transfer doRetrieve( final ArtifactStore store, final String path, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        logger.trace( "Attempting to retrieve: {} from: {}", path, store.getKey() );

        if ( store.isDisabled() )
        {
            logger.debug( "Content not available in repository due to store disabled for {}, path is {}", store,
                         path );
            return null;
        }

        Transfer item = null;
        try
        {
            item = downloadManager.retrieve( store, path, eventMetadata );

            if ( item == null )
            {
                for ( final ContentGenerator generator : contentGenerators )
                {
                    logger.trace( "Attempting to generate content for path: {} in: {} via: {}", path, store,
                                  generator );
                    item = generator.generateFileContent( store, path, eventMetadata );
                    if ( item != null )
                    {
                        logger.debug( "Resource generated for {}, clean NFC and delete obsolete http-metadata.json", item.getResource() );
                        nfc.clearMissing( item.getResource() );
                        Transfer httpMeta = item.getSiblingMeta( HTTP_METADATA_EXT );
                        try
                        {
                            httpMeta.delete();
                        }
                        catch ( IOException e )
                        {
                            logger.warn( "Failed to delete {}", httpMeta.getResource() );
                        }
                        break;
                    }
                }
            }
        }
        catch ( IndyWorkflowException e )
        {
            e.filterLocationErrors();
        }

        return item;
    }

    @Override
    public Transfer store( final ArtifactStore store, final String path, final InputStream stream,
                           final TransferOperation op )
            throws IndyWorkflowException
    {
        return store( store, path, stream, op, new EventMetadata() );
    }

    @Override
    @Measure( timers = @MetricNamed( DEFAULT ), exceptions = @MetricNamed( DEFAULT ) )
    public Transfer store( final ArtifactStore store, final String path, final InputStream stream,
                           final TransferOperation op, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        if ( group == store.getKey().getType() )
        {
            try
            {
                final List<ArtifactStore> allMembers = storeManager.query()
                                      .packageType( store.getPackageType() )
                                      .enabledState( true )
                                      .getOrderedConcreteStoresInGroup( store.getName() );

                final Transfer txfr = store( allMembers, store.getKey(), path, stream, op, eventMetadata );
                logger.debug( "Stored: {} for group: {} in: {}", path, store.getKey(), txfr );
                return txfr;
            }
            catch ( final IndyDataException e )
            {
                throw new IndyWorkflowException( "Failed to lookup concrete members of: %s. Reason: %s", e, store,
                                                  e.getMessage() );
            }
        }

        logger.debug( "Storing: {} for: {} with event metadata: {}", path, store.getKey(), eventMetadata );
        final Transfer txfr = downloadManager.store( store, path, stream, op, eventMetadata );
        if ( txfr != null )
        {
            final KeyedLocation kl = (KeyedLocation) txfr.getLocation();
            ArtifactStore transferStore;
            try
            {
                transferStore = storeManager.getArtifactStore( kl.getKey() );
            }
            catch ( final IndyDataException e )
            {
                throw new IndyWorkflowException( "Failed to lookup store: %s. Reason: %s", e, kl.getKey(),
                                                  e.getMessage() );
            }

            for ( final ContentGenerator generator : contentGenerators )
            {
                generator.handleContentStorage( transferStore, path, txfr, eventMetadata );
            }

            if ( !store.equals( transferStore ) )
            {
                for ( final ContentGenerator generator : contentGenerators )
                {
                    generator.handleContentStorage( transferStore, path, txfr, eventMetadata );
                }
            }

            clearNFCEntries(kl, path);
        }

        return txfr;
    }

    private void clearNFCEntries( final KeyedLocation kl, final String path )
    {
        try
        {
            storeManager.query()
                        .getGroupsAffectedBy( kl.getKey() )
                        .stream()
                        .map( ( g ) -> new ConcreteResource( LocationUtils.toLocation( g ), path ) )
                        .forEach( ( cr ) -> nfc.clearMissing( cr ) );

            nfc.clearMissing( new ConcreteResource( kl, path ) );
        }
        catch ( IndyDataException e )
        {
            logger.error( String.format("Failed to clear NFC entries affected by upload of: %s to: %s. Reason: %s", path,
                                        kl.getKey(), e.getMessage()), e );
        }
    }

    //    @Override
//    public Transfer store( final List<? extends ArtifactStore> stores, final String path, final InputStream stream,
//                           final TransferOperation op )
//            throws IndyWorkflowException
//    {
//        return store( stores, path, stream, op, new EventMetadata() );
//    }

    @Override
    public Transfer store( final List<? extends ArtifactStore> stores, final StoreKey topKey, final String path, final InputStream stream,
                           final TransferOperation op, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        logger.debug( "Storing: {} in: {} with event metadata: {}", path, stores, eventMetadata );
        final Transfer txfr = downloadManager.store( stores, path, stream, op, eventMetadata );
        if ( txfr != null )
        {
            final KeyedLocation kl = (KeyedLocation) txfr.getLocation();
            ArtifactStore transferStore;
            try
            {
                transferStore = storeManager.getArtifactStore( kl.getKey() );
            }
            catch ( final IndyDataException e )
            {
                throw new IndyWorkflowException( "Failed to lookup store: %s. Reason: %s", e, kl.getKey(),
                                                  e.getMessage() );
            }

            for ( final ContentGenerator generator : contentGenerators )
            {
                logger.debug( "{} Handling content storage of: {} in: {}", generator, path, transferStore.getKey() );
                generator.handleContentStorage( transferStore, path, txfr, eventMetadata );
            }

            clearNFCEntries(kl, path);
        }

        return txfr;
    }

    @Override
    public boolean delete( final ArtifactStore store, final String path )
            throws IndyWorkflowException
    {
        return delete( store, path, new EventMetadata() );
    }

    @Override
    @Measure
    public boolean delete( final ArtifactStore store, final String path, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        if ( Boolean.TRUE.equals( eventMetadata.get( CHECK_CACHE_ONLY ) ) )
        {
            return deleteCache( store, path, eventMetadata );
        }

        boolean result = false;
        if ( group == store.getKey().getType() )
        {
            List<ArtifactStore> members;
            try
            {
                members = storeManager.query()
                                      .packageType( store.getPackageType() )
                                      .enabledState( true )
                                      .getOrderedConcreteStoresInGroup( store.getName() );
            }
            catch ( final IndyDataException e )
            {
                throw new IndyWorkflowException( "Failed to lookup concrete members of: %s. Reason: %s", e, store,
                                                  e.getMessage() );
            }

            for ( final ArtifactStore member : members )
            {
                if ( downloadManager.delete( member, path, eventMetadata ) )
                {
                    result = true;
                    for ( final ContentGenerator generator : contentGenerators )
                    {
                        generator.handleContentDeletion( member, path, eventMetadata );
                    }
                }
            }

            if ( result )
            {
                for ( final ContentGenerator generator : contentGenerators )
                {
                    generator.handleContentDeletion( store, path, eventMetadata );
                }
            }
        }
        else
        {
            if ( downloadManager.delete( store, path, eventMetadata ) )
            {
                result = true;
                for ( final ContentGenerator generator : contentGenerators )
                {
                    generator.handleContentDeletion( store, path, eventMetadata );
                }
            }
        }

        return result;
    }

    /**
     * clean just the cache (storage of groups and remote repos)
     */
    private boolean deleteCache( ArtifactStore store, String path, EventMetadata eventMetadata )
                    throws IndyWorkflowException
    {
        logger.trace( "Delete cache, store:{}, path:{}", store.getName(), path );
        if ( hosted == store.getKey().getType() )
        {
            return false;
        }
        else
        {
            return downloadManager.delete( store, path, eventMetadata );
        }
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
        for ( final ArtifactStore store : stores )
        {
            result = delete( store, path, eventMetadata ) || result;
        }

        return result;
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
        downloadManager.rescan( store, eventMetadata );
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
        downloadManager.rescanAll( stores, eventMetadata );
    }

    @Override
    public List<StoreResource> list( final ArtifactStore store, final String path )
            throws IndyWorkflowException
    {
        return list( store, path, new EventMetadata() );
    }

    @Override
    @Measure( timers = @MetricNamed( DEFAULT ), exceptions = @MetricNamed( DEFAULT ) )
    public List<StoreResource> list( final ArtifactStore store, final String path, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        final EventMetadata metadata = setAllowRemoteDownloading(eventMetadata);

        List<StoreResource> listed;
        if ( group == store.getKey().getType() )
        {
            List<ArtifactStore> members;
            try
            {
                members = storeManager.query()
                                      .packageType( store.getPackageType() )
                                      .enabledState( true )
                                      .getOrderedConcreteStoresInGroup( store.getName() );
            }
            catch ( final IndyDataException e )
            {
                throw new IndyWorkflowException( "Failed to lookup concrete members of: %s. Reason: %s", e, store,
                                                  e.getMessage() );
            }

            listed = new ArrayList<>();
            for ( final ContentGenerator generator : contentGenerators )
            {
                final List<StoreResource> generated =
                        generator.generateGroupDirectoryContent( (Group) store, members, path, eventMetadata );
                if ( generated != null )
                {
                    listed.addAll( generated );
                }
            }

            for ( final ArtifactStore member : members )
            {
                List<StoreResource> storeListing = null;
                try
                {
                    storeListing = list( member, path, metadata );
                }
                catch ( IndyWorkflowException e )
                {
                    e.filterLocationErrors();
                }

                if ( storeListing != null )
                {
                    listed.addAll( storeListing );
                }
            }
        }
        else
        {
            listed = downloadManager.list( store, path, metadata );

            for ( final ContentGenerator producer : contentGenerators )
            {
                final List<StoreResource> produced =
                        producer.generateDirectoryContent( store, path, listed, metadata );
                if ( produced != null )
                {
                    listed.addAll( produced );
                }
            }
        }

        return dedupeListing( listed );
    }

    private EventMetadata setAllowRemoteDownloading( final EventMetadata eventMetadata )
    {
        if ( eventMetadata == null )
        {
            return new EventMetadata().set( TransferManager.ALLOW_REMOTE_LISTING_DOWNLOAD,
                                            indyConfig.isAllowRemoteListDownload() );
        }
        else if ( eventMetadata.get( TransferManager.ALLOW_REMOTE_LISTING_DOWNLOAD ) == null )
        {
            return eventMetadata.set( TransferManager.ALLOW_REMOTE_LISTING_DOWNLOAD,
                                      indyConfig.isAllowRemoteListDownload() );
        }
        else
        {
            return eventMetadata;
        }
    }

    @Override
    public List<StoreResource> list( final List<? extends ArtifactStore> stores, final String path )
            throws IndyWorkflowException
    {
        final List<StoreResource> listed = new ArrayList<>();
        for ( final ArtifactStore store : stores )
        {
            List<StoreResource> storeListing = null;
            try
            {
                storeListing = list( store, path, new EventMetadata() );
            }
            catch ( IndyWorkflowException e )
            {
                e.filterLocationErrors();
            }

            if ( storeListing != null )
            {
                listed.addAll( storeListing );
            }
        }

        return dedupeListing( listed );
    }

    @Override
    public Transfer getTransfer( final StoreKey store, final String path, final TransferOperation op )
            throws IndyWorkflowException
    {
        try
        {
            return getTransfer( storeManager.getArtifactStore( store ), path, op );
        }
        catch ( final IndyDataException e )
        {
            throw new IndyWorkflowException( ApplicationStatus.BAD_REQUEST.code(),
                                              "Failed to retrieve ArtifactStore for key: %s. Reason: %s", e, store,
                                              e.getMessage() );

        }
    }

    @Override
    public Transfer getTransfer( final ArtifactStore store, final String path, final TransferOperation op )
            throws IndyWorkflowException
    {
        logger.trace( "Getting transfer for: {}/{} (op: {})", store.getKey(), path, op );
        if ( group == store.getKey().getType() )
        {
            KeyedLocation location = LocationUtils.toLocation( store );
            SpecialPathInfo spInfo = specialPathManager.getSpecialPathInfo( location, path, store.getPackageType() );
            if ( spInfo == null || !spInfo.isMergable() )
            {
                try
                {
                    final List<ArtifactStore> allMembers = storeManager.query()
                                          .packageType( store.getPackageType() )
                                          .enabledState( true )
                                          .getOrderedConcreteStoresInGroup( store.getName() );

                    logger.trace( "Trying to retrieve suitable transfer for: {} in group: {}", path, store.getName() );
                    logger.trace( "Members in group {}: {}", store.getName(), allMembers );

                    return getTransfer( allMembers, path, op );
                }
                catch ( final IndyDataException e )
                {
                    throw new IndyWorkflowException( "Failed to lookup concrete members of: %s. Reason: %s", e, store,
                                                      e.getMessage() );
                }
            }
            else
            {
                logger.trace( "Detected mergable special path: {}/{}.", store.getKey(), path );
            }
        }

        logger.trace( "Retrieving storage reference (Transfer) directly for: {}/{}", store.getKey(), path );
        return downloadManager.getStorageReference( store, path, op );
    }

    @Override
    public Transfer getTransfer( final List<ArtifactStore> stores, final String path, final TransferOperation op )
            throws IndyWorkflowException
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.trace( "Looking for path: '{}' in stores: {}", path,
                      stores.stream().map( ArtifactStore::getKey ).collect( Collectors.toList() ) );

        return downloadManager.getStorageReference( stores, path, op );
    }

    @Override
    public HttpExchangeMetadata getHttpMetadata( final Transfer txfr )
            throws IndyWorkflowException
    {
        final Transfer meta = txfr.getSiblingMeta( HttpExchangeMetadata.FILE_EXTENSION );
        return readExchangeMetadata( meta );
    }

    @Override
    public HttpExchangeMetadata getHttpMetadata( final StoreKey key, final String path )
            throws IndyWorkflowException
    {
        Transfer transfer = getTransfer( key, path, TransferOperation.DOWNLOAD );
        if ( transfer != null && transfer.exists() )
        {
            Transfer meta = transfer.getSiblingMeta( HttpExchangeMetadata.FILE_EXTENSION );
            if ( meta != null && meta.exists() )
            {
                return readExchangeMetadata( meta );
            }
        }

        return null;
    }

    @Override
    // TODO: to add content generation handling here, for things like merged metadata, checksum files, etc.
    public boolean exists(ArtifactStore store, String path)
        throws IndyWorkflowException
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.trace( "Checking existence of: {} in: {}", path, store.getKey() );
        if ( store instanceof Group )
        {
            try
            {
                final List<ArtifactStore> allMembers = storeManager.query()
                                      .packageType( store.getPackageType() )
                                      .enabledState( true )
                                      .getOrderedConcreteStoresInGroup( store.getName() );

                logger.trace( "Trying to retrieve suitable transfer for: {} in group: {}", path, store.getName() );
                logger.trace( "Members in group {}: {}", store.getName(), allMembers );

                for ( ArtifactStore member : allMembers )
                {
                    if ( exists( member, path ) )
                    {
                        return true;
                    }
                }

                return false;
            }
            catch ( final IndyDataException e )
            {
                throw new IndyWorkflowException( "Failed to lookup concrete members of: %s. Reason: %s", e, store,
                                                 e.getMessage() );
            }
        }
        else
        {
            return downloadManager.exists(store, path);
        }
    }

    private HttpExchangeMetadata readExchangeMetadata( final Transfer meta )
            throws IndyWorkflowException
    {
        logger.trace( "Reading HTTP exchange metadata from: {}", meta );
        if ( meta != null && meta.exists() )
        {
            try(InputStream stream = meta.openInputStream( false ))
            {
                String raw = IOUtils.toString( stream );
                logger.trace( "HTTP Metadata string is:\n\n{}\n\n", raw );

                return mapper.readValue( raw, HttpExchangeMetadata.class );
            }
            catch ( final IOException e )
            {
                throw new IndyWorkflowException( "HTTP exchange metadata appears to be damaged: %s. Reason: %s", e,
                                                  meta, e.getMessage() );
            }
        }
        else
        {
            logger.trace( "Cannot read HTTP exchange: {}. Transfer is missing!", meta );
        }

        return null;
    }

}

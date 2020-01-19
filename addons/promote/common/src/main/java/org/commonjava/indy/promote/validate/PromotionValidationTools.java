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
package org.commonjava.indy.promote.validate;

import groovy.lang.Closure;
import org.commonjava.cdi.util.weft.ExecutorConfig;
import org.commonjava.cdi.util.weft.WeftManaged;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.content.ContentDigester;
import org.commonjava.indy.content.ContentManager;
import org.commonjava.indy.content.StoreResource;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.data.ArtifactStoreQuery;
import org.commonjava.propulsor.metrics.annotation.Measure;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.promote.conf.PromoteConfig;
import org.commonjava.indy.promote.validate.model.ValidationRequest;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.atlas.maven.graph.rel.ProjectRelationship;
import org.commonjava.atlas.maven.ident.ref.ArtifactRef;
import org.commonjava.atlas.maven.ident.ref.ProjectRef;
import org.commonjava.atlas.maven.ident.ref.ProjectVersionRef;
import org.commonjava.atlas.maven.ident.util.ArtifactPathInfo;
import org.commonjava.maven.galley.TransferException;
import org.commonjava.maven.galley.TransferManager;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.io.checksum.ContentDigest;
import org.commonjava.maven.galley.maven.GalleyMavenException;
import org.commonjava.maven.galley.maven.model.view.MavenPomView;
import org.commonjava.maven.galley.maven.model.view.meta.MavenMetadataView;
import org.commonjava.maven.galley.maven.parse.MavenMetadataReader;
import org.commonjava.maven.galley.maven.parse.MavenPomReader;
import org.commonjava.maven.galley.maven.rel.MavenModelProcessor;
import org.commonjava.maven.galley.maven.rel.ModelProcessorConfig;
import org.commonjava.maven.galley.maven.spi.type.TypeMapper;
import org.commonjava.maven.galley.maven.util.ArtifactPathUtils;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.transport.htcli.model.HttpExchangeMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.inject.Inject;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.commonjava.indy.promote.util.Batcher.batch;
import static org.commonjava.indy.promote.validate.util.ReadOnlyTransfer.readOnlyWrapper;
import static org.commonjava.indy.promote.validate.util.ReadOnlyTransfer.readOnlyWrappers;
import static org.commonjava.maven.galley.io.ChecksummingTransferDecorator.FORCE_CHECKSUM;

/**
 * Created by jdcasey on 9/11/15.
 */
public class PromotionValidationTools
{
    final Logger logger = LoggerFactory.getLogger( this.getClass() );

    public static final String AVAILABLE_IN_STORES = "availableInStores";

    @Deprecated
    public static final String AVAILABLE_IN_STORE_KEY = "availableInStoreKey";

    private static final int DEFAULT_RULE_PARALLEL_WAIT_TIME_MINS = 30;

    private static final String ITERATION_DEPTH = "promotion-validation-parallel-depth";

    private static final String ITERATION_ITEM = "promotion-validation-parallel-item";

    @Inject
    private ContentManager contentManager;

    @Inject
    private StoreDataManager storeDataManager;

    @Inject
    private MavenPomReader pomReader;

    @Inject
    private MavenMetadataReader metadataReader;

    @Inject
    private MavenModelProcessor modelProcessor;

    @Inject
    private TypeMapper typeMapper;

    @Inject
    private TransferManager transferManager;

    @Inject
    private ContentDigester contentDigester;

    @Inject
    private PromoteConfig promoteConfig;

    @Inject
    @WeftManaged
    @ExecutorConfig( named = "promote-validation-rules-executor", threads = 8 )
    private Executor ruleParallelExecutor;

    protected PromotionValidationTools()
    {
    }

    public PromotionValidationTools( final ContentManager manager, final StoreDataManager storeDataManager,
                                     final MavenPomReader pomReader, final MavenMetadataReader metadataReader,
                                     final MavenModelProcessor modelProcessor, final TypeMapper typeMapper,
                                     final TransferManager transferManager, final ContentDigester contentDigester,
                                     final Executor ruleParallelExecutor, final PromoteConfig config )
    {
        contentManager = manager;
        this.storeDataManager = storeDataManager;
        this.pomReader = pomReader;
        this.metadataReader = metadataReader;
        this.modelProcessor = modelProcessor;
        this.typeMapper = typeMapper;
        this.transferManager = transferManager;
        this.contentDigester = contentDigester;
        this.ruleParallelExecutor = ruleParallelExecutor;
        this.promoteConfig = config;
    }

    public StoreKey[] getValidationStoreKeys( final ValidationRequest request )
            throws PromotionValidationException
    {
        return getValidationStoreKeys( request, false );
    }

    /**
     * @deprecated This method now is only used for api backward compatible
     */
    @Deprecated
    public StoreKey[] getValidationStoreKeys( final ValidationRequest request, final boolean includeSource,
                                              final boolean includeTarget )
            throws PromotionValidationException
    {
        return getValidationStoreKeys( request, includeSource );
    }

    public StoreKey[] getValidationStoreKeys( final ValidationRequest request, final boolean includeSource )
            throws PromotionValidationException
    {
        String verifyStores = request.getValidationParameter( PromotionValidationTools.AVAILABLE_IN_STORES );
        if ( verifyStores == null )
        {
            verifyStores = request.getValidationParameter( PromotionValidationTools.AVAILABLE_IN_STORE_KEY );
        }

        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.debug( "Got extra validation keys string: '{}'", verifyStores );

        Set<StoreKey> verifyStoreKeys = new HashSet<>();
        if ( includeSource )
        {
            verifyStoreKeys.add( request.getSourceRepository().getKey() );
        }


        if ( verifyStores == null )
        {
            logger.warn(
                    "No external store (availableInStoreKey parameter) specified for validating path availability in rule-set: {}. Using target: {} instead.",
                    request.getRuleSet().getName(), request.getTarget() );
        }
        else
        {
            List<StoreKey> extras = Stream.of( verifyStores.split( "\\s*,\\s*" ) )
                                          .map( StoreKey::fromString )
                                          .filter( item -> item != null )
                                          .collect( Collectors.toList() );

            if ( extras.isEmpty() )
            {
                throw new PromotionValidationException( "No valid StoreKey instances could be parsed from '%s'",
                                                        verifyStores );
            }
            else
            {
                verifyStoreKeys.addAll( extras );
            }
        }

        if ( verifyStoreKeys.isEmpty() )
        {
            verifyStoreKeys.add( request.getTarget() );
        }

        logger.debug( "Using validation StoreKeys: {}", verifyStoreKeys );

        return verifyStoreKeys.toArray( new StoreKey[0] );
    }

    public String toArtifactPath( final ProjectVersionRef ref )
            throws TransferException
    {
        return ArtifactPathUtils.formatArtifactPath( ref, typeMapper );
    }

    public String toMetadataPath( final ProjectRef ref, final String filename )
            throws TransferException
    {
        return ArtifactPathUtils.formatMetadataPath( ref, filename );
    }

    public String toMetadataPath( final String groupId, final String filename )
            throws TransferException
    {
        return ArtifactPathUtils.formatMetadataPath( groupId, filename );
    }

    public Set<ProjectRelationship<?, ?>> getRelationshipsForPom( final String path, final ModelProcessorConfig config,
                                                                  final ValidationRequest request,
                                                                  final StoreKey... extraLocations )
            throws IndyWorkflowException, GalleyMavenException, IndyDataException
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.trace( "Retrieving relationships for POM: {} (using extra locations: {})", path,
                      Arrays.asList( extraLocations ) );

        ArtifactRef artifactRef = getArtifact( path );
        if ( artifactRef == null )
        {
            logger.trace( "{} is not a valid artifact reference. Skipping.", path );
            return null;
        }

        StoreKey key = request.getSourceRepository().getKey();
        Transfer transfer = retrieve( request.getSourceRepository(), path );
        if ( transfer == null )
        {
            logger.trace( "Could not retrieve Transfer instance for: {} (path: {}, extra locations: {})", key, path,
                          Arrays.asList( extraLocations ) );
            return null;
        }

        List<Location> locations = new ArrayList<>( extraLocations.length + 1 );
        locations.add( transfer.getLocation() );
        addLocations( locations, extraLocations );

        MavenPomView pomView =
                pomReader.read( artifactRef.asProjectVersionRef(), transfer, locations, MavenPomView.ALL_PROFILES );

        try
        {
            URI source = new URI( "indy:" + key.getType().name() + ":" + key.getName() );

            return modelProcessor.readRelationships( pomView, source, config ).getAllRelationships();
        }
        catch ( final URISyntaxException e )
        {
            throw new IllegalStateException(
                    "Failed to construct URI for ArtifactStore: " + key + ". Reason: " + e.getMessage(), e );
        }
    }

    public void addLocations( final List<Location> locations, final StoreKey... extraLocations )
            throws IndyDataException
    {
        for ( StoreKey extra : extraLocations )
        {
            ArtifactStore store = getArtifactStore( extra );
            locations.add( LocationUtils.toLocation( store ) );
        }
    }

    @Measure
    public MavenPomView readPom( final String path, final ValidationRequest request, final StoreKey... extraLocations )
            throws IndyWorkflowException, GalleyMavenException, IndyDataException
    {
        ArtifactRef artifactRef = getArtifact( path );
        if ( artifactRef == null )
        {
            return null;
        }

        Transfer transfer = retrieve( request.getSourceRepository(), path );

        List<Location> locations = new ArrayList<>( extraLocations.length + 1 );
        locations.add( transfer.getLocation() );
        addLocations( locations, extraLocations );

        return pomReader.read( artifactRef.asProjectVersionRef(), transfer, locations, MavenPomView.ALL_PROFILES );
    }

    @Measure
    public MavenPomView readLocalPom( final String path, final ValidationRequest request )
            throws IndyWorkflowException, GalleyMavenException
    {
        ArtifactRef artifactRef = getArtifact( path );
        if ( artifactRef == null )
        {
            throw new IndyWorkflowException( "Invalid artifact path: %s. Could not parse ArtifactRef from path.",
                                             path );
        }

        Transfer transfer = retrieve( request.getSourceRepository(), path );

        return pomReader.readLocalPom( artifactRef.asProjectVersionRef(), transfer, MavenPomView.ALL_PROFILES );
    }

    public ArtifactRef getArtifact( final String path )
    {
        ArtifactPathInfo pathInfo = ArtifactPathInfo.parse( path );
        return pathInfo == null ? null : pathInfo.getArtifact();
    }

    public MavenMetadataView getMetadata( final ProjectRef ref, final List<? extends Location> locations )
            throws GalleyMavenException
    {
        return metadataReader.getMetadata( ref, locations );
    }

    public MavenMetadataView readMetadata( final ProjectRef ref, final List<Transfer> transfers )
            throws GalleyMavenException
    {
        return metadataReader.readMetadata( ref, transfers );
    }

    public MavenMetadataView getMetadata( final ProjectRef ref, final List<? extends Location> locations,
                                          final EventMetadata eventMetadata )
            throws GalleyMavenException
    {
        return metadataReader.getMetadata( ref, locations, eventMetadata );
    }

    public MavenMetadataView readMetadata( final ProjectRef ref, final List<Transfer> transfers,
                                           final EventMetadata eventMetadata )
            throws GalleyMavenException
    {
        return metadataReader.readMetadata( ref, transfers, eventMetadata );
    }

    @Measure
    public MavenPomView read( final ProjectVersionRef ref, final Transfer pom, final List<? extends Location> locations,
                              final String... activeProfileLocations )
            throws GalleyMavenException
    {
        return pomReader.read( ref, pom, locations, activeProfileLocations );
    }

    @Measure
    public MavenPomView read( final ProjectVersionRef ref, final List<? extends Location> locations,
                              final boolean cache, final EventMetadata eventMetadata, final String... activeProfileIds )
            throws GalleyMavenException
    {
        return pomReader.read( ref, locations, cache, eventMetadata, activeProfileIds );
    }

    @Measure
    public MavenPomView read( final ProjectVersionRef ref, final List<? extends Location> locations,
                              final boolean cache, final String... activeProfileIds )
            throws GalleyMavenException
    {
        return pomReader.read( ref, locations, cache, activeProfileIds );
    }

    @Measure
    public MavenPomView readLocalPom( final ProjectVersionRef ref, final Transfer transfer,
                                      final String... activeProfileIds )
            throws GalleyMavenException
    {
        return pomReader.readLocalPom( ref, transfer, activeProfileIds );
    }

    @Measure
    public MavenPomView read( final ProjectVersionRef ref, final List<? extends Location> locations,
                              final String... activeProfileIds )
            throws GalleyMavenException
    {
        return pomReader.read( ref, locations, activeProfileIds );
    }

    @Measure
    public MavenPomView readLocalPom( final ProjectVersionRef ref, final Transfer transfer, final boolean cache,
                                      final EventMetadata eventMetadata, final String... activeProfileIds )
            throws GalleyMavenException
    {
        return pomReader.readLocalPom( ref, transfer, cache, eventMetadata, activeProfileIds );
    }

    @Measure
    public MavenPomView readLocalPom( final ProjectVersionRef ref, final Transfer transfer,
                                      final EventMetadata eventMetadata, final String... activeProfileIds )
            throws GalleyMavenException
    {
        return pomReader.readLocalPom( ref, transfer, eventMetadata, activeProfileIds );
    }

    @Measure
    public MavenPomView read( final ProjectVersionRef ref, final List<? extends Location> locations,
                              final EventMetadata eventMetadata, final String... activeProfileIds )
            throws GalleyMavenException
    {
        return pomReader.read( ref, locations, eventMetadata, activeProfileIds );
    }

    @Measure
    public MavenPomView readLocalPom( final ProjectVersionRef ref, final Transfer transfer, final boolean cache,
                                      final String... activeProfileIds )
            throws GalleyMavenException
    {
        return pomReader.readLocalPom( ref, transfer, cache, activeProfileIds );
    }

    @Measure
    public MavenPomView read( final ProjectVersionRef ref, final Transfer pom, final List<? extends Location> locations,
                              final EventMetadata eventMetadata, final String... activeProfileLocations )
            throws GalleyMavenException
    {
        return pomReader.read( ref, pom, locations, eventMetadata, activeProfileLocations );
    }

    public Transfer getTransfer( final List<ArtifactStore> stores, final String path )
            throws IndyWorkflowException
    {
        return readOnlyWrapper( contentManager.getTransfer( stores, path, TransferOperation.DOWNLOAD ) );
    }

    public Transfer getTransfer( final StoreKey storeKey, final String path )
            throws IndyWorkflowException
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.info( "Retrieving transfer for: {} in {}", path, storeKey );
        Transfer result = readOnlyWrapper( contentManager.getTransfer( storeKey, path, TransferOperation.DOWNLOAD ) );
        logger.info( "Result: {}", result );
        return result;
    }

    public Transfer getTransfer( final ArtifactStore store, final String path )
            throws IndyWorkflowException
    {
        return readOnlyWrapper( contentManager.getTransfer( store, path, TransferOperation.DOWNLOAD ) );
    }

    public Transfer retrieve( final ArtifactStore store, final String path, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        return readOnlyWrapper( contentManager.retrieve( store, path, eventMetadata ) );
    }

    public Transfer retrieve( final ArtifactStore store, final String path )
            throws IndyWorkflowException
    {
        return readOnlyWrapper( contentManager.retrieve( store, path ) );
    }

    public List<Transfer> retrieveAll( final List<? extends ArtifactStore> stores, final String path,
                                       final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        return readOnlyWrappers( contentManager.retrieveAll( stores, path, eventMetadata ) );
    }

    public List<Transfer> retrieveAll( final List<? extends ArtifactStore> stores, final String path )
            throws IndyWorkflowException
    {
        return readOnlyWrappers( contentManager.retrieveAll( stores, path ) );
    }

    public Transfer retrieveFirst( final List<? extends ArtifactStore> stores, final String path,
                                   final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        return readOnlyWrapper( contentManager.retrieveFirst( stores, path, eventMetadata ) );
    }

    public Transfer retrieveFirst( final List<? extends ArtifactStore> stores, final String path )
            throws IndyWorkflowException
    {
        return readOnlyWrapper( contentManager.retrieveFirst( stores, path ) );
    }

    public boolean exists( final StoreKey storeKey, final String path )
            throws IndyWorkflowException, IndyDataException
    {
        ArtifactStore store = getArtifactStore( storeKey );
        if ( store == null )
        {
            throw new IndyDataException( "Artifact store with key " + storeKey + " was not found." );
        }
        return contentManager.exists( store, path );
    }

    public boolean exists( final ArtifactStore store, final String path )
            throws IndyWorkflowException
    {
        return contentManager.exists( store, path );
    }

    public List<StoreResource> list( final ArtifactStore store, final String path )
            throws IndyWorkflowException
    {
        return contentManager.list( store, path );
    }

    public List<StoreResource> list( final ArtifactStore store, final String path, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        return contentManager.list( store, path, eventMetadata );
    }

    public List<StoreResource> list( final List<? extends ArtifactStore> stores, final String path )
            throws IndyWorkflowException
    {
        return contentManager.list( stores, path );
    }

    public Map<ContentDigest, String> digest( final StoreKey key, final String path, String packageType )
            throws IndyWorkflowException
    {
        return contentDigester.digest( key, path, new EventMetadata( packageType ).set( FORCE_CHECKSUM, Boolean.TRUE ) )
                              .getDigests();
    }

    public HttpExchangeMetadata getHttpMetadata( final Transfer txfr )
            throws IndyWorkflowException
    {
        return contentManager.getHttpMetadata( txfr );
    }

    public HttpExchangeMetadata getHttpMetadata( final StoreKey storeKey, final String path )
            throws IndyWorkflowException
    {
        return contentManager.getHttpMetadata( storeKey, path );
    }

    public ArtifactStoreQuery<ArtifactStore> artifactStoreQuery()
            throws IndyDataException
    {
        return storeDataManager.query();
    }

    public ArtifactStore getArtifactStore( final StoreKey key )
            throws IndyDataException
    {
        return storeDataManager.getArtifactStore( key );
    }

    public Set<ArtifactStore> getAllArtifactStores()
            throws IndyDataException
    {
        return storeDataManager.getAllArtifactStores();
    }

    public Transfer getTransfer( final StoreResource resource )
    {
        return transferManager.getCacheReference( resource );
    }

    public <T> void paralleledEach( Collection<T> collection, Closure closure )
    {
        final Logger logger = LoggerFactory.getLogger( this.getClass() );
        logger.trace( "Exe parallel on collection {} with closure {}", collection, closure );
        runParallelAndWait( collection, closure, logger );
    }

    public <T> void paralleledEach( T[] array, Closure closure )
    {
        final Logger logger = LoggerFactory.getLogger( this.getClass() );
        logger.trace( "Exe parallel on array {} with closure {}", array, closure );
        runParallelAndWait( Arrays.asList( array ), closure, logger );
    }

    public <K, V> void paralleledEach( Map<K, V> map, Closure closure )
    {
        Set<Map.Entry<K, V>> entries = map.entrySet();
        final Logger logger = LoggerFactory.getLogger( this.getClass() );
        logger.trace( "Exe parallel on map {} with closure {}", entries, closure );
        runParallelAndWait( entries, closure, logger );
    }

    public <T> void paralleledInBatch( Collection<T> collection, Closure closure )
    {
        int batchSize = promoteConfig.getParalleledBatchSize();
        logger.trace( "Exe parallel on collection {} with closure {} in batch {}", collection, closure, batchSize );
        Collection<Collection<T>> batches = batch( collection, batchSize );
        runParallelInBatchAndWait( batches, closure, logger );
    }

    public <T> void paralleledInBatch( T[] array, Closure closure )
    {
        int batchSize = promoteConfig.getParalleledBatchSize();
        logger.trace( "Exe parallel on array {} with closure {} in batch {}", array, closure, batchSize );
        Collection<Collection<T>> batches = batch( Arrays.asList( array ), batchSize );
        runParallelInBatchAndWait( batches, closure, logger );
    }

    public <K, V> void paralleledInBatch( Map<K, V> map, Closure closure )
    {
        int batchSize = promoteConfig.getParalleledBatchSize();
        Set<Map.Entry<K, V>> entries = map.entrySet();
        logger.trace( "Exe parallel on map {} with closure {} in batch {}", entries, closure, batchSize );
        Collection<Collection<Map.Entry<K, V>>> batches = batch( entries, batchSize );
        runParallelInBatchAndWait( batches, closure, logger );
    }

    private <T> void runParallelInBatchAndWait( Collection<Collection<T>> batches, Closure closure, Logger logger )
    {
        final CountDownLatch latch = new CountDownLatch( batches.size() );
        batches.forEach( batch -> ruleParallelExecutor.execute( () -> {
            try
            {
                logger.trace( "The paralleled exe on batch {}", batch );
                batch.forEach( e -> {
                    String depthStr = MDC.get( ITERATION_DEPTH );
                    MDC.put( ITERATION_DEPTH, depthStr == null ? "0" : String.valueOf( Integer.parseInt( depthStr ) + 1 ) );
                    MDC.put( ITERATION_ITEM, String.valueOf( e ) );
                    try
                    {
                        closure.call( e );
                    }
                    finally
                    {
                        MDC.remove( ITERATION_ITEM );
                        MDC.remove( ITERATION_DEPTH );
                    }
                } );
            }
            finally
            {
                latch.countDown();
            }
        } ) );

        waitForCompletion( latch );
    }

    private <T> void runParallelAndWait( Collection<T> runCollection, Closure closure, Logger logger )
    {
        Set<T> todo = new HashSet<>( runCollection );
        final CountDownLatch latch = new CountDownLatch( todo.size() );
        todo.forEach( e -> ruleParallelExecutor.execute( () -> {
            String depthStr = MDC.get( ITERATION_DEPTH );
            MDC.put( ITERATION_DEPTH, depthStr == null ? "0" : String.valueOf( Integer.parseInt( depthStr ) + 1 ) );
            MDC.put( ITERATION_ITEM, String.valueOf( e ) );

            try
            {
                logger.trace( "The paralleled exe on element {}", e );
                closure.call( e );
            }
            finally
            {
                latch.countDown();
                MDC.remove( ITERATION_ITEM );
                MDC.remove( ITERATION_DEPTH );
            }
        } ) );

        waitForCompletion( latch );
    }

    private void waitForCompletion( CountDownLatch latch )
    {
        try
        {
            // true if the count reached zero and false if timeout
            boolean finished = latch.await( DEFAULT_RULE_PARALLEL_WAIT_TIME_MINS, TimeUnit.MINUTES );
            if ( !finished )
            {
                throw new RuntimeException( "Parallel execution timeout" );
            }
        }
        catch ( InterruptedException e )
        {
            logger.error( "Rule validation execution failed due to parallel running timeout for {} minutes",
                          DEFAULT_RULE_PARALLEL_WAIT_TIME_MINS );
        }
    }

    public <T> void forEach( Collection<T> collection, Closure closure )
    {
        logger.trace( "Exe on collection {} with closure {}", collection, closure );
        collection.forEach( closure::call );
    }

    public <T> void forEach( T[] array, Closure closure )
    {
        logger.trace( "Exe on array {} with closure {}", array, closure );
        Arrays.asList( array ).forEach( closure::call );
    }

    public <K, V> void forEach( Map<K, V> map, Closure closure )
    {
        Set<Map.Entry<K, V>> entries = map.entrySet();
        logger.trace( "Exe on map {} with closure {}", entries, closure );
        entries.forEach( closure::call );
    }
}

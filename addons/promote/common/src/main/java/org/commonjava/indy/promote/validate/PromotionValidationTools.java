/**
 * Copyright (C) 2011 Red Hat, Inc. (jdcasey@commonjava.org)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.indy.promote.validate;

import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.content.ContentDigester;
import org.commonjava.indy.content.ContentManager;
import org.commonjava.indy.content.StoreResource;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.promote.validate.model.ValidationRequest;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.maven.atlas.graph.rel.ProjectRelationship;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.atlas.ident.util.ArtifactPathInfo;
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

import javax.inject.Inject;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.commonjava.indy.model.core.StoreType.group;
import static org.commonjava.indy.model.core.StoreType.remote;
import static org.commonjava.indy.promote.validate.util.ReadOnlyTransfer.readOnlyWrapper;
import static org.commonjava.indy.promote.validate.util.ReadOnlyTransfer.readOnlyWrappers;
import static org.commonjava.maven.galley.io.ChecksummingTransferDecorator.FORCE_CHECKSUM;

/**
 * Created by jdcasey on 9/11/15.
 */
public class PromotionValidationTools
{
    public static final String AVAILABLE_IN_STORES = "availableInStores";

    @Deprecated
    public static final String AVAILABLE_IN_STORE_KEY = "availableInStoreKey";

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

    protected PromotionValidationTools()
    {
    }

    public PromotionValidationTools( final ContentManager manager, final StoreDataManager storeDataManager,
                                     final MavenPomReader pomReader, final MavenMetadataReader metadataReader,
                                     final MavenModelProcessor modelProcessor, final TypeMapper typeMapper,
                                     final TransferManager transferManager, final ContentDigester contentDigester )
    {
        contentManager = manager;
        this.storeDataManager = storeDataManager;
        this.pomReader = pomReader;
        this.metadataReader = metadataReader;
        this.modelProcessor = modelProcessor;
        this.typeMapper = typeMapper;
        this.transferManager = transferManager;
        this.contentDigester = contentDigester;
    }

    public StoreKey[] getValidationStoreKeys( final ValidationRequest request, final boolean includeSource )
            throws PromotionValidationException
    {
        return getValidationStoreKeys( request, includeSource, true );
    }

    public StoreKey[] getValidationStoreKeys( final ValidationRequest request, final boolean includeSource,
                                              final boolean includeTarget )
            throws PromotionValidationException
    {
        String verifyStores = request.getValidationParameter( PromotionValidationTools.AVAILABLE_IN_STORES );
        if ( verifyStores == null )
        {
            verifyStores = request.getValidationParameter( PromotionValidationTools.AVAILABLE_IN_STORE_KEY );
        }

        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.debug( "Got extra validation keys string: '{}'", verifyStores );

        List<StoreKey> verifyStoreKeys = new ArrayList<>();
        if ( includeSource )
        {
            verifyStoreKeys.add( request.getSourceRepository().getKey() );
        }

        if ( includeTarget )
        {
            verifyStoreKeys.add( request.getTarget() );
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

        logger.debug( "Using validation StoreKeys: {}", verifyStoreKeys );

        return verifyStoreKeys.toArray( new StoreKey[verifyStoreKeys.size()] );
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

    public MavenPomView read( final ProjectVersionRef ref, final Transfer pom, final List<? extends Location> locations,
                              final String... activeProfileLocations )
            throws GalleyMavenException
    {
        return pomReader.read( ref, pom, locations, activeProfileLocations );
    }

    public MavenPomView read( final ProjectVersionRef ref, final List<? extends Location> locations,
                              final boolean cache, final EventMetadata eventMetadata, final String... activeProfileIds )
            throws GalleyMavenException
    {
        return pomReader.read( ref, locations, cache, eventMetadata, activeProfileIds );
    }

    public MavenPomView read( final ProjectVersionRef ref, final List<? extends Location> locations,
                              final boolean cache, final String... activeProfileIds )
            throws GalleyMavenException
    {
        return pomReader.read( ref, locations, cache, activeProfileIds );
    }

    public MavenPomView readLocalPom( final ProjectVersionRef ref, final Transfer transfer,
                                      final String... activeProfileIds )
            throws GalleyMavenException
    {
        return pomReader.readLocalPom( ref, transfer, activeProfileIds );
    }

    public MavenPomView read( final ProjectVersionRef ref, final List<? extends Location> locations,
                              final String... activeProfileIds )
            throws GalleyMavenException
    {
        return pomReader.read( ref, locations, activeProfileIds );
    }

    public MavenPomView readLocalPom( final ProjectVersionRef ref, final Transfer transfer, final boolean cache,
                                      final EventMetadata eventMetadata, final String... activeProfileIds )
            throws GalleyMavenException
    {
        return pomReader.readLocalPom( ref, transfer, cache, eventMetadata, activeProfileIds );
    }

    public MavenPomView readLocalPom( final ProjectVersionRef ref, final Transfer transfer,
                                      final EventMetadata eventMetadata, final String... activeProfileIds )
            throws GalleyMavenException
    {
        return pomReader.readLocalPom( ref, transfer, eventMetadata, activeProfileIds );
    }

    public MavenPomView read( final ProjectVersionRef ref, final List<? extends Location> locations,
                              final EventMetadata eventMetadata, final String... activeProfileIds )
            throws GalleyMavenException
    {
        return pomReader.read( ref, locations, eventMetadata, activeProfileIds );
    }

    public MavenPomView readLocalPom( final ProjectVersionRef ref, final Transfer transfer, final boolean cache,
                                      final String... activeProfileIds )
            throws GalleyMavenException
    {
        return pomReader.readLocalPom( ref, transfer, cache, activeProfileIds );
    }

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

    public HostedRepository getHostedRepository( final String name )
            throws IndyDataException
    {
        return storeDataManager.getHostedRepository( name );
    }

    public RemoteRepository getRemoteRepository( final String name )
            throws IndyDataException
    {
        return storeDataManager.getRemoteRepository( name );
    }

    public Group getGroup( final String name )
            throws IndyDataException
    {
        return storeDataManager.getGroup( name );
    }

    public ArtifactStore getArtifactStore( final StoreKey key )
            throws IndyDataException
    {
        return storeDataManager.getArtifactStore( key );
    }

    public List<ArtifactStore> getAllArtifactStores()
            throws IndyDataException
    {
        return storeDataManager.getAllArtifactStores();
    }

    public List<? extends ArtifactStore> getAllArtifactStores( final StoreType type )
            throws IndyDataException
    {
        return storeDataManager.getAllArtifactStores( type );
    }

    public List<Group> getAllGroups()
            throws IndyDataException
    {
        return storeDataManager.getAllGroups();
    }

    public List<RemoteRepository> getAllRemoteRepositories()
            throws IndyDataException
    {
        return storeDataManager.getAllRemoteRepositories();
    }

    public List<HostedRepository> getAllHostedRepositories()
            throws IndyDataException
    {
        return storeDataManager.getAllHostedRepositories();
    }

    public List<ArtifactStore> getAllConcreteArtifactStores()
            throws IndyDataException
    {
        return storeDataManager.getAllConcreteArtifactStores();
    }

    public List<ArtifactStore> getOrderedConcreteStoresInGroup( final String groupName )
            throws IndyDataException
    {
        return storeDataManager.getOrderedConcreteStoresInGroup( groupName, false );
    }

    public List<ArtifactStore> getOrderedStoresInGroup( final String groupName )
            throws IndyDataException
    {
        return storeDataManager.getOrderedStoresInGroup( groupName, false );
    }

    public Set<Group> getGroupsContaining( final StoreKey repo )
            throws IndyDataException
    {
        return storeDataManager.getGroupsContaining( repo );
    }

    @Deprecated
    public boolean hasRemoteRepository( final String name )
    {
        return storeDataManager.hasRemoteRepository( name );
    }

    public boolean hasRemoteRepository( final String packageType, final String name )
    {
        return storeDataManager.hasArtifactStore( new StoreKey( packageType, remote, name ) );
    }

    @Deprecated
    public boolean hasGroup( final String name )
    {
        return storeDataManager.hasArtifactStore( new StoreKey( group, name ) );
    }

    public boolean hasGroup( final String packageType, final String name )
    {
        return storeDataManager.hasArtifactStore( new StoreKey( packageType, group, name ) );
    }

    public boolean hasHostedRepository( final String name )
    {
        return storeDataManager.hasHostedRepository( name );
    }

    public boolean hasArtifactStore( final StoreKey key )
    {
        return storeDataManager.hasArtifactStore( key );
    }

    public RemoteRepository findRemoteRepository( final String url )
    {
        return storeDataManager.findRemoteRepository( url );
    }

    public Transfer getTransfer( final StoreResource resource )
    {
        return transferManager.getCacheReference( resource );
    }
}

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

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.repository.metadata.Metadata;
import org.apache.maven.artifact.repository.metadata.Plugin;
import org.apache.maven.artifact.repository.metadata.SnapshotVersion;
import org.apache.maven.artifact.repository.metadata.Versioning;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Reader;
import org.apache.maven.artifact.repository.metadata.io.xpp3.MetadataXpp3Writer;
import org.commonjava.atlas.maven.ident.ref.SimpleTypeAndClassifier;
import org.commonjava.atlas.maven.ident.ref.TypeAndClassifier;
import org.commonjava.atlas.maven.ident.util.ArtifactPathInfo;
import org.commonjava.atlas.maven.ident.util.SnapshotUtils;
import org.commonjava.atlas.maven.ident.util.VersionUtils;
import org.commonjava.atlas.maven.ident.version.SingleVersion;
import org.commonjava.atlas.maven.ident.version.part.SnapshotPart;
import org.commonjava.cdi.util.weft.DrainingExecutorCompletionService;
import org.commonjava.cdi.util.weft.ExecutorConfig;
import org.commonjava.cdi.util.weft.Locker;
import org.commonjava.cdi.util.weft.WeftExecutorService;
import org.commonjava.cdi.util.weft.WeftManaged;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.content.DirectContentAccess;
import org.commonjava.indy.content.MergedContentAction;
import org.commonjava.indy.content.StoreResource;
import org.commonjava.indy.core.content.AbstractMergedContentGenerator;
import org.commonjava.indy.core.content.group.GroupMergeHelper;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.propulsor.metrics.annotation.Measure;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.pkg.maven.content.group.MavenMetadataMerger;
import org.commonjava.indy.pkg.maven.content.group.MavenMetadataProvider;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.maven.parse.GalleyMavenXMLException;
import org.commonjava.maven.galley.maven.parse.XMLInfrastructure;
import org.commonjava.maven.galley.maven.spi.type.TypeMapper;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.model.TypeMapping;
import org.commonjava.maven.galley.spi.nfc.NotFoundCache;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.annotation.PostConstruct;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.commonjava.atlas.maven.ident.util.SnapshotUtils.LOCAL_SNAPSHOT_VERSION_PART;
import static org.commonjava.indy.core.content.group.GroupMergeHelper.GROUP_METADATA_EXISTS;
import static org.commonjava.indy.core.content.group.GroupMergeHelper.GROUP_METADATA_GENERATED;
import static org.commonjava.indy.core.ctl.PoolUtils.detectOverloadVoid;
import static org.commonjava.maven.galley.io.SpecialPathConstants.HTTP_METADATA_EXT;
import static org.commonjava.maven.galley.util.PathUtils.normalize;
import static org.commonjava.maven.galley.util.PathUtils.parentPath;

public class MavenMetadataGenerator
    extends AbstractMergedContentGenerator
{

    private static final String ARTIFACT_ID = "artifactId";

    private static final String GROUP_ID = "groupId";

    private static final String VERSION = "version";

    private static final String LAST_UPDATED = "lastUpdated";

    private static final String TIMESTAMP = "timestamp";

    private static final String BUILD_NUMBER = "buildNumber";

    private static final String EXTENSION = "extension";

    private static final String VALUE = "value";

    private static final String UPDATED = "updated";

    private static final String LOCAL_COPY = "localCopy";

    private static final String LATEST = "latest";

    private static final String RELEASE = "release";

    private static final String CLASSIFIER = "classifier";

    @Inject
    private MetadataCacheManager cacheManager;

    private static final Set<String> HANDLED_FILENAMES = Collections.unmodifiableSet( new HashSet<String>()
    {

        {
            add( MavenMetadataMerger.METADATA_NAME );
            add( MavenMetadataMerger.METADATA_MD5_NAME );
            add( MavenMetadataMerger.METADATA_SHA_NAME );
            add( MavenMetadataMerger.METADATA_SHA256_NAME );
        }

        private static final long serialVersionUID = 1L;

    } );

    @Inject
    private XMLInfrastructure xml;

    @Inject
    private TypeMapper typeMapper;

    @Inject
    private MavenMetadataMerger merger;

    @Inject
    private Instance<MavenMetadataProvider> metadataProviderInstances;

    private List<MavenMetadataProvider> metadataProviders;

    @Inject
    @WeftManaged
    @ExecutorConfig( named="maven-metadata-generator", threads=50, loadSensitive = ExecutorConfig.BooleanLiteral.TRUE, maxLoadFactor = 10000 )
    private WeftExecutorService mavenMDGeneratorService;

    // don't need to inject since it's only used internally
    private final Locker<String> mergerLocks = new Locker<>();

    private static final int THREAD_WAITING_TIME_SECONDS = 300;

    protected MavenMetadataGenerator()
    {
    }

    public MavenMetadataGenerator( final DirectContentAccess fileManager, final StoreDataManager storeManager,
                                   final XMLInfrastructure xml, final TypeMapper typeMapper,
                                   final MavenMetadataMerger merger, final GroupMergeHelper mergeHelper,
                                   final NotFoundCache nfc, WeftExecutorService mavenMDGeneratorService,
                                   final MergedContentAction... mergedContentActions )
    {
        super( fileManager, storeManager, mergeHelper, nfc, mergedContentActions );
        this.xml = xml;
        this.typeMapper = typeMapper;
        this.merger = merger;
        this.mavenMDGeneratorService = mavenMDGeneratorService;
        start();
    }

    @PostConstruct
    public void start()
    {
        metadataProviders = new ArrayList<>();
        if ( metadataProviderInstances != null )
        {
            metadataProviderInstances.forEach( provider -> metadataProviders.add( provider ) );
        }
    }

    public void clearAllMerged( ArtifactStore store, String...paths )
    {
        super.clearAllMerged( store, paths );
    }

    @Override
    @Measure
    public Transfer generateFileContent( final ArtifactStore store, final String path, final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        // metadata merging is something else...don't handle it here.
        if ( StoreType.group == store.getKey().getType() )
        {
            return null;
        }

        if ( !canProcess( path ) )
        {
            return null;
        }

        boolean generated;

        // TODO: Generation of plugin metadata files (groupId-level) is harder, and requires cracking open the jar file
        // This is because that's the only place the plugin prefix can be reliably retrieved from.

        // regardless, we will need this first level of listings. What we do with it will depend on the logic below...
        final String parentPath = Paths.get( path )
                                       .getParent()
                                       .toString();

        List<StoreResource> firstLevel;
        try
        {
            firstLevel = fileManager.listRaw( store, parentPath );
        }
        catch ( final IndyWorkflowException e )
        {
            logger.error( String.format( "SKIP: Failed to generate maven-metadata.xml from listing of directory contents for: %s under path: %s",
                                         store, parentPath ), e );
            return null;
        }

        String toGenPath = path;
        if ( !path.endsWith( MavenMetadataMerger.METADATA_NAME ) )
        {
            toGenPath = normalize( normalize( parentPath( toGenPath ) ), MavenMetadataMerger.METADATA_NAME );
        }

        ArtifactPathInfo snapshotPomInfo = null;

        if ( parentPath.endsWith( LOCAL_SNAPSHOT_VERSION_PART ) )
        {
            // If we're in a version directory, first-level listing should include a .pom file
            for ( final StoreResource resource : firstLevel )
            {
                if ( resource.getPath().endsWith( ".pom" ) )
                {
                    snapshotPomInfo = ArtifactPathInfo.parse( resource.getPath() );
                    break;
                }
            }
        }

        if ( snapshotPomInfo != null )
        {
            logger.debug( "Generating maven-metadata.xml for snapshots, store: {}", store.getKey() );
            generated = writeSnapshotMetadata( snapshotPomInfo, firstLevel, store, toGenPath, eventMetadata );
        }
        else
        {
            logger.debug( "Generating maven-metadata.xml for releases, store: {}", store.getKey() );
            generated = writeVersionMetadata( firstLevel, store, toGenPath, eventMetadata );
        }

        logger.debug( "[Result] Generating maven-metadata.xml for store: {}, result: {}", store.getKey(), generated );
        return generated ? fileManager.getTransfer( store, path ) : null;
    }

    @Override
    public List<StoreResource> generateDirectoryContent( final ArtifactStore store, final String path,
                                                         final List<StoreResource> existing,
                                                         final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        final StoreResource mdResource =
            new StoreResource( LocationUtils.toLocation( store ), Paths.get( path, MavenMetadataMerger.METADATA_NAME )
                                                                       .toString() );

        if ( existing.contains( mdResource ) )
        {
            return null;
        }

        int pathElementsCount = StringUtils.strip( path, "/" ).split( "/" ).length;
        // if there is a possibility we are listing an artifactId
        if ( pathElementsCount >= 2 )
        {
            // regardless, we will need this first level of listings. What we do with it will depend on the logic below...
            final List<StoreResource> firstLevelFiles = fileManager.listRaw( store, path, eventMetadata );

            ArtifactPathInfo samplePomInfo = null;
            for ( final StoreResource topResource : firstLevelFiles )
            {
                final String topPath = topResource.getPath();
                if ( topPath.endsWith( ".pom" ) )
                {
                    samplePomInfo = ArtifactPathInfo.parse( topPath );
                    break;
                }
            }

            // if this dir does not contain a pom check if a subdir contain a pom
            if ( samplePomInfo == null )
            {
                List<String> firstLevelDirs = firstLevelFiles.stream()
                                                             .map( ConcreteResource::getPath )
                                                             .filter( (subpath) -> subpath.endsWith( "/" ) )
                                                             .collect( Collectors.toList() );
                final Map<String, List<StoreResource>> secondLevelMap = fileManager.listRaw( store, firstLevelDirs, eventMetadata );
                nextTopResource: for ( final String topPath : firstLevelDirs )
                {
                    final List<StoreResource> secondLevelListing = secondLevelMap.get( topPath );
                    for ( final StoreResource fileResource : secondLevelListing )
                    {
                        if ( fileResource.getPath()
                                         .endsWith( ".pom" ) )
                        {
                            samplePomInfo = ArtifactPathInfo.parse( fileResource.getPath() );
                            break nextTopResource;
                        }
                    }
                }
            }

            // TODO: Generation of plugin metadata files (groupId-level) is harder, and requires cracking open the jar file
            // This is because that's the only place the plugin prefix can be reliably retrieved from.
            // We won't worry about this for now.
            if ( samplePomInfo != null )
            {
                final List<StoreResource> result = new ArrayList<>();
                result.add( mdResource );
                result.add( new StoreResource( LocationUtils.toLocation( store ),
                                               Paths.get( path, MavenMetadataMerger.METADATA_MD5_NAME )
                                               .toString() ) );
                result.add( new StoreResource( LocationUtils.toLocation( store ),
                                               Paths.get( path, MavenMetadataMerger.METADATA_SHA_NAME )
                                               .toString() ) );
                return result;
            }
        }

        return null;
    }

    /**
     *
     * @param group
     * @param members Concrete stores in group
     * @param path
     * @param eventMetadata
     * @return
     * @throws IndyWorkflowException
     */
    @Override
    @Measure
    public Transfer generateGroupFileContent( final Group group, final List<ArtifactStore> members, final String path,
                                              final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        String toMergePath = path;
        if ( !path.endsWith( MavenMetadataMerger.METADATA_NAME ) )
        {
            toMergePath = normalize( normalize( parentPath( toMergePath ) ), MavenMetadataMerger.METADATA_NAME );
        }

        Transfer target = fileManager.getTransfer( group, toMergePath );
        if ( exists( target ) )
        {
            // Means there is no metadata change if this transfer exists, so directly return it.
            logger.trace( "Metadata file exists for group {} of path {}, no need to regenerate.", group.getKey(), path );
            eventMetadata.set( GROUP_METADATA_EXISTS, true );
            return target;
        }

        AtomicReference<IndyWorkflowException> wfEx = new AtomicReference<>();
        String mergePath = toMergePath;
        boolean mergingDone = mergerLocks.ifUnlocked( computeKey(group, toMergePath), p->{
            try
            {
                logger.debug( "Start metadata generation for the metadata file for this path {} in group {}", path,
                              group );
                List<StoreKey> contributing = new ArrayList<>();
                final Metadata md = generateGroupMetadata( group, members, contributing, path );
                if ( md != null )
                {
                    final Versioning versioning = md.getVersioning();
                    logger.trace(
                            "Regenerated Metadata for group {} of path {}: latest version: {}, versioning versions:{}",
                            group.getKey(), mergePath, versioning != null ? versioning.getLatest() : null,
                            versioning != null ? versioning.getVersions() : null );
                    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    try
                    {
                        logger.trace( "Metadata file lost for group {} of path {}, will regenerate.", group.getKey(),
                                      path );
                        new MetadataXpp3Writer().write( baos, md );

                        final byte[] merged = baos.toByteArray();
                        try (final OutputStream fos = target.openOutputStream( TransferOperation.GENERATE, true,
                                                                               eventMetadata ))
                        {
                            fos.write( merged );
                        }
                        catch ( final IOException e )
                        {
                            throw new IndyWorkflowException( "Failed to write merged metadata to: {}.\nError: {}", e,
                                                             target, e.getMessage() );
                        }

                        String mergeInfo = writeGroupMergeInfo( md, group, contributing, mergePath );
                        eventMetadata.set( GROUP_METADATA_GENERATED, true );
                        MetadataInfo info = new MetadataInfo( md );
                        info.setMetadataMergeInfo( mergeInfo );

                        putToMetadataCache( group.getKey(), mergePath, info );
                    }
                    catch ( final IOException e )
                    {
                        logger.error( String.format( "Cannot write consolidated metadata: %s to: %s. Reason: %s", path,
                                                     group.getKey(), e.getMessage() ), e );
                    }
                }
            }
            catch ( IndyWorkflowException e )
            {
                wfEx.set( e );
                return false;
            }

            return true;
        }, (p,mergerLock)->{
            logger.info(
                    "The metadata generation is still in process by another thread for the metadata file for this path {} in group {}, so block current thread to wait for result",
                    path, group );

            return mergerLocks.waitForLock( THREAD_WAITING_TIME_SECONDS, mergerLock );
        } );

        IndyWorkflowException ex = wfEx.get();
        if ( ex != null )
        {
            throw ex;
        }

        if ( exists( target ) )
        {
            // if this is a checksum file, we need to return the original path.
            Transfer original = fileManager.getTransfer( group, path );
            if ( exists( original ) )
            {
                logger.debug( "This is a checksum file, return the original path {}", path );
                return original;
            }
        }

        if ( mergingDone )
        {
            logger.info(
                    "Merging finished but the merging file not created correctly. See merging related log for details. Merging group: {}, path: {}",
                    group, path );
        }
        else
        {
            logger.error(
                    "Merging not finished but thread waiting timeout, caused current thread will get a null merging result. Try to enlarge the waiting timeout. Merging group: {}, path: {}",
                    group, path );
        }

        return null;
    }

    private String computeKey( final Group group, final String path )
    {
        return group.getKey().toString() + "-" + path;
    }

    private String writeGroupMergeInfo( final Metadata md, final Group group, final List<StoreKey> contributingMembers, final String path )
            throws IndyWorkflowException
    {
        logger.trace( "Start write .info file based on if the cache exists for group {} of members {} in path {}. ",
                      group.getKey(), contributingMembers, path );
        final Transfer mergeInfoTarget = fileManager.getTransfer( group, path + GroupMergeHelper.MERGEINFO_SUFFIX );
        logger.trace( ".info file not found for {} of members {} in path {}", group.getKey(), contributingMembers, path );

        logger.trace(
                "metadata merge info not cached for group {} of members {} in path {}, will regenerate.",
                group.getKey(), contributingMembers, path );
        String metaMergeInfo = helper.generateMergeInfoFromKeys( contributingMembers );

        logger.trace( "Metadata merge info for {} of members {} in path {} is {}", group.getKey(), contributingMembers,
                      path, metaMergeInfo );
        helper.writeMergeInfo( metaMergeInfo, group, path );
        logger.trace( ".info file regenerated for group {} of members {} in path. Full path: {}", group.getKey(), contributingMembers,
                      path );

        return metaMergeInfo;
    }

    /**
     * Will generate group related files(e.g maven-metadata.xml) from cache level, which means all the generation of the
     * files will be cached. In terms of cache clearing, see #{@link MetadataMergeListener}
     *
     * @param group
     * @param members concrete store in group
     * @param path
     *
     * @return
     *
     * @throws IndyWorkflowException
     */
    private Metadata generateGroupMetadata( final Group group, final List<ArtifactStore> members,
                                            final List<StoreKey> contributingMembers, final String path )
            throws IndyWorkflowException
    {

        if ( !canProcess( path ) )
        {
            logger.error( "The path is not a metadata file: {} ", path );
            return null;
        }

        String toMergePath = path;
        if ( !path.endsWith( MavenMetadataMerger.METADATA_NAME ) )
        {
            toMergePath = normalize( normalize( parentPath( toMergePath ) ), MavenMetadataMerger.METADATA_NAME );
        }

        Metadata meta = getMetaFromCache( group.getKey(), toMergePath );

        if ( meta != null )
        {
            return meta;
        }

        Metadata master = new Metadata();
        master.setVersioning( new Versioning() );

        MetadataIncrementalResult incrementalResult =
                new MetadataIncrementalResult( new HashSet<>( members ), Collections.emptySet(), master );

        incrementalResult = mergeMissing( group, incrementalResult, toMergePath, "cached", this::retrieveCached );

        contributingMembers.addAll( incrementalResult.merged );

        incrementalResult = mergeMissing( group, incrementalResult, toMergePath, "downloaded", this::downloadMissing );

        contributingMembers.addAll( incrementalResult.merged );

        incrementalResult = mergeMissing( group, incrementalResult, toMergePath, "generated", this::generateMissing );

        contributingMembers.addAll( incrementalResult.merged );

        if ( metadataProviders != null )
        {
            master = mergeProviderMetadata( group, incrementalResult.result, toMergePath );
        }
        else
        {
            master = incrementalResult.result;
        }

        if ( !incrementalResult.missing.isEmpty() )
        {
            logger.warn(
                    "After download and generation attempts, metadata is still missing from the following stores: {}, size: {}",
                    incrementalResult.missing, incrementalResult.missing.size() );
        }

        Versioning versioning = master.getVersioning();
        List<String> versions = versioning.getVersions();
        logger.debug( "Get versioning, versions: {}, release: {}, latest: {}", versions, versioning.getRelease(), versioning.getLatest() );
        if ( versions != null && !versions.isEmpty() )
        {
            merger.sortVersions( master );
            return master;
        }

        List<SnapshotVersion> snapshotVersions = versioning.getSnapshotVersions();
        if ( snapshotVersions != null && !snapshotVersions.isEmpty() )
        {
            if ( logger.isTraceEnabled() )
            {
                snapshotVersions.forEach(
                        snapshotVersion -> logger.trace( "snapshotVersion: {}", snapshotVersion.getVersion() ) );
            }
            return master;
        }

        List<Plugin> plugins = master.getPlugins();
        if ( plugins != null && !plugins.isEmpty() )
        {
            return master;
        }

        logger.info( "The group metadata generation is not successful for path: {} in group: {}", path, group );
        return null;
    }

    private void putToMetadataCache( StoreKey key, String toMergePath, MetadataInfo meta )
    {
        cacheManager.put( new MetadataKey( key, toMergePath ), meta );
    }

    private Callable<MetadataResult> generateMissing( ArtifactStore store, String toMergePath )
    {
        return ()->{
            try
            {
                logger.trace( "Starting metadata generation: {}:{}", store.getKey(), toMergePath );
                Transfer memberMetaTxfr = generateFileContent( store, toMergePath, new EventMetadata() );

                if ( exists( memberMetaTxfr ) )
                {
                    final MetadataXpp3Reader reader = new MetadataXpp3Reader();

                    try (InputStream in = memberMetaTxfr.openInputStream())
                    {
                        String content = IOUtils.toString( in );
                        Metadata memberMeta = reader.read( new StringReader( content ), false );

                        clearObsoleteFiles( memberMetaTxfr );

                        return new MetadataResult( store, memberMeta );
                    }
                }
            }
            catch ( final Exception e )
            {
                String msg = String.format( "EXCLUDING Failed generated metadata: %s:%s. Reason: %s", store.getKey(),
                                            toMergePath, e.getMessage() );
                logger.error( msg, e );
            }
            finally
            {
                logger.trace( "Ending metadata generation: {}:{}", store.getKey(), toMergePath );
            }

            return new MetadataResult( store, null );
        };
    }

    /**
     * Clear obsolete files after a meta is generated. This may be http download metadata, etc.
     * @param item
     */
    private void clearObsoleteFiles( Transfer item )
    {
        Transfer httpMeta = item.getSiblingMeta( HTTP_METADATA_EXT );
        try
        {
            httpMeta.delete();
        }
        catch ( IOException e )
        {
            logger.warn( "Failed to delete {}", httpMeta.getResource() );
        }

    }

    private Callable<MetadataResult> retrieveCached( final ArtifactStore store, final String toMergePath )
    {
        return ()->{
            Metadata memberMeta;
            memberMeta = getMetaFromCache( store.getKey(), toMergePath );

            if ( memberMeta != null )
            {
                return new MetadataResult( store, memberMeta );
            }

            return new MetadataResult( store, null );
        };
    }

    private static final class MetadataResult
    {
        private final ArtifactStore store;
        private final Metadata metadata;
        private final boolean missing;

        public MetadataResult( final ArtifactStore store, final Metadata metadata )
        {
            this.store = store;
            this.metadata = metadata;
            this.missing = metadata == null;
        }
    }

    private static final class MetadataIncrementalResult
    {
        private final Set<ArtifactStore> missing;
        private final Set<StoreKey> merged;
        private final Metadata result;

        public MetadataIncrementalResult( final Set<ArtifactStore> missing, final Set<StoreKey> merged,
                                          final Metadata result )
        {
            this.missing = missing;
            this.merged = merged;
            this.result = result;
        }
    }

    private MetadataIncrementalResult mergeMissing( final Group group,
                                                    final MetadataIncrementalResult incrementalResult,
                                                    final String toMergePath, String description,
                                                    BiFunction<ArtifactStore, String, Callable<MetadataResult>> func )
            throws IndyWorkflowException
    {
        Set<ArtifactStore> missing = incrementalResult.missing;
        Metadata master = incrementalResult.result;

        logger.debug( "Merge member metadata for {}, {}, missing: {}, size: {}", group.getKey(), description,
                      missing, missing.size() );

        DrainingExecutorCompletionService<MetadataResult> svc =
                new DrainingExecutorCompletionService<>( mavenMDGeneratorService );

        detectOverloadVoid( () -> missing.forEach( store -> svc.submit( func.apply( store, toMergePath ) ) ) );

        Set<ArtifactStore> resultingMissing = new HashSet<>(); // return stores failed download
        Set<StoreKey> included = new HashSet<>();
        try
        {
            svc.drain( mr -> {
                if ( mr != null )
                {
                    if ( mr.missing )
                    {
                        resultingMissing.add( mr.store );
                    }
                    else
                    {
                        included.add( mr.store.getKey() );
                        merger.merge( master, mr.metadata, group, toMergePath );
                        putToMetadataCache( mr.store.getKey(), toMergePath, new MetadataInfo( mr.metadata ) );
                    }
                }
            } );
        }
        catch ( InterruptedException e )
        {
            logger.debug( "Interrupted while merging " + description + " member metadata." );
        }
        catch ( ExecutionException e )
        {
            throw new IndyWorkflowException( "Failed to merge downloaded " + description + " member metadata.", e );
        }

        return new MetadataIncrementalResult( resultingMissing, included, master );
    }

    private Metadata mergeProviderMetadata( final Group group, final Metadata master,
                                                             final String toMergePath )
            throws IndyWorkflowException
    {

        logger.debug( "Merge metadata for non-store providers in: {} on path: {}", group.getKey(), toMergePath );

        DrainingExecutorCompletionService<Metadata> svc =
                new DrainingExecutorCompletionService<>( mavenMDGeneratorService );

        detectOverloadVoid( () -> metadataProviders.forEach( provider -> svc.submit( ()->{
            try
            {
                return provider.getMetadata( group.getKey(), toMergePath );
            }
            catch ( IndyWorkflowException e )
            {
                logger.error( String.format(
                        "Cannot read metadata: %s from metadata provider: %s. Reason: %s",
                        toMergePath, provider.getClass().getSimpleName(),
                        e.getMessage() ), e );
            }

            return null;
        }) ) );

        try
        {
            svc.drain( metadata -> {
                if ( metadata != null )
                {
                    merger.merge( master, metadata, group, toMergePath );
                }
            } );
        }
        catch ( InterruptedException e )
        {
            logger.debug( "Interrupted while merging provider member metadata." );
        }
        catch ( ExecutionException e )
        {
            throw new IndyWorkflowException( "Failed to merge provider member metadata.", e );
        }

        return master;
    }

    private Callable<MetadataResult> downloadMissing( ArtifactStore store, String toMergePath )
    {
        return () -> {
            try
            {
                logger.trace( "Starting metadata download: {}:{}", store.getKey(), toMergePath );
                Transfer memberMetaTxfr = fileManager.retrieveRaw( store, toMergePath, new EventMetadata() );

                if ( exists( memberMetaTxfr ) )
                {
                    final MetadataXpp3Reader reader = new MetadataXpp3Reader();

                    try (InputStream in = memberMetaTxfr.openInputStream())
                    {
                        String content = IOUtils.toString( in );
                        Metadata memberMeta = reader.read( new StringReader( content ), false );
                        return new MetadataResult( store, memberMeta );
                    }
                }
                else
                {
                    return new MetadataResult( store, null );
                }
            }
            catch ( final Exception e )
            {
                String msg = String.format( "EXCLUDING Failed metadata download: %s:%s. Reason: %s", store.getKey(),
                                            toMergePath, e.getMessage() );
                logger.error( msg, e );
                //                    errors.add( msg );
            }
            finally
            {
                logger.trace( "Ending metadata download: {}:{}", store.getKey(), toMergePath );
            }

            return null;
        };
    }

    private boolean exists( final Transfer target )
    {
        return target != null && target.exists();
    }

    private Metadata getMetaFromCache( final StoreKey key, final String path )
    {
        final MetadataInfo metaMergeInfo = getMetaInfoFromCache( key, path );
        if ( metaMergeInfo != null )
        {
            logger.trace( "FOUND metadata: {} in group: {} with merge info:\n\n{}\n\n", path, key, metaMergeInfo.getMetadataMergeInfo() );
            return metaMergeInfo.getMetadata();
        }
        return null;
    }

    private MetadataInfo getMetaInfoFromCache( final StoreKey key, final String path )
    {
        return cacheManager.get( new MetadataKey( key, path ) );
    }

    @Override
    public boolean canProcess( final String path )
    {
        for ( final String filename : HANDLED_FILENAMES )
        {
            if ( path.endsWith( filename ) )
            {
                return true;
            }
        }

        return false;
    }

    @Override
    public List<StoreResource> generateGroupDirectoryContent( final Group group, final List<ArtifactStore> members,
                                                              final String path, final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        return generateDirectoryContent( group, path, Collections.emptyList(), eventMetadata );
    }

    @Override
    protected String getMergedMetadataName()
    {
        return MavenMetadataMerger.METADATA_NAME;
    }

    private boolean writeVersionMetadata( final List<StoreResource> firstLevelFiles, final ArtifactStore store,
                                          final String path, final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        ArtifactPathInfo samplePomInfo = null;

        logger.debug( "writeVersionMetadata, firstLevelFiles:{}, store:{}", firstLevelFiles, store.getKey() );

        // first level will contain version directories...for each directory, we need to verify the presence of a .pom file before including
        // as a valid version
        final List<SingleVersion> versions = new ArrayList<>();
        nextTopResource: for ( final StoreResource topResource : firstLevelFiles )
        {
            final String topPath = topResource.getPath();
            if ( topPath.endsWith( "/" ) )
            {
                final List<StoreResource> secondLevelListing = fileManager.listRaw( store, topPath );
                for ( final StoreResource fileResource : secondLevelListing )
                {
                    if ( fileResource.getPath()
                                     .endsWith( ".pom" ) )
                    {
                        ArtifactPathInfo filePomInfo = ArtifactPathInfo.parse( fileResource.getPath() );
                        // check if the pom is valid for the path
                        if ( filePomInfo != null )
                        {
                            versions.add( VersionUtils.createSingleVersion( new File( topPath ).getName() ) );
                            if ( samplePomInfo == null )
                            {
                                samplePomInfo = filePomInfo;
                            }

                            continue nextTopResource;
                        }
                    }
                }
            }
        }

        if ( versions.isEmpty() )
        {
            logger.debug( "writeVersionMetadata, versions is empty, store:{}", store.getKey() );
            return false;
        }

        logger.debug( "writeVersionMetadata, versions: {}, store:{}", versions, store.getKey() );

        Collections.sort( versions );

        final Transfer metadataFile = fileManager.getTransfer( store, path );
        OutputStream stream = null;
        try
        {
            final Document doc = xml.newDocumentBuilder()
                                    .newDocument();

            final Map<String, String> coordMap = new HashMap<>();
            coordMap.put( ARTIFACT_ID, samplePomInfo == null ? null : samplePomInfo.getArtifactId() );
            coordMap.put( GROUP_ID, samplePomInfo == null ? null : samplePomInfo.getGroupId() );

            final String lastUpdated = SnapshotUtils.generateUpdateTimestamp( SnapshotUtils.getCurrentTimestamp() );

            doc.appendChild( doc.createElementNS( doc.getNamespaceURI(), "metadata" ) );
            xml.createElement( doc.getDocumentElement(), null, coordMap );

            final Map<String, String> versioningMap = new HashMap<>();
            versioningMap.put( LAST_UPDATED, lastUpdated );

            final SingleVersion latest = versions.get( versions.size() - 1 );
            versioningMap.put( LATEST, latest.renderStandard() );

            SingleVersion release = null;
            for ( int i = versions.size() - 1; i >= 0; i-- )
            {
                final SingleVersion r = versions.get( i );
                if ( r.isRelease() )
                {
                    release = r;
                    break;
                }
            }

            if ( release != null )
            {
                versioningMap.put( RELEASE, release.renderStandard() );
            }

            xml.createElement( doc, "versioning", versioningMap );
            final Element versionsElem =
                xml.createElement( doc, "versioning/versions", Collections.emptyMap() );

            for ( final SingleVersion version : versions )
            {
                final Element vElem = doc.createElement( VERSION );
                vElem.setTextContent( version.renderStandard() );

                versionsElem.appendChild( vElem );
            }

            final String xmlStr = xml.toXML( doc, true );
            logger.debug( "writeVersionMetadata, xmlStr: {}", xmlStr );
            stream = metadataFile.openOutputStream( TransferOperation.GENERATE, true, eventMetadata );
            stream.write( xmlStr.getBytes( UTF_8 ) );
        }
        catch ( final GalleyMavenXMLException e )
        {
            throw new IndyWorkflowException( "Failed to generate maven metadata file: %s. Reason: %s", e, path,
                                              e.getMessage() );
        }
        catch ( final IOException e )
        {
            throw new IndyWorkflowException( "Failed to write generated maven metadata file: %s. Reason: %s", e,
                                              metadataFile, e.getMessage() );
        }
        finally
        {
            closeQuietly( stream );
        }

        logger.debug( "writeVersionMetadata, DONE, store: {}", store.getKey() );
        return true;
    }

    private boolean writeSnapshotMetadata( final ArtifactPathInfo info, final List<StoreResource> files,
                                           final ArtifactStore store, final String path,
                                           final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        // first level will contain files that have the timestamp-buildnumber version suffix...for each, we need to parse this info.
        final Map<SnapshotPart, Set<ArtifactPathInfo>> infosBySnap = new HashMap<>();
        for ( final StoreResource resource : files )
        {
            final ArtifactPathInfo resInfo = ArtifactPathInfo.parse( resource.getPath() );
            if ( resInfo != null )
            {
                final SnapshotPart snap = resInfo.getSnapshotInfo();
                Set<ArtifactPathInfo> infos = infosBySnap.computeIfAbsent( snap, k -> new HashSet<>() );
                infos.add( resInfo );
            }
        }

        if ( infosBySnap.isEmpty() )
        {
            return false;
        }

        final List<SnapshotPart> snaps = new ArrayList<>( infosBySnap.keySet() );
        Collections.sort( snaps );

        final Transfer metadataFile = fileManager.getTransfer( store, path );
        OutputStream stream = null;
        try
        {
            final Document doc = xml.newDocumentBuilder()
                                    .newDocument();

            final Map<String, String> coordMap = new HashMap<>();
            coordMap.put( ARTIFACT_ID, info.getArtifactId() );
            coordMap.put( GROUP_ID, info.getGroupId() );
            coordMap.put( VERSION, info.getVersion() );

            final String lastUpdated = SnapshotUtils.generateUpdateTimestamp( SnapshotUtils.getCurrentTimestamp() );

            doc.appendChild( doc.createElementNS( doc.getNamespaceURI(), "metadata" ) );
            xml.createElement( doc.getDocumentElement(), null, coordMap );

            xml.createElement( doc, "versioning", Collections.singletonMap( LAST_UPDATED, lastUpdated ) );

            SnapshotPart snap = snaps.get( snaps.size() - 1 );
            Map<String, String> snapMap = new HashMap<>();
            if ( snap.isLocalSnapshot() )
            {
                snapMap.put( LOCAL_COPY, Boolean.TRUE.toString() );
            }
            else
            {
                snapMap.put( TIMESTAMP, SnapshotUtils.generateSnapshotTimestamp( snap.getTimestamp() ) );

                snapMap.put( BUILD_NUMBER, Integer.toString( snap.getBuildNumber() ) );
            }

            xml.createElement( doc, "versioning/snapshot", snapMap );

            for ( SnapshotPart snap1 : snaps )
            {
                snap = snap1;

                // the last one is the most recent.
                final Set<ArtifactPathInfo> infos = infosBySnap.get( snap );
                for ( final ArtifactPathInfo pathInfo : infos )
                {
                    snapMap = new HashMap<>();

                    final TypeAndClassifier tc =
                            new SimpleTypeAndClassifier( pathInfo.getType(), pathInfo.getClassifier() );

                    final TypeMapping mapping = typeMapper.lookup( tc );

                    final String classifier = mapping == null ? pathInfo.getClassifier() : mapping.getClassifier();
                    if ( classifier != null && classifier.length() > 0 )
                    {
                        snapMap.put( CLASSIFIER, classifier );
                    }

                    snapMap.put( EXTENSION, mapping == null ? pathInfo.getType() : mapping.getExtension() );
                    snapMap.put( VALUE, pathInfo.getVersion() );
                    snapMap.put( UPDATED, lastUpdated );

                    xml.createElement( doc, "versioning/snapshotVersions/snapshotVersion", snapMap );
                }
            }

            final String xmlStr = xml.toXML( doc, true );
            stream = metadataFile.openOutputStream( TransferOperation.GENERATE, true, eventMetadata );
            stream.write( xmlStr.getBytes( UTF_8 ) );
        }
        catch ( final GalleyMavenXMLException e )
        {
            throw new IndyWorkflowException( "Failed to generate maven metadata file: %s. Reason: %s", e, path,
                                              e.getMessage() );
        }
        catch ( final IOException e )
        {
            throw new IndyWorkflowException( "Failed to write generated maven metadata file: %s. Reason: %s", e,
                                              metadataFile, e.getMessage() );
        }
        finally
        {
            closeQuietly( stream );
        }

        return true;
    }

    // Parking this here, transplanted from ScheduleManager, because this is where it belongs. It might be
    // covered elsewhere, but in case it's not, we can use this as a reference...
    //    public void updateSnapshotVersions( final StoreKey key, final String path )
    //    {
    //        final ArtifactPathInfo pathInfo = ArtifactPathInfo.parse( path );
    //        if ( pathInfo == null )
    //        {
    //            return;
    //        }
    //
    //        final ArtifactStore store;
    //        try
    //        {
    //            store = dataManager.getArtifactStore( key );
    //        }
    //        catch ( final IndyDataException e )
    //        {
    //            logger.error( String.format( "Failed to update metadata after snapshot deletion. Reason: {}",
    //                                         e.getMessage() ), e );
    //            return;
    //        }
    //
    //        if ( store == null )
    //        {
    //            logger.error( "Failed to update metadata after snapshot deletion in: {}. Reason: Cannot find corresponding ArtifactStore",
    //                          key );
    //            return;
    //        }
    //
    //        final Transfer item = fileManager.getStorageReference( store, path );
    //        if ( item.getParent() == null || item.getParent()
    //                                             .getParent() == null )
    //        {
    //            return;
    //        }
    //
    //        final Transfer metadata = fileManager.getStorageReference( store, item.getParent()
    //                                                                              .getParent()
    //                                                                              .getPath(), "maven-metadata.xml" );
    //
    //        if ( metadata.exists() )
    //        {
    //            //            logger.info( "[UPDATE VERSIONS] Updating snapshot versions for path: {} in store: {}", path, key.getName() );
    //            Reader reader = null;
    //            Writer writer = null;
    //            try
    //            {
    //                reader = new InputStreamReader( metadata.openInputStream() );
    //                final Metadata md = new MetadataXpp3Reader().read( reader );
    //
    //                final Versioning versioning = md.getVersioning();
    //                final List<String> versions = versioning.getVersions();
    //
    //                final String version = pathInfo.getVersion();
    //                String replacement = null;
    //
    //                final int idx = versions.indexOf( version );
    //                if ( idx > -1 )
    //                {
    //                    if ( idx > 0 )
    //                    {
    //                        replacement = versions.get( idx - 1 );
    //                    }
    //
    //                    versions.remove( idx );
    //                }
    //
    //                if ( version.equals( md.getVersion() ) )
    //                {
    //                    md.setVersion( replacement );
    //                }
    //
    //                if ( version.equals( versioning.getLatest() ) )
    //                {
    //                    versioning.setLatest( replacement );
    //                }
    //
    //                final SnapshotPart si = pathInfo.getSnapshotInfo();
    //                if ( si != null )
    //                {
    //                    final SnapshotPart siRepl = SnapshotUtils.extractSnapshotVersionPart( replacement );
    //                    final Snapshot snapshot = versioning.getSnapshot();
    //
    //                    final String siTstamp = SnapshotUtils.generateSnapshotTimestamp( si.getTimestamp() );
    //                    if ( si.isRemoteSnapshot() && siTstamp.equals( snapshot.getTimestamp() )
    //                        && si.getBuildNumber() == snapshot.getBuildNumber() )
    //                    {
    //                        if ( siRepl != null )
    //                        {
    //                            if ( siRepl.isRemoteSnapshot() )
    //                            {
    //                                snapshot.setTimestamp( SnapshotUtils.generateSnapshotTimestamp( siRepl.getTimestamp() ) );
    //                                snapshot.setBuildNumber( siRepl.getBuildNumber() );
    //                            }
    //                            else
    //                            {
    //                                snapshot.setLocalCopy( true );
    //                            }
    //                        }
    //                        else
    //                        {
    //                            versioning.setSnapshot( null );
    //                        }
    //                    }
    //                }
    //
    //                writer = new OutputStreamWriter( metadata.openOutputStream( TransferOperation.GENERATE, true ) );
    //                new MetadataXpp3Writer().write( writer, md );
    //            }
    //            catch ( final IOException e )
    //            {
    //                logger.error( "Failed to update metadata after snapshot deletion.\n  Snapshot: {}\n  Metadata: {}\n  Reason: {}",
    //                              e, item.getFullPath(), metadata, e.getMessage() );
    //            }
    //            catch ( final XmlPullParserException e )
    //            {
    //                logger.error( "Failed to update metadata after snapshot deletion.\n  Snapshot: {}\n  Metadata: {}\n  Reason: {}",
    //                              e, item.getFullPath(), metadata, e.getMessage() );
    //            }
    //            finally
    //            {
    //                closeQuietly( reader );
    //                closeQuietly( writer );
    //            }
    //        }
    //    }
    //
}

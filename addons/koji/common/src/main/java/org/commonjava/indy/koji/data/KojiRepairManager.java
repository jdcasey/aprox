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
package org.commonjava.indy.koji.data;

import com.redhat.red.build.koji.KojiClient;
import com.redhat.red.build.koji.KojiClientException;
import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiSessionInfo;
import org.commonjava.atlas.maven.ident.ref.ArtifactRef;
import org.commonjava.atlas.maven.ident.ref.SimpleArtifactRef;
import org.commonjava.cdi.util.weft.DrainingExecutorCompletionService;
import org.commonjava.cdi.util.weft.ExecutorConfig;
import org.commonjava.cdi.util.weft.SingleThreadedExecutorService;
import org.commonjava.cdi.util.weft.WeftExecutorService;
import org.commonjava.cdi.util.weft.WeftManaged;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.audit.ChangeSummary;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.koji.conf.IndyKojiConfig;
import org.commonjava.indy.koji.content.IndyKojiContentProvider;
import org.commonjava.indy.koji.content.KojiBuildInfoWithRemote;
import org.commonjava.indy.koji.content.KojiContentConsolidator;
import org.commonjava.indy.koji.content.KojiPathPatternFormatter;
import org.commonjava.indy.koji.model.KojiMultiRepairResult;
import org.commonjava.indy.koji.model.KojiRepairRequest;
import org.commonjava.indy.koji.model.KojiRepairResult;
import org.commonjava.indy.koji.util.KojiUtils;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.maven.galley.event.EventMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import static org.commonjava.indy.core.ctl.PoolUtils.detectOverloadVoid;
import static org.commonjava.indy.koji.content.KojiContentManagerDecorator.CREATION_TRIGGER_GAV;
import static org.commonjava.indy.koji.model.IndyKojiConstants.KOJI_ORIGIN;
import static org.commonjava.indy.koji.model.IndyKojiConstants.KOJI_ORIGIN_BINARY;
import static org.commonjava.indy.model.core.StoreType.group;
import static org.commonjava.indy.model.core.StoreType.remote;

/**
 * Component responsible for repair Koji remote repositories.
 *
 * @author ruhan
 */
@ApplicationScoped
public class KojiRepairManager
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    public static final String METADATA_KOJI_BUILD_ID = "koji-build-id"; // metadata key for recording koji build id

    @Inject
    private IndyKojiConfig config;

    @Inject
    private StoreDataManager storeManager;

    @Inject
    private IndyKojiContentProvider kojiCachedClient;

    @Inject
    private KojiPathPatternFormatter kojiPathFormatter;

    @Inject
    private KojiUtils kojiUtils;

    @Inject
    private KojiContentConsolidator consolidator;

    @Inject
    @WeftManaged
    @ExecutorConfig( named="koji-background-operations", threads=50, priority = 3, loadSensitive = ExecutorConfig.BooleanLiteral.TRUE, maxLoadFactor = 100)
    private WeftExecutorService backgroundOperationExecutor;

    private ReentrantLock opLock = new ReentrantLock(); // operations are synchronized

    protected KojiRepairManager()
    {
    }

    public KojiRepairManager( final StoreDataManager storeManager, final IndyKojiConfig config,
                              final KojiClient kojiClient )
    {
        this.storeManager = storeManager;
        this.config = config;
        this.kojiCachedClient = new IndyKojiContentProvider( kojiClient, null );
        this.backgroundOperationExecutor = new SingleThreadedExecutorService( "koji-repairs" );
    }

    public KojiMultiRepairResult consolidateAllRemoteRepos( final StoreKey targetGroupKey, final String user )
            throws KojiRepairException, IndyWorkflowException
    {
        Group group;
        try
        {
            group = (Group) storeManager.getArtifactStore( targetGroupKey );
        }
        catch ( IndyDataException e )
        {
            throw new IndyWorkflowException( "Cannot retrieve group: %s", e, targetGroupKey );
        }

        HostedRepository consolidationTarget =
                consolidator.getOrCreateConsolidationTarget( group, targetGroupKey.getName(), user, true );

        if ( opLock.tryLock() )
        {
            try
            {
                Map<StoreKey, RemoteRepository> repoMap = new HashMap<>();
                try
                {
                    getAllKojiRemotes().stream()
                                                     .filter( r -> group.getConstituents().contains( r.getKey() ) )
                                                     .forEach( r->repoMap.put(r.getKey(), r) );
                }
                catch ( IndyDataException e )
                {
                    throw new IndyWorkflowException( "Cannot retrieve all Koji remote repositories.", e );
                }

                DrainingExecutorCompletionService<KojiRepairResult> repairService =
                        new DrainingExecutorCompletionService<>( backgroundOperationExecutor );

                List<RemoteRepository> kojiRemotes = group.getConstituents()
                                                          .stream()
                                                          .filter( k -> repoMap.containsKey( k ) )
                                                          .map( k -> repoMap.get( k ) )
                                                          .collect( Collectors.toList() );

                detectOverloadVoid( () -> kojiRemotes.forEach( store -> repairService.submit( () -> {

                    KojiRepairRequest req = new KojiRepairRequest( store.getKey(), false );
                    KojiRepairResult ret = new KojiRepairResult( req );
                    StoreKey remoteKey = store.getKey();

                    if ( Boolean.FALSE.toString()
                                      .equals(
                                              store.getMetadata( KojiContentConsolidator.KOJI_CONSOLIDATION_RESULT ) ) )
                    {
                        return ret.withError( String.format( "Consolidation has already failed for: %s", remoteKey ) );
                    }

                    final String nvr = kojiUtils.getBuildNvr( remoteKey );
                    if ( nvr == null )
                    {
                        String error = String.format( "Not a koji store: %s", remoteKey );
                        return ret.withError( error );
                    }

                    try
                    {
                        KojiSessionInfo session = null;
                        KojiBuildInfo build = kojiCachedClient.getBuildInfo( nvr, session );

                        List<KojiArchiveInfo> archives = kojiCachedClient.listArchivesForBuild( build.getId(), session );

                        final KojiBuildInfoWithRemote buildRemote = new KojiBuildInfoWithRemote( build, archives, store );
                        consolidator.startConsolidation( consolidationTarget, group, buildRemote, user );

                        if ( storeManager.hasArtifactStore( remoteKey ) )
                        {
                            return ret.withError( String.format( "Consolidation has failed for: %s", remoteKey ) );
                        }

                        KojiRepairResult.RepairResult repairResult = new KojiRepairResult.RepairResult( remoteKey );
                        repairResult.withPropertyChange( "content_consolidation", false, true );

                        return ret.withResult( repairResult );
                    }
                    catch ( KojiClientException e )
                    {
                        String error = String.format( "Cannot getBuildInfo: %s, error: %s", remoteKey, e );
                        logger.debug( error, e );
                        return ret.withError( error, e );
                    }
                } ) ) );

                List<KojiRepairResult> results = new ArrayList<>();
                try
                {
                    repairService.drain( repair->{
                        results.add( repair );
                    } );

                    return new KojiMultiRepairResult( results );
                }
                catch ( InterruptedException | ExecutionException e )
                {
                    logger.error( "Failed to consolidate Koji remote repository content in: " + targetGroupKey, e );
                }
            }
            finally{
                opLock.unlock();
            }
        }

        return new KojiMultiRepairResult();
    }

    public KojiMultiRepairResult repairAllPathMasks( final String user )
            throws KojiRepairException, IndyWorkflowException
    {
        KojiMultiRepairResult result = new KojiMultiRepairResult();

        if ( opLock.tryLock() )
        {
            try
            {
                List<RemoteRepository> kojiRemotes = getAllKojiRemotes();

                DrainingExecutorCompletionService<KojiRepairResult> repairService =
                        new DrainingExecutorCompletionService<>( backgroundOperationExecutor );

                detectOverloadVoid( () -> kojiRemotes.forEach( r -> repairService.submit( () -> {
                    logger.info( "Attempting to repair path masks in Koji remote: {}", r.getKey() );

                    KojiRepairRequest request = new KojiRepairRequest( r.getKey(), false );
                    try
                    {
                        return repairPathMask( request, user, true );
                    }
                    catch ( KojiRepairException e )
                    {
                        logger.error( "Failed to execute repair for: " + r.getKey(), e );
                    }

                    return null;
                } ) ) );

                List<KojiRepairResult> results = new ArrayList<>();
                try
                {
                    repairService.drain( r -> {
                        if ( r != null )
                        {
                            results.add( r );
                        }
                    } );
                }
                catch ( InterruptedException | ExecutionException e )
                {
                    logger.error( "Failed to repair path masks.", e );
                }

                result.setResults( results );
            }
            catch ( IndyDataException e )
            {
                throw new KojiRepairException( "Failed to list Koji remote repositories for repair. Reason: %s", e, e.getMessage() );
            }
            finally
            {
                opLock.unlock();
            }
        }
        else
        {
            throw new KojiRepairException( "Koji repair manager is busy." );
        }

        return result;
    }

    public KojiRepairResult repairPathMask( KojiRepairRequest request, String user )
            throws KojiRepairException
    {
        return repairPathMask( request, user, false );
    }

    public KojiRepairResult repairPathMask( KojiRepairRequest request, String user, boolean skipLock )
        throws KojiRepairException
    {
        KojiRepairResult ret = new KojiRepairResult( request );

        if ( skipLock || opLock.tryLock() )
        {
            try
            {
                ArtifactStore store = getRequestedStore(request, ret);

                if ( store == null )
                {
                    return ret;
                }

                store = store.copyOf();

                StoreKey remoteKey = request.getSource();

                if ( remoteKey.getType() == remote )
                {
                    final String nvr = kojiUtils.getBuildNvr( remoteKey );
                    if ( nvr == null )
                    {
                        String error = String.format( "Not a koji store: %s", remoteKey );
                        return ret.withError( error );
                    }

                    try
                    {
                        KojiSessionInfo session = null;
                        KojiBuildInfo build = kojiCachedClient.getBuildInfo( nvr, session );

                        List<KojiArchiveInfo> archives = kojiCachedClient.listArchivesForBuild( build.getId(), session );

                        ArtifactRef artifactRef = SimpleArtifactRef.parse( store.getMetadata( CREATION_TRIGGER_GAV ) );
                        if ( artifactRef == null )
                        {
                            String error = String.format(
                                    "Koji remote repository: %s does not have %s metadata. Cannot retrieve accurate path masks.",
                                    remoteKey, CREATION_TRIGGER_GAV );
                            return ret.withError( error );
                        }

                        // set pathMaskPatterns using build output paths
                        Set<String> patterns = kojiPathFormatter.getPatterns( artifactRef, archives, true );
                        logger.debug( "For repo: {}, resetting path_mask_patterns to:\n\n{}\n\n", store.getKey(),
                                     patterns );

                        KojiRepairResult.RepairResult repairResult = new KojiRepairResult.RepairResult( remoteKey );
                        repairResult.withPropertyChange( "path_mask_patterns", store.getPathMaskPatterns(), patterns );

                        ret.withResult( repairResult );

                        store.setPathMaskPatterns( patterns );

                        final ChangeSummary changeSummary = new ChangeSummary( user,
                                                                               "Repairing remote repository path masks to Koji build: "
                                                                                       + build.getNvr() );

                        storeManager.storeArtifactStore( store, changeSummary, false, true, new EventMetadata() );
                    }
                    catch ( KojiClientException e )
                    {
                        String error = String.format( "Cannot getBuildInfo: %s, error: %s", remoteKey, e );
                        logger.debug( error, e );
                        return ret.withError( error, e );
                    }
                    catch ( IndyDataException e )
                    {
                        String error = String.format( "Failed to store changed remote repository: %s, error: %s", remoteKey, e );
                        logger.debug( error, e );
                        return ret.withError( error, e );
                    }
                }
                else
                {
                    String error = String.format( "Not a remote koji repository: %s", remoteKey );
                    return ret.withError( error );
                }
            }
            finally
            {
                if ( !skipLock )
                {
                    opLock.unlock();
                }
            }
        }
        else
        {
            throw new KojiRepairException( "Koji repair manager is busy." );
        }

        return ret;
    }

    public KojiRepairResult repairVol( KojiRepairRequest request, String user, String baseUrl )
                    throws KojiRepairException, KojiClientException
    {
        boolean flag = opLock.tryLock();
        if ( flag )
        {
            try
            {
                StoreKey storeKey = request.getSource();

                KojiRepairResult ret = new KojiRepairResult( request );

                ArtifactStore store;
                try
                {
                    store = storeManager.getArtifactStore( storeKey );
                }
                catch ( IndyDataException e )
                {
                    String error = String.format( "Cannot get store: %s, error: %s", storeKey, e );
                    logger.warn( error, e );
                    return ret.withError( error );
                }

                if ( store == null )
                {
                    String error = String.format( "No such store: %s.", storeKey );
                    return ret.withError( error );
                }

                if ( storeKey.getType() == group )
                {
                    return repairGroupVol( request, (Group) store, user );
                }
                else if ( storeKey.getType() == remote )
                {
                    return repairRemoteRepositoryVol( request, (RemoteRepository) store, user );
                }
                else
                {
                    String error = String.format( "Not a group or remote koji store: %s", storeKey );
                    return ret.withError( error );
                }
            }
            finally
            {
                opLock.unlock();
            }
        }
        else
        {
            throw new KojiRepairException( "opLock held by other" );
        }
    }

    private KojiRepairResult repairGroupVol( KojiRepairRequest request, Group group, String user )
                    throws KojiClientException
    {
        KojiRepairResult ret = new KojiRepairResult( request );

        List<StoreKey> stores = group.getConstituents();
        if ( stores.isEmpty() )
        {
            return ret.withNoChange( group.getKey() );
        }

        KojiSessionInfo session = null;

        List<Object> args = new ArrayList<>(  );
        stores.forEach( storeKey -> {
            String nvr = kojiUtils.getBuildNvr( storeKey );
            if ( nvr != null )
            {
                args.add( nvr );
            }
            else
            {
                ret.withIgnore( storeKey );
            }
        } );
        List<KojiBuildInfo> buildInfoList = kojiCachedClient.getBuildInfo( args, session );

        buildInfoList.forEach( buildInfo -> {
            try
            {
                KojiRepairResult.RepairResult repairResult =
                                doRepair( group.getPackageType(), null, buildInfo, user, request.isDryRun() );
                ret.withResult( repairResult );
            }
            catch ( KojiRepairException e )
            {
                // we do not fail the whole attempt if one store failed
                logger.debug( "Repair failed", e );
                ret.withResult( new KojiRepairResult.RepairResult( e.getStoreKey(), e ) );
            }
        } );

        return ret;
    }

    private KojiRepairResult repairRemoteRepositoryVol( KojiRepairRequest request, RemoteRepository repository,
                                                     String user ) throws KojiRepairException
    {
        StoreKey storeKey = repository.getKey();

        KojiRepairResult ret = new KojiRepairResult( request );

        final String nvr = kojiUtils.getBuildNvr( storeKey );
        if ( nvr == null )
        {
            String error = String.format( "Not a koji store: %s", storeKey );
            return ret.withError( error );
        }

        KojiBuildInfo buildInfo;
        try
        {
            KojiSessionInfo session = null;
            buildInfo = kojiCachedClient.getBuildInfo( nvr, session );
        }
        catch ( KojiClientException e )
        {
            String error = String.format( "Cannot getBuildInfo: %s, error: %s", storeKey, e );
            logger.debug( error, e );
            return ret.withError( error, e );
        }

        KojiRepairResult.RepairResult repairResult =
                        doRepair( repository.getPackageType(), repository, buildInfo, user,
                                  request.isDryRun() );

        return ret.withResult( repairResult );
    }

    /**
     * Repair one remote repository.
     * @param packageType
     * @param repository repository to be repaired. If null, the repository name will be retrieved according to
     *                         the buildInfo and the naming format in koji.conf.
     * @param buildInfo koji build which this repository proxies
     * @param user the user does the repair
     * @param isDryRun
     * @return
     * @throws KojiRepairException
     */
    private KojiRepairResult.RepairResult doRepair( String packageType, RemoteRepository repository,
                                                    KojiBuildInfo buildInfo, String user, boolean isDryRun )
                    throws KojiRepairException
    {
        StoreKey storeKey;
        if ( repository != null )
        {
            storeKey = repository.getKey();
        }
        else
        {
            String name = kojiUtils.getRepositoryName( buildInfo );
            storeKey = new StoreKey( packageType, StoreType.remote, name );
            try
            {
                repository = (RemoteRepository) storeManager.getArtifactStore( storeKey );
            }
            catch ( IndyDataException e )
            {
                throw new KojiRepairException( "Cannot get store: %s. Reason: %s", e, storeKey,
                                               e.getMessage() );
            }
        }

        KojiRepairResult.RepairResult repairResult = new KojiRepairResult.RepairResult( storeKey );

        String url = repository.getUrl();

        String newUrl;
        try
        {
            newUrl = kojiUtils.formatStorageUrl( config.getStorageRootUrl(), buildInfo ); // volume is involved
        }
        catch ( MalformedURLException e )
        {
            throw new KojiRepairException( "Failed to format storage Url: %s. Reason: %s", e, storeKey,
                                           e.getMessage() );
        }

        boolean changed = !url.equals( newUrl );
        if ( changed )
        {
            repairResult.withPropertyChange( "url", url, newUrl );

            if ( !isDryRun )
            {
                ChangeSummary changeSummary = new ChangeSummary( user,
                                                                 "Repair " + storeKey + " url with volume: " + buildInfo
                                                                                 .getVolumeName() );
                repository.setUrl( newUrl );
                repository.setMetadata( METADATA_KOJI_BUILD_ID, Integer.toString( buildInfo.getId() ) );
                boolean fireEvents = false;
                boolean skipIfExists = false;
                try
                {
                    storeManager.storeArtifactStore( repository, changeSummary, skipIfExists, fireEvents, new EventMetadata() );
                }
                catch ( IndyDataException e )
                {
                    throw new KojiRepairException( "Failed to repair store: %s. Reason: %s", e, storeKey, e.getMessage() );
                }
            }
        }
        return repairResult;
    }

    public KojiMultiRepairResult repairAllMetadataTimeout( final String user, boolean isDryRun )
            throws KojiRepairException, IndyWorkflowException
    {
        KojiMultiRepairResult result = new KojiMultiRepairResult();

        if ( opLock.tryLock() )
        {
            try
            {
                List<RemoteRepository> kojiRemotes = getAllKojiRemotes();

                DrainingExecutorCompletionService<KojiRepairResult> repairService =
                        new DrainingExecutorCompletionService<>( backgroundOperationExecutor );

                detectOverloadVoid( () -> kojiRemotes.forEach( r -> repairService.submit( () -> {
                    logger.info( "Attempting to repair path masks in Koji remote: {}", r.getKey() );

                    KojiRepairRequest request = new KojiRepairRequest( r.getKey(), isDryRun );
                    try
                    {
                        return repairMetadataTimeout( request, user, true );
                    }
                    catch ( KojiRepairException e )
                    {
                        logger.error( "Failed to execute repair for: " + r.getKey(), e );
                    }

                    return null;
                } ) ) );

                List<KojiRepairResult> results = new ArrayList<>();
                try
                {
                    repairService.drain( r -> {
                        if ( r != null )
                        {
                            results.add( r );
                        }
                    } );
                }
                catch ( InterruptedException | ExecutionException e )
                {
                    logger.error( "Failed to repair metadata timeout.", e );
                }

                result.setResults( results );
            }
            catch ( IndyDataException e )
            {
                throw new KojiRepairException( "Failed to list Koji remote repositories for repair. Reason: %s", e, e.getMessage() );
            }
            finally
            {
                opLock.unlock();
            }
        }
        else
        {
            throw new KojiRepairException( "Koji repair manager is busy." );
        }

        return result;
    }

    public KojiRepairResult repairMetadataTimeout( KojiRepairRequest request, String user, boolean skipLock ) throws KojiRepairException{
        KojiRepairResult ret = new KojiRepairResult( request );

        if ( skipLock || opLock.tryLock() )
        {
            try
            {
                ArtifactStore store = getRequestedStore(request, ret);

                if ( store == null )
                {
                    return ret;
                }

                store = store.copyOf();

                StoreKey remoteKey = request.getSource();
                if ( remoteKey.getType() == remote )
                {
                    final String nvr = kojiUtils.getBuildNvr( remoteKey );
                    if ( nvr == null )
                    {
                        String error = String.format( "Not a koji store: %s", remoteKey );
                        return ret.withError( error );
                    }

                    try
                    {
                        final int NEVER_TIMEOUT_VALUE = -1;
                        if ( !request.isDryRun() )
                        {
                            ( (RemoteRepository) store ).setMetadataTimeoutSeconds( NEVER_TIMEOUT_VALUE );
                            final ChangeSummary changeSummary = new ChangeSummary( user,
                                                                                   "Repairing remote repository path masks to Koji build: "
                                                                                           + nvr );
                            storeManager.storeArtifactStore( store, changeSummary, false, true, new EventMetadata() );
                        }
                        KojiRepairResult.RepairResult repairResult = new KojiRepairResult.RepairResult( remoteKey );
                        repairResult.withPropertyChange( "metadata_timeout", ( (RemoteRepository) store ).getMetadataTimeoutSeconds(), NEVER_TIMEOUT_VALUE );

                        ret.withResult( repairResult );
                    }
                    catch ( IndyDataException e )
                    {
                        String error =
                                String.format( "Failed to store changed remote repository: %s, error: %s", remoteKey,
                                               e );
                        logger.debug( error, e );
                        return ret.withError( error, e );
                    }
                }
                else
                {
                    String error = String.format( "Not a remote koji repository: %s", remoteKey );
                    return ret.withError( error );
                }
            }
            finally
            {
                if ( !skipLock )
                {
                    opLock.unlock();
                }
            }
        }
        else
        {
            throw new KojiRepairException( "Koji repair manager is busy." );
        }

        return ret;
    }

    public KojiRepairResult repairMetadataTimeout( KojiRepairRequest request, String user )
            throws KojiRepairException
    {
        return repairMetadataTimeout( request, user, false );
    }


    private List<RemoteRepository> getAllKojiRemotes()
            throws IndyDataException
    {
        return storeManager.query()
                           .storeTypes( remote )
                           .stream( ( remote ) ->
                                            KOJI_ORIGIN.equals( remote.getMetadata( ArtifactStore.METADATA_ORIGIN ) )
                                                    || KOJI_ORIGIN_BINARY.equals(
                                                    remote.getMetadata( ArtifactStore.METADATA_ORIGIN ) ) )
                           .map( s -> (RemoteRepository) s )
                           .filter( Objects::nonNull )
                           .collect( Collectors.toList() );
    }

    private ArtifactStore getRequestedStore(final KojiRepairRequest request, final KojiRepairResult ret ){
        StoreKey remoteKey = request.getSource();

        ArtifactStore store = null;
        try
        {
            store = storeManager.getArtifactStore( remoteKey );
        }
        catch ( IndyDataException e )
        {
            String error = String.format( "Cannot get store: %s, error: %s", remoteKey, e );
            logger.warn( error, e );
            ret.withError( error );
            return null;
        }

        if ( store == null )
        {
            String error = String.format( "No such store: %s.", remoteKey );
            ret.withError( error );
            return null;
        }

        return store;
    }


}

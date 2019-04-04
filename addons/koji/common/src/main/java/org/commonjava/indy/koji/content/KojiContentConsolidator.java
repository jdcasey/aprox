package org.commonjava.indy.koji.content;

import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;
import org.commonjava.atlas.maven.ident.ref.ArtifactRef;
import org.commonjava.cdi.util.weft.DrainingExecutorCompletionService;
import org.commonjava.cdi.util.weft.ExecutorConfig;
import org.commonjava.cdi.util.weft.Locker;
import org.commonjava.cdi.util.weft.ThreadContext;
import org.commonjava.cdi.util.weft.WeftExecutorService;
import org.commonjava.cdi.util.weft.WeftManaged;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.audit.ChangeSummary;
import org.commonjava.indy.content.ContentDigester;
import org.commonjava.indy.content.ContentManager;
import org.commonjava.indy.core.inject.GroupMembershipLocks;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.koji.conf.IndyKojiConfig;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.maven.galley.TransferException;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.io.checksum.ContentDigest;
import org.commonjava.maven.galley.io.checksum.TransferMetadata;
import org.commonjava.maven.galley.maven.spi.type.TypeMapper;
import org.commonjava.maven.galley.maven.util.ArtifactPathUtils;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Boolean.TRUE;
import static org.commonjava.indy.data.StoreDataManager.IGNORE_READONLY;
import static org.commonjava.indy.koji.content.KojiContentManagerDecorator.SKIP_KOJI_CONTENT_LAYER;
import static org.commonjava.indy.model.core.StoreType.hosted;
import static org.commonjava.indy.pkg.PackageTypeConstants.PKG_TYPE_MAVEN;

@ApplicationScoped
public class KojiContentConsolidator
{
    private static final ContentDigest KOJI_CHECKSUM_TYPE = ContentDigest.MD5;

    private static final int MAX_CONSOLIDATION_RETRIES = 3;

    public static final String KOJI_CONSOLIDATION_RESULT = "koji-consolidation-result";

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @WeftManaged
    @ExecutorConfig( threads=8, priority=5, named="koji-content-consolidation", daemon=true )
    @Inject
    private ExecutorService consolidationService;

    @WeftManaged
    @ExecutorConfig( named="koji-background-operations", threads=50, priority = 3, loadSensitive = ExecutorConfig.BooleanLiteral.TRUE, maxLoadFactor = 100)
    @Inject
    private WeftExecutorService backgroundOperationExecutor;

    @Inject
    private TypeMapper typeMapper;

    @Inject
    private ContentDigester digester;
    
    @Inject
    private ContentManager contentManager;

    @Inject
    private StoreDataManager storeDataManager;

    @GroupMembershipLocks
    @Inject
    private Locker<StoreKey> groupMembershipLocker;

    @Inject
    private IndyKojiConfig config;

    public HostedRepository getOrCreateConsolidationTarget( final Group targetGroup, final String targetName,
                                                            final String user, final boolean storeGroup )
    {
        StoreKey consolidationTarget = new StoreKey( PKG_TYPE_MAVEN, hosted, targetName );
        HostedRepository consolidationTargetRepo = null;
        try
        {
            consolidationTargetRepo = (HostedRepository) storeDataManager.getArtifactStore( consolidationTarget );
            ChangeSummary cs =
                    new ChangeSummary( user, "Creating Koji remote consolidation target repository." );

            if ( consolidationTargetRepo == null )
            {
                consolidationTargetRepo =
                        new HostedRepository( consolidationTarget.getPackageType(), consolidationTarget.getName() );

                consolidationTargetRepo.setReadonly( true );
                consolidationTargetRepo.setAllowSnapshots( false );

                storeDataManager.storeArtifactStore( consolidationTargetRepo, cs, false, true,
                                                     new EventMetadata() );
            }

            if ( !targetGroup.getConstituents().contains( consolidationTarget ))
            {
                targetGroup.getConstituents().add( 0, consolidationTarget );

                if ( storeGroup )
                {
                    storeDataManager.storeArtifactStore( targetGroup, cs, false, true, new EventMetadata() );
                }
            }
        }
        catch ( IndyDataException e )
        {
            logger.error( String.format(
                    "Cannot retrieve / create consolidation-target hosted repository: %s for target group: %s. Reason: %s",
                    consolidationTarget, targetGroup.getKey(), e.getMessage() ), e );
        }

        return consolidationTargetRepo;
    }

    private static final class ConsolidationDownload
    {
        private final AtomicInteger tries = new AtomicInteger( 0 );
        private final String path;
        private final KojiArchiveInfo archive;

        ConsolidationDownload( String path, KojiArchiveInfo archive )
        {
            this.path = path;
            this.archive = archive;
        }

        int incrementTries()
        {
            return tries.incrementAndGet();
        }
    }

    /**
     * Start the process of consolidation from the Koji remote repository, using the build / archive info from Koji to
     * guide the list of paths to fetch and consolidate. Do NOT overwrite existing paths.
     *
     * This will thread off the operation in order to avoid processing all of the downloads synchronously with the user
     * request that triggered it.
     *  @param consolidationTargetRepo Repository into which Koji content should be copied
     * @param targetGroup Group where Koji content should be contained, which also contains the consolidated repository
     * @param buildRemote Information about the Koji build, including a RemoteRepository instance and archive / build info from Koji itself
     * @param user
     */
    public void startConsolidation( final HostedRepository consolidationTargetRepo, final Group targetGroup,
                                    final KojiBuildInfoWithRemote buildRemote, final String user )
    {
        backgroundOperationExecutor.execute( ()->{
            ThreadContext context = ThreadContext.getContext( true );
            context.put( SKIP_KOJI_CONTENT_LAYER, Boolean.TRUE );
            try
            {
                final EventMetadata skipReadOnlyMetadata = new EventMetadata();
                skipReadOnlyMetadata.set( IGNORE_READONLY, TRUE );

                Set<String> metadataToClear = new HashSet<>();
                List<ConsolidationDownload> downloads = new ArrayList<>();

                buildRemote.getArchives().forEach( (archive)->{
                    ArtifactRef artifact = archive.asArtifact();
                    try
                    {
                        String path = ArtifactPathUtils.formatArtifactPath( artifact, typeMapper );
                        downloads.add( new ConsolidationDownload( path, archive ) );

                        String metadata = Paths.get( path ).getParent().resolve( "maven-metadata.xml" ).toString();
                        metadataToClear.add( metadata );
                    }
                    catch ( TransferException e )
                    {
                        logger.error(
                                String.format( "Failed to format path for artifact: %s in build: %s. Reason: %s", artifact,
                                               buildRemote.getBuild().getNvr(), e.getMessage() ), e );
                    }
                } );

                DrainingExecutorCompletionService<ConsolidationDownload> svc =
                        new DrainingExecutorCompletionService<>( consolidationService );

                downloads.forEach( cdl -> svc.submit(
                        downloadAndConsolidate( cdl, buildRemote, consolidationTargetRepo,
                                                skipReadOnlyMetadata ) ) );

                boolean interrupt = false;
                try
                {
                    svc.drain( (cdl)->{
                        if ( cdl != null )
                        {
                            svc.submit( downloadAndConsolidate( cdl, buildRemote, consolidationTargetRepo,
                                                                skipReadOnlyMetadata ) );
                        }
                    } );

                    DrainingExecutorCompletionService<Void> mdSvc =
                            new DrainingExecutorCompletionService<>( consolidationService );

                    metadataToClear.forEach( mdPath -> mdSvc.submit( () -> {
                        try
                        {
                            contentManager.delete( consolidationTargetRepo, mdPath, skipReadOnlyMetadata );
                            contentManager.delete( targetGroup, mdPath, skipReadOnlyMetadata );
                        }
                        catch ( IndyWorkflowException e )
                        {
                            logger.error( String.format(
                                    "Failed to delete metadata after Koji build: %s repository consolidation: %s to: %s. Reason: %s",
                                    buildRemote.getBuild().getNvr(), buildRemote.getRemoteRepository().getKey(),
                                    consolidationTargetRepo.getKey(), e.getMessage() ), e );
                        }

                        return null;
                    } ) );

                    mdSvc.drain( (v)->{} );
                }
                catch ( ExecutionException | InterruptedException e )
                {
                    interrupt = (e instanceof InterruptedException);
                }
                finally
                {
                    updateStoresFromConsolidation( interrupt, buildRemote, consolidationTargetRepo, targetGroup, user );
                }
            }
            finally
            {
                context.remove( SKIP_KOJI_CONTENT_LAYER );
            }
        } );
    }

    private void updateStoresFromConsolidation( final boolean interrupt, final KojiBuildInfoWithRemote buildRemote,
                                                final HostedRepository consolidationTargetRepo, final Group targetGroup,
                                                final String user )
    {
        groupMembershipLocker.lockAnd( targetGroup.getKey(), config.getLockTimeoutSeconds(), k-> {
            try
            {
                // if we have metadata saying the consolidation failed for this remote repo, save the repo and leave it
                // in the group membership.
                if ( Boolean.FALSE.toString().equals( buildRemote.getRemoteRepository().getMetadata( KOJI_CONSOLIDATION_RESULT ) ) )
                {
                    final ChangeSummary cs = new ChangeSummary( user,
                                                                "Storing Koji remote for which consolidation has failed." );
                    storeDataManager.storeArtifactStore( buildRemote.getRemoteRepository(), cs, false, true, new EventMetadata() );
                }
                else
                {
                    // otherwise, if consolidation succeeds, remove the remote repo and modify the group's membership.
                    final ChangeSummary cs = new ChangeSummary( user,
                                                                "Removing consolidated Koji remote repository." );

                    Group g = targetGroup.copyOf();
                    g.removeConstituent( buildRemote.getRemoteRepository().getKey() );

                    storeDataManager.storeArtifactStore( g, cs, false, true, new EventMetadata() );

                    storeDataManager.deleteArtifactStore( buildRemote.getRemoteRepository().getKey(), cs, new EventMetadata() );

                }
            }
            catch ( IndyDataException e )
            {
                logger.error( String.format(
                        "Failed to adjust store definitions after attempted consolidation of: %s into: %s (group: %s). Reason: %s",
                        buildRemote.getBuild().getNvr(), consolidationTargetRepo.getKey(), targetGroup.getKey(), e.getMessage() ), e );
            }

            return null;
        }, (k,lock)->false);

        if ( interrupt )
        {
            // preserve interrupted state.
            Thread.currentThread().interrupt();
        }
    }

    private Callable<ConsolidationDownload> downloadAndConsolidate( final ConsolidationDownload cdl,
                                                                    final KojiBuildInfoWithRemote buildRemote,
                                                                    final ArtifactStore consolidationTargetRepo,
                                                                    EventMetadata skipReadOnlyMetadata )
    {
        return () -> {
            Transfer downloaded = null;

            boolean retry = false;
            try
            {
                // if the file already exists in the consolidation repo, don't overwrite it!
                if ( contentManager.exists( consolidationTargetRepo, cdl.path ) )
                {
                    return null;
                }
            }
            catch ( IndyWorkflowException e )
            {
                logger.error( String.format(
                        "Failed to check pre-existence of path: %s in consolidation target: %s for Koji build: %s. Reason: %s",
                        cdl.path, consolidationTargetRepo.getKey(), buildRemote.getBuild().getNvr(), e.getMessage() ), e );
            }

            try
            {
                // Try to download from Koji
                downloaded = contentManager.retrieve( buildRemote.getRemoteRepository(), cdl.path, skipReadOnlyMetadata );
            }
            catch ( IndyWorkflowException e )
            {
                logger.error( String.format( "Failed to retrieve archive %s path: %s from koji build: %s. Reason: %s",
                                             cdl.archive.getArchiveId(), cdl.path, buildRemote.getBuild().getNvr(), e.getMessage() ),
                              e );

                // if the download fails, let's retry.
                retry = true;
            }

            // if download succeeds, we need to verify it and then transfer it to the consolidation repo.
            if ( downloaded != null )
            {
                retry = attemptPathConsolidation( downloaded, cdl, buildRemote, consolidationTargetRepo, skipReadOnlyMetadata );
            }

            // we can only retry if our retry count in this consolidation entry hasn't exceeded the max retry count.
            // if it has, consolidation has failed.
            if ( retry && cdl.incrementTries() < MAX_CONSOLIDATION_RETRIES )
            {
                return cdl;
            }
            else
            {
                if ( retry )
                {
                    logger.error( String.format(
                            "Path: %s marked for retry, but maximum retry count: %s has been exceeded! Consolidation has failed for: %s",
                            cdl.path, MAX_CONSOLIDATION_RETRIES, buildRemote.getBuild().getNvr() ) );

                    buildRemote.getRemoteRepository().setMetadata( KOJI_CONSOLIDATION_RESULT, Boolean.FALSE.toString() );
                }

                return null;
            }

        };
    }

    private boolean attemptPathConsolidation( final Transfer downloaded, final ConsolidationDownload cdl,
                                              final KojiBuildInfoWithRemote buildRemote,
                                              final ArtifactStore consolidationTargetRepo,
                                              final EventMetadata skipReadOnlyMetadata )
    {
        boolean retry = false;

        // we calculate the digest information as the download happens, enabling us to verify it immediately.
        TransferMetadata digesterMetadata = digester.getContentMetadata( downloaded );
        String calculatedChecksum = digesterMetadata.getDigests().get( KOJI_CHECKSUM_TYPE );

        // if it checks out, let's consolidate it.
        if ( calculatedChecksum.equals( cdl.archive.getChecksum() ) )
        {
            try (InputStream in = downloaded.openInputStream())
            {
                Transfer consolidated = contentManager.store( consolidationTargetRepo, cdl.path, in, TransferOperation.UPLOAD,
                                                              skipReadOnlyMetadata );

                // if consolidation appears to succeed, let's verify the checksum on that
                if ( consolidated != null )
                {
                    digesterMetadata = digester.getContentMetadata( consolidated );
                    String consolidatedChecksum = digesterMetadata.getDigests().get( KOJI_CHECKSUM_TYPE );

                    if ( !calculatedChecksum.equals( consolidatedChecksum ) )
                    {
                        // if the checksum doesn't match, we'll delete and retry. We may have been interrupted.
                        logger.error( String.format(
                                "Calculated checksum for consolidation target is: '%s', but as downloaded it was: '%s'. Consolidation failed!",
                                consolidatedChecksum, calculatedChecksum ) );

                        consolidated.delete( false );
                    }
                }
            }
            catch ( IOException | IndyWorkflowException e )
            {
                logger.error( String.format(
                        "Failed to store pre-fetched path: %s from Koji build: %s to consolidation target: %s. Reason: %s",
                        cdl.path, buildRemote.getBuild().getNvr(), consolidationTargetRepo.getKey(), e.getMessage() ), e );

                retry = true;
            }
        }
        else
        {
            logger.error( String.format(
                    "Calculated checksum for: %s in build %s is: '%s', but Koji reports it as: '%s'. Cannot transfer!",
                    cdl.path, buildRemote.getBuild().getNvr(), calculatedChecksum, cdl.archive.getChecksum() ) );

            try
            {
                // if the downloaded file's checksum doesn't match the koji archive info, delete and we'll retry.
                downloaded.delete( false );
            }
            catch ( IOException e )
            {
                logger.error(
                        String.format( "Failed to delete corrupted path: %s from Koji build: %s. Reason: %s", cdl.path,
                                       buildRemote.getBuild().getNvr(), e.getMessage() ), e );
            }

            retry = true;
        }

        return retry;
    }

}

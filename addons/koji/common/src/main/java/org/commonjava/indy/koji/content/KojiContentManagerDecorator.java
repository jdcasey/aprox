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
package org.commonjava.indy.koji.content;

import com.redhat.red.build.koji.KojiClientException;
import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildState;
import com.redhat.red.build.koji.model.xmlrpc.KojiSessionInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiTagInfo;
import org.commonjava.cdi.util.weft.Locker;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.audit.ChangeSummary;
import org.commonjava.indy.content.ContentManager;
import org.commonjava.indy.content.index.ContentIndexManager;
import org.commonjava.indy.core.inject.GroupMembershipLocks;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.koji.conf.IndyKojiConfig;
import org.commonjava.indy.koji.util.KojiUtils;
import org.commonjava.propulsor.metrics.annotation.Measure;
import org.commonjava.indy.measure.annotation.MetricNamed;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.atlas.maven.ident.ref.ArtifactRef;
import org.commonjava.atlas.maven.ident.util.ArtifactPathInfo;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.spi.nfc.NotFoundCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.decorator.Decorator;
import javax.decorator.Delegate;
import javax.inject.Inject;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.commonjava.indy.koji.model.IndyKojiConstants.KOJI_ORIGIN;
import static org.commonjava.indy.koji.model.IndyKojiConstants.KOJI_ORIGIN_BINARY;
import static org.commonjava.indy.measure.annotation.MetricNamed.DEFAULT;
import static org.commonjava.indy.model.core.StoreType.group;
import static org.commonjava.maven.galley.maven.util.ArtifactPathUtils.formatMetadataPath;

/**
 * {@link ContentManager} decorator that watches the retrieve() methods. If the result is going to be a null {@link Transfer}
 * this decorator will attempt the following:
 * <ol>
 *     <li>Parse the path into a GAV</li>
 *     <li>Lookup matching builds in Koji</li>
 *     <li>Sort the builds by creation timestamp, with earliest Date first.</li>
 *     <li>Iterate through the builds until we find a build we can proxy:
 *       <ol>
 *           <li>List the tags that contain the build</li>
 *           <li>Check that the tag matches a {@link IndyKojiConfig} tag-whitelist pattern</li>
 *           <li>If the tag is accepted, format the storage URL to the build and create a {@link RemoteRepository} to proxy it.</li>
 *       </ol>
 *     </li>
 *     <li>If we have a proxied build repository from above, lookup the target group whose membership should be modified, based on the entrypoint group's name (using {@link IndyKojiConfig} target-groups</li>
 *     <li>Modify the target group's membership. Store the remote repository and the target group.</li>
 *     <li>Attempt to retrieve the requested path from the new proxy repository.</li>
 *     <li>Return the results.</li>
 * </ol>
 *
 * <b>NOTE:</b> Currently we're not attempting to wrap retrieveAll() or retrieveFirst() methods, since these are only
 * used by the PromotionValidationTools class, which exposes methods to promotion validation scripts. This is not the
 * place to be adding new Koji build proxies...
 *
 * Created by jdcasey on 5/20/16.
 */
@Decorator
public abstract class KojiContentManagerDecorator
        implements ContentManager
{
    private Logger logger = LoggerFactory.getLogger( KojiContentManagerDecorator.class.getName() );

    public static final String CREATION_TRIGGER_GAV = "creation-trigger-GAV";

    private static final String NVR = "koji-NVR";

    @Delegate
    @Inject
    private ContentManager delegate;

    @Inject
    private StoreDataManager storeDataManager;

    @Inject
    private IndyKojiContentProvider kojiContentProvider;

    @Inject
    private KojiUtils kojiUtils;

    @Inject
    private IndyKojiConfig config;

    @Inject
    private NotFoundCache nfc;

    @Inject
    private KojiBuildAuthority buildAuthority;

    @Inject
    private ContentIndexManager indexManager;

    @GroupMembershipLocks
    @Inject
    private Locker<StoreKey> groupMembershipLocker;

    @Inject
    private KojiPathPatternFormatter pathFormatter;

    @Override
    @Measure( timers = @MetricNamed( DEFAULT ) )
    public boolean exists( ArtifactStore store, String path )
            throws IndyWorkflowException
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.debug( "KOJI: Delegating initial existence check for: {}/{}", store.getKey(), path );
        boolean result = delegate.exists( store, path );
        if ( !result && kojiUtils.isVersionSignatureAllowedWithPath( path ) && StoreType.group == store.getKey().getType() )
        {
            Group group = (Group) store;

            logger.info( "KOJI: Checking whether Koji contains a build matching: {}", path );
            RemoteRepository kojiProxy = findKojiBuildAnd( store, path, new EventMetadata(), null, this::createRemoteRepository );
            if ( kojiProxy != null )
            {
                adjustTargetGroup( kojiProxy, group );
                result = delegate.exists( kojiProxy, path );
            }

            if ( result )
            {
                nfc.clearMissing( new ConcreteResource( LocationUtils.toLocation( store ), path ) );
            }
        }

        return result;
    }

    @Override
    public Transfer retrieve( final ArtifactStore store, final String path )
            throws IndyWorkflowException
    {
        return retrieve( store, path, new EventMetadata() );
    }

    @Override
    @Measure( timers = @MetricNamed( DEFAULT ) )
    public Transfer retrieve( final ArtifactStore store, final String path, final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.debug( "KOJI: Delegating initial retrieval attempt for: {}/{}", store.getKey(), path );
        Transfer result = delegate.retrieve( store, path, eventMetadata );
        if ( result == null && kojiUtils.isVersionSignatureAllowedWithPath( path ) && StoreType.group == store.getKey().getType() )
        {
            logger.info( "KOJI: Checking for Koji build matching: {}", path );
            Group group = (Group) store;

            RemoteRepository kojiProxy = findKojiBuildAnd( store, path, eventMetadata, null, this::createRemoteRepository );
            if ( kojiProxy != null )
            {
                adjustTargetGroup( kojiProxy, group );
                result = delegate.retrieve( kojiProxy, path, eventMetadata );
            }

            if ( result != null )
            {
                nfc.clearMissing( new ConcreteResource( LocationUtils.toLocation( store ), path ) );
            }
        }

        // Finally, pass the Transfer back.
        return result;
    }

    @Override
    public Transfer getTransfer( StoreKey storeKey, String path, TransferOperation op )
            throws IndyWorkflowException
    {
        ArtifactStore store;
        try
        {
            store = storeDataManager.getArtifactStore( storeKey );
        }
        catch ( IndyDataException e )
        {
            throw new IndyWorkflowException( "Cannot retrieve artifact store definition for: %s. Reason: %s", e,
                                             storeKey, e.getMessage() );
        }

        if ( store == null )
        {
            Logger logger = LoggerFactory.getLogger( getClass() );
            logger.warn( "No such store: {} (while retrieving transfer for path: {} (op: {})", storeKey, path, op );
            return null;
        }

        return getTransfer( store, path, op );
    }

    @Override
    public Transfer getTransfer( final ArtifactStore store, final String path, final TransferOperation operation )
            throws IndyWorkflowException
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.debug( "KOJI: Delegating initial getTransfer() attempt for: {}/{}", store.getKey(), path );
        Transfer result = delegate.getTransfer( store, path, operation );
        if ( result == null && kojiUtils.isVersionSignatureAllowedWithPath( path ) && TransferOperation.DOWNLOAD == operation && StoreType.group == store.getKey().getType() )
        {
            logger.info( "KOJI: Checking for Koji build matching: {}", path );
            Group group = (Group) store;

            RemoteRepository kojiProxy = findKojiBuildAnd( store, path, new EventMetadata(), null, this::createRemoteRepository );
            if ( kojiProxy != null )
            {
                adjustTargetGroup( kojiProxy, group );

                EventMetadata eventMetadata =
                        new EventMetadata().set( ContentManager.ENTRY_POINT_STORE, store.getKey() );
                result = delegate.retrieve( kojiProxy, path, eventMetadata );
            }

            if ( result != null && result.exists() )
            {
                nfc.clearMissing( new ConcreteResource( LocationUtils.toLocation( store ), path ) );
            }
        }

        // Finally, pass the Transfer back.
        return result;
    }

    @Measure( timers = @MetricNamed( DEFAULT ), exceptions = @MetricNamed( DEFAULT ) )
    private <T> T findKojiBuildAnd( ArtifactStore store, String path, EventMetadata eventMetadata, T defValue, KojiBuildAction<T> action )
            throws IndyWorkflowException
    {
        if ( !config.getEnabled() )
        {
            Logger logger = LoggerFactory.getLogger( getClass() );
            logger.debug( "Koji content-manager decorator is disabled." );
            logger.debug("When koji addon is disenabled , path:{},config instance is {}",path,config.toString());
            return defValue;
        }

        Group group = (Group) store;

        if ( !config.isEnabledFor( group.getName() ) )
        {
            Logger logger = LoggerFactory.getLogger( getClass() );
            logger.debug( "Koji content-manager decorator not enabled for: {}.", store.getKey() );
            logger.debug("When the group is disenabled , path:{},config instance is {}",path,config.toString());
            return defValue;
        }

        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.debug("When the koji is enabled , path:{},config instance is {}",path,config.toString());

        // TODO: This won't work for maven-metadata.xml files! We need to hit a POM or jar or something first.
        // FIXME: This won't work for NPM!
        ArtifactPathInfo pathInfo = ArtifactPathInfo.parse( path );
        if ( pathInfo != null )
        {
            ArtifactRef artifactRef = pathInfo.getArtifact();
            logger.info( "Searching for Koji build: {}", artifactRef );

            return proxyKojiBuild( store.getKey(), artifactRef, path, eventMetadata, defValue, action );
        }
        else
        {
            logger.info( "Path is not a maven artifact reference: {}", path );
        }

        return defValue;
    }

    private <T> T proxyKojiBuild( final StoreKey inStore, final ArtifactRef artifactRef, final String path,
                                  EventMetadata eventMetadata, T defValue, KojiBuildAction<T> consumer )
            throws IndyWorkflowException
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        try
        {
//            return kojiClient.withKojiSession( ( session ) -> {
            KojiSessionInfo session = null;

                List<KojiBuildInfo> builds = kojiContentProvider.listBuildsContaining( artifactRef, session );

                Collections.sort( builds, ( build1, build2 ) -> build2.getCreationTime()
                                                                      .compareTo( build1.getCreationTime() ) );

                logger.debug( "Got {} builds from koji. Looking for best match.", builds.size() );

                Map<Integer, List<KojiTagInfo>> tagsMap = getTagsByBuildIds( builds, session ); // use multicall

                for ( KojiBuildInfo build : builds )
                {
                    if ( build.getBuildState() != KojiBuildState.COMPLETE )
                    {
                        logger.debug( "Build: {} is not completed. The state is {}. Skipping.",
                                      build.getNvr(), build.getBuildState() );
                        continue;
                    }

                    boolean buildAllowed = false;
                    if ( kojiUtils.isBinaryBuild(build) )
                    {
                        // This is not a real build, it's a binary import.
                        if ( config.isProxyBinaryBuilds() )
                        {
                            logger.info("Trying binary build: {} with id: {}", build.getNvr(), build.getId());
                            buildAllowed = true;
                        }
                        else
                        {
                            logger.debug("Skipping binary build: {} with id: {}", build.getNvr(), build.getId());
                        }
                    }
                    else
                    {
                        logger.info("Trying build: {} with id: {}", build.getNvr(), build.getId());
                        if ( !config.isTagPatternsEnabled() )
                        {
                            buildAllowed = true;
                        }
                        else
                        {
                            List<KojiTagInfo> tags = tagsMap.get( build.getId() );
                            logger.debug( "Build {} is in {} tags.", build.getId(), tags.size() );
                            if ( logger.isTraceEnabled() )
                            {
                                logTagsForBuild( build.getId(), tags );
                            }

                            for (KojiTagInfo tag : tags) {
                                // If the tags match patterns configured in whitelist, construct a new remote repo.
                                if (config.isTagAllowed(tag.getName())) {
                                    logger.info("Koji tag is on whitelist: {}", tag.getName());
                                    buildAllowed = true;
                                    break;
                                } else {
                                    logger.debug("Tag: {} is not in the whitelist.", tag.getName());
                                }
                            }
                        }
                    }

                    if (buildAllowed) {
                        // If the authoritative store is not configured, one or both systems is missing MD5 information,
                        // or the artifact matches the one in the authoritative store, go ahead.
                        if (buildAuthority.isAuthorized(path, eventMetadata, artifactRef, build, session)) {
                            return consumer.execute(inStore, artifactRef, build, session);
                        }
                    } else {
                        logger.debug("No whitelisted tags found for: {}", build.getNvr());
                    }
                }

                logger.trace( "No builds were found that matched the restrictions." );

                return defValue;
//            } );
        }
        catch ( KojiClientException e )
        {
            throw new IndyWorkflowException( "Cannot retrieve builds for: %s. Error: %s", e, artifactRef,
                                             e.getMessage() );
        }
    }

    private void logTagsForBuild( int id, List<KojiTagInfo> tags )
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        List<String> tagNames = new ArrayList<>();
        for ( KojiTagInfo t : tags )
        {
            tagNames.add( t.getName() );
        }
        logger.trace( "Tags for build {}: {}", id, tagNames );
    }

    private Map<Integer,List<KojiTagInfo>> getTagsByBuildIds( List<KojiBuildInfo> builds, KojiSessionInfo session )
                    throws KojiClientException
    {
        if ( !builds.isEmpty() )
        {
            List<Integer> buildIds = new ArrayList<>( );
            for ( KojiBuildInfo b : builds )
            {
                buildIds.add( b.getId() );
            }
            return kojiContentProvider.listTags( buildIds, session );
        }
        return Collections.EMPTY_MAP;
    }

    private RemoteRepository createRemoteRepository( StoreKey inStore, ArtifactRef artifactRef, final KojiBuildInfo build,
                                                     final KojiSessionInfo session )
            throws KojiClientException
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        try
        {
            List<KojiArchiveInfo> archives = kojiContentProvider.listArchivesForBuild( build.getId(), session );

            boolean isBinaryBuild = kojiUtils.isBinaryBuild( build );
            String name = kojiUtils.getRepositoryName( build );
            StoreKey remoteKey = new StoreKey( inStore.getPackageType(), StoreType.remote, name );

            RemoteRepository remote = ( RemoteRepository ) storeDataManager.getArtifactStore( remoteKey );
            if ( remote == null )
            {
                // Using a RemoteRepository allows us to use the higher-level APIs in Indy, as opposed to TransferManager
                final KojiRepositoryCreator creator = kojiUtils.createRepoCreator();

                if ( creator == null )
                {
                    throw new KojiClientException( "Cannot proceed without a valid KojiRepositoryCreator instance." );
                }

                remote = creator.createRemoteRepository( inStore.getPackageType(), name,
                                                         kojiUtils.formatStorageUrl( config.getStorageRootUrl(), build ),
                                                         config.getDownloadTimeoutSeconds() );

                remote.setServerCertPem( config.getServerPemContent() );

                if ( isBinaryBuild )
                {
                    remote.setMetadata( ArtifactStore.METADATA_ORIGIN, KOJI_ORIGIN_BINARY );
                }
                else
                {
                    remote.setMetadata( ArtifactStore.METADATA_ORIGIN, KOJI_ORIGIN );
                }

                // set pathMaskPatterns using build output paths
                Set<String> patterns = pathFormatter.getPatterns( inStore, artifactRef, archives );

                // pre-index the koji build artifacts and set authoritative index of the remote to let the
                // koji remote repo directly go through the content index
                patterns.forEach( path->indexManager.indexPathInStores( path, remoteKey ) );
                remote.setAuthoritativeIndex( true );
                remote.setPathMaskPatterns( patterns );

                remote.setMetadata( CREATION_TRIGGER_GAV, artifactRef.toString() );
                remote.setMetadata( NVR, build.getNvr() );

                final ChangeSummary changeSummary = new ChangeSummary( ChangeSummary.SYSTEM_USER,
                                                                       "Creating remote repository for Koji build: " + build
                                                                               .getNvr() );

                storeDataManager.storeArtifactStore( remote, changeSummary, false, true, new EventMetadata() );
                logger.info( "Koji {}: Set path masks {} with artifact {} to repo: {}", name, patterns, artifactRef, remote );
//                logger.debug( "Koji {}, add pathMaskPatterns: {}", name, patterns );
            }
            else
            {
                if ( remote.isDisabled() )
                {
                    logger.info( "Remote repository {} already exists, but is currently disabled. Returning null.",
                                 remoteKey );
                    remote = null;
                }
                else
                {
                    // this is a strange situation - delegate did not find the requested resource while Koji claims it
                    // is contained in this repo and it already exists and is enabled. Possibly corrupted list of path
                    // mask patterns set on the repo or content index
                    logger.warn( "Remote repository {} already exists. Using it as is.", remoteKey );
                }
            }

            return remote;
        }
        catch ( MalformedURLException e )
        {
            throw new KojiClientException( "Koji add-on seems misconifigured. Could not generate URL to repo for "
                                                   + "build: %s\nBase URL: %s\nError: %s", e, build.getNvr(),
                                           config.getStorageRootUrl(), e.getMessage() );
        }
        catch ( IOException e )
        {
            throw new KojiClientException(
                    "Failed to read server SSL PEM information from Koji configuration for new hosted repo: %s", e,
                    e.getMessage() );
        }
        catch ( IndyDataException e )
        {
            throw new KojiClientException( "Failed to store temporary remote repo: %s", e, e.getMessage() );
        }
    }

    private Group adjustTargetGroup( final RemoteRepository buildRepo, final Group srcGroup )
            throws IndyWorkflowException
    {
        Logger logger = LoggerFactory.getLogger( getClass() );

        // try to lookup the group -> targetGroup mapping in config, using the
        // entry-point group as the lookup key. If that returns null, the targetGroup is
        // the entry-point group.
        boolean isBinaryBuild = KOJI_ORIGIN_BINARY.equals( buildRepo.getMetadata( ArtifactStore.METADATA_ORIGIN) );

        String targetName = isBinaryBuild ? config.getTargetBinaryGroup( srcGroup.getName() )
                : config.getTargetGroup( srcGroup.getName() );

        StoreKey targetKey = srcGroup.getKey();

        if ( targetName != null )
        {
            targetKey = new StoreKey( srcGroup.getPackageType(), group, targetName );
        }

        StoreKey tk = targetKey;
        AtomicReference<IndyWorkflowException> wfEx = new AtomicReference<>();

        Group result = groupMembershipLocker.lockAnd( targetKey, config.getLockTimeoutSeconds(), k->{
            Group targetGroup = null;
            try
            {
                targetGroup = (Group) storeDataManager.getArtifactStore( tk );
            }
            catch ( IndyDataException e )
            {
                wfEx.set( new IndyWorkflowException(
                        "Cannot lookup koji-addition target group: %s (source group: %s). Reason: %s", e, targetName,
                        srcGroup.getName(), e.getMessage() ) );

                return null;
            }

            logger.info( "Adding Koji build proxy: {} to group: {}", buildRepo.getKey(), targetGroup.getKey() );

            // Append the new remote repo as a member of the targetGroup.
            targetGroup.addConstituent( buildRepo );
            try
            {
                final ChangeSummary changeSummary = new ChangeSummary( ChangeSummary.SYSTEM_USER,
                                                                       "Adding remote repository for Koji build: "
                                                                               + buildRepo.getMetadata( NVR ) );

                storeDataManager.storeArtifactStore( targetGroup, changeSummary, false, true, new EventMetadata() );
            }
            catch ( IndyDataException e )
            {
                wfEx.set( new IndyWorkflowException( "Cannot store target-group: %s changes for: %s. Error: %s", e,
                                                        targetGroup.getName(), buildRepo.getMetadata( NVR ),
                                                        e.getMessage() ) );
                return null;
            }

            logger.info( "Retrieving GAV: {} from: {}", buildRepo.getMetadata( CREATION_TRIGGER_GAV ), buildRepo );

            return targetGroup;
        }, (k, lock)->{
            return false;
        } );

        IndyWorkflowException ex = wfEx.get();
        if ( ex != null )
        {
            throw ex;
        }

        // TODO: how to index it for the group...?
        return result;
    }

    private interface KojiBuildAction<T>
    {
        T execute( StoreKey inStore, ArtifactRef artifactRef, KojiBuildInfo build, KojiSessionInfo session )
                throws KojiClientException;
    }

}

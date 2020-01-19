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

import com.redhat.red.build.koji.KojiClient;
import com.redhat.red.build.koji.KojiClientException;
import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildArchiveCollection;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiSessionInfo;
import org.apache.commons.io.IOUtils;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.content.ContentDigester;
import org.commonjava.indy.content.DirectContentAccess;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.koji.conf.IndyKojiConfig;
import org.commonjava.propulsor.metrics.annotation.Measure;
import org.commonjava.indy.measure.annotation.MetricNamed;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.subsys.infinispan.CacheProducer;
import org.commonjava.atlas.maven.ident.ref.ProjectRef;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.io.checksum.ContentDigest;
import org.commonjava.maven.galley.io.checksum.TransferMetadata;
import org.commonjava.maven.galley.maven.spi.type.TypeMapper;
import org.commonjava.maven.galley.maven.util.ArtifactPathUtils;
import org.commonjava.maven.galley.model.Transfer;
import org.infinispan.manager.DefaultCacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static java.lang.Boolean.TRUE;
import static org.apache.commons.lang.StringUtils.contains;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.apache.commons.lang.StringUtils.trimToEmpty;
import static org.commonjava.indy.measure.annotation.MetricNamed.DEFAULT;
import static org.commonjava.maven.galley.io.ChecksummingTransferDecorator.FORCE_CHECKSUM;

/**
 * Created by jdcasey on 1/4/17.
 *
 * Implements authoritative artifact checking using checksums against some authoritative repository. If no authoritative
 * store is configured, all builds are treated as authoritative and cleared for use.
 */
@ApplicationScoped
public class KojiBuildAuthority
{

    private static final List<String> EXCLUDED_FILE_ENDINGS = Collections.unmodifiableList(
            Arrays.asList( "-sources.zip", "-patches.zip", "-sources.jar", "-javadoc.jar" ) );

    @Inject
    private IndyKojiConfig config;

    @Inject
    private TypeMapper typeMapper;

    @Inject
    private IndyKojiContentProvider kojiContentProvider;

    @Inject
    private StoreDataManager storeDataManager;

    @Inject
    private ContentDigester contentDigester;

    @Inject
    private DirectContentAccess directContentAccess;

    protected KojiBuildAuthority(){}

    public KojiBuildAuthority( IndyKojiConfig config, TypeMapper typeMapper, KojiClient kojiClient,
                               StoreDataManager storeDataManager, ContentDigester contentDigester,
                               DirectContentAccess directContentAccess, DefaultCacheManager cacheManager )
    {
        this.config = config;
        this.typeMapper = typeMapper;
        this.kojiContentProvider = new IndyKojiContentProvider( kojiClient, new CacheProducer( null, cacheManager, null ) );
        this.storeDataManager = storeDataManager;
        this.contentDigester = contentDigester;
        this.directContentAccess = directContentAccess;
    }

    /**
     * Enum used to sort the order of preference for archives coming from Koji for a give build.
     */
    public enum TypePriority
    {
        jar,
        other,
        xml,
        pom;

        public static TypePriority get( String type )
        {
            for ( TypePriority tp : values() )
            {
                if ( tp.name().equalsIgnoreCase( type ) )
                {
                    return tp;
                }
            }

            return other;
        }
    }

    public boolean isAuthorized( String path, EventMetadata eventMetadata, ProjectRef ref, KojiBuildInfo build,
                                 KojiSessionInfo session )
            throws KojiClientException
    {
        return isAuthorized( path, eventMetadata, ref, build, session, new HashMap<>() );
    }

    @Measure( timers = @MetricNamed( DEFAULT ) )
    public boolean isAuthorized( String path, EventMetadata eventMetadata, ProjectRef ref, KojiBuildInfo build,
                                 KojiSessionInfo session, Map<Integer, KojiBuildArchiveCollection> seenBuildArchives )
            throws KojiClientException
    {
        ArtifactStore authoritativeStore = getAuthoritativeStore();

        if ( authoritativeStore != null )
        {
            KojiBuildArchiveCollection archiveCollection = seenBuildArchives.get( build.getId() );
            if ( archiveCollection == null )
            {
                List<KojiArchiveInfo> archiveList = kojiContentProvider.listArchivesForBuild( build.getId(), session );
                archiveCollection = new KojiBuildArchiveCollection( build, archiveList );
                seenBuildArchives.put( build.getId(), archiveCollection );
            }

            if ( archiveCollection == null )
            {
                throw new KojiClientException( "Failed to retrieve archives for build: %s", build );
            }

            // @formatter:off
            Predicate<KojiArchiveInfo> archiveInfoFilter = ( archive ) -> EXCLUDED_FILE_ENDINGS.stream()
                                                                                               .allMatch( ending -> !archive.getFilename().endsWith( ending ) );
            List<KojiArchiveInfo> sortedArchives = archiveCollection.getArchives()
                                                                    .stream()
                                                                    // filter out excluded filename endings.
                                                                    .filter( archiveInfoFilter )
                                                                    // sort so jars are favored first, then poms, then everything else.
                                                                    .sorted( ( a1, a2 ) -> {
                                                                        TypePriority t1 = TypePriority.get( a1.getExtension() );
                                                                        TypePriority t2 = TypePriority.get( a2.getExtension() );

                                                                        return Integer.valueOf( t1.ordinal() )
                                                                                      .compareTo( t2.ordinal() );
                                                                    } )
                                                                    // make a list of the result
                                                                    .collect( Collectors.toList() );
            // @formatter:on

            for ( KojiArchiveInfo archive : sortedArchives )
            {
                try
                {
                    if ( isMavenArtifact( archive ) )
                    {
                        // skip non-Maven artifacts
                        continue;
                    }

                    if ( containsPlaceholders( archive ) )
                    {
                        return false;
                    }

                    String artifactPath = ArtifactPathUtils.formatArtifactPath( archive.asArtifact(), typeMapper );
                    String md5 = checksumArtifact( authoritativeStore, artifactPath, eventMetadata );
                    if ( isNotBlank( md5 ) )
                    {
                        //FIXME: not sure if all koji archives are using md5 as checksum type for maven build
                        String kojiMd5 = archive.getChecksum();

                        Logger logger = LoggerFactory.getLogger( getClass() );
                        logger.info(
                                "Checking checksum for {} (path: {}) in auth store {}, auth store checksum:{}, koji build check sum:{}",
                                ref, path, authoritativeStore, md5, kojiMd5 );

                        if ( !md5.equals( kojiMd5 ) )
                        {
                            // if checksum is not the same, it means the artifact in koji is DIFFERENT from the one in the authoritative store. Reject this.
                            return false;
                        }
                    }
                }
                catch ( Exception e )
                {
                    Logger logger = LoggerFactory.getLogger( getClass() );
                    logger.error( "SHOULD NEVER HAPPEN: Failed to transform artifact to path: " + e.getMessage(), e );
                }
            }
        }

        return true;
    }

    /**
     * Checks if the given archive is a Maven artifact, i.e. has artifactId and groupId set. If one of those is
     * missing, it is not considered to be a Maven artifact.
     */
    private boolean isMavenArtifact( final KojiArchiveInfo archive )
    {
        return isNotBlank( archive.getArtifactId() ) && isNotBlank( archive.getGroupId() );
    }

    /**
     * Checks if the given archive GAV contains unreplaced placeholders, e.g. "${parent.version}" in the version field.
     */
    private boolean containsPlaceholders( final KojiArchiveInfo archive )
    {
        return contains( trimToEmpty( archive.getArtifactId() ), "${" )
                || contains( trimToEmpty( archive.getGroupId() ), "${" )
                || contains( trimToEmpty( archive.getVersion() ), "${" );
    }

    private ArtifactStore getAuthoritativeStore()
    {
        if ( config.getArtifactAuthorityStore() != null )
        {
            final StoreKey authStoreKey = StoreKey.fromString( config.getArtifactAuthorityStore() );
            try
            {
                return storeDataManager.getArtifactStore( authStoreKey );
            }
            catch ( IndyDataException e )
            {
                Logger logger = LoggerFactory.getLogger( getClass() );
                logger.warn( "Error occurred when finding authoritative store for {}, error message: {}", authStoreKey,
                             e.getMessage() );
            }
        }
        return null;
    }

    private String checksumArtifact( ArtifactStore store, String path, EventMetadata eventMetadata )
    {
        final Logger logger = LoggerFactory.getLogger( getClass() );
        try
        {
            if ( directContentAccess.exists( store, path ) )
            {
                String md5Path = path + ".md5";
                Transfer md5 = directContentAccess.retrieveRaw( store, md5Path, eventMetadata );
                if ( md5 != null && md5.exists() )
                {
                    try (InputStream in = md5.openInputStream( true ))
                    {
                        return IOUtils.toString( in ).trim();
                    }
                    catch ( IOException e )
                    {
                        logger.warn( "Error reading MD5 checksum for transfer of path {} in store {}, error is {}",
                                     md5Path, store, e.getMessage() );
                    }
                }
                else
                {
                    EventMetadata forcedEventMetadata = new EventMetadata( eventMetadata ).set(FORCE_CHECKSUM, TRUE);
                    final TransferMetadata artifactData = contentDigester.digest( store.getKey(), path, forcedEventMetadata );
                    if ( artifactData != null )
                    {
                        return artifactData.getDigests().get( ContentDigest.MD5 );
                    }
                }
            }
        }
        catch ( IndyWorkflowException e )
        {
            logger.warn( "Error happened when calculate md5 checksum for transfer of path {} in store {}, error is {}",
                         path, store, e.getMessage() );
        }

        return null;
    }
}

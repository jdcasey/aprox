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
package org.commonjava.indy.core.ctl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.commonjava.indy.IndyRequestConstants;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.content.ContentManager;
import org.commonjava.indy.content.StoreResource;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.measure.annotation.Measure;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.model.core.dto.DirectoryListingDTO;
import org.commonjava.indy.model.galley.KeyedLocation;
import org.commonjava.indy.subsys.template.IndyGroovyException;
import org.commonjava.indy.subsys.template.TemplatingEngine;
import org.commonjava.indy.util.ApplicationContent;
import org.commonjava.indy.util.ApplicationStatus;
import org.commonjava.indy.util.MimeTyper;
import org.commonjava.indy.util.UriFormatter;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.transport.htcli.model.HttpExchangeMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.apache.commons.lang.StringUtils.isBlank;
import static org.commonjava.maven.galley.util.PathUtils.normalize;
import static org.commonjava.maven.galley.util.PathUtils.parentPath;

@ApplicationScoped
public class ContentController
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Deprecated
    public static final String LISTING_HTML_FILE = IndyRequestConstants.LISTING_HTML_FILE;

    public static final String CONTENT_BROWSE_ROOT = "/browse";

    public static final String CONTENT_BROWSE_API_ROOT = "/api/browse";

    public static final String[] BROWSER_USER_AGENT =
            new String[] { "Mozilla/", "Chrome/", "Safari/", "OPR/", "Trident/", "Gecko/", "AppleWebKit/" };

    private static final int MAX_PEEK_COUNT = 100;

    public static final String HTML_TAG_PATTERN = ".*\\<(!DOCTYPE|[-_.a-zA-Z0-9]+).*";

    public static final Pattern PATH_PATTERN = Pattern.compile( "^([-\\w:@&?=+,.!/~*'%$_;\\(\\)]*)?$");

    private static final int MAX_PEEK_BYTES = 16384;

    @Inject
    private StoreDataManager storeManager;

    @Inject
    private ContentManager contentManager;

    @Inject
    private TemplatingEngine templates;

    @Inject
    private ObjectMapper mapper;

    @Inject
    private MimeTyper mimeTyper;

    protected ContentController()
    {
    }

    public ContentController( final StoreDataManager storeManager, final ContentManager contentManager,
                              final TemplatingEngine templates, final ObjectMapper mapper, final MimeTyper mimeTyper )
    {
        this.storeManager = storeManager;
        this.contentManager = contentManager;
        this.templates = templates;
        this.mapper = mapper;
        this.mimeTyper = mimeTyper;
    }

    public ApplicationStatus delete( final StoreType type, final String name, final String path )
        throws IndyWorkflowException
    {
        return delete( type, name, path, new EventMetadata() );
    }

    public ApplicationStatus delete( final StoreType type, final String name, final String path,
                                     final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        return delete( new StoreKey( type, name ), path, eventMetadata );
    }

    public ApplicationStatus delete( final StoreKey key, final String path )
        throws IndyWorkflowException
    {
        return delete( key, path, new EventMetadata() );
    }

    public ApplicationStatus delete( final StoreKey key, final String path, final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        final ArtifactStore store = getStore( key );

        final boolean deleted = contentManager.delete( store, path, eventMetadata );
        return deleted ? ApplicationStatus.NO_CONTENT : ApplicationStatus.NOT_FOUND;
    }

    public Transfer get( final StoreKey key, final String path )
        throws IndyWorkflowException
    {
        return get( key, path, new EventMetadata() );
    }

    public Transfer get( final StoreKey key, final String path, final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        final ArtifactStore store = getStore( key );

        validatePath( key, path );
        return contentManager.retrieve( store, path, eventMetadata );
    }

    public String getContentType( final String path )
    {
        return mimeTyper.getContentType( path );
    }

    public Transfer store( final StoreType type, final String name, final String path, final InputStream stream )
        throws IndyWorkflowException
    {
        return store( type, name, path, stream, new EventMetadata() );
    }

    public Transfer store( final StoreType type, final String name, final String path, final InputStream stream,
                           final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        return store( new StoreKey( type, name ), path, stream, eventMetadata );
    }

    public Transfer store( final StoreKey key, final String path, final InputStream stream )
        throws IndyWorkflowException
    {
        return store( key, path, stream, new EventMetadata() );
    }

    @Measure
    public Transfer store( final StoreKey key, final String path, final InputStream stream,
                           final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        final ArtifactStore store = getStore( key );

        validatePath( key, path );
        logger.info( "Storing: {} in: {} with event metadata: {}", path, store, eventMetadata );

        return contentManager.store( store, path, stream, TransferOperation.UPLOAD, eventMetadata );
    }

    private void validatePath( final StoreKey key, final String path ) throws IndyWorkflowException
    {
        if ( !isValidPath( path ) )
        {
            throw new IndyWorkflowException( 400, "Invalid path: %s (target repo: %s)", path, key );
        }
    }

    boolean isValidPath( String path )
    {
        if ( isBlank( path ) )
        {
            return false;
        }
        if ( !PATH_PATTERN.matcher( path ).matches() )
        {
            return false;
        }
        return true;
    }

    public void rescan( final StoreKey key )
        throws IndyWorkflowException
    {
        rescan( key, new EventMetadata() );
    }

    public void rescan( final StoreKey key, final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        final ArtifactStore artifactStore = getStore( key );
        contentManager.rescan( artifactStore, eventMetadata );
    }

    public void rescanAll()
        throws IndyWorkflowException
    {
        rescanAll( new EventMetadata() );
    }

    public void rescanAll( final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        try
        {
            final List<ArtifactStore> stores = storeManager.query().concreteStores().getAll();
            contentManager.rescanAll( stores, eventMetadata );
        }
        catch ( final IndyDataException e )
        {
            throw new IndyWorkflowException( ApplicationStatus.SERVER_ERROR.code(),
                                              "Failed to retrieve list of concrete stores. Reason: {}", e,
                                              e.getMessage() );
        }
    }

    public void deleteAll( final String path )
        throws IndyWorkflowException
    {
        deleteAll( path, new EventMetadata() );
    }

    public void deleteAll( final String path, final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        try
        {
            final List<ArtifactStore> stores = storeManager.query().concreteStores().getAll();
            contentManager.deleteAll( stores, path, eventMetadata );
        }
        catch ( final IndyDataException e )
        {
            throw new IndyWorkflowException( ApplicationStatus.SERVER_ERROR.code(),
                                              "Failed to retrieve list of concrete stores. Reason: {}", e,
                                              e.getMessage() );
        }
    }

    public ArtifactStore getStore( final StoreKey key )
        throws IndyWorkflowException
    {
        ArtifactStore store;
        try
        {
            store = storeManager.getArtifactStore( key );
        }
        catch ( final IndyDataException e )
        {
            throw new IndyWorkflowException( ApplicationStatus.SERVER_ERROR.code(),
                                              "Cannot retrieve store: {}. Reason: {}", e, key, e.getMessage() );
        }

        if ( store == null )
        {
            throw new IndyWorkflowException( ApplicationStatus.NOT_FOUND.code(), "Cannot find store: {}", key );
        }

        if ( store.isDisabled() )
        {
            throw new IndyWorkflowException( ApplicationStatus.NOT_FOUND.code(), "Store is disabled: {}", key );
        }

        return store;
    }

    /**
     * @deprecated directory listing has been moved to addons/content-browse
     */
    @Deprecated
    public String renderListing( final String acceptHeader, final StoreType type, final String name, final String path,
                                 final String serviceUrl, final UriFormatter uriFormatter )
        throws IndyWorkflowException
    {
        final StoreKey key = new StoreKey( type, name );
        return renderListing( acceptHeader, key, path, serviceUrl, uriFormatter );
    }

    /**
     * @deprecated directory listing has been moved to addons/content-browse
     */
    @Deprecated
    public String renderListing( final String acceptHeader, final StoreKey key, final String requestPath,
                                 final String serviceUrl, final UriFormatter uriFormatter )
            throws IndyWorkflowException
    {
        return renderListing( acceptHeader, key, requestPath, serviceUrl, uriFormatter, new EventMetadata() );
    }

    /**
     * @deprecated directory listing has been moved to addons/content-browse
     */
    @Deprecated
    public String renderListing( final String acceptHeader, final StoreKey key, final String requestPath,
                                 final String serviceUrl, final UriFormatter uriFormatter, final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        String path = requestPath;
        if ( path.endsWith( LISTING_HTML_FILE ) )
        {
            path = normalize( parentPath( path ) );
        }

        validatePath( key, path );

        final List<StoreResource> listed = getListing( key, path, eventMetadata );
        if ( ApplicationContent.application_json.equals( acceptHeader ) )
        {
            final DirectoryListingDTO dto = new DirectoryListingDTO( StoreResource.convertToEntries( listed ) );
            try
            {
                return mapper.writeValueAsString( dto );
            }
            catch ( final JsonProcessingException e )
            {
                throw new IndyWorkflowException( "Failed to render listing to JSON: %s. Reason: %s", e, dto,
                                                  e.getMessage() );
            }
        }

        final Map<String, Set<String>> listingUrls = new TreeMap<>();

        final String storeUrl =
            uriFormatter.formatAbsolutePathTo( serviceUrl, key.getType()
                                                              .singularEndpointName(), key.getName() );

        if ( listed != null )
        {
            // first pass, process only obvious directory entries (ending in '/')
            // second pass, process the remainder.
            for ( int pass = 0; pass < 2; pass++ )
            {
                for ( final ConcreteResource res : listed )
                {
                    String p = res.getPath();
                    if ( pass == 0 && !p.endsWith( "/" ) )
                    {
                        continue;
                    }
                    if ( p.endsWith( "-" ) || p.endsWith( "-/" ) )
                    {
                        //skip npm adduser path to avoid the sensitive info showing.
                        continue;
                    }
                    else if ( pass == 1 )
                    {
                        if ( !p.endsWith( "/" ) )
                        {
                            final String dirpath = p + "/";
                            if ( listingUrls.containsKey( normalize( storeUrl, dirpath ) ) )
                            {
                                p = dirpath;
                            }
                        }
                        else
                        {
                            continue;
                        }
                    }

                    final String localUrl = normalize( storeUrl, p );
                    Set<String> sources = listingUrls.get( localUrl );
                    if ( sources == null )
                    {
                        sources = new HashSet<>();
                        listingUrls.put( localUrl, sources );
                    }

                    sources.add( normalize( res.getLocationUri(), res.getPath() ) );
                }
            }
        }

        final List<String> sources = new ArrayList<>();
        if ( listed != null )
        {
            for ( final ConcreteResource res : listed )
            {
                // KeyedLocation is all we use in Indy.
                logger.debug( "Formatting sources URL for: {}", res );
                final KeyedLocation kl = (KeyedLocation) res.getLocation();

                final String uri = uriFormatter.formatAbsolutePathTo( serviceUrl, kl.getKey()
                                                                                    .getType()
                                                                                    .singularEndpointName(), kl.getKey()
                                                                                                               .getName() );
                if ( !sources.contains( uri ) )
                {
                    logger.debug( "adding source URI: '{}'", uri );
                    sources.add( uri );
                }
            }
        }

        Collections.sort( sources );

        String parentPath = normalize( parentPath( path ) );
        if ( !parentPath.endsWith( "/" ) )
        {
            parentPath += "/";
        }

        final String parentUrl;
        if ( parentPath.equals( path ) )
        {
            parentPath = null;
            parentUrl = null;
        }
        else
        {
            parentUrl =
                uriFormatter.formatAbsolutePathTo( serviceUrl, key.getType()
                                                                  .singularEndpointName(), key.getName(), parentPath );
        }

        final Map<String, Object> params = new HashMap<>();
        params.put( "items", listingUrls );
        params.put( "parentUrl", parentUrl );
        params.put( "parentPath", parentPath );
        params.put( "path", path );
        params.put( "storeKey", key );
        params.put( "storeUrl", storeUrl );
        params.put( "baseUrl", serviceUrl );
        params.put( "sources", sources );

        // render...
        try
        {
            return templates.render( acceptHeader, "directory-listing", params );
        }
        catch ( final IndyGroovyException e )
        {
            throw new IndyWorkflowException( e.getMessage(), e );
        }
    }

    public List<StoreResource> getListing( final StoreType type, final String name, final String path )
        throws IndyWorkflowException
    {
        return getListing( new StoreKey( type, name ), path );
    }

    public List<StoreResource> getListing( final StoreKey key, final String path )
            throws IndyWorkflowException
    {
        return getListing( key, path, new EventMetadata() );
    }

    public List<StoreResource> getListing( final StoreKey key, final String path, final EventMetadata eventMetadata )
        throws IndyWorkflowException
    {
        validatePath( key, path );

        final ArtifactStore store = getStore( key );
        return contentManager.list( store, path, eventMetadata );
    }

    public boolean isHtmlContent( final Transfer item )
        throws IndyWorkflowException
    {
        final byte[] head = new byte[MAX_PEEK_BYTES];
        BufferedReader reader = null;
        InputStream raw = null;
        try
        {
            raw = item.openInputStream( false );
            final int read = raw.read( head );
            if ( read > 0 )
            {
                reader = new BufferedReader( new InputStreamReader( new ByteArrayInputStream( head ) ) );

                String line;
                int count = 0;
                while ( ( line = reader.readLine() ) != null && count < MAX_PEEK_COUNT )
                {
                    if ( line.matches( HTML_TAG_PATTERN ) )
                    {
                        return true;
                    }
                    count++;
                }
            }
        }
        catch ( final IOException e )
        {
            throw new IndyWorkflowException( "Cannot read: %s. Reason: %s", e, item, e.getMessage() );
        }
        finally
        {
            closeQuietly( raw );
            closeQuietly( reader );
        }

        return false;
    }

    public Transfer getTransfer( final StoreKey storeKey, final String path, final TransferOperation op )
        throws IndyWorkflowException
    {
        validatePath( storeKey, path );
        return contentManager.getTransfer( storeKey, path, op );
    }

    public HttpExchangeMetadata getHttpMetadata( final StoreKey storeKey, final String path )
        throws IndyWorkflowException
    {
        logger.debug( "Getting HTTP metadata for: {}/{}", storeKey, path );
        return contentManager.getHttpMetadata( storeKey, path );
    }

    public HttpExchangeMetadata getHttpMetadata( final Transfer txfr )
        throws IndyWorkflowException
    {
        return contentManager.getHttpMetadata( txfr );
    }

    public boolean exists(StoreKey sk, String path) throws IndyWorkflowException {
        validatePath( sk, path );
        return contentManager.exists( getStore( sk ), path );
    }
}

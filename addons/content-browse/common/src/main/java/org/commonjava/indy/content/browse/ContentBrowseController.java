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
package org.commonjava.indy.content.browse;

import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.content.ContentManager;
import org.commonjava.indy.content.StoreResource;
import org.commonjava.indy.content.browse.model.ContentBrowseResult;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.propulsor.metrics.annotation.Measure;
import org.commonjava.indy.measure.annotation.MetricNamed;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.galley.KeyedLocation;
import org.commonjava.indy.util.ApplicationStatus;
import org.commonjava.indy.util.UriFormatter;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.commonjava.indy.measure.annotation.MetricNamed.DEFAULT;
import static org.commonjava.maven.galley.util.PathUtils.normalize;
import static org.commonjava.maven.galley.util.PathUtils.parentPath;

@ApplicationScoped
public class ContentBrowseController
{
    private final Logger logger = LoggerFactory.getLogger( this.getClass() );

    @Inject
    private StoreDataManager storeManager;

    @Inject
    private ContentManager contentManager;

    protected ContentBrowseController()
    {
    }

    public ContentBrowseController( final StoreDataManager storeManager, final ContentManager contentManager )
    {
        this.storeManager = storeManager;
        this.contentManager = contentManager;
    }

    @Measure( timers = @MetricNamed( DEFAULT ), exceptions = @MetricNamed( DEFAULT ) )
    public ContentBrowseResult browseContent( final StoreKey storeKey, final String path, final String browseBaseUri,
                                              final String contentBaseUri, final UriFormatter uriFormatter,
                                              EventMetadata eventMetadata )
            throws IndyWorkflowException
    {

        final EventMetadata metadata = eventMetadata.set( ContentManager.ENTRY_POINT_STORE, storeKey );

        return renderResult( storeKey, PathUtils.normalize( path ), browseBaseUri, contentBaseUri, uriFormatter,
                             metadata );

    }

    private ContentBrowseResult renderResult( final StoreKey key, final String requestPath,
                                              final String browseServiceUrl, final String contentServiceUrl, final UriFormatter uriFormatter,
                                              final EventMetadata eventMetadata )
            throws IndyWorkflowException
    {
        String path = requestPath.endsWith( "/" ) ? requestPath : requestPath + "/";

        final ArtifactStore store = getStore( key );
        final List<StoreResource> listed = contentManager.list( store, path, eventMetadata );

        final Map<String, Set<String>> listingUrls = new TreeMap<>();

        final String storeBrowseUrl =
                uriFormatter.formatAbsolutePathTo( browseServiceUrl, key.getType().singularEndpointName(), key.getName() );

        final String storeContentUrl =
                uriFormatter.formatAbsolutePathTo( contentServiceUrl, key.getType().singularEndpointName(), key.getName() );

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
                            if ( listingUrls.containsKey( normalize( storeBrowseUrl, dirpath ) ) )
                            {
                                p = dirpath;
                            }
                        }
                        else
                        {
                            continue;
                        }
                    }

                    String localUrl;
                    if ( p.endsWith( "/" ) )
                    {
                        localUrl = normalize( storeBrowseUrl, p );
                    }
                    else
                    {
                        // So this means current path is a file not a directory, and needs to construct it to point to content api /api/content
                        localUrl = normalize( storeContentUrl, p );
                    }
                    Set<String> sources = listingUrls.computeIfAbsent( localUrl, k -> new HashSet<>() );

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

                final String uri = uriFormatter.formatAbsolutePathTo( browseServiceUrl, kl.getKey().getType().singularEndpointName(),
                                                         kl.getKey().getName() );
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
            parentUrl = uriFormatter.formatAbsolutePathTo( browseServiceUrl, key.getType().singularEndpointName(), key.getName(),
                                              parentPath );
        }

        final List<ContentBrowseResult.ListingURLResult> listingURLResults = new ArrayList<>( listingUrls.size() );

        for ( String localUrl : listingUrls.keySet() )
        {
            final String apiPath = localUrl.replace( storeBrowseUrl, "" ).replace( storeContentUrl, "" );

            listingURLResults.add(
                    new ContentBrowseResult.ListingURLResult( apiPath, localUrl, listingUrls.get( localUrl ) ) );
        }


        final ContentBrowseResult result = new ContentBrowseResult();
        result.setListingUrls( listingURLResults );
        result.setParentUrl( parentUrl );
        result.setParentPath( parentPath );
        result.setPath( path );
        result.setStoreKey( key );
        result.setStoreBrowseUrl( storeBrowseUrl );
        result.setStoreContentUrl( storeContentUrl );
        result.setBaseBrowseUrl( browseServiceUrl );
        result.setBaseContentUrl( contentServiceUrl );
        result.setSources( sources );

        return result;
    }

    private ArtifactStore getStore( final StoreKey key )
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

}

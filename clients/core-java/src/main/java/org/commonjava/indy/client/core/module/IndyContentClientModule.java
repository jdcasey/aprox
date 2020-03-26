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
package org.commonjava.indy.client.core.module;

import org.apache.commons.io.IOUtils;
import org.commonjava.indy.IndyContentConstants;
import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.client.core.IndyClientModule;
import org.commonjava.indy.client.core.helper.HttpResources;
import org.commonjava.indy.client.core.helper.PathInfo;
import org.commonjava.indy.client.core.util.UrlUtils;
import org.commonjava.indy.model.core.DemoteRequest;
import org.commonjava.indy.model.core.PackageTypes;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.model.core.dto.DirectoryListingDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

import static org.commonjava.indy.client.core.util.UrlUtils.buildUrl;
import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;

public class IndyContentClientModule
    extends IndyClientModule
{

    private static final String CONTENT_BASE = "content";

    @Deprecated
    public String contentUrl( final StoreType type, final String name, final String... path )
    {
        return contentUrl( new StoreKey( MAVEN_PKG_KEY, type, name ), path );
    }
    
    public String contentUrl( final StoreKey key, final String... path )
    {
        return buildUrl( http.getBaseUrl(), aggregatePathParts( key, path ) );
    }

    @Deprecated
    public String contentPath( final StoreType type, final String name, final String... path )
    {
        return contentPath( new StoreKey( MAVEN_PKG_KEY, type, name ), path );
    }
    
    public String contentPath( final StoreKey key, final String... path )
    {
        return buildUrl( null, aggregatePathParts( key, path ) );
    }

    /**
     * Please do not use this method to get list content, because new content browse is not using html but using json now,
     * so this method will return wrong result now. Please use {@link org.commonjava.indy.content.browse.client.IndyContentBrowseClientModule} instead.
     * This method will be removed in recent release.
     */
    @Deprecated
    public DirectoryListingDTO listContents( final StoreKey key, final String path )
            throws IndyClientException
    {
        String p = path;
        if ( !path.endsWith( "/" ) )
        {
            p += "/";
        }

        return http.get( contentPath( key, p ), DirectoryListingDTO.class );
    }

    /**
     * Please do not use this method to get list content, because new content browse is not using html but using json now,
     * so this method will return wrong result now. Please use {@link org.commonjava.indy.content.browse.client.IndyContentBrowseClientModule} instead.
     * This method will be removed in recent release.
     */
    @Deprecated
    public DirectoryListingDTO listContents( final StoreType type, final String name, final String path )
            throws IndyClientException
    {
        return listContents( new StoreKey( MAVEN_PKG_KEY, type, name ), path );
    }

    public void deleteCache( final StoreKey key, final String path ) // delete cached file for group/remote
                    throws IndyClientException
    {
        http.deleteCache( contentPath( key, path ) );
    }

    public void delete( final StoreKey key, final String path )
            throws IndyClientException
    {
        http.delete( contentPath( key, path ) );
    }

    public void demote( final StoreKey key, final DemoteRequest request )
            throws IndyClientException
    {
        http.postRaw( contentPath( key, "demote" ), request );
    }

    @Deprecated
    public void delete( final StoreType type, final String name, final String path )
            throws IndyClientException
    {
        delete( new StoreKey( MAVEN_PKG_KEY, type, name ), path );
    }

    public boolean exists( final StoreKey key, final String path )
            throws IndyClientException
    {
        return http.exists( contentPath( key, path ) );
    }

    public Boolean exists( StoreKey key, String path, boolean cacheOnly )
            throws IndyClientException
    {
        return http.exists( contentPath( key, path ),
                            () -> Collections.<String, String>singletonMap( IndyContentConstants.CHECK_CACHE_ONLY,
                                                                            Boolean.toString( cacheOnly ) ) );
    }

    @Deprecated
    public boolean exists( final StoreType type, final String name, final String path )
            throws IndyClientException
    {
        return exists( new StoreKey( MAVEN_PKG_KEY, type, name ), path );
    }
    
    @Deprecated
    public Boolean exists( StoreType type, String name, String path, boolean cacheOnly )
            throws IndyClientException
    {
        return exists( new StoreKey( MAVEN_PKG_KEY, type, name ), path, cacheOnly );
    }

    public void store( final StoreKey key, final String path, final InputStream stream )
            throws IndyClientException
    {
        http.putWithStream( contentPath( key, path ), stream );
    }

    @Deprecated
    public void store( final StoreType type, final String name, final String path, final InputStream stream )
            throws IndyClientException
    {
        store( new StoreKey( MAVEN_PKG_KEY, type, name ), path, stream );
    }

    public PathInfo getInfo( final StoreKey key, final String path )
        throws IndyClientException
    {
        final Map<String, String> headers = http.head( contentPath( key, path ) );
        return new PathInfo( headers );
    }

    @Deprecated
    public PathInfo getInfo( final StoreType type, final String name, final String path )
            throws IndyClientException
    {
        return getInfo( new StoreKey( MAVEN_PKG_KEY, type, name ), path );
    }
    
    public InputStream get( final StoreKey key, final String path )
            throws IndyClientException
    {
        final HttpResources resources = http.getRaw( contentPath( key, path ) );

        if ( resources.getStatusCode() != 200 )
        {
            IOUtils.closeQuietly( resources );
            if ( resources.getStatusCode() == 404 )
            {
                return null;
            }

            throw new IndyClientException( resources.getStatusCode(), "Response returned status: %s.",
                                           resources.getStatusLine() );
        }

        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.debug( "Returning stream that should contain: {} bytes", resources.getResponse().getFirstHeader( "Content-Length" ) );
        try
        {
            return resources.getResponseStream();
        }
        catch ( final IOException e )
        {
            IOUtils.closeQuietly( resources );
            throw new IndyClientException( "Failed to open response content stream: %s", e,
                                           e.getMessage() );
        }
    }

    @Deprecated
    public InputStream get( final StoreType type, final String name, final String path )
            throws IndyClientException
    {
        return get( new StoreKey( MAVEN_PKG_KEY, type, name ), path );
    }
    
    private String[] aggregatePathParts( final StoreKey key, final String... path )
    {
        final String[] parts = new String[path.length + 4];
        int i=0;
        parts[i++] = CONTENT_BASE;
        parts[i++] = key.getPackageType();
        parts[i++] = key.getType().singularEndpointName();
        parts[i++] = key.getName();
        System.arraycopy( path, 0, parts, 4, path.length );

        return parts;
    }

}

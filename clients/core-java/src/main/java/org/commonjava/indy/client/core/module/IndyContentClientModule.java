/**
 * Copyright (C) 2011 Red Hat, Inc. (jdcasey@commonjava.org)
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

import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;

public class IndyContentClientModule
    extends IndyClientModule
{

    @Deprecated
    public String contentUrl( final StoreType type, final String name, final String... path )
    {
        return contentUrl( MAVEN_PKG_KEY, type, name, path );
    }
    
    public String contentUrl( final String packageType, final StoreType type, final String name, final String... path )
    {
        validatePackageType( packageType );
        return UrlUtils.buildUrl( http.getBaseUrl(), aggregatePathParts( packageType, type, name, path ) );
    }

    public String contentUrl( final StoreKey key, final String... path )
    {
        return UrlUtils.buildUrl( http.getBaseUrl(), aggregatePathParts( key, path ) );
    }

    @Deprecated
    public String contentPath( final StoreType type, final String name, final String... path )
    {
        return contentPath( MAVEN_PKG_KEY, type, name, path );
    }
    
    public String contentPath( final String packageType, final StoreType type, final String name, final String... path )
    {
        validatePackageType( packageType );

        return UrlUtils.buildUrl( null, aggregatePathParts( packageType, type, name, path ) );
    }

    public String contentPath( final StoreKey key, final String... path )
    {
        return UrlUtils.buildUrl( null, aggregatePathParts( key, path ) );
    }

    public DirectoryListingDTO listContents( final StoreKey key, final String path )
            throws IndyClientException
    {
        return listContents( key.getPackageType(), key.getType(), key.getName(), path );
    }

    @Deprecated
    public DirectoryListingDTO listContents( final StoreType type, final String name, final String path )
            throws IndyClientException
    {
        return listContents( MAVEN_PKG_KEY, type, name, path );
    }

    public DirectoryListingDTO listContents( final String packageType, final StoreType type, final String name,
                                             final String path )
            throws IndyClientException
    {
        validatePackageType( packageType );

        String p = path;
        if ( !path.endsWith( "/" ) )
        {
            p += "/";
        }

        return http.get( contentPath( packageType, type, name, p ), DirectoryListingDTO.class );
    }

    public void delete( final StoreKey key, final String path )
            throws IndyClientException
    {
        http.delete( contentPath( key, path ) );
    }

    @Deprecated
    public void delete( final StoreType type, final String name, final String path )
            throws IndyClientException
    {
        delete( MAVEN_PKG_KEY, type, name, path );
    }
    
    public void delete( final String packageType, final StoreType type, final String name, final String path )
        throws IndyClientException
    {
        validatePackageType( packageType );

        http.delete( contentPath( packageType, type, name, path ) );
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
        return exists( MAVEN_PKG_KEY, type, name, path );
    }
    
    public boolean exists( final String packageType, final StoreType type, final String name, final String path )
        throws IndyClientException
    {
        validatePackageType( packageType );

        return http.exists( contentPath( packageType, type, name, path ) );
    }

    @Deprecated
    public Boolean exists( StoreType type, String name, String path, boolean cacheOnly )
            throws IndyClientException
    {
        return exists( MAVEN_PKG_KEY, type, name, path, cacheOnly );
    }

    public Boolean exists( final String packageType, final StoreType type, final String name, final String path,
                           final boolean cacheOnly )
            throws IndyClientException
    {
        validatePackageType( packageType );

        return http.exists( contentPath( packageType, type, name, path ),
                            () -> Collections.<String, String>singletonMap( IndyContentConstants.CHECK_CACHE_ONLY,
                                                                            Boolean.toString( cacheOnly ) ) );
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
        store( MAVEN_PKG_KEY, type, name, path, stream );
    }

    public void store( final String packageType, final StoreType type, final String name, final String path,
                       final InputStream stream )
            throws IndyClientException
    {
        validatePackageType( packageType );

        http.putWithStream( contentPath( packageType, type, name, path ), stream );
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
        return getInfo( MAVEN_PKG_KEY, type, name, path );
    }
    
    public PathInfo getInfo( final String packageType, final StoreType type, final String name, final String path )
            throws IndyClientException
    {
        validatePackageType( packageType );

        final Map<String, String> headers = http.head( contentPath( packageType, type, name, path ) );
        return new PathInfo( headers );
    }

    public InputStream get( final StoreKey key, final String path )
            throws IndyClientException
    {
        return get( key.getPackageType(), key.getType(), key.getName(), path );
    }

    @Deprecated
    public InputStream get( final StoreType type, final String name, final String path )
            throws IndyClientException
    {
        return get( MAVEN_PKG_KEY, type, name, path );
    }
    
    public InputStream get( final String packageType, final StoreType type, final String name, final String path )
        throws IndyClientException
    {
        validatePackageType( packageType );

        final HttpResources resources = http.getRaw( contentPath( packageType, type, name, path ) );

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

    private String[] aggregatePathParts( final StoreKey key, final String... path )
    {
        return aggregatePathParts( key.getPackageType(), key.getType(), key.getName(), path );
    }

    private String[] aggregatePathParts( final String packageType, final StoreType type, final String name, final String... path )
    {
        validatePackageType( packageType );

        final String[] parts = new String[path.length + 2];
        parts[0] = type.singularEndpointName();
        parts[1] = name;
        System.arraycopy( path, 0, parts, 2, path.length );

        return parts;
    }

    private void validatePackageType( final String packageType )
    {
        if ( !PackageTypes.contains( packageType ) )
        {
            throw new IllegalArgumentException( "Unsupported package type: " + packageType + ". Valid values are: "
                                                        + PackageTypes.getPackageTypes() );
        }
    }

}

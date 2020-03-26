/**
 * Copyright (C) 2013 Red Hat, Inc.
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
package org.commonjava.indy.repo.proxy;

import org.apache.commons.lang3.StringUtils;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static org.commonjava.indy.model.core.StoreKey.fromString;
import static org.commonjava.indy.repo.proxy.RepoProxyAddon.ADDON_NAME;
import static org.commonjava.maven.galley.util.PathUtils.normalize;

public class RepoProxyUtils
{
    private static final Logger logger = LoggerFactory.getLogger( RepoProxyUtils.class );

    private static final String STORE_PATH_PATTERN = ".*/(maven|npm)/(group|hosted)/(.+?)(/.*)?";

    private static final String NPM_METADATA_NAME = "package.json";

    static Optional<String> getProxyTo( final String originalPath, final StoreKey proxyToKey )
    {
        if ( StoreType.remote != proxyToKey.getType() )
        {
            return empty();
        }
        final Optional<String> origStoreKey = getOriginalStoreKeyFromPath( originalPath );
        if ( origStoreKey.isPresent() )
        {
            final String originStoreKeyStr = origStoreKey.get();
            final String[] parts = originStoreKeyStr.split( ":" );
            final String proxyToStorePathString = keyToPath( proxyToKey );
            final String proxyTo = originalPath.replaceAll( keyToPath( originStoreKeyStr ), proxyToStorePathString );
            logger.trace( "Found proxy to store rule: from {} to {}", originStoreKeyStr, proxyToStorePathString );
            return of( proxyTo );
        }

        return empty();
    }

    static Optional<String> getOriginalStoreKeyFromPath( final String originalPath )
    {
        final Pattern pat = Pattern.compile( STORE_PATH_PATTERN );
        final Matcher match = pat.matcher( originalPath );
        String storeKeyString = null;
        if ( match.matches() )
        {
            storeKeyString = String.format( "%s:%s:%s", match.group( 1 ), match.group( 2 ), match.group( 3 ) );
            logger.trace( "Found matched original store key {} in path {}", storeKeyString, originalPath );
        }
        else
        {
            logger.trace( "There is not matched original store key in path {}", originalPath );
        }

        return ofNullable( storeKeyString );
    }

    static String keyToPath( final String keyString )
    {
        return StringUtils.isNotBlank( keyString ) ? keyString.replaceAll( ":", "/" ) : "";
    }

    static String keyToPath( final StoreKey key )
    {
        return keyToPath( key.toString() );
    }

    static String extractPath( final String fullPath, final String repoPath )
    {
        if ( StringUtils.isBlank( fullPath ) || !fullPath.contains( repoPath ) )
        {
            return "";
        }
        String checkingRepoPath = repoPath;
        if ( repoPath.endsWith( "/" ) )
        {
            checkingRepoPath = repoPath.substring( 0, repoPath.length() - 1 );
        }
        final int pos = fullPath.indexOf( checkingRepoPath );
        final int pathStartPos = pos + checkingRepoPath.length() + 1;
        if ( pathStartPos >= fullPath.length() )
        {
            return "";
        }
        String path = fullPath.substring( pathStartPos );
        if ( StringUtils.isNotBlank( path ) && !path.startsWith( "/" ) )
        {
            path = "/" + path;
        }
        return path;
    }

    static boolean isNPMMetaPath( final String path )
    {
        if ( StringUtils.isBlank( path ) )
        {
            return false;
        }
        String checkingPath = path;
        if ( path.startsWith( "/" ) )
        {
            checkingPath = path.substring( 1 );
        }
        // This is considering the single path for npm standard like "/jquery"
        final boolean isSinglePath = checkingPath.split( "/" ).length < 2;
        // This is considering the scoped path for npm standard like "/@type/jquery"
        final boolean isScopedPath = checkingPath.startsWith( "@" ) && checkingPath.split( "/" ).length < 3;
        // This is considering the package.json file itself
        final boolean isPackageJson = checkingPath.trim().endsWith( "/" + NPM_METADATA_NAME );

        trace( logger, "path: {}, isSinglePath: {}, isScopedPath: {}, isPackageJson: {}", path, isSinglePath,
               isScopedPath, isPackageJson );
        return isSinglePath || isScopedPath || isPackageJson;
    }

    static String getRequestAbsolutePath( HttpServletRequest request )
    {
        final String pathInfo = request.getPathInfo();

        return normalize( request.getServletPath(), request.getContextPath(), request.getPathInfo() );
    }

    public static void trace( final Logger logger, final String template, final Object... params )
    {
        if ( logger.isTraceEnabled() )
        {
            final String finalTemplate = ADDON_NAME + ": " + template;
            logger.trace( finalTemplate, params );
        }
    }
}

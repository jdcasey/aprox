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

import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.repo.proxy.conf.RepoProxyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.commonjava.indy.repo.proxy.RepoProxyAddon.ADDON_NAME;
import static org.commonjava.indy.repo.proxy.RepoProxyUtils.extractPath;
import static org.commonjava.indy.repo.proxy.RepoProxyUtils.getRequestAbsolutePath;
import static org.commonjava.indy.repo.proxy.RepoProxyUtils.keyToPath;

@ApplicationScoped
public class ContentBrowseRewriteResponseDecorator
        implements RepoProxyResponseDecorator
{
    private static final Logger logger = LoggerFactory.getLogger( ContentBrowseRewriteResponseDecorator.class );

    @Inject
    private RepoProxyConfig config;

    @Override
    public HttpServletResponse decoratingResponse( HttpServletRequest request, HttpServletResponse response,
                                                   final StoreKey proxyToStoreKey )
            throws IOException
    {
        if ( !config.isEnabled() || !config.isContentBrowseRewriteEnabled() )
        {
            logger.debug(
                    "[{}] Addon not enabled or content browse rewrite not allowed, will not decorate the response for Content browse rewriting.",
                    ADDON_NAME );
            return response;
        }

        final String absolutePath = getRequestAbsolutePath( request );
        if ( !absolutePath.startsWith( "/api/browse/" ) )
        {
            logger.debug(
                    "[{}] Content browse rewrite: {} is not a content browse request, will not decorate the response for content browse rewriting. ",
                    ADDON_NAME, absolutePath );
            return response;
        }

        final String pathInfo = request.getPathInfo();
        final Optional<String> originalRepo = RepoProxyUtils.getOriginalStoreKeyFromPath( pathInfo );
        if ( !originalRepo.isPresent() )
        {
            logger.debug( "[{}] No matched repo path in request path {}, will not rewrite.", ADDON_NAME, pathInfo );
            return response;
        }

        final String originalRepoStr = originalRepo.get();

        final StoreKey originalKey = StoreKey.fromString( originalRepoStr );

        final String originalRepoPath = keyToPath( originalRepoStr );
        final String path = extractPath( pathInfo, originalRepoPath );
        final boolean ruleInPath = pathInfo.contains( originalRepoPath );
        if ( ruleInPath )
        {
            final String proxyToKeyPath = keyToPath( proxyToStoreKey );
            final Map<String, String> replacingMap = new HashMap<>(2);
            replacingMap.put( originalRepoPath, proxyToKeyPath );
            replacingMap.put( originalRepoStr, proxyToStoreKey.toString() );
            return new ContentReplacingResponseWrapper( request, response,
                                                        replacingMap );
        }
        return response;
    }
}

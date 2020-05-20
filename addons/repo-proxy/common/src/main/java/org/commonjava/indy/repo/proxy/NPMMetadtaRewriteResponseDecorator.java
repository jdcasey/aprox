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
package org.commonjava.indy.repo.proxy;

import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.pkg.npm.model.NPMPackageTypeDescriptor;
import org.commonjava.indy.repo.proxy.conf.RepoProxyConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.Optional;

import static org.commonjava.indy.repo.proxy.RepoProxyAddon.ADDON_NAME;
import static org.commonjava.indy.repo.proxy.RepoProxyUtils.extractPath;
import static org.commonjava.indy.repo.proxy.RepoProxyUtils.isNPMMetaPath;
import static org.commonjava.indy.repo.proxy.RepoProxyUtils.keyToPath;
import static org.commonjava.indy.repo.proxy.RepoProxyUtils.trace;

/**
 * This response decorator will do following
 * <ul>
 *     <li>Intercept NPM metadata path, like /jquery, /@angular/core or /jquery/package.json</li>
 *     <li>Replacing url which is using the proxy-to repo path with the request repo path in the metadata content, like "tarball:"</li>
 *     <li>And this decorator can be disabled by config "npm.meta.rewrite.enabled", default is enabled</li>
 * </ul>
 */
@ApplicationScoped
public class NPMMetadtaRewriteResponseDecorator
        implements RepoProxyResponseDecorator
{
    private static final Logger logger = LoggerFactory.getLogger( NPMMetadtaRewriteResponseDecorator.class );

    @Inject
    private RepoProxyConfig config;

    @Override
    public HttpServletResponse decoratingResponse( final HttpServletRequest request, final HttpServletResponse response,
                                                   final StoreKey proxyToStoreKey )
            throws IOException
    {
        if ( !config.isEnabled() || !config.isNpmMetaRewriteEnabled() )
        {
            logger.debug(
                    "[{}] Addon not enabled or npm meta rewrite not allowed, will not decorate the response for NPM metadata rewriting.",
                    ADDON_NAME );
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
        if ( !NPMPackageTypeDescriptor.NPM_PKG_KEY.equals( originalKey.getPackageType() ) )
        {
            logger.debug( "[{}] Not a NPM content request for path {}, will not rewrite.", ADDON_NAME, pathInfo );
            return response;
        }

        final String originalRepoPath = keyToPath( originalRepoStr );
        final String path = extractPath( pathInfo, originalRepoPath );
        if ( isNPMMetaPath( path ) )
        {
            final String proxyToKeyPath = keyToPath( proxyToStoreKey );
            logger.debug( "[{}] NPM rewriting replacement: from {} to {}", ADDON_NAME, proxyToKeyPath,
                          originalRepoPath );
            return new ContentReplacingResponseWrapper( request, response,
                                                        Collections.singletonMap( originalRepoPath, proxyToKeyPath ) );
        }
        else
        {
            logger.debug( "[{}] NPM meta rewrite: {} is not a metadata path", ADDON_NAME, path );
        }

        return response;
    }

}

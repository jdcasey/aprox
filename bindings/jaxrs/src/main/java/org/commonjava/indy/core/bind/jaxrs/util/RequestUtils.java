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
package org.commonjava.indy.core.bind.jaxrs.util;

import org.commonjava.maven.galley.util.PathUtils;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.HttpMethod;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.commonjava.indy.IndyRequestConstants.LISTING_HTML_FILE;
import static org.commonjava.indy.core.ctl.ContentController.BROWSER_USER_AGENT;
import static org.commonjava.indy.core.ctl.ContentController.CONTENT_BROWSE_API_ROOT;
import static org.commonjava.indy.core.ctl.ContentController.CONTENT_BROWSE_ROOT;

public final class RequestUtils
{
    public static Map<String, List<String>> extractRequestHeadersToMap( HttpServletRequest request )
    {
        final Map<String, List<String>> headersMap = new HashMap<>();
        if ( request != null )
        {
            for ( String headerKey : Collections.list( request.getHeaderNames() ) )
            {
                Enumeration<String> headerValues = request.getHeaders( headerKey );
                List<String> headerValuesList=  new ArrayList<>();
                while(headerValues.hasMoreElements()){
                    headerValuesList.add( headerValues.nextElement() );
                }
                headersMap.put( headerKey, headerValuesList );
            }

        }
        return Collections.unmodifiableMap( headersMap );
    }

    public static  Response redirectContentListing( final String packageType, final String type, final String name,
                                             final String originPath, final HttpServletRequest request,
                                             final Consumer<Response.ResponseBuilder> builderModifier )
    {
        // final AcceptInfo acceptInfo = jaxRsRequestHelper.findAccept( request, ApplicationContent.text_html );
        String path = originPath;
        if (path != null && path.endsWith(LISTING_HTML_FILE)) {
            path = path.replaceAll(String.format("(%s)$", LISTING_HTML_FILE), "");
        }

        // A problem here is that if client is not browser(like curl or a program http call), the redirect response will
        // just return a 303 location /browse/*, which is just a single html page with no actual content(because page is rendering
        // by javascript). So I think we should consider to judge by the User-Agent to decide the real action, and if its not a browser
        // action, we should redirect it to a REST call of /api/browse which will return the content result in JSON.
        boolean isBrowser = false;
        for (String userAgent : BROWSER_USER_AGENT) {
            if (request.getHeader("User-Agent").contains(userAgent) && HttpMethod.GET.equalsIgnoreCase( request.getMethod())) {
                isBrowser = true;
                break;
            }
        }
        final String root = isBrowser ? CONTENT_BROWSE_ROOT : CONTENT_BROWSE_API_ROOT;
        final String browseUri = PathUtils.normalize( root, packageType, type, name, path);
        Response.ResponseBuilder builder = Response.seeOther( URI.create( browseUri));
        if (builderModifier != null) {
            builderModifier.accept(builder);
        }
        return builder.build();
    }

    /**
     * Check if a path with request is a request to access directory listing
     *
     * @param path
     * @param request
     * @return
     */
    public static boolean isDirectoryPath( final String path, final HttpServletRequest request )
    {
        return path == null || path.equals( "" ) || isDirectoryPathForRequest( request ) || path.endsWith(
                LISTING_HTML_FILE );
    }

    /**
     * Check if http request is accessing directory listing
     *
     * @param request
     * @return
     */
    public static boolean isDirectoryPathForRequest( final HttpServletRequest request )
    {
        final String pathInfo = request.getPathInfo().trim();
        return pathInfo.endsWith( "/" ) || pathInfo.endsWith( LISTING_HTML_FILE );
    }
}

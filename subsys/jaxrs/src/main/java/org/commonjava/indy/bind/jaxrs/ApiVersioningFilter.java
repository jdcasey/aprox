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
package org.commonjava.indy.bind.jaxrs;

import org.commonjava.indy.stats.IndyDeprecatedApis;
import org.commonjava.indy.stats.IndyVersioning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;

import static io.undertow.util.StatusCodes.GONE;
import static org.apache.commons.lang.StringUtils.isBlank;
import static org.commonjava.indy.stats.IndyVersioning.HEADER_INDY_API_VERSION;
import static org.commonjava.indy.stats.IndyVersioning.HEADER_INDY_CUR_API_VERSION;
import static org.commonjava.indy.stats.IndyVersioning.HEADER_INDY_MIN_API_VERSION;
import static org.commonjava.indy.util.ApplicationHeader.accept;

/**
 * We do the following steps when we release a new apiVersion ( e.g. 1 to 2 )
 *
 *  1) Update '<apiVersion>' in indy-parent pom.xml. It will update apiVersion in indy-version.properties in
 *  indy-model-core-java during build. This apiVersion will be released along with the client jars.
 *
 *  2) If an api is going to be changed in terms of req/resp message formats or so and we want to support both v1
 *  and v2 users, we need to copy the method to a new one and annotate it with '@Produce "application/indy-v1+json"'.
 *  This method serves old v1 client users. The new method could be placed in a new resource class.
 *
 *  3) We continue working on the v2 version via the original method. This method serves new v2 client users.
 *
 *  4) If no changes to an api, we just leave it as is. All v1 and v2 clients will access it by default.
 *
 *
 *  ## Something behind this versioning strategy ##
 *
 *  When client sends a request to Indy, it loads the apiVersion in the 'indy-version.properties' in the
 *  client jar and add a header 'Indy-API-Version' (e.g., 'Indy-API-Version: 1'). When server receives the request,
 *  it goes through ApiVersioningFilter to adjust 'Accept' headers with the most acceptable type being prioritised.
 *  The servlet container respect the priority and choose the right method to use.
 */
@ApplicationScoped
public class ApiVersioningFilter
                implements Filter
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private IndyVersioning indyVersioning;

    @Inject
    private IndyDeprecatedApis indyDeprecatedApis;

    public ApiVersioningFilter()
    {
    }

    public ApiVersioningFilter( IndyVersioning versioning )
    {
        this.indyVersioning = versioning;
    }

    @Override
    public void init( final FilterConfig filterConfig ) throws ServletException
    {
    }

    @Override
    public void doFilter( final ServletRequest request, final ServletResponse response, final FilterChain chain )
                    throws IOException, ServletException
    {
        HttpServletRequest httpServletRequest = (HttpServletRequest) request;
        HttpServletResponse httpServletResponse = (HttpServletResponse) response;

        String reqApiVersion = httpServletRequest.getHeader( HEADER_INDY_API_VERSION );

        // Insert 3 headers into outgoing responses
        httpServletResponse.addHeader( HEADER_INDY_API_VERSION, reqApiVersion );
        httpServletResponse.addHeader( HEADER_INDY_CUR_API_VERSION, indyVersioning.getApiVersion() );
        httpServletResponse.addHeader( HEADER_INDY_MIN_API_VERSION, indyDeprecatedApis.getMinApiVersion() );

        Optional<IndyDeprecatedApis.DeprecatedApiEntry>
                deprecatedApiEntry = indyDeprecatedApis.getDeprecated( reqApiVersion );

        String deprecatedApiVersion = null;

        if ( deprecatedApiEntry.isPresent() )
        {
            if ( deprecatedApiEntry.get().isOff() )
            {
                httpServletResponse.setStatus( GONE ); // Return 410
                return;
            }
            else
            {
                deprecatedApiVersion = deprecatedApiEntry.get().getValue();
            }
        }

        VersioningRequest versioningRequest =
                        new VersioningRequest( httpServletRequest, reqApiVersion, deprecatedApiVersion );
        chain.doFilter( versioningRequest, response );
    }

    @Override
    public void destroy()
    {
    }

    /**
     * This class is used to adjust 'Accept' header. HttpServletRequest objects are read-only. We extend the
     * functionality by employing a decorator pattern and add mutability in the extended class.
     *
     * If the user requests with header 'Indy-API-Version', we will insert "application/indy-v[X]+json"
     * to accept values where X is the deprecated version most suitable to serve this request, and make it the most
     * acceptable value via ';q=' suffix. Or if no deprecated version were found, we just leave the header as is.
     */
    private class VersioningRequest
                    extends HttpServletRequestWrapper
    {

        private final static String APPLICATION = "application";

        private final String reqApiVersion;

        private final String deprecatedApiVersion;

        public VersioningRequest( HttpServletRequest request, String reqApiVersion, String deprecatedApiVersion )
        {
            super( request );
            this.reqApiVersion = reqApiVersion;
            this.deprecatedApiVersion = deprecatedApiVersion;
        }

        @Override
        public Enumeration<String> getHeaders( String name )
        {
            Enumeration<String> eu = super.getHeaders( name );

            if ( isBlank( reqApiVersion ) || isBlank( deprecatedApiVersion ))
            {
                return eu; // not change to headers
            }

            if ( !accept.key().equals( name ) )
            {
                return eu; // only care about the "Accept" request header
            }

            String key = accept.key();
            logger.trace( "Adjust header {}, value: {}", key, super.getHeader( name ) );

            List<String> values = new ArrayList<>();
            while ( eu.hasMoreElements() )
            {
                String tok = eu.nextElement();
                if ( tok.startsWith( APPLICATION ) ) // adjust, e.g., application/json -> application/indy-v1+json
                {
                    float priority = 1f;
                    String[] kv = tok.split( "/" );
                    values.add( APPLICATION + "/indy-v" + deprecatedApiVersion + "+" + kv[1] + ";q=" + priority );
                    values.add( tok + ";q=" + getNextPriority( priority ) ); // keep the original value
                }
                else
                {
                    values.add( tok );
                }
            }

            logger.trace( "Adjust complete, new values: {}", values );
            return Collections.enumeration( values );
        }

        private float getNextPriority( float priority )
        {
            return priority - 0.1f;
        }

    }
}

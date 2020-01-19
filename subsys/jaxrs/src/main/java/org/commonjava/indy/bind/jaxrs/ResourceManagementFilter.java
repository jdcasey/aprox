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

import org.commonjava.cdi.util.weft.ThreadContext;
import org.commonjava.maven.galley.model.SpecialPathInfo;
import org.commonjava.maven.galley.spi.cache.CacheProvider;
import org.commonjava.maven.galley.spi.io.SpecialPathManager;
import org.commonjava.propulsor.metrics.MetricsConstants;
import org.commonjava.propulsor.metrics.MetricsManager;
import org.commonjava.propulsor.metrics.annotation.Measure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.commonjava.indy.metrics.RequestContextHelper.CLIENT_ADDR;
import static org.commonjava.indy.metrics.RequestContextHelper.CUMULATIVE_COUNTS;
import static org.commonjava.indy.metrics.RequestContextHelper.CUMULATIVE_TIMINGS;
import static org.commonjava.indy.metrics.RequestContextHelper.EXTERNAL_ID;
import static org.commonjava.indy.metrics.RequestContextHelper.INTERNAL_ID;
import static org.commonjava.indy.metrics.RequestContextHelper.IS_METERED;
import static org.commonjava.indy.metrics.RequestContextHelper.PREFERRED_ID;
import static org.commonjava.indy.metrics.RequestContextHelper.REQUEST_PHASE;
import static org.commonjava.indy.metrics.RequestContextHelper.REQUEST_PHASE_START;
import static org.commonjava.indy.metrics.RequestContextHelper.X_FORWARDED_FOR;

@ApplicationScoped
public class ResourceManagementFilter
        implements Filter
{

    public static final String HTTP_REQUEST = "http-request";

    public static final String ORIGINAL_THREAD_NAME = "original-thread-name";

    public static final String METHOD_PATH_TIME = "method-path-time";

    private static final String BASE_CONTENT_METRIC = "indy.content.";

    private static final String POM_CONTENT_METRIC = BASE_CONTENT_METRIC + "pom";

    private static final String NORMAL_CONTENT_METRIC = BASE_CONTENT_METRIC + "other";

    private static final String METADATA_CONTENT_METRIC = BASE_CONTENT_METRIC + "metadata";

    private static final String SPECIAL_CONTENT_METRIC = BASE_CONTENT_METRIC + "special";

    private static final String FORCE_METERED = "force-metered";

    @Inject
    private CacheProvider cacheProvider;

    @Inject
    private MDCManager mdcManager;

    @Inject
    private SpecialPathManager specialPathManager;

    @Inject
    private MetricsManager metricsManager;

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private final Logger restLogger = LoggerFactory.getLogger( "org.commonjava.topic.rest.inbound" );

    @Override
    public void init( final FilterConfig filterConfig )
            throws ServletException
    {
        if ( logger.isTraceEnabled() )
        {
            cacheProvider.startReporting();
        }
    }

    @Override
    @Measure
    public void doFilter( final ServletRequest request, final ServletResponse response, final FilterChain chain )
            throws IOException, ServletException
    {
        String name = Thread.currentThread().getName();
        String clientAddr = request.getRemoteAddr();

        final HttpServletRequest hsr = (HttpServletRequest) request;
        final String xForwardFor = hsr.getHeader( X_FORWARDED_FOR );
        if ( xForwardFor != null )
        {
            clientAddr = xForwardFor; // OSE proxy use HTTP header 'x-forwarded-for' to represent user IP
        }

        String tn = hsr.getMethod() + " " + hsr.getPathInfo() + " (" + System.currentTimeMillis() + "." + System.nanoTime() + ")";
        String qs = hsr.getQueryString();

        try
        {
            ThreadContext.clearContext();
            ThreadContext threadContext = ThreadContext.getContext( true );

            boolean isMetered = metricsManager.isMetered( ()->{
                String header = hsr.getHeader( FORCE_METERED );
                return ( header == null || Boolean.parseBoolean( header ) );
            } );

            threadContext.put( IS_METERED, isMetered );

            threadContext.put( ORIGINAL_THREAD_NAME, name );

            threadContext.put( HTTP_REQUEST, hsr );

            threadContext.put( METHOD_PATH_TIME, tn );

            threadContext.put( CLIENT_ADDR, clientAddr );

            putRequestIDs( hsr, threadContext, mdcManager );

            mdcManager.putUserIP( clientAddr );

            mdcManager.putExtraHeaders( hsr );

            logger.debug( "START request: {} (from: {})", tn, clientAddr );

            Thread.currentThread().setName( tn );

            MDC.put( REQUEST_PHASE, REQUEST_PHASE_START );
            restLogger.info( "START {}{} (from: {})", hsr.getRequestURL(), qs == null ? "" : "?" + qs, clientAddr );
            MDC.remove( REQUEST_PHASE );

            AtomicReference<IOException> ioex = new AtomicReference<>();
            AtomicReference<ServletException> seex = new AtomicReference<>();

            metricsManager.wrapWithStandardMetrics( () -> {
                try
                {
                    chain.doFilter( request, response );
                }
                catch ( IOException e )
                {
                    ioex.set( e );
                }
                catch ( ServletException e )
                {
                    seex.set( e );
                }
                return null;
            }, pathClassifier( hsr.getPathInfo() ) );

            if ( ioex.get() != null )
            {
                throw ioex.get();
            }

            if ( seex.get() != null )
            {
                throw seex.get();
            }
        }
        finally
        {
            logger.debug( "Cleaning up resources for thread: {}", Thread.currentThread().getName() );
            try
            {
                cacheProvider.cleanupCurrentThread();
            }
            catch ( Exception e )
            {
                logger.error( "Failed to cleanup resources", e );
            }

            ThreadContext ctx = ThreadContext.getContext( false );
            if ( ctx != null )
            {
                Map<String, Double> cumulativeTimings = (Map<String, Double>) ctx.get( CUMULATIVE_TIMINGS );
                if ( cumulativeTimings != null )
                {
                    cumulativeTimings.forEach(
                            ( k, v ) -> MDC.put( CUMULATIVE_TIMINGS + "." + k, String.format( "%.3f", v ) ) );
                }

                Map<String, Integer> cumulativeCounts = (Map<String, Integer>) ctx.get( CUMULATIVE_COUNTS );
                if ( cumulativeCounts != null )
                {
                    cumulativeCounts.forEach(
                            ( k, v ) -> MDC.put( CUMULATIVE_COUNTS + "." + k, String.format( "%d", v ) ) );
                }
            }

            restLogger.info( "END {}{} (from: {})", hsr.getRequestURL(), qs == null ? "" : "?" + qs, clientAddr );

            Thread.currentThread().setName( name );
            ThreadContext.clearContext();

            logger.debug( "END request: {} (from: {})", tn, clientAddr );

            mdcManager.clear();

//            Thread.currentThread().getThreadGroup().list();

        }
    }

    private Supplier<String> pathClassifier( final String pathInfo )
    {
        return ()->{
            if ( !pathInfo.contains( "content" ))
            {
                return MetricsConstants.SKIP_METRIC;
            }
            SpecialPathInfo spi = specialPathManager.getSpecialPathInfo( pathInfo );
            if ( spi == null )
            {
                return pathInfo.endsWith( ".pom" ) ? POM_CONTENT_METRIC : NORMAL_CONTENT_METRIC;
            }
            else if ( spi.isMetadata() )
            {
                return METADATA_CONTENT_METRIC;
            }
            else
            {
                return SPECIAL_CONTENT_METRIC;
            }
        };
    }

    /**
     * Put to MDC / threadContext request IDs.
    */
    private void putRequestIDs( HttpServletRequest hsr, ThreadContext threadContext, MDCManager mdcManager )
    {
        /* We would always generate internalID and provide that in the MDC.
         * If the calling service supplies an externalID, we'd map that under its own key.
         * PreferredID should try to use externalID if it's available, and default over to using internalID if it's not.
         * What this gives us is a single key we can use to reference an ID for the request,
         * and whenever possible it'll reflect the externally supplied ID.
         */
        String internalID = UUID.randomUUID().toString();
        String externalID = hsr.getHeader( EXTERNAL_ID );
        String preferredID = externalID != null ? externalID : internalID;

        mdcManager.putRequestIDs( internalID, externalID, preferredID );

        /*
         * We should also put the same values in the ThreadContext map, so we can reference them from code without
         * having to go through the logging framework
         */
        threadContext.put( INTERNAL_ID, internalID );
        if ( externalID != null )
        {
            threadContext.put( EXTERNAL_ID, externalID );
        }
        threadContext.put( PREFERRED_ID, preferredID );
    }

    @Override
    public void destroy()
    {
    }

}

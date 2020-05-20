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
package org.commonjava.indy.httprox.handler;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import org.apache.commons.lang3.ClassUtils;
import org.apache.http.HttpRequest;
import org.apache.http.RequestLine;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.bind.jaxrs.MDCManager;
import org.commonjava.indy.metrics.RequestContextHelper;
import org.commonjava.indy.core.ctl.ContentController;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.folo.model.TrackingKey;
import org.commonjava.indy.httprox.conf.HttproxConfig;
import org.commonjava.indy.httprox.conf.TrackingType;
import org.commonjava.indy.httprox.keycloak.KeycloakProxyAuthenticator;
import org.commonjava.indy.httprox.util.HttpConduitWrapper;
import org.commonjava.indy.httprox.util.ProxyMeter;
import org.commonjava.indy.httprox.util.ProxyResponseHelper;
import org.commonjava.indy.metrics.conf.IndyMetricsConfig;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.sli.metrics.GoldenSignalsMetricSet;
import org.commonjava.indy.subsys.http.HttpWrapper;
import org.commonjava.indy.subsys.http.util.UserPass;
import org.commonjava.indy.subsys.infinispan.CacheHandle;
import org.commonjava.indy.subsys.infinispan.CacheProducer;
import org.commonjava.indy.util.ApplicationHeader;
import org.commonjava.indy.util.ApplicationStatus;
import org.commonjava.maven.galley.spi.cache.CacheProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.ChannelListener;
import org.xnio.StreamConnection;
import org.xnio.conduits.ConduitStreamSinkChannel;
import org.xnio.conduits.ConduitStreamSourceChannel;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.URL;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import org.commonjava.cdi.util.weft.WeftExecutorService;

import static com.codahale.metrics.MetricRegistry.name;
import static java.lang.Integer.parseInt;
import static org.apache.commons.codec.digest.DigestUtils.sha256Hex;
import static org.commonjava.indy.metrics.RequestContextHelper.getContext;
import static org.commonjava.indy.httprox.util.HttpProxyConstants.ALLOW_HEADER_VALUE;
import static org.commonjava.indy.httprox.util.HttpProxyConstants.CONNECT_METHOD;
import static org.commonjava.indy.httprox.util.HttpProxyConstants.GET_METHOD;
import static org.commonjava.indy.httprox.util.HttpProxyConstants.HEAD_METHOD;
import static org.commonjava.indy.httprox.util.HttpProxyConstants.OPTIONS_METHOD;
import static org.commonjava.indy.httprox.util.HttpProxyConstants.PROXY_AUTHENTICATE_FORMAT;
import static org.commonjava.indy.httprox.util.HttpProxyConstants.PROXY_METRIC_LOGGER;
import static org.commonjava.indy.subsys.http.util.UserPass.parse;
import static org.commonjava.indy.util.ApplicationHeader.proxy_authenticate;
import static org.commonjava.indy.util.ApplicationStatus.PROXY_AUTHENTICATION_REQUIRED;

public final class ProxyResponseWriter
                implements ChannelListener<ConduitStreamSinkChannel>
{

    private static final String HTTP_PROXY_AUTH_CACHE = "httproxy-auth-cache";

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private final Logger restLogger = LoggerFactory.getLogger( PROXY_METRIC_LOGGER );

    private final ConduitStreamSourceChannel sourceChannel;

    private ProxyRequestReader proxyRequestReader;

    private final SocketAddress peerAddress;

    private ProxySSLTunnel sslTunnel;

    private boolean directed = false;

    private final CacheHandle<String, Boolean> proxyAuthCache;

    private Throwable error;

    private final HttproxConfig config;

    private final ContentController contentController;

    private final StoreDataManager storeManager;

    private KeycloakProxyAuthenticator proxyAuthenticator;

    private CacheProvider cacheProvider;

    private ProxyRepositoryCreator repoCreator;

    private HttpRequest httpRequest;

    private final MDCManager mdcManager;

    private final MetricRegistry metricRegistry;

    private GoldenSignalsMetricSet sliMetricSet;

    private long startNanos;

    private final IndyMetricsConfig metricsConfig;

    private final String cls; // short class name for metrics

    private final WeftExecutorService tunnelAndMITMExecutor;

    private boolean summaryReported;

    // run short-living tunnels and MITM servers

    public ProxyResponseWriter( final HttproxConfig config, final StoreDataManager storeManager,
                                final ContentController contentController,
                                final KeycloakProxyAuthenticator proxyAuthenticator, final CacheProvider cacheProvider,
                                final MDCManager mdcManager, final ProxyRepositoryCreator repoCreator,
                                final StreamConnection accepted, final IndyMetricsConfig metricsConfig,
                                final MetricRegistry metricRegistry, final GoldenSignalsMetricSet sliMetricSet,
                                final CacheProducer cacheProducer,
                                final long start, final WeftExecutorService executor )
    {
        this.config = config;
        this.contentController = contentController;
        this.storeManager = storeManager;
        this.proxyAuthenticator = proxyAuthenticator;
        this.cacheProvider = cacheProvider;
        this.mdcManager = mdcManager;
        this.repoCreator = repoCreator;
        this.peerAddress = accepted.getPeerAddress();
        this.sourceChannel = accepted.getSourceChannel();
        this.metricsConfig = metricsConfig;
        this.metricRegistry = metricRegistry;
        this.sliMetricSet = sliMetricSet;
        startNanos = start;
        this.cls = ClassUtils.getAbbreviatedName( getClass().getName(), 1 ); // e.g., foo.bar.ClassA -> f.b.ClassA
        this.proxyAuthCache = cacheProducer.getCache( HTTP_PROXY_AUTH_CACHE );
        this.tunnelAndMITMExecutor = executor;
    }

    public void setProxyRequestReader( ProxyRequestReader proxyRequestReader )
    {
        this.proxyRequestReader = proxyRequestReader;
    }

    @Override
    public void handleEvent( final ConduitStreamSinkChannel channel )
    {
        if ( metricsConfig == null || metricRegistry == null )
        {
            doHandleEvent( channel );
            return;
        }

        Timer timer = metricRegistry.timer( name( metricsConfig.getNodePrefix(), cls, "handleEvent" ) );
        Timer.Context timerContext = timer.time();
        try
        {
            doHandleEvent( channel );
        }
        finally
        {
            timerContext.stop();
        }
    }

    private void doHandleEvent( final ConduitStreamSinkChannel sinkChannel )
    {
        if ( directed )
        {
            return;
        }

        ProxyMeter meter =
                new ProxyMeter( httpRequest.getRequestLine().getMethod(), httpRequest.getRequestLine().toString(),
                                startNanos, sliMetricSet, restLogger, peerAddress );
        try
        {
            HttpConduitWrapper http = new HttpConduitWrapper( sinkChannel, httpRequest, contentController, cacheProvider );
            if ( httpRequest == null )
            {
                if ( error != null )
                {
                    logger.debug( "Handling error from request reader: " + error.getMessage(), error );
                    handleError( error, http );
                }
                else
                {
                    logger.debug( "Invalid state (no error or request) from request reader. Sending 400." );
                    try
                    {
                        http.writeStatus( ApplicationStatus.BAD_REQUEST );
                    }
                    catch ( final IOException e )
                    {
                        logger.error( "Failed to write BAD REQUEST for missing HTTP first-line to response channel.", e );
                    }
                }

                return;
            }

            restLogger.info( "SERVE {} (from: {})", httpRequest.getRequestLine(), peerAddress );

            // TODO: Can we handle this?
            final String oldThreadName = Thread.currentThread().getName();
            Thread.currentThread().setName( "PROXY-" + httpRequest.getRequestLine().toString() );
            sinkChannel.getCloseSetter().set( ( c ) -> {
                logger.trace( "Sink channel closing." );
                Thread.currentThread().setName( oldThreadName );
                if ( sslTunnel != null )
                {
                    logger.trace( "Close ssl tunnel" );
                    sslTunnel.close();
                }
            } );

            logger.debug( "\n\n\n>>>>>>> Handle write\n\n\n" );
            if ( error == null )
            {
                ProxyResponseHelper proxyResponseHelper =
                        new ProxyResponseHelper( httpRequest, config, contentController, repoCreator, storeManager,
                                                 metricsConfig, metricRegistry, cls );
                try
                {
                    if ( repoCreator == null )
                    {
                        throw new IndyDataException( "No valid instance of ProxyRepositoryCreator" );
                    }

                    final UserPass proxyUserPass = parse( ApplicationHeader.proxy_authorization, httpRequest, null );
                    logger.info( "Using proxy authentication: {}", proxyUserPass );

                    mdcManager.putExtraHeaders( httpRequest );

                    // FIXME: We cannot trace through this interface currently!
                    mdcManager.putExternalID( proxyUserPass == null ? null : proxyUserPass.getUser() );

                    logger.debug( "Proxy UserPass: {}\nConfig secured? {}\nConfig tracking type: {}", proxyUserPass,
                                  config.isSecured(), config.getTrackingType() );
                    if ( proxyUserPass == null && ( config.isSecured() || TrackingType.ALWAYS == config.getTrackingType() ) )
                    {

                        String realmInfo = String.format( PROXY_AUTHENTICATE_FORMAT, config.getProxyRealm() );

                        logger.info( "Not authenticated to proxy. Sending response: {} / {}: {}",
                                     PROXY_AUTHENTICATION_REQUIRED, proxy_authenticate, realmInfo );

                        http.writeStatus( PROXY_AUTHENTICATION_REQUIRED );
                        http.writeHeader( proxy_authenticate, realmInfo );
                    }
                    else
                    {
                        RequestLine requestLine = httpRequest.getRequestLine();
                        String method = requestLine.getMethod().toUpperCase();
                        String trackingId = null;
                        boolean authenticated = true;

                        if ( proxyUserPass != null )
                        {
                            TrackingKey trackingKey = proxyResponseHelper.getTrackingKey( proxyUserPass );
                            if ( trackingKey != null )
                            {
                                trackingId = trackingKey.getId();
                                RequestContextHelper.setContext( RequestContextHelper.CONTENT_TRACKING_ID, trackingId );
                            }

                            String authCacheKey = generateAuthCacheKey( proxyUserPass );
                            Boolean isAuthToken = proxyAuthCache.get( authCacheKey );
                            if ( Boolean.TRUE.equals( isAuthToken ) )
                            {
                                authenticated = true;
                                logger.debug( "Found auth key in cache" );
                            }
                            else
                            {
                                logger.debug(
                                        "Passing BASIC authentication credentials to Keycloak bearer-token translation authenticator" );
                                authenticated = proxyAuthenticator.authenticate( proxyUserPass, http );
                                if ( authenticated )
                                {
                                    proxyAuthCache.put( authCacheKey, Boolean.TRUE, config.getAuthCacheExpirationHours(), TimeUnit.HOURS );
                                }
                            }
                            logger.debug( "Authentication done, result: {}", authenticated );
                        }

                        if ( authenticated )
                        {
                            switch ( method )
                            {
                                case GET_METHOD:
                                case HEAD_METHOD:
                                {
                                    final URL url = new URL( requestLine.getUri() );
                                    logger.debug( "getArtifactStore starts, trackingId: {}, url: {}", trackingId, url );
                                    ArtifactStore store = proxyResponseHelper.getArtifactStore( trackingId, url );
                                    proxyResponseHelper.transfer( http, store, url.getPath(), GET_METHOD.equals( method ), proxyUserPass, meter );
                                    break;
                                }
                                case OPTIONS_METHOD:
                                {
                                    http.writeStatus( ApplicationStatus.OK );
                                    http.writeHeader( ApplicationHeader.allow, ALLOW_HEADER_VALUE );
                                    break;
                                }
                                case CONNECT_METHOD:
                                {
                                    if ( !config.isMITMEnabled() )
                                    {
                                        logger.debug( "CONNECT method not supported unless MITM-proxying is enabled." );
                                        http.writeStatus( ApplicationStatus.BAD_REQUEST );
                                        break;
                                    }

                                    String uri = requestLine.getUri(); // e.g, github.com:443
                                    logger.debug( "Get CONNECT request, uri: {}", uri );

                                    String[] toks = uri.split( ":" );
                                    String host = toks[0];
                                    int port = parseInt( toks[1] );

                                    directed = true;

                                    // After this, the proxy simply opens a plain socket to the target server and relays
                                    // everything between the initial client and the target server (including the TLS handshake).

                                    SocketChannel socketChannel;

                                    ProxyMITMSSLServer svr =
                                            new ProxyMITMSSLServer( host, port, trackingId, proxyUserPass,
                                                                    proxyResponseHelper, contentController,
                                                                    cacheProvider, config, meter );
                                    tunnelAndMITMExecutor.submit( svr );
                                    socketChannel = svr.getSocketChannel();

                                    if ( socketChannel == null )
                                    {
                                        logger.debug( "Failed to get MITM socket channel" );
                                        http.writeStatus( ApplicationStatus.SERVER_ERROR );
                                        svr.stop();
                                        break;
                                    }

                                    sslTunnel = new ProxySSLTunnel( sinkChannel, socketChannel, config );
                                    tunnelAndMITMExecutor.submit( sslTunnel );
                                    proxyRequestReader.setProxySSLTunnel( sslTunnel ); // client input will be directed to target socket

                                    // When all is ready, send the 200 to client. Client send the SSL handshake to reader,
                                    // reader direct it to tunnel to MITM. MITM finish the handshake and read the request data,
                                    // retrieve remote content and send back to tunnel to client.
                                    http.writeStatus( ApplicationStatus.OK );
                                    http.writeHeader( "Status", "200 OK\n" );

                                    break;
                                }
                                default:
                                {
                                    http.writeStatus( ApplicationStatus.METHOD_NOT_ALLOWED );
                                }
                            }
                        }
                    }

                    logger.debug( "Response complete." );
                }
                catch ( final Throwable e )
                {
                    error = e;
                }
                finally
                {
                    mdcManager.clear();
                }
            }

            if ( error != null )
            {
                handleError( error, http );
            }

            try
            {
                if ( directed )
                {
                    ; // do not close sink channel
                }
                else
                {
                    http.close();
                }
            }
            catch ( final IOException e )
            {
                logger.error( "Failed to shutdown response", e );
            }
        }
        finally
        {
            meter.reportResponseSummary();
        }
    }

    private String generateAuthCacheKey( UserPass proxyUserPass )
    {
        return sha256Hex( proxyUserPass.getUser() + ":" + proxyUserPass.getPassword() );
    }

    private void handleError( final Throwable error, final HttpWrapper http )
    {
        logger.error( "HTTProx request failed: " + error.getMessage(), error );
        try
        {
            if ( http.isOpen() )
            {
                if ( error instanceof IndyWorkflowException )
                {
                    http.writeStatus( ApplicationStatus.getStatus( ( (IndyWorkflowException) error ).getStatus() ) );
                }
                else
                {
                    http.writeStatus( ApplicationStatus.SERVER_ERROR );
                }

                http.writeError( error );

                logger.debug( "Response error complete." );
            }
        }
        catch ( final IOException closeException )
        {
            logger.error( "Failed to close httprox request: " + error.getMessage(), error );
        }
    }

    public void setError( final Throwable error )
    {
        this.error = error;
    }

    public void setHttpRequest( final HttpRequest request )
    {
        this.httpRequest = request;
    }
}

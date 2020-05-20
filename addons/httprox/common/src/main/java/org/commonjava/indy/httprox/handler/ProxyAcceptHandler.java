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

import org.commonjava.cdi.util.weft.ExecutorConfig;
import org.commonjava.cdi.util.weft.WeftExecutorService;
import org.commonjava.cdi.util.weft.WeftManaged;
import org.commonjava.indy.bind.jaxrs.MDCManager;
import org.commonjava.indy.metrics.RequestContextHelper;
import org.commonjava.indy.core.ctl.ContentController;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.httprox.conf.HttproxConfig;
import org.commonjava.indy.httprox.keycloak.KeycloakProxyAuthenticator;
import org.commonjava.indy.metrics.conf.IndyMetricsConfig;
import org.commonjava.indy.model.core.AccessChannel;
import org.commonjava.indy.sli.metrics.GoldenSignalsMetricSet;
import org.commonjava.indy.subsys.infinispan.CacheProducer;
import org.commonjava.indy.subsys.template.IndyGroovyException;
import org.commonjava.indy.subsys.template.ScriptEngine;
import org.commonjava.maven.galley.spi.cache.CacheProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.ChannelListener;
import org.xnio.StreamConnection;
import org.xnio.channels.AcceptingChannel;
import org.xnio.conduits.ConduitStreamSinkChannel;
import org.xnio.conduits.ConduitStreamSourceChannel;

import javax.inject.Inject;
import java.io.IOException;

import static org.commonjava.indy.metrics.RequestContextHelper.PACKAGE_TYPE;
import static org.commonjava.indy.metrics.RequestContextHelper.REQUEST_PHASE;
import static org.commonjava.indy.metrics.RequestContextHelper.REQUEST_PHASE_START;
import static org.commonjava.indy.metrics.RequestContextHelper.setContext;
import static org.commonjava.indy.httprox.util.HttpProxyConstants.PROXY_METRIC_LOGGER;
import static org.commonjava.indy.pkg.PackageTypeConstants.PKG_TYPE_GENERIC_HTTP;

/**
 * Created by jdcasey on 8/13/15.
 */
public class ProxyAcceptHandler
        implements ChannelListener<AcceptingChannel<StreamConnection>>
{
    private static final String HTTPROX_REPO_CREATOR_SCRIPT = "httprox-repo-creator.groovy";

    public static final String HTTPROX_ORIGIN = "httprox";

    @Inject
    private HttproxConfig config;

    @Inject
    private StoreDataManager storeManager;

    @Inject
    private ContentController contentController;

    @Inject
    private KeycloakProxyAuthenticator proxyAuthenticator;

    @Inject
    private CacheProvider cacheProvider;

    @Inject
    private ScriptEngine scriptEngine;

    @Inject
    private MDCManager mdcManager;

    @Inject
    MetricRegistry metricRegistry;

    @Inject
    private IndyMetricsConfig metricsConfig;

    @Inject
    private CacheProducer cacheProducer;

    @Inject
    private GoldenSignalsMetricSet sliMetricSet;

    @Inject
    private ProxyTransfersExecutor proxyExecutor;

    protected ProxyAcceptHandler()
    {
    }

    public ProxyAcceptHandler( HttproxConfig config, StoreDataManager storeManager, ContentController contentController,
                               KeycloakProxyAuthenticator proxyAuthenticator, CacheProvider cacheProvider,
                               ScriptEngine scriptEngine, MDCManager mdcManager,
                               IndyMetricsConfig metricsConfig, MetricRegistry metricRegistry,
                               CacheProducer cacheProducer, ProxyTransfersExecutor executor )
    {
        this.config = config;
        this.storeManager = storeManager;
        this.contentController = contentController;
        this.proxyAuthenticator = proxyAuthenticator;
        this.cacheProvider = cacheProvider;
        this.scriptEngine = scriptEngine;
        this.mdcManager = mdcManager;
        this.metricsConfig = metricsConfig;
        this.metricRegistry = metricRegistry;
        this.cacheProducer = cacheProducer;
        this.proxyExecutor = executor;
    }

    public ProxyRepositoryCreator createRepoCreator()
    {
        ProxyRepositoryCreator creator = null;
        try
        {
            creator = scriptEngine.parseStandardScriptInstance( ScriptEngine.StandardScriptType.store_creators,
                                                                HTTPROX_REPO_CREATOR_SCRIPT, ProxyRepositoryCreator.class );
        }
        catch ( IndyGroovyException e )
        {
            Logger logger = LoggerFactory.getLogger( getClass() );
            logger.error( String.format( "Cannot create ProxyRepositoryCreator instance: %s. Disabling httprox support.",
                                         e.getMessage() ), e );
            config.setEnabled( false );
        }
        return creator;
    }

    @Override
    public void handleEvent( AcceptingChannel<StreamConnection> channel )
    {
        long start = System.nanoTime();
        RequestContextHelper.setContext( RequestContextHelper.ACCESS_CHANNEL, AccessChannel.GENERIC_PROXY.toString() );
        setContext( PACKAGE_TYPE, PKG_TYPE_GENERIC_HTTP );

        final Logger logger = LoggerFactory.getLogger( getClass() );

        StreamConnection accepted;
        try
        {
            accepted = channel.accept();
        }
        catch ( IOException e )
        {
            logger.error( "Failed to accept httprox connection: " + e.getMessage(), e );
            accepted = null;
        }

        // to remove the return in the catch clause, which is bad form...
        if ( accepted == null )
        {
            return;
        }

        RequestContextHelper.setContext( REQUEST_PHASE, REQUEST_PHASE_START );
        LoggerFactory.getLogger( PROXY_METRIC_LOGGER )
                     .info( "START HTTProx request (from: {})", accepted.getPeerAddress() );
        RequestContextHelper.clearContext( REQUEST_PHASE );

        logger.debug( "accepted {}", accepted.getPeerAddress() );

        final ConduitStreamSourceChannel source = accepted.getSourceChannel();
        final ConduitStreamSinkChannel sink = accepted.getSinkChannel();

        final ProxyRepositoryCreator creator = createRepoCreator();

        final ProxyResponseWriter writer =
                        new ProxyResponseWriter( config, storeManager, contentController, proxyAuthenticator,
                                                 cacheProvider, mdcManager, creator, accepted,
                                                 metricsConfig, metricRegistry, sliMetricSet, cacheProducer, start,
                                                 proxyExecutor.getExecutor() );

        logger.debug( "Setting writer: {}", writer );
        sink.getWriteSetter().set( writer );

        final ProxyRequestReader reader = new ProxyRequestReader( writer, sink );
        writer.setProxyRequestReader( reader );

        logger.debug( "Setting reader: {}", reader );
        source.getReadSetter().set( reader );
        source.resumeReads();

    }

}

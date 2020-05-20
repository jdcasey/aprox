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
package org.commonjava.indy.httprox;

import groovy.text.GStringTemplateEngine;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpHost;
import org.apache.http.HttpStatus;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.protocol.HttpClientContext;
import org.apache.http.conn.routing.HttpRoutePlanner;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.DefaultProxyRoutePlanner;
import org.commonjava.cdi.util.weft.PoolWeftExecutorService;
import org.commonjava.cdi.util.weft.WeftExecutorService;
import org.commonjava.indy.bind.jaxrs.MDCManager;
import org.commonjava.indy.conf.DefaultIndyConfiguration;
import org.commonjava.indy.content.ContentDigester;
import org.commonjava.indy.content.ContentManager;
import org.commonjava.indy.content.DirectContentAccess;
import org.commonjava.indy.content.DownloadManager;
import org.commonjava.indy.core.content.ContentGeneratorManager;
import org.commonjava.indy.core.content.DefaultContentDigester;
import org.commonjava.indy.core.content.DefaultContentManager;
import org.commonjava.indy.core.content.DefaultDirectContentAccess;
import org.commonjava.indy.core.content.DefaultDownloadManager;
import org.commonjava.indy.core.ctl.ContentController;
import org.commonjava.indy.core.inject.ExpiringMemoryNotFoundCache;
import org.commonjava.indy.httprox.conf.HttproxConfig;
import org.commonjava.indy.httprox.handler.ProxyAcceptHandler;
import org.commonjava.indy.httprox.handler.ProxyTransfersExecutor;
import org.commonjava.indy.httprox.keycloak.KeycloakProxyAuthenticator;
import org.commonjava.indy.mem.data.MemoryStoreDataManager;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.commonjava.indy.subsys.datafile.DataFileManager;
import org.commonjava.indy.subsys.datafile.change.DataFileEventManager;
import org.commonjava.indy.subsys.infinispan.CacheHandle;
import org.commonjava.indy.subsys.infinispan.CacheProducer;
import org.commonjava.indy.subsys.keycloak.conf.KeycloakConfig;
import org.commonjava.indy.subsys.template.ScriptEngine;
import org.commonjava.indy.subsys.template.TemplatingEngine;
import org.commonjava.indy.test.fixture.core.MockContentAdvisor;
import org.commonjava.indy.test.fixture.core.MockInstance;
import org.commonjava.indy.util.MimeTyper;
import org.commonjava.atlas.maven.ident.ref.ProjectVersionRef;
import org.commonjava.maven.galley.auth.MemoryPasswordManager;
import org.commonjava.maven.galley.io.SpecialPathManagerImpl;
import org.commonjava.maven.galley.io.checksum.TransferMetadata;
import org.commonjava.maven.galley.maven.parse.PomPeek;
import org.commonjava.maven.galley.nfc.MemoryNotFoundCache;
import org.commonjava.maven.galley.spi.transport.TransportManager;
import org.commonjava.maven.galley.testing.core.CoreFixture;
import org.commonjava.maven.galley.transport.TransportManagerImpl;
import org.commonjava.maven.galley.transport.htcli.HttpClientTransport;
import org.commonjava.maven.galley.transport.htcli.HttpImpl;
import org.commonjava.maven.galley.transport.htcli.util.HttpUtil;
import org.commonjava.propulsor.boot.BootOptions;
import org.commonjava.test.http.expect.ExpectationServer;
import org.infinispan.Cache;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.manager.DefaultCacheManager;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import static org.commonjava.indy.model.core.GenericPackageTypeDescriptor.GENERIC_PKG_KEY;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class HttpProxyTest
{

    private static final String USER = "user";

    private static final String PASS = "password";

    private static final String HOST = "127.0.0.1";

    protected final Logger logger = LoggerFactory.getLogger( getClass() );

    @Rule
    public ExpectationServer server = new ExpectationServer();

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Rule
    public CoreFixture core = new CoreFixture( false, temp );

    private int proxyPort;

    private HttpProxy proxy;

    private MemoryStoreDataManager storeManager;

    private static DefaultCacheManager cacheManager;

    private static Cache<String, TransferMetadata> contentMetadata;

    @BeforeClass
    public static void setupClass()
    {
        cacheManager = new DefaultCacheManager( new ConfigurationBuilder().simpleCache( true ).build() );

        contentMetadata = cacheManager.getCache( "content-metadata", true );

    }

    @Before
    public void setup()
            throws Exception
    {
        contentMetadata.clear();

        core.initGalley();

        final TransportManager transports =
                new TransportManagerImpl( new HttpClientTransport( new HttpImpl( new MemoryPasswordManager() ) ) );

        core.withTransportManager( transports );

        core.initMissingComponents();

        final HttproxConfig config = new HttproxConfig();
        config.setEnabled( true );

        proxyPort = config.getPort();

        final BootOptions bootOpts = new BootOptions();
        bootOpts.setBind( HOST );

        storeManager = new MemoryStoreDataManager( true );

        final IndyObjectMapper mapper = new IndyObjectMapper( true );

        final DefaultIndyConfiguration indyConfig = new DefaultIndyConfiguration();
        indyConfig.setNotFoundCacheTimeoutSeconds( 1 );
        final ExpiringMemoryNotFoundCache nfc = new ExpiringMemoryNotFoundCache( indyConfig );

        WeftExecutorService rescanService =
                        new PoolWeftExecutorService( "test-rescan-executor", (ThreadPoolExecutor) Executors.newCachedThreadPool(), 2, 10f, false,null, null );

        final DownloadManager downloadManager =
                new DefaultDownloadManager( storeManager, core.getTransferManager(), core.getLocationExpander(),
                                            new MockInstance<>( new MockContentAdvisor() ), nfc, rescanService );

        WeftExecutorService contentAccessService =
                        new PoolWeftExecutorService( "test-content-access-executor", (ThreadPoolExecutor) Executors.newCachedThreadPool(), 2, 10f, false,null, null );
        DirectContentAccess dca =
                new DefaultDirectContentAccess( downloadManager, contentAccessService );

        ContentDigester contentDigester = new DefaultContentDigester( dca, new CacheHandle<>(
                "content-metadata", contentMetadata ) );

        final ContentManager contentManager =
                new DefaultContentManager( storeManager, downloadManager, mapper, new SpecialPathManagerImpl(),
                                           new MemoryNotFoundCache(), contentDigester, new ContentGeneratorManager() );

        DataFileManager dfm = new DataFileManager( temp.newFolder(), new DataFileEventManager() );
        final TemplatingEngine templates = new TemplatingEngine( new GStringTemplateEngine(), dfm );
        final ContentController contentController =
                new ContentController( storeManager, contentManager, templates, mapper, new MimeTyper() );

        KeycloakConfig kcConfig = new KeycloakConfig();
        kcConfig.setEnabled( false );

        final KeycloakProxyAuthenticator auth = new KeycloakProxyAuthenticator( kcConfig, config );

        ScriptEngine scriptEngine = new ScriptEngine( dfm );

        WeftExecutorService transferService =
              new PoolWeftExecutorService( "test-mitm-transfers", (ThreadPoolExecutor) Executors.newCachedThreadPool(), 2, 10f, false,null, null );
        ProxyTransfersExecutor handler = new ProxyTransfersExecutor( transferService );

        proxy = new HttpProxy( config, bootOpts,
                               new ProxyAcceptHandler( config, storeManager, contentController, auth, core.getCache(),
                                                       scriptEngine, new MDCManager(), null, null,
                                                       new CacheProducer( null, cacheManager, null ),
                                                       handler ) );
        proxy.start();
    }

    @After
    public void teardown()
    {
        if ( proxy != null )
        {
            proxy.stop();
        }
    }

    @Test
    public void proxySimplePomAndAutoCreateRemoteRepo()
            throws Exception
    {
        final String testRepo = "test";
        final PomRef pom = loadPom( "simple.pom", Collections.emptyMap() );
        final String url = server.formatUrl( testRepo, pom.path );
        server.expect( url, 200, pom.pom );

        final HttpGet get = new HttpGet( url );
        final CloseableHttpClient client = proxiedHttp();
        CloseableHttpResponse response = null;

        InputStream stream = null;
        try
        {
            response = client.execute( get, proxyContext( USER, PASS ) );
            stream = response.getEntity().getContent();
            final String resultingPom = IOUtils.toString( stream );

            assertThat( resultingPom, notNullValue() );
            assertThat( resultingPom, equalTo( pom.pom ) );
        }
        finally
        {
            IOUtils.closeQuietly( stream );
            HttpUtil.cleanupResources( client, get, response );
        }

        final RemoteRepository remoteRepo = (RemoteRepository) storeManager.getArtifactStore(
                new StoreKey( GENERIC_PKG_KEY, StoreType.remote, "httprox_127-0-0-1_" + server.getPort() ) );

        assertThat( remoteRepo, notNullValue() );
        assertThat( remoteRepo.getUrl(), equalTo( server.getBaseUri() ) );
    }

    @Test
    public void proxy404()
            throws Exception
    {
        final String testRepo = "test";
        final PomRef pom = loadPom( "simple.pom", Collections.emptyMap() );
        final String url = server.formatUrl( testRepo, pom.path );

        final HttpGet get = new HttpGet( url );
        final CloseableHttpClient client = proxiedHttp();
        CloseableHttpResponse response = null;

        final InputStream stream = null;
        try
        {
            response = client.execute( get, proxyContext( USER, PASS ) );
            assertThat( response.getStatusLine().getStatusCode(), equalTo( HttpStatus.SC_NOT_FOUND ) );
        }
        finally
        {
            IOUtils.closeQuietly( stream );
            HttpUtil.cleanupResources( client, get, response );
        }

        final RemoteRepository remoteRepo = (RemoteRepository) storeManager.getArtifactStore(
                new StoreKey( GENERIC_PKG_KEY, StoreType.remote, "httprox_127-0-0-1_" + server.getPort() ) );

        assertThat( remoteRepo, notNullValue() );
        assertThat( remoteRepo.getUrl(), equalTo( server.getBaseUri() ) );
    }

    @Test
    @Ignore( "return upstream 5xx class errors as 404 to keep Maven & co happy" )
    public void proxy500As502()
            throws Exception
    {
        final String testRepo = "test";
        final PomRef pom = loadPom( "simple.pom", Collections.emptyMap() );
        final String url = server.formatUrl( testRepo, pom.path );
        server.registerException( new File( "/" + testRepo, pom.path ).getPath(), "Expected exception", 500 );

        final HttpGet get = new HttpGet( url );
        final CloseableHttpClient client = proxiedHttp();
        CloseableHttpResponse response = null;

        final InputStream stream = null;
        try
        {
            response = client.execute( get, proxyContext( USER, PASS ) );
            assertThat( response.getStatusLine().getStatusCode(), equalTo( HttpStatus.SC_BAD_GATEWAY ) );
        }
        finally
        {
            IOUtils.closeQuietly( stream );
            HttpUtil.cleanupResources( client, get, response );
        }

        final RemoteRepository remoteRepo = (RemoteRepository) storeManager.getArtifactStore(
                new StoreKey( GENERIC_PKG_KEY, StoreType.remote, "httprox_127-0-0-1" ) );

        assertThat( remoteRepo, notNullValue() );
        assertThat( remoteRepo.getUrl(), equalTo( server.getBaseUri() ) );
    }

    protected HttpClientContext proxyContext( final String user, final String pass )
    {
        final CredentialsProvider creds = new BasicCredentialsProvider();
        creds.setCredentials( new AuthScope( HOST, proxyPort ), new UsernamePasswordCredentials( user, pass ) );
        final HttpClientContext ctx = HttpClientContext.create();
        ctx.setCredentialsProvider( creds );

        return ctx;
    }

    protected CloseableHttpClient proxiedHttp()
            throws Exception
    {
        final HttpRoutePlanner planner = new DefaultProxyRoutePlanner( new HttpHost( HOST, proxyPort ) );
        return HttpClients.custom().setRoutePlanner( planner ).build();
    }

    protected PomRef loadPom( final String name, final Map<String, String> substitutions )
    {
        try
        {
            final String resource = name.endsWith( ".pom" ) ? name : name + ".pom";
            logger.info( "Loading POM: {}", resource );

            final InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream( resource );

            String pom = IOUtils.toString( stream );
            IOUtils.closeQuietly( stream );

            for ( final Map.Entry<String, String> entry : substitutions.entrySet() )
            {
                pom = pom.replace( "@" + entry.getKey() + "@", entry.getValue() );
            }

            final PomPeek peek = new PomPeek( pom, false );
            final ProjectVersionRef gav = peek.getKey();

            final String path =
                    String.format( "%s/%s/%s/%s-%s.pom", gav.getGroupId().replace( '.', '/' ), gav.getArtifactId(),
                                   gav.getVersionString(), gav.getArtifactId(), gav.getVersionString() );

            return new PomRef( pom, path );
        }
        catch ( final Exception e )
        {
            e.printStackTrace();
            fail( "Failed to read POM from: " + name );
        }

        return null;
    }

    protected static final class PomRef
    {
        PomRef( final String pom, final String path )
        {
            this.pom = pom;
            this.path = path;
        }

        protected final String pom;

        protected final String path;
    }

}

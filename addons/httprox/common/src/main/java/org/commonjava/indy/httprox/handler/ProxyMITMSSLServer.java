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

import org.apache.http.ProtocolVersion;
import org.apache.http.RequestLine;
import org.apache.http.message.BasicRequestLine;
import org.commonjava.indy.core.ctl.ContentController;
import org.commonjava.indy.httprox.conf.HttproxConfig;
import org.commonjava.indy.httprox.util.CertificateAndKeys;
import org.commonjava.indy.httprox.util.HttpConduitWrapper;
import org.commonjava.indy.httprox.util.ProxyMeter;
import org.commonjava.indy.httprox.util.ProxyResponseHelper;
import org.commonjava.indy.httprox.util.OutputStreamSinkChannel;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.subsys.http.util.UserPass;
import org.commonjava.maven.galley.spi.cache.CacheProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.BufferedOutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.channels.SocketChannel;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static java.lang.Integer.parseInt;
import static javax.ws.rs.HttpMethod.GET;
import static javax.ws.rs.HttpMethod.HEAD;
import static org.commonjava.propulsor.boot.PortFinder.findOpenPort;
import static org.commonjava.indy.httprox.util.CertUtils.createKeyStore;
import static org.commonjava.indy.httprox.util.CertUtils.createSignedCertificateAndKey;
import static org.commonjava.indy.httprox.util.CertUtils.getPrivateKey;
import static org.commonjava.indy.httprox.util.CertUtils.loadX509Certificate;
import static org.commonjava.indy.httprox.util.HttpProxyConstants.GET_METHOD;

/**
 * We create server crt based on the host name and use a CA crt to sign it. We send the CA crt to client and they use
 * it when sending requests to MITM server.
 */
public class ProxyMITMSSLServer implements Runnable
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private static final int FIND_OPEN_PORT_MAX_RETRIES = 16;

    private static final int GET_SOCKET_CHANNEL_MAX_RETRIES = 32;

    private static final int GET_SOCKET_CHANNEL_WAIT_TIME_IN_MILLISECONDS = 500;

    private static final int ACCEPT_SOCKET_WAIT_TIME_IN_MILLISECONDS = 20000;

    private final String host;

    private final int port;

    private final HttproxConfig config;

    private ProxyMeter meterTemplate;

    private volatile int serverPort;

    private final String trackingId;

    private final UserPass proxyUserPass;

    private final ContentController contentController;

    private final CacheProvider cacheProvider;

    private final ProxyResponseHelper proxyResponseHelper;

    private volatile boolean isCancelled = false;

    public ProxyMITMSSLServer( String host, int port, String trackingId, UserPass proxyUserPass,
                               ProxyResponseHelper proxyResponseHelper, ContentController contentController,
                               CacheProvider cacheProvider, HttproxConfig config, final ProxyMeter meterTemplate )
    {
        this.host = host;
        this.port = port;
        this.trackingId = trackingId;
        this.proxyUserPass = proxyUserPass;
        this.proxyResponseHelper = proxyResponseHelper;
        this.contentController = contentController;
        this.cacheProvider = cacheProvider;
        this.config = config;
        this.meterTemplate = meterTemplate;
    }

    @Override
    public void run()
    {
        try
        {
            execute();
        }
        catch ( Exception e )
        {
            logger.warn( "Exception failed", e );
        }
    }

    private volatile boolean started;

    private char[] keystorePassword = "password".toCharArray(); // keystore password can not be null

    // TODO: What are the memory footprint implications of this? It seems like these will never be purged.
    private static Map<String, HostContext> hostContextMap = new ConcurrentHashMap(); // cache keystore and socket factory, key: hostname

    /**
     * Generate the keystore on-the-fly and initiate SSL socket factory.
     */
    private SSLServerSocketFactory getSSLServerSocketFactory( String host ) throws Exception
    {
        AtomicReference<Exception> err = new AtomicReference<>();
        HostContext context = hostContextMap.computeIfAbsent( host, (k) -> {
           try
           {
               final KeyStore ks = getKeyStore(k);
               final KeyManagerFactory kmf = KeyManagerFactory.getInstance( KeyManagerFactory.getDefaultAlgorithm() );
               kmf.init( ks, keystorePassword );

               final SSLContext sc = SSLContext.getInstance( "TLS" );
               sc.init( kmf.getKeyManagers(), null, null );
               final SSLServerSocketFactory factory = sc.getServerSocketFactory();
               return new HostContext(ks, factory);
           }
           catch ( Exception e )
           {
               err.set( e );
           }
           return null;
        } );
        if ( context == null || err.get() != null )
        {
            throw err.get();
        }

        return context.getSslSocketFactory();
    }

    private KeyStore getKeyStore( String host ) throws Exception
    {
        PrivateKey caKey = getPrivateKey( config.getMITMCAKey() );
        X509Certificate caCert = loadX509Certificate( new File( config.getMITMCACert() ));

        String dn = config.getMITMDNTemplate().replace( "<host>", host ); // e.g., "CN=<host>, O=Test Org"

        CertificateAndKeys certificateAndKeys = createSignedCertificateAndKey( dn, caCert, caKey, false );
        Certificate signedCertificate = certificateAndKeys.getCertificate();
        logger.debug( "Create signed cert:\n" + signedCertificate.toString() );

        KeyStore ks = createKeyStore();
        String alias = host;
        ks.setKeyEntry( alias, certificateAndKeys.getPrivateKey(), keystorePassword, new Certificate[] { signedCertificate, caCert } );
        return ks;
    }

    private void execute() throws Exception
    {
        ProxyMeter meter = null;
        SSLServerSocketFactory sslServerSocketFactory = getSSLServerSocketFactory( host );

        serverPort = findOpenPort( FIND_OPEN_PORT_MAX_RETRIES );

        // TODO: What is the performance implication of opening a new server socket each time? Should we try to cache these?
        try ( ServerSocket sslServerSocket = sslServerSocketFactory.createServerSocket( serverPort ) )
        {

            sslServerSocket.setSoTimeout( ACCEPT_SOCKET_WAIT_TIME_IN_MILLISECONDS ); //in case the response handler times out
            started = true;

            if ( !isCancelled )
            {
                try ( Socket socket = sslServerSocket.accept() )
                {
                    logger.debug( "MITM server started, {}", sslServerSocket );
                    long startNanos = System.nanoTime();
                    String method = null;
                    String requestLine = null;

                    meter = meterTemplate.copy( startNanos, method, requestLine );

                    socket.setSoTimeout( (int) TimeUnit.MINUTES.toMillis( config.getMITMSoTimeoutMinutes() ) );

                    logger.debug( "MITM server accepted" );
                    try ( BufferedReader in = new BufferedReader( new InputStreamReader( socket.getInputStream() ) ) )
                    {

                        // TODO: Should we implement a while loop around this with some sort of read timeout, in case multiple requests are inlined?
                        // In principle, any sort of network communication is permitted over this port, but even if we restrict this to
                        // HTTPS only, couldn't there be multiple requests over the port at a time?
                        String path = null;
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ( ( line = in.readLine() ) != null )
                        {
                            sb.append( line + "\n" );
                            if ( line.startsWith( GET ) || line.startsWith( HEAD ) ) // only care about GET/HEAD
                            {
                                String[] toks = line.split("\\s+");
                                method = toks[0];
                                path = toks[1];
                                requestLine = line;
                            }
                            else if ( line.isEmpty() )
                            {
                                logger.debug( "Get empty line and break" );
                                break;
                            }
                        }

                        logger.debug( "Request:\n{}", sb.toString() );

                        if ( path != null )
                        {
                            try
                            {
                                transferRemote( socket, host, port, method, path, meter );
                            }
                            catch ( Exception e )
                            {
                                logger.error( "Transfer remote failed", e );
                            }
                        }
                        else
                        {
                            logger.debug( "MITM server failed to get request from client" );
                        }
                    }
                }
            }
            logger.debug( "MITM server closed" );
        }
        finally
        {
            if (meter != null)
            {
                meter.reportResponseSummary();
            }
            isCancelled = false;
            started = false;
        }
    }

    private void transferRemote( Socket socket, String host, int port, String method, String path,
                                 final ProxyMeter meter ) throws Exception
    {
        String protocol = "https";
        String auth = null;
        String query = null;
        String fragment = null;
        URI uri = new URI( protocol, auth, host, port, path, query, fragment );
        URL remoteUrl = uri.toURL();
        logger.debug( "Requesting remote URL: {}", remoteUrl.toString() );

        ArtifactStore store = proxyResponseHelper.getArtifactStore( trackingId, remoteUrl );
        try (BufferedOutputStream out = new BufferedOutputStream( socket.getOutputStream() ))
        {
            HttpConduitWrapper http =
                            new HttpConduitWrapper( new OutputStreamSinkChannel( out ), null, contentController,
                                                    cacheProvider );
            proxyResponseHelper.transfer( http, store, remoteUrl.getPath(), GET_METHOD.equals( method ),
                                          proxyUserPass, meter );
            out.flush();
            http.close();
        }
    }

    public SocketChannel getSocketChannel() throws InterruptedException, ExecutionException
    {
        for ( int i = 0; i < GET_SOCKET_CHANNEL_MAX_RETRIES; i++ )
        {
            logger.debug( "Try to get socket channel #{}", i + 1 );
            if ( started )
            {
                logger.debug( "Server started" );
                try
                {
                    return openSocketChannelToMITM();
                }
                catch ( IOException e )
                {
                    throw new ExecutionException( "Open socket channel to MITM failed", e );
                }
            }
            else
            {
                logger.debug( "Server not started, wait..." );
                TimeUnit.MILLISECONDS.sleep( GET_SOCKET_CHANNEL_WAIT_TIME_IN_MILLISECONDS );
            }
        }
        return null;
    }

    private SocketChannel openSocketChannelToMITM() throws IOException
    {
        logger.debug( "Open socket channel to MITM server, localhost:{}", serverPort );

        InetSocketAddress target = new InetSocketAddress( "localhost", serverPort );
        return SocketChannel.open( target );
    }

    /**
     * Signal the request and response should be cancelled.
     */
    public void stop()
    {
        isCancelled = true;
        logger.debug( "MITM server timed out waiting for response creation" );
    }

    class HostContext{
       private KeyStore keystore;
       private SSLServerSocketFactory sslSocketFactory;
       HostContext(KeyStore ks, SSLServerSocketFactory factory){
          keystore = ks;
          sslSocketFactory = factory;
       }
      KeyStore getKeystore() {
         return keystore;
      }
      SSLServerSocketFactory getSslSocketFactory() {
         return sslSocketFactory;
      }
       
    }
}

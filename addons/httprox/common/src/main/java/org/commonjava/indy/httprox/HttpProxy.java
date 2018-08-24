/**
 * Copyright (C) 2011-2018 Red Hat, Inc. (https://github.com/Commonjava/indy)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.indy.httprox;

import org.commonjava.indy.action.IndyLifecycleException;
import org.commonjava.indy.action.ShutdownAction;
import org.commonjava.indy.action.StartupAction;
import org.commonjava.indy.boot.BootOptions;
import org.commonjava.indy.boot.PortFinder;
import org.commonjava.indy.boot.jaxrs.XnioService;
import org.commonjava.indy.httprox.conf.HttproxConfig;
import org.commonjava.indy.httprox.handler.ProxyAcceptHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.StreamConnection;
import org.xnio.Xnio;
import org.xnio.XnioWorker;
import org.xnio.channels.AcceptingChannel;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.IOException;
import java.net.InetSocketAddress;

@ApplicationScoped
public class HttpProxy
        implements XnioService
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private HttproxConfig config;

    @Inject
    private ProxyAcceptHandler acceptHandler;

    private AcceptingChannel<StreamConnection> server;

    protected HttpProxy()
    {
    }

    public HttpProxy( final HttproxConfig config, ProxyAcceptHandler acceptHandler )
    {
        this.config = config;
        this.acceptHandler = acceptHandler;
    }

    @Override
    public void start( final XnioWorker worker, final BootOptions bootOptions )
    {
        if ( !config.isEnabled() )
        {
            logger.info( "HTTProx proxy is disabled." );
            return;
        }

        String bind;
        if ( bootOptions.getBind() == null )
        {
            bind = "0.0.0.0";
        }
        else
        {
            bind = bootOptions.getBind();
        }

        logger.info( "Starting HTTProx proxy on: {}:{}", bind, config.getPort() );

        OptionMap socketOptions = OptionMap.builder()
                                           .set(Options.WORKER_IO_THREADS, worker.getIoThreadCount())
                                           .set(Options.TCP_NODELAY, true)
                                           .set(Options.REUSE_ADDRESSES, true)
                                           .set(Options.BALANCING_TOKENS, 1)
                                           .set(Options.BALANCING_CONNECTIONS, 2)
                                           .set(Options.BACKLOG, 1000)
                                           .getMap();

        final InetSocketAddress addr;
        if ( config.getPort() < 1 )
        {
            ThreadLocal<InetSocketAddress> using = new ThreadLocal<>();
            server = PortFinder.findPortFor( 16, ( foundPort ) -> {
                InetSocketAddress a = new InetSocketAddress( bind, config.getPort() );
                AcceptingChannel<StreamConnection> result =
                        worker.createStreamConnectionServer( a, acceptHandler, socketOptions );

                result.resumeAccepts();
                using.set( a );

                return result;
            } );

            addr = using.get();
            config.setPort( addr.getPort() );
        }
        else
        {
            addr = new InetSocketAddress( bind, config.getPort() );
            try
            {
                server = worker.createStreamConnectionServer( addr, acceptHandler, OptionMap.EMPTY );
            }
            catch ( IOException e )
            {
                logger.error( String.format( "Failed to start HTTProx general content proxy: %s", e.getMessage() ), e );
            }

            server.resumeAccepts();
        }
    }

//    @Override
//    public void start()
//            throws IndyLifecycleException
//    {
//        if ( !config.isEnabled() )
//        {
//            logger.info( "HTTProx proxy is disabled." );
//            return;
//        }
//
//        String bind;
//        if ( bootOptions.getBind() == null )
//        {
//            bind = "0.0.0.0";
//        }
//        else
//        {
//            bind = bootOptions.getBind();
//        }
//
//        logger.info( "Starting HTTProx proxy on: {}:{}", bind, config.getPort() );
//
//        XnioWorker worker;
//        try
//        {
//            // borrowed from Undertow.class
//            int ioThreads = Math.max( Runtime.getRuntime().availableProcessors(), 2 );
//            int workerThreads = ioThreads * 8;
//            OptionMap optionMap = OptionMap.builder()
//                                           .set( Options.WORKER_IO_THREADS, ioThreads )
//                                           .set( Options.CONNECTION_HIGH_WATER, 1000000 )
//                                           .set( Options.CONNECTION_LOW_WATER, 1000000 )
//                                           .set( Options.WORKER_TASK_CORE_THREADS, workerThreads )
//                                           .set( Options.WORKER_TASK_MAX_THREADS, workerThreads )
//                                           .set( Options.TCP_NODELAY, true )
//                                           .set( Options.CORK, true )
//                                           .getMap();
//
//            worker = Xnio.getInstance().createWorker( optionMap );
//
//            OptionMap socketOptions = OptionMap.builder()
//                                               .set(Options.WORKER_IO_THREADS, worker.getIoThreadCount())
//                                               .set(Options.TCP_NODELAY, true)
//                                               .set(Options.REUSE_ADDRESSES, true)
//                                               .set(Options.BALANCING_TOKENS, 1)
//                                               .set(Options.BALANCING_CONNECTIONS, 2)
//                                               .set(Options.BACKLOG, 1000)
//                                               .getMap();
//
//            final InetSocketAddress addr;
//            if ( config.getPort() < 1 )
//            {
//                ThreadLocal<InetSocketAddress> using = new ThreadLocal<>();
//                ThreadLocal<IOException> errorHolder = new ThreadLocal<>();
//                server = PortFinder.findPortFor( 16, ( foundPort ) -> {
//                    InetSocketAddress a = new InetSocketAddress( bind, config.getPort() );
//                    AcceptingChannel<StreamConnection> result =
//                            worker.createStreamConnectionServer( a, acceptHandler, socketOptions );
//
//                    result.resumeAccepts();
//                    using.set( a );
//
//                    return result;
//                } );
//
//                addr = using.get();
//                config.setPort( addr.getPort() );
//            }
//            else
//            {
//                addr = new InetSocketAddress( bind, config.getPort() );
//                server = worker.createStreamConnectionServer( addr, acceptHandler, OptionMap.EMPTY );
//
//                server.resumeAccepts();
//            }
//            logger.info( "HTTProxy listening on: {}", addr );
//        }
//        catch ( IllegalArgumentException | IOException e )
//        {
//            throw new IndyLifecycleException( "Failed to start HTTProx general content proxy: %s", e, e.getMessage() );
//        }
//    }

    //    @Override
//    public void stop()
//    {
//        if ( server != null )
//        {
//            try
//            {
//                logger.info( "stopping server" );
//                server.suspendAccepts();
//                server.close();
//            }
//            catch ( final IOException e )
//            {
//                logger.error( "Failed to stop: " + e.getMessage(), e );
//            }
//        }
//    }
//
//    @Override
//    public String getId()
//    {
//        return "httproxy-listener";
//    }
//
//    @Override
//    public int getStartupPriority()
//    {
//        return 1;
//    }
//
//    @Override
//    public int getShutdownPriority()
//    {
//        return 99;
//    }

}

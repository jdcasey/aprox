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

import org.commonjava.indy.httprox.conf.HttproxConfig;
import org.commonjava.indy.httprox.util.ChannelUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xnio.conduits.ConduitStreamSinkChannel;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.commonjava.indy.httprox.util.ChannelUtils.DEFAULT_READ_BUF_SIZE;
import static org.commonjava.indy.httprox.util.ChannelUtils.flush;

/**
 * Created by ruhan on 9/6/18.
 */
public class ProxySSLTunnel implements Runnable
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    //private volatile Selector selector; // selecting READ events for target channel

    //private static final long SELECTOR_TIMEOUT = 60 * 1000; // 60 seconds

    private final ConduitStreamSinkChannel sinkChannel;

    private final SocketChannel socketChannel;

    private volatile boolean closed;

    private final HttproxConfig config;

    public ProxySSLTunnel( ConduitStreamSinkChannel sinkChannel, SocketChannel socketChannel, HttproxConfig config )
    {
        this.sinkChannel = sinkChannel;
        this.socketChannel = socketChannel;
        this.config = config;
    }

    @Override
    public void run()
    {
        try
        {
            pipeTargetToSinkChannel( sinkChannel, socketChannel );
        }
        catch ( Exception e )
        {
            logger.error( "Pipe to sink channel failed", e );
        }
    }

    private void pipeTargetToSinkChannel( ConduitStreamSinkChannel sinkChannel, SocketChannel targetChannel )
                    throws IOException
    {
        targetChannel.socket().setSoTimeout( (int) TimeUnit.MINUTES.toMillis( config.getMITMSoTimeoutMinutes() ) );
        InputStream inStream = new BufferedInputStream( targetChannel.socket().getInputStream() );
        ReadableByteChannel wrappedChannel = Channels.newChannel( inStream );

        ByteBuffer byteBuffer = ByteBuffer.allocate( DEFAULT_READ_BUF_SIZE );

        int total = 0;
        while ( true )
        {
            if ( closed )
            {
                logger.debug( "Tunnel closed" );
                break;
            }

            int read = -1;
            try
            {
                read = wrappedChannel.read( byteBuffer );
            }
            catch ( IOException e )
            {
                logger.debug( "Read target channel breaks, {}", e.toString() );
                break;
            }

            if ( read <= 0 )
            {
                logger.debug( "Read breaks, read: {}", read );
                break;
            }

            //limit is set to current position and position is set to zero
            byteBuffer.flip();

            //final byte[] bytes = new byte[byteBuffer.limit()];
            //byteBuffer.get( bytes );

            logger.debug( "Write to sink channel, size: {}", byteBuffer.limit() );
            ChannelUtils.write( sinkChannel, byteBuffer );
            sinkChannel.flush();
            byteBuffer.clear();

            total += read;
        }

        logger.debug( "Write to sink channel complete, transferred: {}", total );

        flush( sinkChannel );
        sinkChannel.shutdownWrites();
        sinkChannel.close();

        closed = true;

    }

/*
    private void pipeTargetToSinkChannel( ConduitStreamSinkChannel sinkChannel, SocketChannel targetChannel )
                    throws IOException
    {
        logger.trace( "Start target to sink channel pipe" );
        selector = Selector.open();

        targetChannel.configureBlocking( false );
        targetChannel.register( selector, SelectionKey.OP_READ );

        breakPipe:
        while ( true )
        {
            if ( closed )
            {
                break;
            }

            logger.trace( "Select on target channel" );
            int readyChannels = selector.select( SELECTOR_TIMEOUT );

            logger.trace( "Select returns, {} ready channels", readyChannels );
            if ( readyChannels == 0 || !selector.isOpen() )
            {
                logger.trace( "No ready channel or selector closed, break" );
                break;
            }

            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> iterator = selectedKeys.iterator();
            while ( iterator.hasNext() )
            {
                SelectionKey key = iterator.next();
                if ( key.isReadable() )
                {
                    logger.trace( "Read from target channel" );
                    byte[] bytes;
                    try
                    {
                        bytes = doRead( (SocketChannel) key.channel() );
                    }
                    catch ( IOException e )
                    {
                        if ( e.getMessage().contains( "Connection reset by peer" ) )
                        {
                            logger.warn( e.getMessage() );
                            break breakPipe;
                        }
                        else
                        {
                            throw e;
                        }
                    }

                    logger.trace( "Read done, write to sink channel, bytes: {}", bytes.length );
                    if ( bytes.length > 0 && sinkChannel.isOpen() )
                    {
                        sinkChannel.write( ByteBuffer.wrap( bytes ) );
                    }
                    else
                    {
                        // read 0 or -1 means the other side have closed the socket
                        logger.debug( "Peer closed socket" );
                        break breakPipe;
                    }
                }
                iterator.remove();
            }
        }
    }

    private byte[] doRead( SocketChannel channel ) throws IOException
    {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();

        ByteBuffer buf = ByteBuffer.allocate( DEFAULT_READ_BUF_SIZE );
        int read = -1;
        while ( ( read = channel.read( buf ) ) > 0 )
        {
            buf.flip();
            final byte[] bbuf = new byte[buf.limit()];
            buf.get( bbuf );
            bos.write( bbuf );
        }
        return bos.toByteArray();
    }
*/

    public void write( byte[] bytes ) throws IOException
    {
        socketChannel.write( ByteBuffer.wrap( bytes ) );
    }

    public void close()
    {
        try
        {
            //selector.close(); // wake it up to complete the tunnel
            socketChannel.close();
        }
        catch ( IOException e )
        {
            logger.error( "Close tunnel selector failed", e );
        }
    }
}

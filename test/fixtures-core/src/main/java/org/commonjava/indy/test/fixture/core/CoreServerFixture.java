/**
 * Copyright (C) 2011-2017 Red Hat, Inc. (https://github.com/Commonjava/indy)
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
package org.commonjava.indy.test.fixture.core;

import static org.commonjava.indy.boot.PortFinder.findOpenPort;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Properties;

import org.codehaus.plexus.interpolation.InterpolationException;
import org.commonjava.indy.boot.IndyBootException;
import org.commonjava.indy.boot.BootFinder;
import org.commonjava.indy.boot.BootInterface;
import org.commonjava.indy.boot.IndyBootOptions;
import org.commonjava.indy.boot.BootStatus;
import org.junit.rules.TemporaryFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CoreServerFixture
    implements Closeable
{

    private static final int MAX_PORTGEN_TRIES = 16;

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private final IndyBootOptions options;

    private final BootInterface booter;

    private BootStatus status;

    private TemporaryFolder temp;

    public CoreServerFixture( final TemporaryFolder temp )
        throws IndyBootException, IOException
    {
        this( BootFinder.find(), newBootOptions( null, temp.newFolder( "indy-home" ).getAbsolutePath() ) );
        this.temp = temp;
    }

    public CoreServerFixture( final BootInterface booter, final IndyBootOptions options )
    {
        this.booter = booter;
        this.options = editBootOptions( options, null );
        this.temp = newTemporaryFolder();
    }

    public CoreServerFixture()
        throws IndyBootException, IOException
    {
        this.booter = BootFinder.find();
        this.temp = newTemporaryFolder();
        this.options = newBootOptions( null, temp.newFolder( "indy-home" )
                                                 .getAbsolutePath() );
    }

    public CoreServerFixture( final BootInterface booter, final File bootDefaults, final File indyHome )
    {
        this( booter, newBootOptions( bootDefaults, indyHome.getAbsolutePath() ) );
    }

    public CoreServerFixture( final BootInterface booter, final File indyHome,
                                   final IndyBootOptions bootOptions )
    {
        this( booter, editBootOptions( bootOptions, indyHome.getAbsolutePath() ) );
    }

    public TemporaryFolder getTempFolder()
    {
        return temp;
    }

    public String getUrl()
    {
        return String.format( "http://127.0.0.1:%d/api/", options.getPort() );
    }

    public BootStatus getBootStatus()
    {
        return status;
    }

    public IndyBootOptions getBootOptions()
    {
        return options;
    }

    public boolean isStarted()
    {
        return status.isSuccess();
    }

    public void start()
        throws Exception
    {
        if ( options.isHelp() )
        {
            throw new IllegalArgumentException( "Cannot start server when help option is enabled." );
        }

        logger.info( "\n\n\n\nIndy STARTING UP\n\n\n" );
        status = booter.start( options );

        if ( status == null )
        {
            throw new IllegalStateException( "Failed to start server!" );
        }
        else if ( status.isFailed() )
        {
            throw new IllegalStateException( "Failed to start server!", status.getError() );
        }
    }

    @Override
    public void close()
    {
        stop();
    }

    public void stop()
    {
        if ( status != null && status.isSuccess() )
        {
            logger.info( "\n\n\n\nIndy SHUTTING DOWN\n\n\n" );
            booter.stop();
        }

        if ( temp != null )
        {
            temp.delete();
        }
    }

    private static TemporaryFolder newTemporaryFolder()
    {
        final TemporaryFolder folder = new TemporaryFolder();
        try
        {
            folder.create();
        }
        catch ( final IOException e )
        {
            throw new IllegalStateException( "Failed to init TemporaryFolder: " + e.getMessage(), e );
        }

        return folder;
    }

    private static IndyBootOptions newBootOptions( final File bootDefaults, final String indyHome )
    {
        final Properties properties = System.getProperties();
        properties.setProperty( "indy.home", indyHome );
        System.setProperties( properties );

        try
        {
            final IndyBootOptions options = new IndyBootOptions( bootDefaults, indyHome );
            options.setPort( findOpenPort( MAX_PORTGEN_TRIES ) );

            return options;
        }
        catch ( IOException | InterpolationException e )
        {
            throw new IllegalStateException( "Cannot start core Indy server with the given configuration: "
                + e.getMessage(), e );
        }
    }

    private static IndyBootOptions editBootOptions( final IndyBootOptions options, final String indyHome )
    {
        if ( indyHome != null )
        {
            final Properties properties = System.getProperties();
            properties.setProperty( "indy.home", indyHome );
            System.setProperties( properties );
        }

        options.setPort( findOpenPort( MAX_PORTGEN_TRIES ) );

        return options;
    }

}

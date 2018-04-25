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
package org.commonjava.indy.boot;

import org.codehaus.plexus.interpolation.InterpolationException;
import org.commonjava.propulsor.boot.BootException;
import org.commonjava.propulsor.boot.BootOptions;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.File;
import java.io.IOException;

public class IndyBootOptions
        extends BootOptions
{

    public static final String BIND_PROP = "bind";

    public static final String PORT_PROP = "port";

    public static final String CONFIG_PROP = "config";

    public static final String WORKERS_PROP = "workers";

    public static final String CONTEXT_PATH_PROP = "context-path";

    public static final String DEFAULT_BIND = "0.0.0.0";

    public static final int DEFAULT_PORT = 8080;

    public static final String INDY_HOME_PROP = "indy.home";

    public static final String CONFIG_PATH_PROP = "indy.config";

    @Option( name = "-i", aliases = { "--interface", "--bind", "--listen" }, usage = "Bind to a particular IP address (default: 0.0.0.0, or all available)" )
    private String bind;

    @Option( name = "-p", aliases = { "--port" }, usage = "Use different port (default: 8080)" )
    private Integer port;

    @Option( name = "-C", aliases = { "--context-path" }, usage = "Specify a root context path for all of indy to use" )
    private String contextPath;
    
    @Option( name = "-S", aliases = { "--secure-config-path" }, usage = "Specify config path of security file" )
    private String secureConfig;
    
    @Option( name = "-R", aliases = { "--secure-realm" }, usage = "Specify security realm" )
    private String secureRealm;
    
    @Override
    public String getApplicationName()
    {
        return "Indy";
    }

    @Override
    public String getHomeSystemProperty()
    {
        return INDY_HOME_PROP;
    }

    @Override
    public String getConfigSystemProperty()
    {
        return CONFIG_PATH_PROP;
    }

    @Override
    public String getHomeEnvar()
    {
        return null;
    }

    public IndyBootOptions()
    {

    }

    
    public IndyBootOptions( final String indyHome )
            throws IOException, InterpolationException
        {
            super( indyHome );
        }
    

    public IndyBootOptions( final File bootDefaults, final String indyHome )
        throws IOException, InterpolationException
    {
        super( indyHome );
        load( bootDefaults, indyHome );

        if ( bind == null )
        {
            bind = resolve( getBootProperties().getProperty( BIND_PROP, DEFAULT_BIND ) );
        }

        if ( port == null )
        {
            port = Integer.parseInt( resolve( getBootProperties().getProperty( PORT_PROP, Integer.toString( DEFAULT_PORT ) ) ) );
        }

        if ( getConfig() == null )
        {
            final String defaultConfigPath = new File( indyHome, "etc/indy/main.conf" ).getPath();
            setConfig(resolve( getBootProperties().getProperty( CONFIG_PROP, defaultConfigPath ) ) );
        }

        contextPath = getBootProperties().getProperty( CONTEXT_PATH_PROP, contextPath );
    }

    public void doCopy( final BootOptions bootOptions )
    {
        IndyBootOptions options = (IndyBootOptions) bootOptions;
        this.bind = options.bind;
        this.port = options.port;
        this.contextPath = options.contextPath;
    }

    public String getBind()
    {
        return bind;
    }

    public int getPort()
    {
        return port;
    }

    public IndyBootOptions setBind( final String bind )
    {
        this.bind = bind;
        return this;
    }

    public void setPort( final int port )
    {
        this.port = port;
    }

    public String getContextPath()
    {
        if ( contextPath == null )
        {
            contextPath = "";
        }

        if ( contextPath.startsWith( "/" ) )
        {
            contextPath = contextPath.substring( 1 );
        }

        return contextPath;
    }

    public String getDeploymentName()
    {
        return "Indy";
    }

    public void setContextPath( final String contextPath )
    {
        if ( contextPath == null )
        {
            this.contextPath = "";
        }
        else if ( contextPath.startsWith( "/" ) )
        {
            this.contextPath = contextPath.substring( 1 );
        }
        else
        {
            this.contextPath = contextPath;
        }
    }

    public boolean parseArgs( final String[] args )
            throws BootException
    {
        final CmdLineParser parser = new CmdLineParser( this );
        boolean canStart = true;
        try
        {
            parser.parseArgument( args );
        }
        catch ( final CmdLineException e )
        {
            throw new BootException( "Failed to parse command-line args: %s", e, e.getMessage() );
        }

        if ( isHelp() )
        {
            printUsage( parser, null );
            canStart = false;
        }

        return canStart;
    }

    public static void printUsage( final CmdLineParser parser, final CmdLineException error )
    {
        if ( error != null )
        {
            System.err.println( "Invalid option(s): " + error.getMessage() );
            System.err.println();
        }

        System.err.println( "Usage: $0 [OPTIONS] [<target-path>]" );
        System.err.println();
        System.err.println();
        // If we are running under a Linux shell COLUMNS might be available for the width
        // of the terminal.
        parser.setUsageWidth( ( System.getenv( "COLUMNS" ) == null ? 100 : Integer.valueOf( System.getenv( "COLUMNS" ) ) ) );
        parser.printUsage( System.err );
        System.err.println();
    }

    public String getIndyHome()
    {
        return getHomeDir();
    }

    public void setIndyHome( final String indyHome )
    {
        setHomeDir( indyHome );
    }

    public void setPort( final Integer port )
    {
        this.port = port;
    }

}

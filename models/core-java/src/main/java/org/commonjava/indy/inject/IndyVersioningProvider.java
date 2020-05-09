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
package org.commonjava.indy.inject;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import javax.enterprise.inject.Default;
import javax.enterprise.inject.Produces;
import javax.inject.Singleton;

import org.commonjava.indy.stats.IndyDeprecatedApis;
import org.commonjava.indy.stats.IndyVersioning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Producer class that reads a properties file off the classpath containing version info for Indy, and assembles an instance of {@link IndyVersioning},
 * which this component then provides for injecting into other components.
 */
@Singleton
public class IndyVersioningProvider
{

    private static final String INDY_VERSIONING_PROPERTIES = "indy-version.properties";

    private static final String INDY_DEPRECATED_APIS_PROPERTIES = "deprecated-apis.properties";

    private final IndyVersioning versioning;

    private final IndyDeprecatedApis deprecatedApis;

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    public IndyVersioningProvider()
    {
        ClassLoader cl = IndyVersioningProvider.class.getClassLoader();

        // Load indy-version
        final Properties props = new Properties();
        try (InputStream is = cl.getResourceAsStream( INDY_VERSIONING_PROPERTIES ))
        {
            if ( is != null )
            {
                props.load( is );
            }
            else
            {
                logger.warn( "Resource not found, file: {}, loader: {}", INDY_VERSIONING_PROPERTIES, cl );
            }
        }
        catch ( final IOException e )
        {
            logger.error( "Failed to read Indy versioning information from classpath resource: "
                                          + INDY_VERSIONING_PROPERTIES, e );
        }

        versioning = new IndyVersioning( props.getProperty( "version", "unknown" ),
                                         props.getProperty( "builder", "unknown" ),
                                         props.getProperty( "commit.id", "unknown" ),
                                         props.getProperty( "timestamp", "unknown" ),
                                         props.getProperty( "api-version", "unknown" ) );

        // Load deprecated-apis
        String deprecatedApiFile = System.getProperty( "ENV_DEPRECATED_API_FILE", INDY_DEPRECATED_APIS_PROPERTIES );
        logger.info( "Get deprecatedApiFile: {}", deprecatedApiFile );

        final Properties deprApis = new Properties();
        try (InputStream is = cl.getResourceAsStream( deprecatedApiFile ))
        {
            if ( is != null )
            {
                deprApis.load( is );
            }
            else
            {
                logger.warn( "Resource not found, file: {}, loader: {}", INDY_DEPRECATED_APIS_PROPERTIES, cl );
            }
        }
        catch ( final IOException e )
        {
            logger.error( "Failed to read Indy deprecated api information from classpath resource: "
                                          + deprecatedApiFile, e );
        }

        deprecatedApis = new IndyDeprecatedApis( deprApis );

    }

    @Produces
    @Default
    public IndyVersioning getVersioningInstance()
    {
        return versioning;
    }

    @Produces
    @Default
    public IndyDeprecatedApis getDeprecatedApis()
    {
        return deprecatedApis;
    }

}

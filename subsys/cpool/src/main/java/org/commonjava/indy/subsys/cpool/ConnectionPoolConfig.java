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
package org.commonjava.indy.subsys.cpool;

import org.commonjava.indy.conf.IndyConfigInfo;
import org.commonjava.propulsor.config.ConfigurationException;
import org.commonjava.propulsor.config.annotation.SectionName;
import org.commonjava.propulsor.config.section.MapSectionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static java.lang.Boolean.TRUE;
import static org.commonjava.indy.subsys.cpool.ConnectionPoolConfig.SECTION;

@ApplicationScoped
@SectionName( SECTION )
public class ConnectionPoolConfig
        extends MapSectionListener
        implements IndyConfigInfo
{
    public static final String SECTION = "connection-pools";

    public static final String DS_PROPERTY_PREFIX = "datasource.";

    private static final String METRICS_SUBKEY = "metrics";

    private static final String HEALTH_CHECKS_SUBKEY = "healthChecks";

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private final Map<String, ConnectionPoolInfo> pools = new HashMap<>();

    public Map<String, ConnectionPoolInfo> getPools()
    {
        return pools;
    }

    @Override
    public void sectionStarted( final String name )
            throws ConfigurationException
    {
        logger.info( "STARTED SECTION: {}", name );
        super.sectionStarted( name );
    }

    @Override
    public void parameter( final String name, final String value )
            throws ConfigurationException
    {
        super.parameter( name, value );

        // don't pass the param through to the background map, consume it here.
        logger.info( "{}: Parsing connection pool {} from: '{}'", this, name, value );

        Map<String, String> valueMap = toMap( value );

        boolean metrics = TRUE.toString().equals( valueMap.remove( METRICS_SUBKEY ) );
        boolean healthChecks = TRUE.toString().equals( valueMap.remove( HEALTH_CHECKS_SUBKEY ) );

        final ConnectionPoolInfo cp =
                new ConnectionPoolInfo( name, toProperties(valueMap), metrics, healthChecks );

        logger.info( "{}: Adding: {}", this, cp );
        pools.put( name, cp );
    }

    private Properties toProperties( final Map<String,String> valueMap )
    {
        Properties props = new Properties();
        valueMap.forEach( ( k, v ) -> props.setProperty( k, v ) );

        return props;
    }

    private Map<String,String> toMap( final String value )
    {
        Map<String, String> result = new HashMap<>();
        Stream.of( value.split( "\\s*,\\s*" ) ).forEach( (s)->{
            String[] parts = s.split( "\\s*=\\s*" );
            if ( parts.length < 1 )
            {
                result.put( parts[0], Boolean.toString( TRUE ) );
            }
            else
            {
                result.put( parts[0], parts[1] );
            }
        } );

        return result;
    }

    @Override
    public String getDefaultConfigFileName()
    {
        return "default-connection-pools.conf";
    }

    @Override
    public InputStream getDefaultConfig()
    {
        return new ByteArrayInputStream( ("[" + SECTION + "]").getBytes() );
    }
}

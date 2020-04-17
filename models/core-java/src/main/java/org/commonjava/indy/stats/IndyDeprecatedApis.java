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
package org.commonjava.indy.stats;

import org.apache.commons.lang.math.FloatRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.inject.Alternative;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.apache.commons.lang.StringUtils.isBlank;

@Alternative
@Named
public class IndyDeprecatedApis
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private Properties props;

    private List<DeprecatedApiEntry> deprecatedApis = new ArrayList<>();

    private String minApiVersion;

    public IndyDeprecatedApis( Properties props )
    {
        this.props = props;

        Float minVersion = 0f;

        Set<String> keys = props.stringPropertyNames();
        for ( String key : keys )
        {
            DeprecatedApiEntry et;
            String value = props.getProperty( key );

            Float startVersion;
            Float endVersion;

            if ( key.indexOf( "," ) >= 0 ) // range
            {
                key = key.replaceAll( "[\\[|\\]]", "" ); // strip off square brackets if present
                String[] kv = key.split( "," );
                startVersion = Float.parseFloat( kv[0].trim() );
                endVersion = Float.parseFloat( kv[1].trim() );
                et = new DeprecatedApiEntry( new FloatRange( startVersion, endVersion ), value);
            }
            else
            {
                endVersion = Float.parseFloat( key.trim() );
                et = new DeprecatedApiEntry( endVersion, value );
            }

            // Calculate minApiVersion
            if ( et.isOff() )
            {
                minVersion = endVersion + 0.1f;
            }
            deprecatedApis.add( et );
        }

        minApiVersion = minVersion.toString();

        logger.debug( "Parsed deprecatedApis:{}, minApiVersion:{}", deprecatedApis, minApiVersion );
    }

    public DeprecatedApiEntry getDeprecated( String reqApiVersion )
    {
        if ( isBlank( reqApiVersion ))
        {
            return null;
        }
        Float reqVer;
        try
        {
            reqVer = Float.parseFloat( reqApiVersion );
        }
        catch ( NumberFormatException e )
        {
            logger.warn( "Unknown api version: {}", reqApiVersion );
            return null;
        }

        // the scopes may overlap, we go through range entries first and other entries next
        for ( DeprecatedApiEntry et : deprecatedApis )
        {
            if ( et.range != null && et.range.containsFloat( reqVer ) )
            {
                return et;
            }
        }

        for ( DeprecatedApiEntry et : deprecatedApis )
        {
            if ( et.endVersion != null && reqVer <= et.endVersion )
            {
                return et;
            }
        }

        return null;
    }

    public String getMinApiVersion()
    {
        return minApiVersion;
    }

    public static class DeprecatedApiEntry
    {
        private FloatRange range;

        private Float endVersion;

        private String value;

        public boolean isOff()
        {
            return "OFF".equals( value );
        }

        DeprecatedApiEntry( Float endVersion, String value )
        {
            this.endVersion = endVersion;
            this.value = value;
        }

        public DeprecatedApiEntry( FloatRange floatRange, String value )
        {
            this.range = floatRange;
            this.value = value;
        }

        @Override
        public String toString()
        {
            return "DeprecatedApiEntry{" + "range=" + range + ", endVersion=" + endVersion + ", value='" + value + '\''
                            + '}';
        }

        public String getValue()
        {
            return value;
        }
    }
}

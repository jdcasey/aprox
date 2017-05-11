/**
 * Copyright (C) 2011 Red Hat, Inc. (jdcasey@commonjava.org)
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
package org.commonjava.indy.model.core;

import org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor;

import java.io.Serializable;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public final class StoreKey
    implements Serializable, Comparable<StoreKey>
{
    private static final long serialVersionUID = 1L;

    // private static final Logger logger = new Logger( StoreKey.class );

    private String packageType;

    private final StoreType type;

    private final String name;

    protected StoreKey()
    {
        this.packageType = null;
        this.type = null;
        this.name = null;
    }

    public StoreKey( final String packageType, final StoreType type, final String name )
    {
        if ( !PackageTypes.contains( packageType ) )
        {
            throw new IllegalArgumentException( "Unsupported package type: " + packageType + ". Valid values are: "
                                                        + PackageTypes.getPackageTypes() );
        }

        this.packageType = packageType;
        this.type = type;
        this.name = name;
    }

    @Deprecated
    public StoreKey( final StoreType type, final String name )
    {
        this.packageType = MavenPackageTypeDescriptor.MAVEN_PKG_KEY;
        this.type = type;
        this.name = name;
    }

    public String getPackageType()
    {
        return packageType;
    }

    public StoreType getType()
    {
        return type;
    }

    public String getName()
    {
        return name;
    }

    @Override
    public String toString()
    {
        return packageType + ":" + type.name() + ":" + name;
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = prime * result + ( ( packageType == null ) ? 0 : packageType.hashCode() );
        result = prime * result + ( ( name == null ) ? 0 : name.hashCode() );
        result = prime * result + ( ( type == null ) ? 0 : type.hashCode() );
        return result;
    }

    @Override
    public boolean equals( final Object obj )
    {
        if ( this == obj )
        {
            return true;
        }
        if ( obj == null )
        {
            return false;
        }
        if ( getClass() != obj.getClass() )
        {
            return false;
        }
        final StoreKey other = (StoreKey) obj;
        if ( packageType == null )
        {
            if ( other.packageType != null )
            {
                return false;
            }
        }
        else if ( !packageType.equals( other.packageType ) )
        {
            return false;
        }
        if ( name == null )
        {
            if ( other.name != null )
            {
                return false;
            }
        }
        else if ( !name.equals( other.name ) )
        {
            return false;
        }
        return type == other.type;
    }

    public static StoreKey fromString( final String id )
    {
        String[] parts = id.split(":");

        String packageType = null;
        String name;
        StoreType type = null;

        // FIXME: We need to get to a point where it's safe for this to be an error and not default to maven.
        if ( parts.length < 3 || isBlank(parts[0]) )
        {
            packageType = MavenPackageTypeDescriptor.MAVEN_PKG_KEY;
        }

        if ( parts.length < 2 || isBlank( parts[1] ) )
        {
            name = id;
            type = StoreType.remote;
        }
        else
        {
            packageType = parts[0];
            type = StoreType.get( parts[1] );
            name = parts[2];
        }

        if ( type == null )
        {
            return null;
        }

        // logger.info( "parsed store-key with type: '{}' and name: '{}'", type, name );

        return new StoreKey( packageType, type, name );
    }

    @Override
    public int compareTo( final StoreKey o )
    {
        int comp = packageType.compareTo( o.packageType );
        if ( comp == 0 )
        {
            comp = type.compareTo( o.type );
        }

        if ( comp == 0 )
        {
            comp = name.compareTo( o.name );
        }

        return comp;
    }

    private static ConcurrentHashMap<StoreKey, StoreKey> deduplications = new ConcurrentHashMap<>();

    public static StoreKey dedupe( StoreKey key )
    {
        StoreKey result = deduplications.get( key );
        if ( result == null )
        {
            deduplications.put( key, key );
            result = key;
        }

        return result;
    }

}

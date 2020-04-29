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
package org.commonjava.indy.model.galley;

import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.transport.htcli.model.HttpLocation;
import org.commonjava.maven.galley.transport.htcli.model.LocationTrustType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.NumberFormat;
import java.text.ParseException;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link KeyedLocation} implementation that represents a {@link RemoteRepository} Indy store, and bridges the handling of {@link RemoteRepository}
 * attributes such as the various timeout types (not-found-cache, connection, and cache timeouts) to expose them via the {@link Location} API.
 */
public class RepositoryLocation
    implements HttpLocation, KeyedLocation
{

    public static final String ATTR_NFC_TIMEOUT_SECONDS = "NFC-timeout";

    private final RemoteRepository repository;

    private final Map<String, Object> attributes = new HashMap<String, Object>();

    private boolean enabled;

    public RepositoryLocation( final RemoteRepository repository )
    {
        this.repository = repository;
        this.enabled = !repository.isDisabled();

        if ( repository.getNfcTimeoutSeconds() > 0 )
        {
            attributes.put( ATTR_NFC_TIMEOUT_SECONDS, repository.getNfcTimeoutSeconds() );
        }

        if ( repository.getTimeoutSeconds() > 0 )
        {
            attributes.put( Location.CONNECTION_TIMEOUT_SECONDS, repository.getTimeoutSeconds() );
        }

        if ( repository.getCacheTimeoutSeconds() > 0 )
        {
            attributes.put( Location.CACHE_TIMEOUT_SECONDS, repository.getCacheTimeoutSeconds() );
        }

        if ( repository.getMetadataTimeoutSeconds() > 0 )
        {
            attributes.put( Location.METADATA_TIMEOUT_SECONDS, repository.getMetadataTimeoutSeconds() );
        }

        if ( repository.getMaxConnections() > 0 )
        {
            attributes.put( Location.MAX_CONNECTIONS, repository.getMaxConnections() );
        }
    }

    @Override
    public boolean allowsPublishing()
    {
        return false;
    }

    @Override
    public boolean allowsStoring()
    {
        return true;
    }

    @Override
    public boolean allowsSnapshots()
    {
        return repository.isAllowSnapshots();
    }

    @Override
    public boolean allowsReleases()
    {
        return repository.isAllowReleases();
    }

    @Override
    public boolean allowsDeletion()
    {
        return true;
    }

    @Override
    public String getUri()
    {
        return repository.getUrl();
    }

    @Override
    public Map<String, Object> getAttributes()
    {
        return attributes;
    }

    @Override
    public <T> T getAttribute( final String key, final Class<T> type )
    {
        return getAttribute( key, type, null );
    }

    @Override
    public <T> T getAttribute( final String key, final Class<T> type, final T defaultValue )
    {
        final Object value = attributes.get( key );
        if ( value == null )
        {
            return defaultValue;
        }
        else if ( type.isAssignableFrom( value.getClass() ) )
        {
            return type.cast( value );
        }
        else if ( value.getClass() == String.class && Number.class.isAssignableFrom( type ) )
        {
            Number n = null;
            try
            {
                n = NumberFormat.getInstance().parse( (String) value );
            }
            catch ( ParseException e )
            {
                Logger logger = LoggerFactory.getLogger( getClass() );
                logger.error( "Failed to get attribute, key: {}, value: {}", key, value, e );
            }
            // convert, e.g., n might be Long while the T is Integer
            Object ret;
            if ( type == Long.TYPE )
            {
                ret = new Long( n.longValue() );
            }
            else if ( type == Float.TYPE )
            {
                ret = new Float( n.floatValue() );
            }
            else if ( type == Double.TYPE )
            {
                ret = new Double( n.doubleValue() );
            }
            else
            {
                ret = new Integer( n.intValue() ); // default int
            }
            return (T) ret;
        }
        return defaultValue;
    }

    @Override
    public Object removeAttribute( final String key )
    {
        return attributes.remove( key );
    }

    @Override
    public Object setAttribute( final String key, final Object value )
    {
        return attributes.put( key, value );
    }

    @Override
    public String getKeyCertPem()
    {
        return repository.getKeyCertPem();
    }

    @Override
    public String getUser()
    {
        return repository.getUser();
    }

    @Override
    public String getHost()
    {
        return repository.getHost();
    }

    @Override
    public int getPort()
    {
        return repository.getPort();
    }

    @Override
    public String getServerCertPem()
    {
        return repository.getServerCertPem();
    }

    @Override
    public LocationTrustType getTrustType()
    {
        return LocationTrustType.getType( repository.getServerTrustPolicy() );
    }

    @Override
    public String getProxyHost()
    {
        return repository.getProxyHost();
    }

    @Override
    public int getProxyPort()
    {
        return repository.getProxyPort();
    }

    @Override
    public boolean isIgnoreHostnameVerification()
    {
        return repository.isIgnoreHostnameVerification();
    }

    @Override
    public String getProxyUser()
    {
        return repository.getProxyUser();
    }

    @Override
    public StoreKey getKey()
    {
        return repository.getKey();
    }

    @Override
    public boolean allowsDownloading()
    {
        return enabled;
    }

    @Override
    public String toString()
    {
        return "RepositoryLocation [" + repository.getKey() + "]";
    }

    @Override
    public String getName()
    {
        return getKey().toString();
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = prime * result + ( ( repository == null ) ? 0 : repository.hashCode() );
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
        final RepositoryLocation other = (RepositoryLocation) obj;
        if ( repository == null )
        {
            if ( other.repository != null )
            {
                return false;
            }
        }
        else if ( !repository.equals( other.repository ) )
        {
            return false;
        }
        return true;
    }
}

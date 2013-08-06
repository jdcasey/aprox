package org.commonjava.aprox.model.galley;

import java.util.HashMap;
import java.util.Map;

import org.commonjava.aprox.model.Repository;
import org.commonjava.aprox.model.StoreKey;
import org.commonjava.maven.galley.transport.htcli.model.HttpLocation;

public class RepositoryLocation
    implements HttpLocation, KeyedLocation
{

    private final Repository repository;

    private final Map<String, Object> attributes = new HashMap<>();

    public RepositoryLocation( final Repository repository )
    {
        this.repository = repository;
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
        return true;
    }

    @Override
    public boolean allowsReleases()
    {
        return true;
    }

    @Override
    public String getUri()
    {
        return repository.getUrl();
    }

    @Override
    public int getTimeoutSeconds()
    {
        return repository.getTimeoutSeconds();
    }

    @Override
    public Map<String, Object> getAttributes()
    {
        return attributes;
    }

    @Override
    public <T> T getAttribute( final String key, final Class<T> type )
    {
        final Object value = attributes.get( key );
        return value == null ? null : type.cast( value );
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
    public String getProxyUser()
    {
        return repository.getProxyUser();
    }

    @Override
    public StoreKey getKey()
    {
        return repository.getKey();
    }

}

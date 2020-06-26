package org.commonjava.indy.pkg.pypi.content;

import org.commonjava.indy.model.core.StoreKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

// FIXME: We MUST NOT use an in-memory CDN redirection map in any real enviornment! This is just for research!
@ApplicationScoped
public class CDNRedirectionDatabase
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private Map<KeyAndPath, String> redirections = new ConcurrentHashMap<>();

    public String lookup( StoreKey storeKey, String path )
    {
        logger.debug( "Looking for CDN redirect of: {}:{}", storeKey, path );
        return redirections.get( new KeyAndPath( storeKey, path ) );
    }

    public void register( StoreKey storeKey, String path, String href )
    {
        logger.info( "Registering CDN redirect: {}:{} -> {}", storeKey, path, href );
        redirections.put( new KeyAndPath( storeKey, path ), href );
    }

    private static final class KeyAndPath
    {
        private StoreKey key;
        private String path;

        KeyAndPath( StoreKey key, String path )
        {
            this.key = key;
            this.path = path;
        }

        @Override
        public boolean equals( Object o )
        {
            if ( this == o )
                return true;
            if ( o == null || getClass() != o.getClass() )
                return false;
            KeyAndPath that = (KeyAndPath) o;
            return key.equals( that.key ) && path.equals( that.path );
        }

        @Override
        public int hashCode()
        {
            return Objects.hash( key, path );
        }
    }

}

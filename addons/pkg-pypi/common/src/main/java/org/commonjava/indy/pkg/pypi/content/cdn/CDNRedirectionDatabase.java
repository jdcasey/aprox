package org.commonjava.indy.pkg.pypi.content.cdn;

import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.subsys.infinispan.CacheHandle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

// FIXME: We MUST NOT use an in-memory CDN redirection map in any real enviornment! This is just for research!
@ApplicationScoped
public class CDNRedirectionDatabase
{
    @Inject
    @PyPICDNCache
    private CacheHandle<CDNContentKey, Map<String, String>> redirections;

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    public Map<String, String> lookupMap( StoreKey storeKey, String path )
    {
        return redirections.get( new CDNContentKey( storeKey, cleanPath( path ) ) );
    }

    public String lookup( StoreKey key, String rawpath )
    {
        String path = cleanPath( rawpath );

        Path p = Paths.get( path );
        Path parentPath = p.getParent();
        if ( parentPath == null )
        {
            return null;
        }

        String basepath = parentPath.toString();
        String fname = Paths.get( path ).getFileName().toString();

        logger.debug( "Looking for CDN redirect of: {}:{}", key, path );
        Map<String, String> redirectMap = redirections.get( new CDNContentKey( key, basepath ) );
        if ( redirectMap != null )
        {
            return redirectMap.get( fname );
        }

        return null;
    }

    private String cleanPath( String rawpath )
    {
        return rawpath.endsWith( "/" ) ? rawpath.substring( 0, rawpath.length()-1 ) : rawpath;
    }

    public void register( StoreKey storeKey, String path, Map<String, String> hrefs )
    {
        logger.info( "Registering CDN redirects: {}:{} -> {}", storeKey, path, hrefs );
        redirections.put( new CDNContentKey( storeKey, cleanPath( path ) ), hrefs );
    }

    public void clear( StoreKey storeKey, String path )
    {
        logger.debug( "CLEAR CDN redirections for: {}:{}", storeKey, path );
        redirections.remove( new CDNContentKey( storeKey, path ) );
    }
}

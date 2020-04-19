package org.commonjava.indy.pathmapped.inject;

import org.commonjava.indy.core.content.group.AbstractGroupRepositoryFilter;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.maven.galley.cache.pathmapped.PathMappedCacheProvider;
import org.commonjava.maven.galley.spi.cache.CacheProvider;
import org.commonjava.storage.pathmapped.core.PathMappedFileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;
import static org.commonjava.indy.pkg.npm.model.NPMPackageTypeDescriptor.NPM_PKG_KEY;

@ApplicationScoped
public class PathMappedGroupRepositoryFilter
                extends AbstractGroupRepositoryFilter
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private CacheProvider cacheProvider;

    private PathMappedFileManager pathMappedFileManager;

    @PostConstruct
    void setup()
    {
        if ( cacheProvider instanceof PathMappedCacheProvider )
        {
            pathMappedFileManager = ( (PathMappedCacheProvider) cacheProvider ).getPathMappedFileManager();
        }
    }

    @Override
    public int getPriority()
    {
        return 0;
    }

    @Override
    public boolean canProcess( String path, Group group )
    {
        if ( pathMappedFileManager != null )
        {
            return true;
        }
        return false;
    }

    /**
     * Filter for remote repos plus hosted repos which contains the target path. Because caller may try to
     * download the target path from remote repositories.
     */
    @Override
    public List<ArtifactStore> filter( String path, Group group, List<ArtifactStore> concreteStores )
    {
        List<String> candidates = getCandidates( concreteStores );
        if ( candidates.isEmpty() )
        {
            logger.debug( "No candidate matches, skip" );
            return concreteStores;
        }

        String strategyPath = getStrategyPath( group.getKey(), path );
        if ( strategyPath == null )
        {
            logger.debug( "Can not get strategy path, group: {}, path: {}", group.getKey(), path );
            return concreteStores;
        }

        Set<String> ret = pathMappedFileManager.getFileSystemContainingDirectory( candidates, strategyPath );

        return concreteStores.stream()
                             .filter( store -> store.getType() == StoreType.remote || ret.contains(
                                             store.getKey().toString() ) )
                             .collect( Collectors.toList() );
    }

    /**
     * Get hosted repos
     */
    private List<String> getCandidates( List<ArtifactStore> concreteStores )
    {
        return concreteStores.stream()
                             .filter( store -> store.getType() == StoreType.hosted )
                             .map( store -> store.getKey().toString() )
                             .collect( Collectors.toList() );
    }

    private String getStrategyPath( final StoreKey key, final String rawPath )
    {
        if ( isBlank( rawPath ) )
        {
            return null;
        }

        Path parent = null;
        if ( key.getPackageType().equals( MAVEN_PKG_KEY ) )
        {
            // Use parent path because 1. maven metadata generator need to list it, 2. it is supper set of file path
            parent = Paths.get( rawPath ).getParent();
        }
        else if ( key.getPackageType().equals( NPM_PKG_KEY ) )
        {
            /*
             * E.g,
             * jquery/-/jquery-1.5.1.tgz -> jquery/-/, jquery-1.5.1.tgz
             * jquery -> jquery/, package.json
             */
            parent = Paths.get( rawPath ).getParent();
        }
        if ( parent == null )
        {
            return rawPath;
        }
        else
        {
            return parent.toString();
        }
    }

}

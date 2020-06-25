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
package org.commonjava.indy.pathmapped.inject;

import com.google.common.collect.Lists;
import org.commonjava.indy.conf.IndyConfiguration;
import org.commonjava.indy.core.content.group.AbstractGroupRepositoryFilter;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.pathmapped.cache.PathMappedMavenGACache;
import org.commonjava.maven.galley.cache.pathmapped.PathMappedCacheProvider;
import org.commonjava.maven.galley.model.SpecialPathInfo;
import org.commonjava.maven.galley.spi.cache.CacheProvider;
import org.commonjava.maven.galley.spi.io.SpecialPathManager;
import org.commonjava.storage.pathmapped.core.PathMappedFileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.commonjava.atlas.maven.ident.util.SnapshotUtils.LOCAL_SNAPSHOT_VERSION_PART;
import static org.commonjava.indy.pkg.PackageTypeConstants.PKG_TYPE_MAVEN;
import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;
import static org.commonjava.indy.pkg.npm.model.NPMPackageTypeDescriptor.NPM_PKG_KEY;

@ApplicationScoped
public class PathMappedGroupRepositoryFilter
                extends AbstractGroupRepositoryFilter
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private CacheProvider cacheProvider;

    @Inject
    private IndyConfiguration indyConfig;

    @Inject
    private PathMappedMavenGACache gaCache;

    @Inject
    private SpecialPathManager specialPathManager;

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

        if ( gaCache.isStarted() && isMavenMetadataNonSnapshotPath( group, path ) )
        {
            logger.debug( "Maven metadata, use GA cache filter result, skip" );
            return concreteStores;
        }

        String strategyPath = getStrategyPath( group.getKey(), path );
        if ( strategyPath == null )
        {
            logger.debug( "Can not get strategy path, group: {}, path: {}", group.getKey(), path );
            return concreteStores;
        }

        // batch it to avoid huge 'IN' query
        Set<String> ret = new HashSet<>();
        int batchSize = indyConfig.getFileSystemContainingBatchSize();
        List<List<String>> subSets = Lists.partition( candidates, batchSize );
        subSets.forEach( subSet -> {
            logger.debug( "Get file system containing, strategyPath: {}, subSet: {}", strategyPath, subSet );
            Set<String> st = pathMappedFileManager.getFileSystemContainingDirectory( subSet, strategyPath );
            if ( st == null )
            {
                // query failed but those candidates may contain the target path so we add all subSet candidates
                logger.warn( "Get fileSystems query failed, add subSet candidates" );
                ret.addAll( subSet );
            }
            else
            {
                ret.addAll( st );
            }
        } );

        return concreteStores.stream()
                             .filter( store -> store.getType() == StoreType.remote || ret.contains(
                                             store.getKey().toString() ) )
                             .collect( Collectors.toList() );
    }

    private boolean isMavenMetadataNonSnapshotPath( Group group, String path )
    {
        if ( group.getPackageType().equals( PKG_TYPE_MAVEN ) )
        {
            SpecialPathInfo pathInfo = specialPathManager.getSpecialPathInfo( path );
            return pathInfo != null && pathInfo.isMetadata() && !path.contains( LOCAL_SNAPSHOT_VERSION_PART );
        }
        return false;
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

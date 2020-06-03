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

import org.commonjava.indy.conf.IndyConfiguration;
import org.commonjava.indy.core.content.group.AbstractGroupRepositoryFilter;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.pathmapped.cache.PathMappedMavenGACache;
import org.commonjava.maven.galley.model.SpecialPathInfo;
import org.commonjava.maven.galley.spi.io.SpecialPathManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.commonjava.indy.pkg.PackageTypeConstants.PKG_TYPE_MAVEN;

@ApplicationScoped
public class PathMappedMavenGACacheGroupRepositoryFilter
                extends AbstractGroupRepositoryFilter
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private IndyConfiguration indyConfig;

    @Inject
    private SpecialPathManager specialPathManager;

    @Inject
    private PathMappedMavenGACache gaCache;

    @Override
    public int getPriority()
    {
        return 1;
    }

    @Override
    public boolean canProcess( String path, Group group )
    {
        if ( gaCache.isStarted() && group.getPackageType().equals( PKG_TYPE_MAVEN ) )
        {
            return true;
        }
        return false;
    }

    /**
     * Filter for remote repos and hosted repos which contain the target groupId/artifactId (GA).
     */
    @Override
    public List<ArtifactStore> filter( String path, Group group, List<ArtifactStore> concreteStores )
    {
        String gaPath = getGAPath( path );
        if ( isBlank( gaPath ) )
        {
            logger.debug( "Not get gaPath, raw path: {}", path );
            return concreteStores;
        }

        if ( !gaCache.matchAny( concreteStores ) )
        {
            logger.debug( "No matched candidate, skip" );
            return concreteStores;
        }

        Set<String> storesContaining = gaCache.getStoresContaining( gaPath );
        logger.debug( "Get from GA cache, gaPath: {}, containing: {}", gaPath, storesContaining );

        Set<String> scanned = gaCache.getScannedStores();

        /*
         * Check staled stores because of repo deletion. GA cache timer task only updates 'scanned-repos'.
         * Stores in each single ga entry are maintained here. For performance reason, we do it in approximately 1/8 rate.
         */
        if ( System.currentTimeMillis() % 8 == 0 )
        {
            checkStaledStores( storesContaining, scanned, gaPath );
        }

        return concreteStores.stream().filter( store -> {
            String storeName = store.getKey().getName();
            if ( store.getType() == StoreType.remote )
            {
                return true;
            }
            if ( storesContaining.contains( storeName ) )
            {
                return true;
            }
            else if ( !scanned.contains( storeName ) ) // unknown
            {
                logger.debug( "Unknown store: {}", storeName );
                return true;
            }
            return false;
        } ).collect( Collectors.toList() );
    }

    private void checkStaledStores( Set<String> storesContaining, Set<String> scanned, String gaPath )
    {
        Set<String> staled = new HashSet<>( storesContaining );
        staled.removeAll( scanned );
        if ( !staled.isEmpty() )
        {
            logger.info( "Clean staled stores, gaPath: {}, staled: {}", gaPath, staled );
            gaCache.reduce( gaPath, staled );
        }
    }

    private String getGAPath( String rawPath )
    {
        if ( isBlank( rawPath ) )
        {
            return null;
        }
        Path gaPath = null;
        Path parent = Paths.get( rawPath ).getParent();
        if ( parent != null )
        {
            SpecialPathInfo pathInfo = specialPathManager.getSpecialPathInfo( rawPath );
            if ( pathInfo != null && pathInfo.isMetadata() )
            {
                // Metadata may be at two levels, e.g., foo/bar/maven-metadata.xml, foo/bar/3.0.0-SNAPSHOT/maven-metadata.xml
                if ( parent.endsWith( "SNAPSHOT" ) )
                {
                    gaPath = parent.getParent();
                }
                else
                {
                    gaPath = parent;
                }
            }
            else
            {
                // gaPath will be two layers upwards, e.g., foo/bar/3.0.0/bar-3.0.0.pom
                gaPath = parent.getParent();
            }
        }
        if ( gaPath != null )
        {
            return gaPath.toString();
        }
        return null;
    }

}

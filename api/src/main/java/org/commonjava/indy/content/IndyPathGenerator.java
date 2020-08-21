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
package org.commonjava.indy.content;

import org.apache.commons.codec.digest.DigestUtils;
import org.commonjava.indy.model.core.PathStyle;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.galley.KeyedLocation;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.indy.util.PathUtils;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.spi.io.PathGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.commonjava.indy.model.core.PathStyle.hashed;

/**
 * {@link PathGenerator} implementation that assumes the locations it sees will be {@link KeyedLocation}, and translates them into storage locations
 * on disk for related content.
 */
@Default
@ApplicationScoped
public class IndyPathGenerator
        implements PathGenerator
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private Instance<StoragePathCalculator> injectedStoragePathCalculators;

    private Set<StoragePathCalculator> pathCalculators;

    public IndyPathGenerator(){}

    public IndyPathGenerator( Set<StoragePathCalculator> pathCalculators )
    {
        this.pathCalculators = pathCalculators;
    }

    @PostConstruct
    public void postConstruct()
    {
        pathCalculators = new HashSet<>();
        if ( !injectedStoragePathCalculators.isUnsatisfied() )
        {
            injectedStoragePathCalculators.forEach( pathCalculators::add );
        }
    }

    @Override
    public String getFilePath( final ConcreteResource resource )
    {
        final KeyedLocation kl = (KeyedLocation) resource.getLocation();
        final StoreKey key = kl.getKey();

        final String name = key.getPackageType() + "/" + key.getType()
                               .name() + "-" + key.getName();

        String fullPath = PathUtils.join( name, getPath( resource ) );
        logger.debug( "Resource {} is stored in: {}", resource, fullPath );
        return fullPath;
    }

    @Override
    public String getPath( final ConcreteResource resource )
    {
        final KeyedLocation kl = (KeyedLocation) resource.getLocation();
        final StoreKey key = kl.getKey();
        String path = resource.getPath();
        if ( hashed == kl.getAttribute( LocationUtils.PATH_STYLE, PathStyle.class ) )
        {
            File f = new File( path );
            String dir = f.getParent();
            if ( dir == null )
            {
                dir = "";
            }

            if ( dir.length() > 1 && dir.startsWith( "/" ) )
            {
                dir = dir.substring( 1, dir.length() );
            }

            String digest = DigestUtils.sha256Hex( dir );

            logger.trace( "Using SHA-256 digest: '{}' for dir: '{}' of path: '{}'", digest, dir, path );

            // Format examples:
            // - aa/bb/aabbccddeeff001122/simple-1.0.pom
            // - aa/bb/aabbccddeeff001122/gulp-size
            // - 00/11/001122334455667788/gulp-size-1.3.0.tgz
            path = String.format( "%s/%s/%s/%s", digest.substring( 0, 2 ), digest.substring( 2, 4 ), digest, f.getName() );
        }
        else
        {
            AtomicReference<String> pathref = new AtomicReference<>( path );
            pathCalculators.forEach( c -> pathref.set( c.calculateStoragePath( key, pathref.get() ) ) );

            path = pathref.get();
        }

        return path;
    }

}

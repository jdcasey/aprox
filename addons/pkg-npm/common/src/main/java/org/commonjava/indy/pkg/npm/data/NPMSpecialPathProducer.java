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
package org.commonjava.indy.pkg.npm.data;

import org.commonjava.indy.action.IndyLifecycleException;
import org.commonjava.indy.content.SpecialPathSetProducer;
import org.commonjava.maven.galley.io.SpecialPathSet;
import org.commonjava.maven.galley.model.FilePatternMatcher;
import org.commonjava.maven.galley.model.SpecialPathInfo;
import org.commonjava.maven.galley.spi.io.SpecialPathManager;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.List;

import static org.commonjava.maven.galley.io.SpecialPathConstants.PKG_TYPE_NPM;

/**
 * Setup special paths related to NPM packages.
 */
@ApplicationScoped
@Named
public class NPMSpecialPathProducer
        implements SpecialPathSetProducer
{

    public SpecialPathSet getSpecialPathSet()
    {
        return new NPMSpecialPathSet();
    }

    private class NPMSpecialPathSet
                    implements SpecialPathSet
    {
        final List<SpecialPathInfo> npmSpecialPaths = new ArrayList<>();

        NPMSpecialPathSet()
        {
            npmSpecialPaths.add( SpecialPathInfo.from( new FilePatternMatcher( "package\\.json" ) )
                                                .setMergable( true )
                                                .setMetadata( true )
                                                .build() );

            npmSpecialPaths.add( SpecialPathInfo.from( new FilePatternMatcher( ".*(\\.md5|\\.sha[\\d]+)$" ) )
                                                .setDecoratable( false )
                                                .setMergable( true )
                                                .setMetadata( true )
                                                .build() );
        }

        @Override
        public List<SpecialPathInfo> getSpecialPathInfos()
        {
            return npmSpecialPaths;
        }

        @Override
        public void registerSpecialPathInfo( SpecialPathInfo pathInfo )
        {
            npmSpecialPaths.add( pathInfo );
        }

        @Override
        public void deregisterSpecialPathInfo( SpecialPathInfo pathInfo )
        {
            npmSpecialPaths.remove( pathInfo );
        }

        @Override
        public String getPackageType()
        {
            return PKG_TYPE_NPM;
        }
    }
}

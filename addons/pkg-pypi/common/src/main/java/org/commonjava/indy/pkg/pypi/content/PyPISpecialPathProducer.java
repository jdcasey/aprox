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
package org.commonjava.indy.pkg.pypi.content;

import org.commonjava.indy.content.SpecialPathSetProducer;
import org.commonjava.maven.galley.io.SpecialPathSet;
import org.commonjava.maven.galley.model.FilePatternMatcher;
import org.commonjava.maven.galley.model.PathPatternMatcher;
import org.commonjava.maven.galley.model.SpecialPathInfo;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Named;
import java.util.ArrayList;
import java.util.List;

import static org.commonjava.indy.pkg.pypi.model.PyPIPackageTypeDescriptor.PYPI_PKG_KEY;

/**
 * Setup special paths related to NPM packages.
 */
@ApplicationScoped
@Named
public class PyPISpecialPathProducer
        implements SpecialPathSetProducer
{

    public SpecialPathSet getSpecialPathSet()
    {
        return new PyPISpecialPathSet();
    }

    private class PyPISpecialPathSet
                    implements SpecialPathSet
    {
        final List<SpecialPathInfo> specialPaths = new ArrayList<>();

        PyPISpecialPathSet()
        {
            specialPaths.add( SpecialPathInfo.from( new PathPatternMatcher( "^[^/]*$" ) )
                                             .setMergable( true )
                                             .setMetadata( true )
                                             .build() );

            specialPaths.add( SpecialPathInfo.from( new FilePatternMatcher( ".*(\\.md5|\\.sha[\\d]+)$" ) )
                                             .setDecoratable( false )
                                             .setMergable( true )
                                             .setMetadata( true )
                                             .build() );
        }

        @Override
        public List<SpecialPathInfo> getSpecialPathInfos()
        {
            return specialPaths;
        }

        @Override
        public void registerSpecialPathInfo( SpecialPathInfo pathInfo )
        {
            specialPaths.add( pathInfo );
        }

        @Override
        public void deregisterSpecialPathInfo( SpecialPathInfo pathInfo )
        {
            specialPaths.remove( pathInfo );
        }

        @Override
        public String getPackageType()
        {
            return PYPI_PKG_KEY;
        }
    }
}

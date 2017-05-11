/**
 * Copyright (C) 2011 Red Hat, Inc. (jdcasey@commonjava.org)
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

import org.commonjava.indy.autoprox.data.*
import org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor;

import java.net.MalformedURLException;
import org.commonjava.indy.model.core.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class JBossOrgRule extends AbstractAutoProxRule
{
    boolean matches( String packageType, String named ) {
        MavenPackageTypeDescriptor.MAVEN_PKG_KEY.equals(packageType) && named.startsWith("JB-")
    }

    RemoteRepository createRemoteRepository( String packageType, String named )
        throws MalformedURLException
    {
        def match = (named =~ /JB-(.+)/)[0]
        return new RemoteRepository( packageType, name: named, url: "https://repository.jboss.org/nexus/content/repositories/${match[1]}/" )
    }

    Group createGroup( String packageType, String named )
    {
        Group g = new Group( packageType, named );
        g.addConstituent( new StoreKey( StoreType.remote, named ) )
/*        g.addConstituent( new StoreKey( StoreType.hosted, named ) )*/
        
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.info( "Created group: {}", g )

        return g
    }
}

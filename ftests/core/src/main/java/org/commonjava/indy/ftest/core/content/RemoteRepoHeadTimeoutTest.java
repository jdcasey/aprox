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
package org.commonjava.indy.ftest.core.content;

import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;
import static org.junit.Assert.assertTrue;

import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.maven.galley.model.Location;
import org.junit.Test;

public class RemoteRepoHeadTimeoutTest
                extends AbstractRemoteRepoTimeoutTest
{

    @Test
    public void run() throws Exception
    {
        final String repo1 = "repo1";
        final String path = "foo/bar/1.0/bar-1.0.pom";

        server.expect( server.formatUrl( repo1, path ), 200, new DelayInputStream() );

        RemoteRepository remote1 = new RemoteRepository( MAVEN_PKG_KEY, repo1, server.formatUrl( repo1 ) );
        setRemoteTimeout( remote1 );

        remote1 = client.stores().create( remote1, "adding remote", RemoteRepository.class );

        client.content().exists( remote1.getKey(), path );

        assertResult( client.stores().load( remote1.getKey(), RemoteRepository.class ) );
    }

    @Override
    protected void setRemoteTimeout( RemoteRepository remote )
    {
        remote.setMetadata( Location.CONNECTION_TIMEOUT_SECONDS, Integer.toString( 1 ) );
    }

    @Override
    protected void assertResult( RemoteRepository remote ) throws Exception
    {
        assertTrue( remote.isDisabled() );
    }

    @Override
    protected boolean createStandardTestStructures()
    {
        return false;
    }
}

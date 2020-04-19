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

import org.commonjava.indy.ftest.core.AbstractContentManagementTest;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.junit.Test;

import java.io.ByteArrayInputStream;

import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.jgroups.util.Util.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class DeleteHostedRepoWithContentTest
                extends AbstractContentManagementTest
{
    protected boolean createStandardTestStructures()
    {
        return false;
    }

    String content = "This is a test";

    String path_1 = "foo/1.0/bar-1.0.jar";
    String path_2 = "foo/2.0/bar-2.0.jar";

    @Test
    public void run() throws Exception
    {
        final HostedRepository repo = new HostedRepository( MAVEN_PKG_KEY, "build_perftest-20200229T021845" );
        final StoreKey key = repo.getKey();
        final HostedRepository result = client.stores().create( repo, name.getMethodName(), HostedRepository.class );

        assertThat( result.getName(), equalTo( repo.getName() ) );
        assertThat( result.equals( repo ), equalTo( true ) );

        // Store files
        client.content().store( repo.getKey(), path_1, new ByteArrayInputStream( content.getBytes() ) );
        client.content().store( repo.getKey(), path_2, new ByteArrayInputStream( content.getBytes() ) );

        ConcreteResource r1 = new ConcreteResource( LocationUtils.toLocation( repo ), path_1 );
        ConcreteResource r2 = new ConcreteResource( LocationUtils.toLocation( repo ), path_2 );

        // Verify files exist
        assertTrue( cacheProvider.exists( r1 ) );
        assertTrue( cacheProvider.exists( r2 ) );

        // Delete repo with deleteContent flag
        client.stores().delete( key, "Delete", true );
        assertThat( client.stores().exists( key ), equalTo( false ) );

        // Verify files were deleted
        assertFalse( cacheProvider.exists( r1 ) );
        assertFalse( cacheProvider.exists( r2 ) );
    }

}

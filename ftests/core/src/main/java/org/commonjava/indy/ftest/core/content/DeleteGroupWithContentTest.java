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

import org.apache.commons.io.IOUtils;
import org.commonjava.indy.ftest.core.AbstractContentManagementTest;
import org.commonjava.indy.ftest.core.category.EventDependent;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class DeleteGroupWithContentTest
                extends AbstractContentManagementTest
{
    protected boolean createStandardTestStructures()
    {
        return false;
    }

    /* @formatter:off */
    private static final String pomContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                    "<project>\n" +
                    "  <modelVersion>4.0.0</modelVersion>\n" +
                    "  <groupId>org.foo</groupId>\n" +
                    "  <artifactId>bar</artifactId>\n" +
                    "  <version>1.0</version>\n" +
                    "  <packaging>pom</packaging>\n" +
                    "</project>\n";
    /* @formatter:on */

    String pomPath = "org/foo/bar/1.0/bar-1.0.pom";

    String metadataPath = "org/foo/bar/maven-metadata.xml";

    @Test
    @Category( EventDependent.class )
    public void run() throws Exception
    {
        // Create hosted repo
        final HostedRepository repo = new HostedRepository( MAVEN_PKG_KEY, "test_1" );
        assertThat( client.stores().create( repo, name.getMethodName(), HostedRepository.class ), notNullValue() );

        // Store pom file in hosted repo
        client.content().store( repo.getKey(), pomPath, new ByteArrayInputStream( pomContent.getBytes() ) );

        // Generate metadata file in hosted repo
        try (InputStream in = client.content().get( repo.getKey(), metadataPath ))
        {
            IOUtils.toString( in );
        }

        // Verify the hosted meta file exists
        ConcreteResource r_meta_hosted = new ConcreteResource( LocationUtils.toLocation( repo ), metadataPath );
        assertTrue( cacheProvider.exists( r_meta_hosted ) );

        // Create group with hosted member
        final Group group = new Group( MAVEN_PKG_KEY, "test_1", repo.getKey() );
        assertThat( client.stores().create( group, name.getMethodName(), Group.class ), notNullValue() );

        // Generate group metadata file
        try (InputStream in = client.content().get( group.getKey(), metadataPath ))
        {
            IOUtils.toString( in );
        }

        // Verify the group meta file exists
        ConcreteResource r_meta = new ConcreteResource( LocationUtils.toLocation( group ), metadataPath );
        assertTrue( cacheProvider.exists( r_meta ) );

        // Delete group
        client.stores().delete( group.getKey(), "Delete", true );
        assertThat( client.stores().exists( group.getKey() ), equalTo( false ) );

        // Verify the group meta file gone
        assertFalse( cacheProvider.exists( r_meta ) );

        // Verify hosted files not change (delete group not affect the files in hosted repo)
        ConcreteResource r1 = new ConcreteResource( LocationUtils.toLocation( repo ), pomPath );
        assertTrue( cacheProvider.exists( r1 ) );
        assertTrue( cacheProvider.exists( r_meta_hosted ) );
    }
}

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
import org.apache.commons.lang.StringUtils;
import org.commonjava.indy.ftest.core.AbstractContentManagementTest;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.test.fixture.core.CoreServerFixture;
import org.commonjava.test.http.expect.ExpectationServer;
import org.junit.Rule;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.commonjava.indy.core.content.group.GroupRepositoryFilterManager.REPO_FILTER;
import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;
import static org.commonjava.indy.subsys.template.ScriptEngine.SCRIPTS_SUBDIR;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

/**
 * Given:
 *   - Custom filter rh-pattern-repofilter.groovy
 *      - Exclude 'build-\d' if path is normal (ie. not match -rh*)
 *      - Do filter only if path is normal file, not metadata
 *
 * Given:
 *   - Remote repo R
 *   - Remote repo R contains normal path P1
 *   - Remote repo R contains metadata M1
 *   - Hosted repo build-1
 *   - Hosted repo build-1 contains path P1 (to confuse retrieval process)
 *   - Hosted repo build-1 contains path P2 (match -rh* pattern)
 *   - Hosted repo build-1 contains metadata M1
 *   - Group G contains build-1, R
 *   - Some other hosted build-x repos without content (to confuse it, we add it before build-1)
 *
 * When:
 *   - Get path P1 from group G
 *
 * Then:
 *   - Ignore the hosted build-1 and get the content from remote R
 *
 * When:
 *   - Get path P2 from group G
 *
 * Then:
 *   - Get content from hosted build-1
 *
 * When:
 *   - Get metadata M1 from group G
 *
 * Then:
 *   - Get merged content from R and build-1
 */
public class RepositoryFilterTest
                extends AbstractContentManagementTest
{

    @Rule
    public ExpectationServer server = new ExpectationServer();

    /* @formatter:off */
    private static final String METADATA_TEMPLATE = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        "<metadata>\n" +
        "  <groupId>org.foo</groupId>\n" +
        "  <artifactId>bar</artifactId>\n" +
        "  <versioning>\n" +
        "    <latest>%version%</latest>\n" +
        "    <release>%version%</release>\n" +
        "    <versions>\n" +
        "      <version>%version%</version>\n" +
        "    </versions>\n" +
        "  </versioning>\n" +
        "</metadata>\n";
    /* @formatter:on */

    @Test
    public void run() throws Exception
    {
        final String path_1 = "org/foo/bar/1.0/bar-1.0.pom";
        final String content_1_remote = "This is a test from remote";
        final String content_1 = "This is a test";

        final String path_2 = "org/foo/bar/1.0-rh-0001/bar-1.0-rh-0001.pom";
        final String content_2 = "This is a rh test";

        final String remoteR = "R";
        final String hostedBuild_1 = "build-1";
        final String groupG = "G";

        final String metadataPath = "org/foo/bar/maven-metadata.xml";

        server.expect( server.formatUrl( remoteR, path_1 ), 200, content_1_remote );
        server.expect( server.formatUrl( remoteR, metadataPath ), 200,
                       METADATA_TEMPLATE.replaceAll( "%version%", "1.0" ) );

        RemoteRepository remote = client.stores()
                                        .create( new RemoteRepository( MAVEN_PKG_KEY, remoteR,
                                                                       server.formatUrl( remoteR ) ), "Add remote",
                                                 RemoteRepository.class );

        HostedRepository hosted = client.stores()
                                        .create( new HostedRepository( MAVEN_PKG_KEY, hostedBuild_1 ), "Add hosted",
                                                 HostedRepository.class );

        client.content().store( hosted.getKey(), path_1, new ByteArrayInputStream( content_1.getBytes() ) );
        client.content().store( hosted.getKey(), path_2, new ByteArrayInputStream( content_2.getBytes() ) );
        client.content()
              .store( hosted.getKey(), metadataPath, new ByteArrayInputStream(
                              METADATA_TEMPLATE.replaceAll( "%version%", "1.0-rh-0001" ).getBytes() ) );

        // add confusing repos, hope it is not confused
        List<StoreKey> storeKeys = new ArrayList<>();
        for ( int i = 100; i < 200; i++ )
        {
            HostedRepository h = client.stores()
                                       .create( new HostedRepository( MAVEN_PKG_KEY, "build-" + i ), "Add hosted",
                                                HostedRepository.class );
            storeKeys.add( h.getKey() );
        }

        storeKeys.add( hosted.getKey() );
        storeKeys.add( remote.getKey() );

        // create group
        StoreKey[] keyArray = new StoreKey[storeKeys.size()];
        Group g = client.stores()
                        .create( new Group( MAVEN_PKG_KEY, groupG, storeKeys.toArray( keyArray ) ), "Add group",
                                 Group.class );

        System.out.printf( "\n\nGroup constituents are:\n  %s\n\n", StringUtils.join( g.getConstituents(), "\n  " ) );

        // get
        try (InputStream stream = client.content().get( g.getKey(), path_1 ))
        {
            String str = IOUtils.toString( stream );
            assertThat( str, equalTo( content_1_remote ) );
        }

        try (InputStream stream = client.content().get( g.getKey(), path_2 ))
        {
            String str = IOUtils.toString( stream );
            assertThat( str, equalTo( content_2 ) );
        }

        try (InputStream stream = client.content().get( g.getKey(), metadataPath ))
        {
            String str = IOUtils.toString( stream );
            assertThat( str, containsString( "<version>1.0</version>" ) );
            assertThat( str, containsString( "<version>1.0-rh-0001</version>" ) );
        }
    }

    @Override
    protected void initTestData( CoreServerFixture fixture ) throws IOException
    {
        String filterFile = "rh-pattern-repofilter.groovy";
        copyToDataFile( "repofilter/" + filterFile, SCRIPTS_SUBDIR + "/" + REPO_FILTER + "/" + filterFile );
    }

    @Override
    protected boolean createStandardTestStructures()
    {
        return false;
    }
}

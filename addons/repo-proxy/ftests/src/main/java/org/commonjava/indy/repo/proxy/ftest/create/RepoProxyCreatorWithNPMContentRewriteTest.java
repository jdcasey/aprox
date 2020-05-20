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
package org.commonjava.indy.repo.proxy.ftest.create;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.commonjava.indy.ftest.core.AbstractContentManagementTest;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.pkg.npm.model.NPMPackageTypeDescriptor;
import org.commonjava.indy.test.fixture.core.CoreServerFixture;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.commonjava.indy.pkg.PackageTypeConstants.PKG_TYPE_NPM;
import static org.commonjava.maven.galley.util.PathUtils.normalize;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;

/**
 * Check if the repo proxy addon can proxy a npm group repo automatically with no remote setup, and npm metadata with huge content can be replaced correctly
 * <br/>
 * GIVEN:
 * <ul>
 *     <li>A external npm repo with specified metadata path of huge content </li>
 *     <li>Configured the repo-proxy enabled</li>
 *     <li>Deployed a repo creator script whose rule to create remote repo which points to the external repo</li>
 * </ul>
 * <br/>
 * WHEN:
 * <ul>
 *     <li>Request npm metadata path through a group repo</li>
 * </ul>
 * <br/>
 * THEN:
 * <ul>
 *     <li>The content of path can be returned correctly from group</li>
 *     <li>The remote repo from creator script has been set up.</li>
 *     <li>The url which points to remote repo has been replaced to the group repo</li>
 * </ul>
 */

public class RepoProxyCreatorWithNPMContentRewriteTest
        extends AbstractContentManagementTest
{
    private static final String REPO_NAME = "npmjs";

    private static final String PATH_ROOT = "jquery";

    private int expectRepoUrlCountMatch;

    @Before
    public void setupRepos()
            throws Exception
    {
        String CONTENT_JQUERY = getHugeMetaContent( server.formatUrl( REPO_NAME ) );
        server.expect( server.formatUrl( REPO_NAME, PATH_ROOT ), 200,
                       new ByteArrayInputStream( CONTENT_JQUERY.getBytes() ) );
    }

    @Test
    public void run()
            throws Exception
    {
        final String remoteName = String.format( "%s-%s", StoreType.group, REPO_NAME );
        final StoreKey remoteKey =
                new StoreKey( NPMPackageTypeDescriptor.NPM_PKG_KEY, StoreType.remote, remoteName );
        RemoteRepository remote = client.stores().load( remoteKey, RemoteRepository.class );
        assertThat( remote, nullValue() );

        final String REMOTE_REPO_PATH = normalize( fixture.getUrl(), "content/npm/remote", remoteName );
        final String GROUP_REPO_PATH = normalize( fixture.getUrl(), "content/npm/group", REPO_NAME );

        final Group group = new Group( PKG_TYPE_NPM, REPO_NAME );
        try (InputStream result = client.content().get( group.getKey(), PATH_ROOT ))
        {
            assertThat( result, notNullValue() );
            final String content = IOUtils.toString( result );
            logger.debug( "NPM Rewrite content: {}", content );
            assertThat( expectRepoUrlCountMatch, equalTo( StringUtils.countMatches( content, GROUP_REPO_PATH ) ) );
            assertThat( content.contains( REMOTE_REPO_PATH ), equalTo( false ) );
        }

        remote = client.stores().load( remoteKey, RemoteRepository.class );
        assertThat( remote, notNullValue() );
    }

    @Override
    protected void initTestConfig( CoreServerFixture fixture )
            throws IOException
    {
        writeConfigFile( "conf.d/repo-proxy.conf", "[repo-proxy]\nenabled=true" );
    }

    private String getHugeMetaContent( final String repoUrl )
    {
        final StringBuilder buffer = new StringBuilder(
                "{\"_id\": \"jquery\",\"_rev\": \"650-60d066f00248b6c41ab11151ed05d37e\",  \"name\": \"jquery\",  \"description\": \"JavaScript library for DOM operations\"" );

        buffer.append( "\"versions\":{" );
        int majorMax = 10, minorMax = 40, relMax = 20;
        expectRepoUrlCountMatch = majorMax * minorMax * relMax;
        for ( int major = 1; major <= majorMax; major++ )
        {
            for ( int minor = 0; minor < minorMax; minor++ )
            {
                for ( int rel = 0; rel < relMax; rel++ )
                {
                    final String version = String.format( "%s.%s.%s", major, minor, rel );
                    buffer.append( quoted( version ) )
                          .append( ": {" )
                          .append( " \"name\": \"jquery\"," )
                          .append( "\"title\": \"jQuery\"," )
                          .append( "\"description\": \"JavaScript library for DOM operations\"," )
                          .append( "\"version\": " )
                          .append( quoted( version ) )
                          .append( "," )
                          .append( "\"main\": \"dist/jquery.js\"," )
                          .append( "\"dist\": {\"shasum\": " )
                          .append( fakeShasum( version ) )
                          .append( "," )
                          .append( "\"tarball\": \"" )
                          .append( repoUrl )
                          .append( "/jquery/-/jquery-" )
                          .append( version )
                          .append( ".tgz\"" + "}" + "}," );
                }
            }
        }
        buffer.deleteCharAt( buffer.length() - 1 );

        buffer.append( "}}" );

        logger.info( "The content size is {} kB", buffer.length() / 1024 );

        return buffer.toString();
    }

    private String quoted( final String content )
    {
        return "\"" + content + "\"";
    }

    private String fakeShasum( final String version )
    {
        final String base = "2ae2d661e906c1a01e044a71bb5b274394";
        final String gen = version.replaceAll( "\\.", "" );
        return base + gen;
    }

    private String getRuleScriptContent()
    {
        final String targetBase = server.formatUrl( REPO_NAME );
        // @formatter:off
        return
                "import org.commonjava.indy.repo.proxy.create.*\n" +
                        "import org.commonjava.indy.model.core.*\n" +
                        "class DefaultRule extends AbstractProxyRepoCreateRule {\n" +
                        "    @Override\n" +
                        "    boolean matches(StoreKey storeKey) {\n" +
                        "        return \"npm\".equals(storeKey.getPackageType()) && StoreType.group==storeKey.getType()\n" +
                        "    }\n" +
                        "    @Override\n" +
                        "    Optional<RemoteRepository> createRemote(StoreKey key) {\n" +
                        "        return Optional.of(new RemoteRepository(key.getPackageType(), String.format(\"%s-%s\", StoreType.group, key.getName()), \"" + targetBase + "\"))\n" +
                        "    }\n" +
                        "}";
        // @formatter:on;
    }

    @Override
    public void initTestData( CoreServerFixture fixture )
            throws IOException
    {
        writeDataFile( "repo-proxy/default-rule.groovy", getRuleScriptContent() );

        super.initTestData( fixture );
    }
}

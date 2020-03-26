/**
 * Copyright (C) 2013 Red Hat, Inc.
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
package org.commonjava.indy.repo.proxy.ftest;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.commonjava.indy.ftest.core.AbstractContentManagementTest;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.test.fixture.core.CoreServerFixture;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.commonjava.indy.pkg.PackageTypeConstants.PKG_TYPE_NPM;
import static org.commonjava.maven.galley.util.PathUtils.normalize;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;

/**
 * Check if the NPM metadata rewriting feature can work well with some huge NPM metadata content
 * <br/>
 * GIVEN:
 * <ul>
 *     <li>Hosted and Remote NPM Repo</li>
 *     <li>Path for NPM metadata and the metadata content is huge (> 2MB with more than 8000 versions)</li>
 *     <li>Hosted does not have path but Remote does</li>
 *     <li>Configured proxy rule with mapping hosted proxy to remote</li>
 * </ul>
 * <br/>
 * WHEN:
 * <ul>
 *     <li>Request path through hosted</li>
 * </ul>
 * <br/>
 * THEN:
 * <ul>
 *     <li>The content of path can be returned correctly from hosted</li>
 *     <li>The url in the content which points to Remote for the returned path has been rewritten to point to Hosted</li>
 * </ul>
 */
public class RepoProxyNPMHugeMetaContentRewriteTest
        extends AbstractContentManagementTest
{
    private static final String REPO_NAME = "npmjs";

    private HostedRepository hosted;

    private static final String PATH_ROOT = "jquery";

    private String CONTENT_ANGULAR_CORE;

    private String REMOTE_REPO_PATH;

    private String HOSTED_REPO_PATH;

    private int expectRepoUrlCountMatch;

    @Before
    public void setupRepos()
            throws Exception
    {
        String CONTENT_JQUERY = getHugeMetaContent( server.formatUrl( REPO_NAME ) );
        server.expect( server.formatUrl( REPO_NAME, PATH_ROOT ), 200,
                       new ByteArrayInputStream( CONTENT_JQUERY.getBytes() ) );

        RemoteRepository remote = client.stores()
                                        .create( new RemoteRepository( PKG_TYPE_NPM, REPO_NAME,
                                                                       server.formatUrl( REPO_NAME ) ), "remote npmjs",
                                                 RemoteRepository.class );

        hosted = new HostedRepository( PKG_TYPE_NPM, REPO_NAME );

        REMOTE_REPO_PATH = normalize( fixture.getUrl(), "content/npm/remote", remote.getName() );
        HOSTED_REPO_PATH = normalize( fixture.getUrl(), "content/npm/hosted", hosted.getName() );
    }

    @Test
    public void run()
            throws Exception
    {
        try (InputStream result = client.content().get( hosted.getKey(), PATH_ROOT ))
        {
            assertThat( result, notNullValue() );
            final String content = IOUtils.toString( result );
            logger.debug( "NPM Rewrite content: {}", content );
            assertThat( expectRepoUrlCountMatch, equalTo( StringUtils.countMatches( content, HOSTED_REPO_PATH ) ) );
            assertThat( content.contains( REMOTE_REPO_PATH ), equalTo( false ) );
        }

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

}

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
 * Check if the NPM metadata rewriting features can work well for this proxy addon
 * <br/>
 * GIVEN:
 * <ul>
 *     <li>Hosted and Remote NPM Repo</li>
 *     <li>Path for NPM metadata</li>
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
public class RepoProxyNPMMetaRewriteTest
        extends AbstractContentManagementTest
{
    private static final String REPO_NAME = "npmjs";

    private HostedRepository hosted;

    private RemoteRepository remote;

    private static final String PATH_ROOT = "jquery";

    private static final String PATH_SCOPED = "@angular/core";

    private static final String PATH_PACKAGE_JSON = "jquery/package.json";

    //@formatter:off
    private static final String CONTENT_JQUERY_TEMPLATE = "{"
            + "\"_id\": \"jquery\","
            + "\"versions\":{"
            + "\"1.5.1\": {"
            + "\"dist\": {"
            + "\"shasum\": \"2ae2d661e906c1a01e044a71bb5b2743942183e5\","
            + "\"tarball\": \"%s/jquery/-/jquery-1.5.1.tgz\""
            + "}"
            + "}"
            + "}"
            + "}";

    private static final String CONTENT_ANGULAR_CORE_TEMPLATE = "{"
            + "\"_id\": \"@angular/core\","
            + "\"versions\": {"
            + "\"9.0.1\": {"
            + "\"dist\": {"
            + "\"shasum\": \"8908112ce6bb22aa1ae537230240ef9a324409ad\","
            + "\"tarball\": \"%s/@angular/core/-/core-9.0.1.tgz\""
            + "}"
            + "}"
            + "}"
            + "}";
    //@formatter:on

    private String CONTENT_JQUERY;

    private String CONTENT_ANGULAR_CORE;

    private String REMOTE_REPO_PATH;

    private String HOSTED_REPO_PATH;

    @Before
    public void setupRepos()
            throws Exception
    {
        CONTENT_JQUERY = String.format( CONTENT_JQUERY_TEMPLATE, server.formatUrl( REPO_NAME ) );
        server.expect( server.formatUrl( REPO_NAME, PATH_ROOT ), 200,
                       new ByteArrayInputStream( CONTENT_JQUERY.getBytes() ) );
        server.expect( server.formatUrl( REPO_NAME, PATH_PACKAGE_JSON ), 200,
                       new ByteArrayInputStream( CONTENT_JQUERY.getBytes() ) );

        CONTENT_ANGULAR_CORE = String.format( CONTENT_ANGULAR_CORE_TEMPLATE, server.formatUrl( REPO_NAME ) );
        server.expect( server.formatUrl( REPO_NAME, PATH_SCOPED ), 200,
                       new ByteArrayInputStream( CONTENT_ANGULAR_CORE.getBytes() ) );

        remote = client.stores()
                       .create( new RemoteRepository( PKG_TYPE_NPM, REPO_NAME, server.formatUrl( REPO_NAME ) ),
                                "remote npmjs", RemoteRepository.class );

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
            assertThat( content, containsString( HOSTED_REPO_PATH ) );
            assertThat( content.contains( REMOTE_REPO_PATH ), equalTo( false ) );
        }

        try (InputStream result = client.content().get( hosted.getKey(), PATH_SCOPED ))
        {
            assertThat( result, notNullValue() );
            final String content = IOUtils.toString( result );
            logger.debug( "NPM Rewrite content: {}", content );
            assertThat( content, containsString( HOSTED_REPO_PATH ) );
            assertThat( content.contains( REMOTE_REPO_PATH ), equalTo( false ) );
        }

        try (InputStream result = client.content().get( hosted.getKey(), PATH_PACKAGE_JSON ))
        {
            assertThat( result, notNullValue() );
            final String content = IOUtils.toString( result );
            logger.debug( "NPM Rewrite content: {}", content );
            assertThat( content, containsString( HOSTED_REPO_PATH ) );
            assertThat( content.contains( REMOTE_REPO_PATH ), equalTo( false ) );
        }

    }

    @Override
    protected void initTestConfig( CoreServerFixture fixture )
            throws IOException
    {
        writeConfigFile( "conf.d/repo-proxy.conf", "[repo-proxy]\nenabled=true" );
    }

}

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

import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.ftest.core.AbstractContentManagementTest;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor;
import org.commonjava.indy.test.fixture.core.CoreServerFixture;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.commonjava.indy.pkg.PackageTypeConstants.PKG_TYPE_MAVEN;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

/**
 * Check if the repo proxy addon can proxy a hosted repo to a remote repo with specific api methods
 * <br/>
 * GIVEN:
 * <ul>
 *     <li>Remote Repo and contains pathA</li>
 *     <li>Configured the repo-proxy enabled</li>
 *     <li>Configured api methods</li>
 * </ul>
 * <br/>
 * WHEN:
 * <ul>
 *     <li>Request pathA through a hosted repo which is same-named as Remote with specified api methods</li>
 *     <li>Request pathA through the hosted without specified api methods</li>
 * </ul>
 * <br/>
 * THEN:
 * <ul>
 *     <li>The content of pathA can be returned correctly from hosted when methods applied</li>
 *     <li>The content of pathA can be returned correctly from hosted when methods not applied</li>
 * </ul>
 */
public class RepoProxyMethodsTest
        extends AbstractContentManagementTest
{
    private static final String REPO_NAME = "test";

    private HostedRepository hosted;

    private RemoteRepository remote;

    private static final String PATH1 = "foo/bar/1.0/foo-bar-1.0.txt";

    private static final String CONTENT1 = "This is content 1";

    @Before
    public void setupRepos()
            throws Exception
    {

        server.expect( server.formatUrl( REPO_NAME, PATH1 ), 200, new ByteArrayInputStream( CONTENT1.getBytes() ) );

        remote = client.stores()
                       .create( new RemoteRepository( PKG_TYPE_MAVEN, REPO_NAME,
                                                      server.formatUrl( REPO_NAME ) ), "remote pnc-builds",
                                RemoteRepository.class );

        hosted = new HostedRepository( MavenPackageTypeDescriptor.MAVEN_PKG_KEY, REPO_NAME );
    }

    @Test( expected = IndyClientException.class )
    public void runPUTWithError()
            throws Exception
    {
        client.content().store( hosted.getKey(), PATH1, new ByteArrayInputStream( CONTENT1.getBytes() ) );
        fail( "Should not store successfully: proxy remote does not support deployment!" );
    }

    @Test
    public void runNoContentStored()
            throws Exception
    {
        InputStream result = client.content().get( hosted.getKey(), PATH1 );
        assertThat( result, nullValue() );
    }

    @Override
    protected void initTestConfig( CoreServerFixture fixture )
            throws IOException
    {
        writeConfigFile( "conf.d/repo-proxy.conf", "[repo-proxy]\nenabled=true\napi.methods=PUT" );
    }

}

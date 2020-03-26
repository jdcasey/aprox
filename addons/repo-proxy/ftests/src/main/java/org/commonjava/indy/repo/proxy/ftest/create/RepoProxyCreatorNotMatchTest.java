/**
 * Copyright (C) 2020 Red Hat, Inc.
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
import org.commonjava.indy.ftest.core.AbstractContentManagementTest;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor;
import org.commonjava.indy.test.fixture.core.CoreServerFixture;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;

/**
 * Check if the repo proxy addon can proxy a hosted repo automatically based on matched script rules
 * <br/>
 * GIVEN:
 * <ul>
 *     <li>A external repo with specified path </li>
 *     <li>Configured the repo-proxy enabled</li>
 *     <li>Deployed a repo creator script rule. This rule will match specified repo name then create same-named repo. If not matched, do nothing.</li>
 * </ul>
 * <br/>
 * WHEN:
 * <ul>
 *     <li>Request path through two Hosted repos, one with matched name in rule and the other not</li>
 * </ul>
 * <br/>
 * THEN:
 * <ul>
 *     <li>The content of path can be returned correctly from hosted which name matches the rule, and related remote will be set up.</li>
 *     <li>The content of path can not be returned correctly from hosted which name does not match the rule, and related remote will not be set up.</li>
 * </ul>
 */
public class RepoProxyCreatorNotMatchTest
        extends AbstractContentManagementTest

{
    private static final String REPO_NAME_1 = "test";

    private static final String REPO_NAME_2 = "not-matched";

    private HostedRepository hosted1 = new HostedRepository( MavenPackageTypeDescriptor.MAVEN_PKG_KEY, REPO_NAME_1 );

    private HostedRepository hosted2 = new HostedRepository( MavenPackageTypeDescriptor.MAVEN_PKG_KEY, REPO_NAME_2 );

    private static final String PATH = "foo/bar/1.0/foo-bar-1.0.txt";

    private static final String CONTENT = "This is content";

    @Before
    public void setupRepos()
            throws Exception
    {
        server.expect( server.formatUrl( REPO_NAME_1, PATH ), 200, new ByteArrayInputStream( CONTENT.getBytes() ) );
    }

    @Test
    public void run()
            throws Exception
    {
        final StoreKey remoteKey1 =
                new StoreKey( MavenPackageTypeDescriptor.MAVEN_PKG_KEY, StoreType.remote, REPO_NAME_1 );
        RemoteRepository remote1 = client.stores().load( remoteKey1, RemoteRepository.class );
        assertThat( remote1, nullValue() );

        try (InputStream result = client.content().get( hosted1.getKey(), PATH ))
        {
            assertThat( result, notNullValue() );
            final String content = IOUtils.toString( result );
            assertThat( content, equalTo( CONTENT ) );
        }

        remote1 = client.stores().load( remoteKey1, RemoteRepository.class );
        assertThat( remote1, notNullValue() );

        final StoreKey remoteKey2 =
                new StoreKey( MavenPackageTypeDescriptor.MAVEN_PKG_KEY, StoreType.remote, REPO_NAME_2 );
        RemoteRepository remote2 = client.stores().load( remoteKey2, RemoteRepository.class );
        assertThat( remote2, nullValue() );

        try (InputStream result = client.content().get( hosted2.getKey(), PATH ))
        {
            assertThat( result, nullValue() );
        }

        remote2 = client.stores().load( remoteKey2, RemoteRepository.class );
        assertThat( remote2, nullValue() );
    }

    @Override
    protected void initTestConfig( CoreServerFixture fixture )
            throws IOException
    {
        writeConfigFile( "conf.d/repo-proxy.conf", "[repo-proxy]\nenabled=true" );
    }

    @Override
    public void initTestData( CoreServerFixture fixture )
            throws IOException
    {
        writeDataFile( "repo-proxy/default-rule.groovy", getRuleScriptContent() );

        super.initTestData( fixture );
    }

    private String getRuleScriptContent()
    {
        final String targetBase = server.formatUrl( REPO_NAME_1 );
        // @formatter:off
        return
            "import org.commonjava.indy.repo.proxy.create.*\n" +
            "import org.commonjava.indy.model.core.*\n" +
            "class DefaultRule extends AbstractProxyRepoCreateRule {\n" +
            "    @Override\n" +
            "    boolean matches(StoreKey storeKey) {\n" +
            "        return \"maven\".equals(storeKey.getPackageType()) && \"" + REPO_NAME_1 + "\".equals(storeKey.getName())\n" +
            "    }\n" +
            "    @Override\n" +
            "    Optional<RemoteRepository> createRemote(StoreKey key) {\n" +
            "        return Optional.of(new RemoteRepository(key.getPackageType(), key.getName(), \"" + targetBase + "\"))\n" +
            "    }\n" +
            "}";
        // @formatter:on;
    }
}

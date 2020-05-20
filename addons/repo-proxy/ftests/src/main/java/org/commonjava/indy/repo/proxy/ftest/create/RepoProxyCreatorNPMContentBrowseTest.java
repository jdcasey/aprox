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

import org.commonjava.indy.client.core.IndyClientModule;
import org.commonjava.indy.content.browse.client.IndyContentBrowseClientModule;
import org.commonjava.indy.content.browse.model.ContentBrowseResult;
import org.commonjava.indy.ftest.core.AbstractContentManagementTest;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.test.fixture.core.CoreServerFixture;
import org.junit.Test;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;

import static org.commonjava.indy.pkg.npm.model.NPMPackageTypeDescriptor.NPM_PKG_KEY;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;

/**
 * Check if the content browse rewriting features in npm can work well for this proxy addon
 * <br/>
 * GIVEN:
 * <ul>
 *     <li>A external repo with specified path </li>
 *     <li>Configured the repo-proxy enabled</li>
 *     <li>Deployed a repo creator script whose rule to create npm remote repo which points to the external repo</li>
 * </ul>
 * <br/>
 * WHEN:
 * <ul>
 *     <li>Request directory path through content-browse api for a npm hosted repo</li>
 * </ul>
 * <br/>
 * THEN:
 * <ul>
 *     <li>The content for this request can be returned correctly.</li>
 *     <li>The content should include hosted repo info for both store key and key path.</li>
 * </ul>
 */
public class RepoProxyCreatorNPMContentBrowseTest
        extends AbstractContentManagementTest

{
    private static final String REPO_NAME = "test";

    private HostedRepository hosted = new HostedRepository( NPM_PKG_KEY, REPO_NAME );

    @Test
    public void run()
            throws Exception
    {
        final StoreKey remoteKey =
                new StoreKey( NPM_PKG_KEY, StoreType.remote, REPO_NAME );
        RemoteRepository remote = client.stores().load( remoteKey, RemoteRepository.class );
        assertThat( remote, nullValue() );

        ContentBrowseResult result = client.module( IndyContentBrowseClientModule.class ).getContentList( hosted.getKey(), "" );
        assertThat( result, notNullValue() );

        remote = client.stores().load( remoteKey, RemoteRepository.class );
        assertThat( remote, notNullValue() );

        assertThat( result.getStoreKey(), equalTo( hosted.getKey() ) );
        assertThat( result.getStoreBrowseUrl(), containsString( "npm/hosted/test" ) );
        assertThat( result.getStoreContentUrl(), containsString( "npm/hosted/test" ) );

    }

    @Override
    protected void initTestConfig( CoreServerFixture fixture )
            throws IOException
    {
        writeConfigFile( "conf.d/repo-proxy.conf", "[repo-proxy]\nenabled=true\n\n[content-browse]\nenabled=true\n" );
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
        final String targetBase = server.formatUrl( REPO_NAME );
        // @formatter:off
        return
            "import org.commonjava.indy.repo.proxy.create.*\n" +
            "import org.commonjava.indy.model.core.*\n" +
            "class DefaultRule extends AbstractProxyRepoCreateRule {\n" +
            "    @Override\n" +
            "    boolean matches(StoreKey storeKey) {\n" +
            "        return \"npm\".equals(storeKey.getPackageType())\n" +
            "    }\n" +
            "    @Override\n" +
            "    Optional<RemoteRepository> createRemote(StoreKey key) {\n" +
            "        return Optional.of(new RemoteRepository(key.getPackageType(), key.getName(), \"" + targetBase + "\"))\n" +
            "    }\n" +
            "}";
        // @formatter:on;
    }

    @Override
    protected Collection<IndyClientModule> getAdditionalClientModules()
    {
        return Collections.singletonList( new IndyContentBrowseClientModule() );
    }
}

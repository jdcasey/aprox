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
package org.commonjava.indy.pkg.npm.content;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.commonjava.indy.client.core.util.UrlUtils;
import org.commonjava.indy.ftest.core.AbstractContentManagementTest;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.commonjava.indy.pkg.npm.model.PackageMetadata;
import org.commonjava.indy.pkg.npm.model.UserInfo;
import org.commonjava.indy.pkg.npm.model.VersionMetadata;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.commonjava.indy.pkg.npm.model.NPMPackageTypeDescriptor.NPM_PKG_KEY;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;

/**
 * This case tests if package.json can be merged successfully for a group repo
 * when: <br />
 * <ul>
 *      <li>creates two remote repos and expect two package.json files in them</li>
 *      <li>creates group A repo contains the two remote members</li>
 * </ul>
 * then: <br />
 * <ul>
 *     <li>the merged file can be retrieved correctly for the group A</li>
 * </ul>
 */
public class NPMGroupContentMergeRetrieveTest
                extends AbstractContentManagementTest
{

    private static final String REPO_X = "X";

    private static final String REPO_Y = "Y";

    private static final String GROUP_A = "A";

    private static final String PATH = "jquery";

    @Test
    public void test() throws Exception
    {
        final String CONTENT_1 = IOUtils.toString(
                Thread.currentThread().getContextClassLoader().getResourceAsStream( "package-1.5.1.json" ) );
        final String CONTENT_2 = IOUtils.toString(
                Thread.currentThread().getContextClassLoader().getResourceAsStream( "package-1.6.2.json" ) );

        server.expect( server.formatUrl( REPO_X, PATH ), 200, new ByteArrayInputStream( CONTENT_1.getBytes() ) );

        server.expect( server.formatUrl( REPO_Y, PATH ), 200, new ByteArrayInputStream( CONTENT_2.getBytes() ) );

        final RemoteRepository repoX = new RemoteRepository( NPM_PKG_KEY, REPO_X, server.formatUrl( REPO_X ) );
        client.stores().create( repoX, "adding npm remote repo", RemoteRepository.class );

        final RemoteRepository repoY = new RemoteRepository( NPM_PKG_KEY, REPO_Y, server.formatUrl( REPO_Y ) );
        client.stores().create( repoY, "adding npm remote repo", RemoteRepository.class );

        final Group groupA = new Group( NPM_PKG_KEY, GROUP_A, repoX.getKey(), repoY.getKey() );
        client.stores().create( groupA, "adding npm group repo", Group.class );

        System.out.printf( "\n\n-------Group constituents are:\n  %s\n\n",
                           StringUtils.join( groupA.getConstituents(), "\n  " ) );

        final InputStream remote = client.content().get( repoX.getKey(), PATH );
        final InputStream group = client.content().get( groupA.getKey(), PATH );

        assertThat( remote, notNullValue() );
        assertThat( group, notNullValue() );

        String contextUrl = UrlUtils.buildUrl( fixture.getUrl(), "content", NPM_PKG_KEY, repoX.getType().name(), REPO_X );
        String maskedUrl = contextUrl + "/jquery/-/jquery-1.5.1.tgz";

        assertThat( IOUtils.toString( remote ), equalTo( CONTENT_1.replace( "https://registry.npmjs.org/jquery/-/jquery-1.5.1.tgz", maskedUrl ) ) );

        IndyObjectMapper mapper = new IndyObjectMapper( true );
        PackageMetadata merged = mapper.readValue( IOUtils.toString( group ), PackageMetadata.class );

        // normal object fields merging verification
        assertThat( merged.getName(), equalTo( "jquery" ) );
        assertThat( merged.getAuthor().getName(), equalTo( "JS Foundation and other contributors" ) );
        assertThat( merged.getRepository().getType(), equalTo( "git" ) );
        assertThat( merged.getRepository().getUrl(), equalTo( "git+https://github.com/jquery/jquery.git" ) );
        assertThat( merged.getReadmeFilename(), equalTo( "README.md" ) );
        assertThat( merged.getHomepage(), equalTo( "https://jquery.com" ) );
        assertThat( merged.getBugs().getUrl(), equalTo( "https://github.com/jquery/jquery/issues" ) );
        assertThat( merged.getLicense().getType(), equalTo( "MIT" ) );

        // dist-tags object merging verification
        assertThat( merged.getDistTags().getBeta(), equalTo( "3.2.1-beta.1" ) );
        assertThat( merged.getDistTags().getLatest(), equalTo( "3.2.1" ) );

        // versions map merging verification
        Map<String, VersionMetadata> versions = merged.getVersions();
        assertThat( versions, notNullValue() );
        assertThat( versions.size(), equalTo( 2 ) );
        assertThat( versions.get( "1.5.1" ).getVersion(), equalTo( "1.5.1" ) );
        assertThat( versions.get( "1.6.2" ).getVersion(), equalTo( "1.6.2" ) );

        // maintainers list merging verification
        List<UserInfo> maintainers = merged.getMaintainers();
        assertThat( maintainers, notNullValue() );
        assertThat( maintainers.size(), equalTo( 3 ) );
        assertThat( maintainers.get( 0 ).getName(), equalTo( "dmethvin" ) );
        assertThat( maintainers.get( 1 ).getName(), equalTo( "mgol" ) );
        assertThat( maintainers.get( 2 ).getName(), equalTo( "scott.gonzalez" ) );

        // time map merging verification
        Map<String, String> times = merged.getTime();
        assertThat( times, notNullValue() );
        assertThat( times.size(), equalTo( 8 ) );
        assertThat( times.get( "modified" ), equalTo( "2017-05-23T10:57:14.309Z" ) );
        assertThat( times.get( "created" ), equalTo( "2011-04-19T07:19:56.392Z" ) );

        // users map merging verification
        Map<String, Boolean> users = merged.getUsers();
        assertThat( users, notNullValue() );
        assertThat( users.size(), equalTo( 10 ) );
        assertThat( users.get( "fgribreau" ), equalTo( true ) );

        // keywords list merging verification
        List<String> keywords = merged.getKeywords();
        assertThat( keywords, notNullValue() );
        assertThat( keywords.size(), equalTo( 4 ) );
        assertThat( keywords.contains( "javascript" ), equalTo( true ) );

        remote.close();
        group.close();
    }

    @Override
    protected boolean createStandardTestStructures()
    {
        return false;
    }
}

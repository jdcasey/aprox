/**
 * Copyright (C) 2011 Red Hat, Inc. (jdcasey@commonjava.org)
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
package org.commonjava.indy.pkg.maven.content;

import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.ftest.core.AbstractContentManagementTest;
import org.commonjava.indy.ftest.core.category.BytemanTest;
import org.commonjava.indy.ftest.core.category.EventDependent;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.jboss.byteman.contrib.bmunit.BMRule;
import org.jboss.byteman.contrib.bmunit.BMUnitConfig;
import org.jboss.byteman.contrib.bmunit.BMUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;

import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;

/**
 * This test assures that when a new metadata file is uploaded to a group's membership stores, any groups containing
 * that group also have their aggregated metadata files cleared for re-aggregation to include the new information.
 * <br/>
 * WHEN:
 * <ul>
 *     <li>Group A contains HostedRepository H</li>
 *     <li>Group B contains Group A</li>
 *     <li>A new maven-metadata.xml file is deployed to HostedRepository H</li>
 * </ul>
 * <br/>
 * THEN:
 * <ul>
 *     <li>Group A's maven-metadata.xml file is deleted</li>
 *     <li>Group B's maven-metadata.xml file is deleted</li>
 * </ul>
 */
@RunWith( BMUnitRunner.class )
@BMUnitConfig( debug = true )
@Category( BytemanTest.class )
public class RecursiveGroupMetadataClearOnUploadTest
        extends AbstractContentManagementTest
{
    private final String hostedName = "hosted";

    private final String groupAName = "groupA";

    private final String groupBName = "groupB";

    private final String path = "org/foo/bar/maven-metadata.xml";

    /* @formatter:off */
    private final String firstPassContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<metadata>\n" +
            "  <groupId>org.foo</groupId>\n" +
            "  <artifactId>bar</artifactId>\n" +
            "  <versioning>\n" +
            "    <latest>1.0</latest>\n" +
            "    <release>1.0</release>\n" +
            "    <versions>\n" +
            "      <version>1.0</version>\n" +
            "    </versions>\n" +
            "    <lastUpdated>20150722164334</lastUpdated>\n" +
            "  </versioning>\n" +
            "</metadata>\n";
        /* @formatter:on */

    /* @formatter:off */
    private final String secondPassContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<metadata>\n" +
            "  <groupId>org.foo</groupId>\n" +
            "  <artifactId>bar</artifactId>\n" +
            "  <versioning>\n" +
            "    <latest>1.1</latest>\n" +
            "    <release>1.1</release>\n" +
            "    <versions>\n" +
            "      <version>1.1</version>\n" +
            "    </versions>\n" +
            "    <lastUpdated>20150822164334</lastUpdated>\n" +
            "  </versioning>\n" +
            "</metadata>\n";
        /* @formatter:on */

    private HostedRepository hostedRepo;

    private Group groupA;

    private Group groupB;

    @Before
    public void setupRepos()
            throws IndyClientException, UnsupportedEncodingException
    {
        String change = "Setup test";
        hostedRepo = client.stores().create( new HostedRepository( MAVEN_PKG_KEY, hostedName ), change, HostedRepository.class );
        groupA = client.stores().create( new Group( MAVEN_PKG_KEY, groupAName, hostedRepo.getKey() ), change, Group.class );
        groupB = client.stores().create( new Group( MAVEN_PKG_KEY, groupBName, groupA.getKey() ), change, Group.class );

        client.content()
              .store( hostedRepo.getKey(), path, new ByteArrayInputStream( firstPassContent.getBytes( "UTF-8" ) ) );
    }

    @BMRule( name = "slow_down", targetClass = "org.commonjava.indy.content.index.ContentIndexManager",
             targetMethod = "clearIndexedPathFrom",
             targetLocation = "ENTRY",
             binding = "tctx:org.commonjava.cdi.util.weft.ThreadContext = ThreadContext.getContext(true);"
                     + "key:org.commonjava.indy.model.core.StoreKey = tctx.get(\"ContentIndex:originKey\");"
                     + "isFlagged:boolean = \"groupA\".equals(key.getName());",
             condition = "isFlagged", action = "System.out.println(\"Slowing down 4s\");" + "Thread.sleep(4000);" )
    @Test
    @Category( EventDependent.class )
    public void run()
            throws Exception
    {
        assertContent( hostedRepo, path, firstPassContent );
        assertContent( groupA, path, firstPassContent );
        assertContent( groupB, path, firstPassContent );

        client.content()
              .store( hostedRepo.getKey(), path, new ByteArrayInputStream( secondPassContent.getBytes( "UTF-8" ) ) );

        //        waitForEventPropagation();

        //        assertContent( hostedRepo, secondPassContent );
        //        assertContent( groupA, secondPassContent );
        assertContent( groupB, path, secondPassContent );

        Thread.currentThread().sleep( 4000 );
    }

    @Override
    protected boolean createStandardTestStructures()
    {
        return false;
    }
}

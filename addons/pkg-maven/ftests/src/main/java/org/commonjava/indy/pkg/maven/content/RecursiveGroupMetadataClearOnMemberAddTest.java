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

import org.apache.commons.io.IOUtils;
import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.ftest.core.AbstractContentManagementTest;
import org.commonjava.indy.ftest.core.category.EventDependent;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.custommonkey.xmlunit.XMLUnit;
import org.jboss.byteman.contrib.bmunit.BMRule;
import org.jboss.byteman.contrib.bmunit.BMUnitConfig;
import org.jboss.byteman.contrib.bmunit.BMUnitRunner;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;

import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;
import static org.custommonkey.xmlunit.XMLAssert.assertXMLEqual;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

/**
 * This test assures that when a new metadata file is uploaded to a group's membership stores, any groups containing
 * that group also have their aggregated metadata files cleared for re-aggregation to include the new information.
 * <br/>
 * GIVEN:
 * <ul>
 *     <li>HostedRepository X exists</li>
 *     <li>HostedRepository X contains maven-metadata.xml at path P</li>
 *     <li>Group A contains HostedRepository X</li>
 *     <li>Group B contains Group A</li>
 *     <li>Group C contains Group B</li>
 *     <li>Groups A, B, and C have maven-metadata.xml files at path P derived from the one in HostedRepository X</li>
 * </ul>
 * <br/>
 * WHEN:
 * <ol>
 *     <li>HostedRepository Y is created</li>
 *     <li>A new maven-metadata.xml file is deployed to path P in HostedRepository Y</li>
 *     <li>HostedRepository Y is added to Group A's membership</li>
 * </ol>
 * <br/>
 * THEN:
 * <ul>
 *     <li>Group A's metadata at path P contains versions from both HostedRepository X and HostedRepository Y</li>
 *     <li>Group B's metadata at path P contains versions from both HostedRepository X and HostedRepository Y</li>
 *     <li>Group C's metadata at path P contains versions from both HostedRepository X and HostedRepository Y</li>
 * </ul>
 */
//@RunWith( BMUnitRunner.class )
//@BMUnitConfig( debug = true )
public class RecursiveGroupMetadataClearOnMemberAddTest
        extends AbstractContentManagementTest
{
    private static final String HOSTED_X = "hostedX";

    private static final String HOSTED_Y = "hostedY";

    private static final String GROUP_A = "groupA";

    private static final String GROUP_B = "groupB";

    private static final String GROUP_C = "groupC";

    private static final String PATH = "org/foo/bar/maven-metadata.xml";

    /* @formatter:off */
    private static final String HOSTED_X_CONTENT = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
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
    private static final String HOSTED_Y_CONTENT = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
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

    /* @formatter:off */
    private static final String COMBINED_CONTENT = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<metadata>\n" +
            "  <groupId>org.foo</groupId>\n" +
            "  <artifactId>bar</artifactId>\n" +
            "  <versioning>\n" +
            "    <latest>1.1</latest>\n" +
            "    <release>1.1</release>\n" +
            "    <versions>\n" +
            "      <version>1.0</version>\n" +
            "      <version>1.1</version>\n" +
            "    </versions>\n" +
            "    <lastUpdated>20150822164334</lastUpdated>\n" +
            "  </versioning>\n" +
            "</metadata>\n";
    /* @formatter:on */

    private HostedRepository hostedX;

    private HostedRepository hostedY;

    private Group groupA;

    private Group groupB;

    private Group groupC;

    @Before
    public void setupRepos()
            throws IndyClientException, UnsupportedEncodingException
    {
        String change = "Setup test";
        hostedX = client.stores().create( new HostedRepository( MAVEN_PKG_KEY, HOSTED_X ), change, HostedRepository.class );
        hostedY = client.stores().create( new HostedRepository( MAVEN_PKG_KEY, HOSTED_Y ), change, HostedRepository.class );
        groupA = client.stores().create( new Group( MAVEN_PKG_KEY, GROUP_A, hostedX.getKey() ), change, Group.class );
        groupB = client.stores().create( new Group( MAVEN_PKG_KEY, GROUP_B, groupA.getKey() ), change, Group.class );
        groupC = client.stores().create( new Group( MAVEN_PKG_KEY, GROUP_C, groupB.getKey() ), change, Group.class );

        client.content()
              .store( hostedX.getKey(), PATH, new ByteArrayInputStream( HOSTED_X_CONTENT.getBytes( "UTF-8" ) ) );
    }

//    @BMRule( name = "slow_down", targetClass = "org.commonjava.indy.content.index.ContentIndexManager",
//             targetMethod = "clearIndexedPathFrom",
//             targetLocation = "ENTRY",
//             binding = "tctx:org.commonjava.cdi.util.weft.ThreadContext = ThreadContext.getContext(true);"
//                     + "key:StoreKey = (StoreKey) tctx.get(\"ContentIndex:originKey\");"
//                     + "isFlagged:boolean = \"groupA\".equals(key.getName());",
//             condition = "isFlagged", action = "System.out.println(\"Slowing down 4s\");" + "Thread.sleep(4000);" )
    @Test
    @Category( EventDependent.class )
    public void run()
            throws Exception
    {
        assertContent( hostedX, PATH, HOSTED_X_CONTENT );
        assertContent( groupA, PATH, HOSTED_X_CONTENT );
        assertContent( groupB, PATH, HOSTED_X_CONTENT );
        assertContent( groupC, PATH, HOSTED_X_CONTENT );

        client.content()
              .store( hostedY.getKey(), PATH, new ByteArrayInputStream( HOSTED_Y_CONTENT.getBytes( "UTF-8" ) ) );

        waitForEventPropagation();

        assertContent( hostedY, PATH, HOSTED_Y_CONTENT );

        groupA.addConstituent( hostedY );
        boolean updated = client.stores().update( groupA, "Add hosted Y" );

        assertThat( "Group A was NOT updated with HostedRepository Y membership!", updated, equalTo( true ) );

        waitForEventPropagation();

        // Order is important here...we want to try the most ambitious one first and move down to the simpler cases.
        // This will prevent us from inadvertently triggering metadata aggregation in a lower group that might not
        // happen otherwise.
        assertContent( groupC, PATH, COMBINED_CONTENT );
        assertContent( groupB, PATH, COMBINED_CONTENT );
        assertContent( groupA, PATH, COMBINED_CONTENT );
    }

    @Override
    protected boolean createStandardTestStructures()
    {
        return false;
    }
}

package org.commonjava.indy.pkg.maven.content;

import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.ftest.core.AbstractContentManagementTest;
import org.commonjava.indy.ftest.core.category.EventDependent;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;

import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

/**
 * Created by jdcasey on 1/19/17.
 *
 * This test assures that a particular maven-metadata.xml file should be missing in a group structure, but after it's
 * uploaded to a repository all groups affected by that repository should be able to return the metadata file.
 * <br/>
 * GIVEN:
 * <ul>
 *     <li>Group A with no members.</li>
 *     <li>Group B containing only Group A as a member.</li>
 *     <li>Retrieval of metadata path P from Group A returns null/404 result</li>
 *     <li>Retrieval of metadata path P from Group B returns null/404 result</li>
 *     <li>HostedRepository X is created for a build</li>
 * </ul>
 * <br/>
 * WHEN:
 * <ul>
 *     <li>metadata path P is uploaded to HostedRepository X as part of a build</li>
 *     <li>HostedRepository X is added to the membership of Group A</li>
 * </ul>
 * <br/>
 * THEN:
 * <ol>
 *     <li>Subsequent requests for metadata path P from Group B return the uploaded metadata</li>
 *     <li>Subsequent requests for metadata path P from Group A return the uploaded metadata</li>
 * </ol>
 * <br/>
 * (<b>NOTE:</b> Order of operations is important for verification steps, just in case checking Group A's metadata could
 * trigger the availability of the metadata in Group B.)
 */
public class RecursiveGroupMetadataFoundAfterMemberAddTest
        extends AbstractContentManagementTest
{
//    @ClassRule
//    public static TestRule TIMEOUT = timeoutRule( 10, TimeUnit.SECONDS );

    private static final String HOSTED_X = "hostedX";

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

    private HostedRepository hostedX;

    private Group groupA;

    private Group groupB;

    private Group groupC;

    @Before
    public void setupRepos()
            throws IndyClientException, UnsupportedEncodingException
    {
        String change = "Setup test";
        hostedX = client.stores().create( new HostedRepository( MAVEN_PKG_KEY, HOSTED_X ), change, HostedRepository.class );
        groupA = client.stores().create( new Group( MAVEN_PKG_KEY, GROUP_A ), change, Group.class );
        groupB = client.stores().create( new Group( MAVEN_PKG_KEY, GROUP_B, groupA.getKey() ), change, Group.class );
        groupC = client.stores().create( new Group( MAVEN_PKG_KEY, GROUP_C, groupB.getKey() ), change, Group.class );
    }

    @Test
    @Category( EventDependent.class )
    public void run()
            throws Exception
    {
        assertNullContent( hostedX, PATH );
        assertNullContent( groupA, PATH );
        assertNullContent( groupB, PATH );

        client.content()
              .store( hostedX.getKey(), PATH, new ByteArrayInputStream( HOSTED_X_CONTENT.getBytes( "UTF-8" ) ) );

        waitForEventPropagation();

        assertContent( hostedX, PATH,HOSTED_X_CONTENT );

        groupA.addConstituent( hostedX );
        boolean updated = client.stores().update( groupA, "Add hosted X" );

        assertThat( "Group A was NOT updated with HostedRepository X membership!", updated, equalTo( true ) );

        waitForEventPropagation();

        // Order is important here...we want to try the most ambitious one first and move down to the simpler cases.
        // This will prevent us from inadvertently triggering metadata aggregation in a lower group that might not
        // happen otherwise.
        assertContent( groupC, PATH, HOSTED_X_CONTENT );
        assertContent( groupB, PATH, HOSTED_X_CONTENT );
        assertContent( groupA, PATH, HOSTED_X_CONTENT );
    }

    @Override
    protected boolean createStandardTestStructures()
    {
        return false;
    }
}

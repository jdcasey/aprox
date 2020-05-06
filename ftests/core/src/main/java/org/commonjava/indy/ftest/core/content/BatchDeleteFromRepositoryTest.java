package org.commonjava.indy.ftest.core.content;

import org.commonjava.indy.ftest.core.AbstractContentManagementTest;
import org.commonjava.indy.model.core.BatchDeleteRequest;
import org.commonjava.indy.model.core.HostedRepository;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

public class BatchDeleteFromRepositoryTest
                extends AbstractContentManagementTest
{

    private HostedRepository hosted;

    private static final String HOSTED_REPO = "build-01";

    private static final String JAR1_PATH = "/org/foo/bar/1/bar-1.jar";

    private static final String POM1_PATH = "/org/foo/bar/1/bar-1.pom";

    private static final String JAR2_PATH = "/org/foo/bar/2/bar-2.jar";

    private static final String POM2_PATH = "/org/foo/bar/2/bar-2.pom";

    private Set<String> paths;

    @Before
    public void setupTest()
                    throws Exception
    {
        String change = "test setup";
        hosted = client.stores().create( new HostedRepository( HOSTED_REPO ), change, HostedRepository.class );
        client.content().store( hosted.getKey(), JAR1_PATH, new ByteArrayInputStream( "This is the jar1".getBytes() ) );
        client.content().store( hosted.getKey(), POM1_PATH, new ByteArrayInputStream( "This is the pom1".getBytes() ) );
        client.content().store( hosted.getKey(), JAR2_PATH, new ByteArrayInputStream( "This is the jar2".getBytes() ) );
        client.content().store( hosted.getKey(), POM2_PATH, new ByteArrayInputStream( "This is the pom2".getBytes() ) );

        paths = new HashSet<>( Arrays.asList( JAR1_PATH, POM1_PATH, JAR2_PATH, POM2_PATH ) );
    }

    @Test
    public void removeFilesFromRepository() throws Exception
    {

        for ( String path : paths )
        {
            boolean exists = client.content().exists( hosted.getKey(), path );
            assertThat( "The file does not exists.", exists, equalTo( true ) );
        }

        BatchDeleteRequest request = new BatchDeleteRequest();
        request.setStoreKey( hosted.getKey() );
        request.setPaths( paths );
        client.maint().deleteFilesFromStore( request );

        for ( String path : paths )
        {
            boolean exists = client.content().exists( hosted.getKey(), path );
            assertThat( "The file is not removed.", exists, equalTo( false ) );
        }
    }

}

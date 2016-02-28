package org.commonjava.indy.model.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.junit.Test;

/**
 * Created by jdcasey on 2/27/16.
 */
public class HostedRepositoryTest
{
    @Test
    public void jsonString()
            throws JsonProcessingException
    {
        HostedRepository repo = new HostedRepository( "test" );
        repo.setFastStorage( "/var/lib/indy/storage/cache-test" );
        repo.setStorage( "/var/lib/indy/storage/hosted-test" );

        String json = new IndyObjectMapper(true).writeValueAsString( repo );
        System.out.println( json );
    }
}

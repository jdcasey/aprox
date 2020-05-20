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
package org.commonjava.indy.folo.ftest.content.admin;

import org.commonjava.indy.folo.client.IndyFoloAdminClientModule;
import org.commonjava.indy.folo.client.IndyFoloContentClientModule;
import org.commonjava.indy.folo.ftest.report.AbstractTrackingReportTest;
import org.commonjava.indy.model.core.BatchDeleteRequest;
import org.commonjava.indy.model.core.StoreKey;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;

import static org.commonjava.indy.model.core.StoreType.hosted;
import static org.commonjava.indy.pkg.PackageTypeConstants.PKG_TYPE_MAVEN;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

/**
 * <b>GIVEN:</b>
 * <ul>
 *     <li>HostedRepository and several artifacts a, b and c</li>
 * </ul>
 *
 * <br/>
 * <b>WHEN:</b>
 * <ul>
 *     <li>Store the artifacts a and b through the FOLO id into the repository</li>
 *     <li>Store the artifact c without FOLO id into the repository</li>
 *     <li>Seal the record</li>
 *     <li>Specifying the FOLO id to batch delete the artifacts from the repository</li>
 * </ul>
 *
 * <br/>
 * <b>THEN:</b>
 * <ul>
 *     <li>The artifacts a and b does not exist in the repository</li>
 *     <li>The artifact c still exists in the repository</li>
 * </ul>
 */
    public class RemoveFilesInTrackingReportTest
                extends AbstractTrackingReportTest
{

    @Test
    public void run()
                    throws Exception
    {
        final String trackingId = newName();

        final InputStream stream = new ByteArrayInputStream( ( "This is a test: " + System.nanoTime() ).getBytes() );

        final String a = "/path/to/1/foo-1.jar";
        final String b = "/path/to/2/foo-2.jar";
        final String c = "/path/to/3/foo-3.jar";

        for ( String path : Arrays.asList( a, b) )
        {
            client.module( IndyFoloContentClientModule.class ).store( trackingId, hosted, STORE, path, stream );
        }

        StoreKey key = new StoreKey( PKG_TYPE_MAVEN, hosted, STORE );

        client.content().store( key, c, stream );

        IndyFoloAdminClientModule adminModule = client.module( IndyFoloAdminClientModule.class );
        boolean success = adminModule.sealTrackingRecord( trackingId );
        assertThat( success, equalTo( true ) );

        boolean exists;
        for ( String path : Arrays.asList(a, b) )
        {
            exists = client.content().exists( key, path );
            assertThat( "The file does not exists.", exists, equalTo( true ) );
        }

        exists = client.content().exists( key, c );
        assertThat( "The file does not exists.", exists, equalTo( true ) );

        BatchDeleteRequest request = new BatchDeleteRequest();
        request.setStoreKey( key );
        request.setTrackingID( trackingId );

        adminModule.deleteFilesFromStoreByTrackingID( request );

        for ( String path : Arrays.asList(a, b) )
        {
            exists = client.content().exists( key, path );
            assertThat( "The file is not removed.", exists, equalTo( false ) );
        }

        exists = client.content().exists( key, c );
        assertThat( "The file does not exists.", exists, equalTo( true ) );

    }

}

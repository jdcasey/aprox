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
package org.commonjava.indy.folo.client;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.HttpPost;
import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.client.core.IndyClientModule;
import org.commonjava.indy.client.core.IndyResponseErrorDetails;
import org.commonjava.indy.client.core.helper.HttpResources;
import org.commonjava.indy.client.core.util.UrlUtils;
import org.commonjava.indy.folo.dto.TrackedContentDTO;
import org.commonjava.indy.folo.dto.TrackingIdsDTO;
import org.commonjava.indy.folo.model.TrackedContentRecord;
import org.commonjava.indy.model.core.DemoteRequest;
import org.commonjava.indy.model.core.StoreType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.commonjava.indy.client.core.helper.HttpResources.entityToString;

public class IndyFoloAdminClientModule
    extends IndyClientModule
{

    public boolean initReport( final String trackingId )
        throws IndyClientException
    {
        return http.put( UrlUtils.buildUrl( "/folo/admin", trackingId, "record" ), trackingId );
    }

    public InputStream getTrackingRepoZip( String trackingId )
            throws IndyClientException, IOException
    {
        HttpResources resources = getHttp().getRaw( UrlUtils.buildUrl("folo/admin", trackingId, "repo/zip" ) );
        if ( resources.getStatusCode() != HttpStatus.SC_OK )
        {
            throw new IndyClientException( resources.getStatusCode(), "Error retrieving repository zip for tracking record: %s.\n%s",
                                            trackingId, new IndyResponseErrorDetails( resources.getResponse() ) );
        }

        return resources.getResponseEntityContent();
    }

    public TrackedContentDTO getTrackingReport( final String trackingId )
        throws IndyClientException
    {
        return http.get( UrlUtils.buildUrl( "/folo/admin", trackingId, "report" ), TrackedContentDTO.class );
    }

    public InputStream exportTrackingReportZip() throws IndyClientException, IOException
    {
        HttpResources resources = http.getRaw( UrlUtils.buildUrl( "folo/admin/report/export" ) );
        if ( resources.getStatusCode() != HttpStatus.SC_OK )
        {
            throw new IndyClientException( resources.getStatusCode(), "Error retrieving record zip: %s",
                                           new IndyResponseErrorDetails( resources.getResponse() ) );
        }

        return resources.getResponseEntityContent();
    }


    public void importTrackingReportZip( InputStream stream ) throws IndyClientException, IOException
    {
        http.putWithStream( UrlUtils.buildUrl( "folo/admin/report/import" ), stream );
    }

    @Deprecated
    public TrackedContentRecord getRawTrackingRecord( final String trackingId )
        throws IndyClientException
    {
        return http.get( UrlUtils.buildUrl( "/folo/admin", trackingId, "record" ), TrackedContentRecord.class );
    }

    public TrackedContentDTO getRawTrackingContent( final String trackingId )
            throws IndyClientException
    {
        return http.get( UrlUtils.buildUrl( "/folo/admin", trackingId, "record" ), TrackedContentDTO.class );
    }

    public TrackedContentDTO recalculateTrackingRecord( final String trackingId )
            throws IndyClientException
    {
        return http.get( UrlUtils.buildUrl( "/folo/admin", trackingId, "record/recalculate" ), TrackedContentDTO.class );
    }

    public void clearTrackingRecord( final String trackingId )
        throws IndyClientException
    {
        http.delete( UrlUtils.buildUrl( "/folo/admin", trackingId, "record" ) );
    }

    public TrackingIdsDTO getTrackingIds( final String trackingType )
            throws IndyClientException
    {
        return http.get( UrlUtils.buildUrl( "/folo/admin/report/ids", trackingType ), TrackingIdsDTO.class );
    }

    public void batchRemoveFiles( final DemoteRequest request )
        throws IndyClientException
    {
        http.postRaw( UrlUtils.buildUrl( "/folo/admin/batch/demote" ), request );
    }

    @Deprecated
    public TrackedContentDTO getTrackingReport( final String trackingId, final StoreType type, final String name )
        throws IndyClientException
    {
        return http.get( UrlUtils.buildUrl( "/folo/admin", trackingId, "report" ),
                         TrackedContentDTO.class );
    }

    @Deprecated
    public TrackedContentRecord getRawTrackingRecord( final String trackingId, final StoreType type, final String name )
        throws IndyClientException
    {
        return http.get( UrlUtils.buildUrl( "/folo/admin", trackingId, "record" ),
                         TrackedContentRecord.class );
    }

    @Deprecated
    public void clearTrackingRecord( final String trackingId, final StoreType type, final String name )
        throws IndyClientException
    {
        http.delete( UrlUtils.buildUrl( "/folo/admin", trackingId, "record" ) );
    }

    public boolean sealTrackingRecord( String trackingId )
            throws IndyClientException
    {
        http.connect();

        HttpPost request = http.newRawPost( UrlUtils.buildUrl( http.getBaseUrl(), "/folo/admin", trackingId, "record" ) );
        HttpResources resources = null;
        try
        {
            resources = http.execute( request );
            HttpResponse response = resources.getResponse();
            StatusLine sl = response.getStatusLine();
            if ( sl.getStatusCode() != 200 )
            {
                if ( sl.getStatusCode() == 404 )
                {
                    return false;
                }

                throw new IndyClientException( sl.getStatusCode(), "Error sealing tracking record %s.\n%s",
                                               trackingId, new IndyResponseErrorDetails( response ) );
            }

            return true;
        }
        finally
        {
            IOUtils.closeQuietly( resources );
        }
    }
}

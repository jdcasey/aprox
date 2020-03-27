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
package org.commonjava.indy.folo.bind.jaxrs;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.bind.jaxrs.IndyResources;
import org.commonjava.indy.bind.jaxrs.util.REST;
import org.commonjava.indy.bind.jaxrs.util.ResponseHelper;
import org.commonjava.indy.core.bind.jaxrs.ContentAccessHandler;
import org.commonjava.indy.core.ctl.ContentController;
import org.commonjava.indy.folo.ctl.FoloAdminController;
import org.commonjava.indy.folo.ctl.FoloConstants;
import org.commonjava.indy.folo.data.FoloContentException;
import org.commonjava.indy.folo.dto.TrackedContentDTO;
import org.commonjava.indy.folo.dto.TrackedContentEntryDTO;
import org.commonjava.indy.folo.dto.TrackingIdsDTO;
import org.commonjava.indy.model.core.DemoteRequest;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.commonjava.maven.galley.event.EventMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.commonjava.indy.folo.ctl.FoloConstants.ALL;
import static org.commonjava.indy.folo.ctl.FoloConstants.TRACKING_TYPE.IN_PROGRESS;
import static org.commonjava.indy.folo.ctl.FoloConstants.TRACKING_TYPE.SEALED;
import static org.commonjava.indy.util.ApplicationContent.application_zip;

@Api( value = "FOLO Tracking Record Access", description = "Manages FOLO tracking records." )
@Path( "/api/folo/admin" )
@ApplicationScoped
@REST
public class FoloAdminResource
        implements IndyResources
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private IndyObjectMapper objectMapper;

    @Inject
    private FoloAdminController controller;

    @Inject
    private ContentController contentController;

    @Inject
    private ContentAccessHandler handler;

    @Inject
    private ResponseHelper responseHelper;

    @ApiOperation( "Recalculate sizes and checksums for every file listed in a tracking record." )
    @ApiResponses(
            { @ApiResponse( code = 200, response = TrackedContentDTO.class, message = "Recalculated tracking report" ),
                    @ApiResponse( code = 404, message = "No such tracking record can be found" ) } )
    @GET
    @Path( "/{id}/record/recalculate" )
    public Response recalculateRecord( @ApiParam( "User-assigned tracking session key" ) @PathParam( "id" ) String id,
                                       @Context final UriInfo uriInfo )
    {
        Response response;
        try
        {
            final String baseUrl = uriInfo.getBaseUriBuilder().path( "api" ).build().toString();
            final TrackedContentDTO report = controller.recalculateRecord( id, baseUrl );

            if ( report == null )
            {
                response = Response.status( Status.NOT_FOUND ).build();
            }
            else
            {
                response = responseHelper.formatOkResponseWithJsonEntity( report );
            }
        }
        catch ( final IndyWorkflowException e )
        {
            logger.error(
                    String.format( "Failed to serialize tracking report for: %s. Reason: %s", id, e.getMessage() ), e );

            response = responseHelper.formatResponse( e );
        }

        return response;
    }

    @ApiOperation(
            "Retrieve the content referenced in a tracking record as a ZIP-compressed Maven repository directory." )
    @ApiResponses( { @ApiResponse( code = 200, response = File.class, message = "ZIP repository content" ),
                           @ApiResponse( code = 404, message = "No such tracking record" ) } )
    @Path( "/{id}/repo/zip" )
    @GET
    @Produces( application_zip )
    public File getZipRepository( @ApiParam( "User-assigned tracking session key" ) @PathParam( "id" ) String id )
    {
        try
        {
            File zip = controller.renderRepositoryZip( id );
            return zip;
            //
            //            final Response.ResponseBuilder builder = Response.ok( zip );
            //            return setInfoHeaders( builder, zip, false, application_zip ).build();
        }
        catch ( IndyWorkflowException e )
        {
            responseHelper.throwError( e );
        }

        return null;
    }

    @ApiOperation( "Alias of /{id}/record, returns the tracking record for the specified key" )
    @ApiResponses( { @ApiResponse( code = 404, message = "No such tracking record exists." ),
                           @ApiResponse( code = 200, message = "Tracking record",
                                         response = TrackedContentDTO.class ), } )
    @Path( "/{id}/report" )
    @GET
    public Response getReport( @ApiParam( "User-assigned tracking session key" ) final @PathParam( "id" ) String id,
                               @Context final UriInfo uriInfo )
    {
        Response response;
        try
        {
            final String baseUrl = uriInfo.getBaseUriBuilder().path( "api" ).build().toString();

            final TrackedContentDTO report = controller.renderReport( id, baseUrl );

            if ( report == null )
            {
                response = Response.status( Status.NOT_FOUND ).build();
            }
            else
            {
                response = responseHelper.formatOkResponseWithJsonEntity( report );
            }
        }
        catch ( final IndyWorkflowException e )
        {
            logger.error(
                    String.format( "Failed to serialize tracking report for: %s. Reason: %s", id, e.getMessage() ), e );

            response = responseHelper.formatResponse( e );
        }

        return response;
    }

    @ApiOperation(
            "Explicitly setup a new tracking record for the specified key, to prevent 404 if the record is never used." )
    @ApiResponses( { @ApiResponse( code = 201, message = "Tracking record was created",
                                   response = TrackedContentDTO.class ), } )
    @Path( "/{id}/record" )
    @PUT
    public Response initRecord( @ApiParam( "User-assigned tracking session key" ) final @PathParam( "id" ) String id,
                                @Context final UriInfo uriInfo )
    {
        Response.ResponseBuilder rb = Response.created( uriInfo.getRequestUri() );
        return rb.build();
    }

    @ApiOperation( "Seal the tracking record for the specified key, to prevent further content logging" )
    @ApiResponses( { @ApiResponse( code = 404, message = "No such tracking record exists." ),
                           @ApiResponse( code = 200, message = "Tracking record",
                                         response = TrackedContentDTO.class ), } )
    @Path( "/{id}/record" )
    @POST
    public Response sealRecord( @ApiParam( "User-assigned tracking session key" ) final @PathParam( "id" ) String id,
                                @Context final UriInfo uriInfo )
    {
        final String baseUrl = uriInfo.getBaseUriBuilder().path( "api" ).build().toString();
        TrackedContentDTO record = controller.seal( id, baseUrl );
        if ( record == null )
        {
            return Response.status( Status.NOT_FOUND ).build();
        }
        else
        {
            return Response.ok().build();
        }
    }

    @ApiOperation( "Alias of /{id}/record, returns the tracking record for the specified key" )
    @ApiResponses( { @ApiResponse( code = 404, message = "No such tracking record exists." ),
                           @ApiResponse( code = 200, message = "Tracking record",
                                         response = TrackedContentDTO.class ), } )
    @Path( "/{id}/record" )
    @GET
    public Response getRecord( @ApiParam( "User-assigned tracking session key" ) final @PathParam( "id" ) String id,
                               @Context final UriInfo uriInfo )
    {
        Response response;
        try
        {
            final String baseUrl = uriInfo.getBaseUriBuilder().path( "api" ).build().toString();
            final TrackedContentDTO record = controller.getRecord( id, baseUrl );
            if ( record == null )
            {
                response = Response.status( Status.NOT_FOUND ).build();
            }
            else
            {
                response = responseHelper.formatOkResponseWithJsonEntity( record );
            }
        }
        catch ( final IndyWorkflowException e )
        {
            logger.error( String.format( "Failed to retrieve tracking report for: %s. Reason: %s", id, e.getMessage() ),
                          e );

            response = responseHelper.formatResponse( e );
        }

        return response;
    }

    @Path( "/{id}/record" )
    @DELETE
    public Response clearRecord( @ApiParam( "User-assigned tracking session key" ) final @PathParam( "id" ) String id )
    {
        Response response;
        try
        {
            controller.clearRecord( id );
            response = Response.status( Status.NO_CONTENT ).build();
        }
        catch ( FoloContentException e )
        {
            response = responseHelper.formatResponse( e );
        }

        return response;
    }

    @ApiOperation( "Retrieve folo report tracking ids for folo records." )
    @ApiResponses( { @ApiResponse( code = 200, response = List.class,
                                   message = "folo tracking ids with sealed or in_progress" ),
                           @ApiResponse( code = 404, message = "No ids found for type" ) } )
    @Path( "/report/ids/{type}" )
    @GET
    public Response getRecordIds(
            @ApiParam( "Report type, should be in_progress|sealed|all" ) final @PathParam( "type" ) String type )
    {
        Response response;
        Set<FoloConstants.TRACKING_TYPE> types = getRequiredTypes( type );

        TrackingIdsDTO ids = controller.getTrackingIds( types );
        if ( ids != null )
        {
            response = responseHelper.formatOkResponseWithJsonEntity( ids );
        }
        else
        {
            response = Response.status( Status.NOT_FOUND ).build();
        }

        return response;
    }


    @ApiOperation( "Export the records as a ZIP file." )
    @ApiResponses( { @ApiResponse( code = 200, response = File.class, message = "ZIP content" ) } )
    @Path( "/report/export" )
    @GET
    @Produces( application_zip )
    public File exportReport()
    {
        try
        {
            return controller.renderReportZip();
        }
        catch ( IndyWorkflowException e )
        {
            responseHelper.throwError( e );
        }

        return null;
    }

    @ApiOperation( "Import records from a ZIP file." )
    @ApiResponses( { @ApiResponse( code = 201, message = "Import ZIP content" ) } )
    @Path( "/report/import" )
    @PUT
    public Response importReport( final @Context UriInfo uriInfo, final @Context HttpServletRequest request )
    {
        try
        {
            controller.importRecordZip( request.getInputStream() );
        }
        catch ( IndyWorkflowException e )
        {
            responseHelper.throwError( e );
        }
        catch ( IOException e )
        {
            responseHelper.throwError( new IndyWorkflowException( "IO error", e ) );
        }

        return Response.created( uriInfo.getRequestUri() ).build();
    }

    private Set<FoloConstants.TRACKING_TYPE> getRequiredTypes( String type )
    {
        Set<FoloConstants.TRACKING_TYPE> types = new HashSet<>();

        if ( IN_PROGRESS.getValue().equals( type ) || ALL.equals( type ) )
        {
            types.add( IN_PROGRESS );
        }
        if ( SEALED.getValue().equals( type ) || ALL.equals( type ) )
        {
            types.add( SEALED );
        }
        return types;
    }

    @ApiOperation( "Batch delete files uploaded through FOLO trackingID under the given storeKey." )
    @ApiResponse( code=200, message = "Batch delete operation finished." )
    @ApiImplicitParam( name = "body", paramType = "body",
                    value = "JSON object, specifying trackingID and storeKey, with other configuration options",
                    required = true, dataType = "org.commonjava.indy.model.core.DemoteRequest" )
    @Path( "/batch/demote" )
    @POST
    public Response doDelete( @Context final UriInfo uriInfo, final DemoteRequest request )
    {

        String trackingID = request.getTrackingID();

        if ( trackingID == null || request.getStoreKey() == null )
        {
            Response.ResponseBuilder builder = Response.status( 400 );
            return builder.build();
        }

        if ( request.getPaths() == null || request.getPaths().isEmpty() )
        {
            final String baseUrl = uriInfo.getBaseUriBuilder().path( "api" ).build().toString();
            try
            {
                final TrackedContentDTO record = controller.getRecord( trackingID, baseUrl );
                if ( record == null || record.getUploads().isEmpty() )
                {
                    Response.ResponseBuilder builder = Response.status( 400 );
                    return builder.build();
                }

                Set<String> paths = new HashSet<>(  );
                for ( TrackedContentEntryDTO entry : record.getUploads() )
                {
                    if ( !paths.contains( entry.getPath() ) )
                    {
                        paths.add( entry.getPath() );
                    }
                }
                request.setPaths( paths );
            }
            catch ( IndyWorkflowException e )
            {
                responseHelper.throwError( e );
            }
        }

        String packageType = request.getStoreKey().getPackageType();
        String type = request.getStoreKey().getType().name();
        String name = request.getStoreKey().getName();

        return handler.doDelete( packageType, type, name, request, new EventMetadata(  ) );
    }


}

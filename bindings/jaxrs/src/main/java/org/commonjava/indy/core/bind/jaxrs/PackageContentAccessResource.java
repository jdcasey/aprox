package org.commonjava.indy.core.bind.jaxrs;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.commonjava.indy.bind.jaxrs.IndyResources;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;

import static org.commonjava.indy.IndyContentConstants.CHECK_CACHE_ONLY;

/**
 * Created by jdcasey on 5/10/17.
 */
public interface PackageContentAccessResource
        extends IndyResources
{
    @ApiOperation( "Store file/artifact content under the given artifact store (type/name) and path." )
    @ApiResponses( { @ApiResponse( code = 201, message = "Content was stored successfully" ), @ApiResponse( code = 400,
                                                                                                            message = "No appropriate storage location was found in the specified store (this store, or a member if a group is specified)." ) } )
    @PUT
    @Path( "/{path: (.+)?}" )
    Response doCreate(
            @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
            @ApiParam( required = true ) @PathParam( "name" ) String name, @PathParam( "path" ) String path,
            @Context UriInfo uriInfo, @Context HttpServletRequest request );

    @ApiOperation( "Delete file/artifact content under the given artifact store (type/name) and path." )
    @ApiResponses( { @ApiResponse( code = 404, message = "Content is not available" ),
                           @ApiResponse( code = 204, message = "Content was deleted successfully" ) } )
    @DELETE
    @Path( "/{path: (.*)}" )
    Response doDelete(
            @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
            @ApiParam( required = true ) @PathParam( "name" ) String name, @PathParam( "path" ) String path );

    @ApiOperation( "Store file/artifact content under the given artifact store (type/name) and path." )
    @ApiResponses( { @ApiResponse( code = 404, message = "Content is not available" ), @ApiResponse( code = 200,
                                                                                                     message = "Header metadata for content (or rendered listing when path ends with '/index.html' or '/'" ), } )
    @HEAD
    @Path( "/{path: (.*)}" )
    Response doHead(
            @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
            @ApiParam( required = true ) @PathParam( "name" ) String name, @PathParam( "path" ) String path,
            @QueryParam( CHECK_CACHE_ONLY ) Boolean cacheOnly, @Context UriInfo uriInfo,
            @Context HttpServletRequest request );

    @ApiOperation( "Retrieve file/artifact content under the given artifact store (type/name) and path." )
    @ApiResponses( { @ApiResponse( code = 404, message = "Content is not available" ),
                           @ApiResponse( code = 200, response = String.class,
                                         message = "Rendered content listing (when path ends with '/index.html' or '/')" ),
                           @ApiResponse( code = 200, response = StreamingOutput.class, message = "Content stream" ), } )
    @GET
    @Path( "/{path: (.*)}" )
    Response doGet(
            @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
            @ApiParam( required = true ) @PathParam( "name" ) String name, @PathParam( "path" ) String path,
            @Context UriInfo uriInfo, @Context HttpServletRequest request );

    @ApiOperation( "Retrieve root listing under the given artifact store (type/name)." )
    @ApiResponses( { @ApiResponse( code = 200, response = String.class, message = "Rendered root content listing" ),
                           @ApiResponse( code = 200, response = StreamingOutput.class, message = "Content stream" ), } )
    @GET
    @Path( "/" )
    Response doGet(
            @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
            @ApiParam( required = true ) @PathParam( "name" ) String name, @Context UriInfo uriInfo,
            @Context HttpServletRequest request );
}

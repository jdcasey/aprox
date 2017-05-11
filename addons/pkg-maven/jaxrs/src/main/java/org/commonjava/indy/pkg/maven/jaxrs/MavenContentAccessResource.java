/**
 * Copyright (C) 2011 Red Hat, Inc. (jdcasey@commonjava.org)
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.indy.pkg.maven.jaxrs;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.commonjava.indy.bind.jaxrs.IndyDeployment;
import org.commonjava.indy.core.bind.jaxrs.ContentAccessHandler;
import org.commonjava.indy.core.bind.jaxrs.PackageContentAccessResource;
import org.commonjava.maven.galley.event.EventMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
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
import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;

@Api( value = "Maven Content Access and Storage",
      description = "Handles retrieval and management of Maven artifact content. This is the main point of access for Maven/Gradle users." )
@Path( "/api/maven/{type: (hosted|group|remote)}/{name}" )
public class MavenContentAccessResource
        implements PackageContentAccessResource
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private ContentAccessHandler handler;

    public MavenContentAccessResource()
    {
    }

    public MavenContentAccessResource( final ContentAccessHandler handler )
    {
        this.handler = handler;
    }

    @Override
    @ApiOperation( "Store Maven artifact content under the given artifact store (type/name) and path." )
    @ApiResponses( { @ApiResponse( code = 201, message = "Content was stored successfully" ), @ApiResponse( code = 400,
                                                                                                            message = "No appropriate storage location was found in the specified store (this store, or a member if a group is specified)." ) } )
    @PUT
    @Path( "/{path: (.+)?}" )
    public Response doCreate(
            final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" )
                    String type, final @ApiParam( required = true ) @PathParam( "name" ) String name,
            final @PathParam( "path" ) String path, final @Context UriInfo uriInfo,
            final @Context HttpServletRequest request )
    {
        return handler.doCreate( MAVEN_PKG_KEY, type, name, path, request,
                                 new EventMetadata(), () -> uriInfo.getBaseUriBuilder()
                                                                   .path( getClass() )
                                                                   .path( path )
                                                                   .build( MAVEN_PKG_KEY, type, name ) );
    }

    @Override
    @ApiOperation( "Delete Maven artifact content under the given artifact store (type/name) and path." )
    @ApiResponses( { @ApiResponse( code = 404, message = "Content is not available" ),
                           @ApiResponse( code = 204, message = "Content was deleted successfully" ) } )
    @DELETE
    @Path( "/{path: (.*)}" )
    public Response doDelete(
            final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" )
                    String type, final @ApiParam( required = true ) @PathParam( "name" ) String name,
            final @PathParam( "path" ) String path )
    {
        return handler.doDelete( MAVEN_PKG_KEY, type, name, path, new EventMetadata() );
    }

    @Override
    @ApiOperation( "Store Maven artifact content under the given artifact store (type/name) and path." )
    @ApiResponses( { @ApiResponse( code = 404, message = "Content is not available" ), @ApiResponse( code = 200,
                                                                                                     message = "Header metadata for content (or rendered listing when path ends with '/index.html' or '/'" ), } )
    @HEAD
    @Path( "/{path: (.*)}" )
    public Response doHead(
            final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" )
                    String type, final @ApiParam( required = true ) @PathParam( "name" ) String name,
            final @PathParam( "path" ) String path, @QueryParam( CHECK_CACHE_ONLY ) final Boolean cacheOnly,
            @Context final UriInfo uriInfo, @Context final HttpServletRequest request )
    {
        final String baseUri = uriInfo.getBaseUriBuilder().path( IndyDeployment.API_PREFIX ).build().toString();
        return handler.doHead( MAVEN_PKG_KEY, type, name, path, cacheOnly, baseUri, request,
                               new EventMetadata() );
    }

    @Override
    @ApiOperation( "Retrieve Maven artifact content under the given artifact store (type/name) and path." )
    @ApiResponses( { @ApiResponse( code = 404, message = "Content is not available" ),
                           @ApiResponse( code = 200, response = String.class,
                                         message = "Rendered content listing (when path ends with '/index.html' or '/')" ),
                           @ApiResponse( code = 200, response = StreamingOutput.class, message = "Content stream" ), } )
    @GET
    @Path( "/{path: (.*)}" )
    public Response doGet(
            final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" )
                    String type, final @ApiParam( required = true ) @PathParam( "name" ) String name,
            final @PathParam( "path" ) String path, @Context final UriInfo uriInfo,
            @Context final HttpServletRequest request )
    {
        final String baseUri = uriInfo.getBaseUriBuilder().path( IndyDeployment.API_PREFIX ).build().toString();

        return handler.doGet( MAVEN_PKG_KEY, type, name, path, baseUri, request, new EventMetadata() );
    }

    @Override
    @ApiOperation( "Retrieve root listing under the given artifact store (type/name)." )
    @ApiResponses( { @ApiResponse( code = 200, response = String.class, message = "Rendered root content listing" ),
                           @ApiResponse( code = 200, response = StreamingOutput.class, message = "Content stream" ), } )
    @GET
    @Path( "/" )
    public Response doGet(
            final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" )
                    String type, final @ApiParam( required = true ) @PathParam( "name" ) String name,
            @Context final UriInfo uriInfo, @Context final HttpServletRequest request )
    {
        final String baseUri = uriInfo.getBaseUriBuilder().path( IndyDeployment.API_PREFIX ).build().toString();

        return handler.doGet( MAVEN_PKG_KEY, type, name, "", baseUri, request, new EventMetadata() );
    }

}

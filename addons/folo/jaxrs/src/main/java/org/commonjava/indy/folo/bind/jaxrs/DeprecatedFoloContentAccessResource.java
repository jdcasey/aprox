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
package org.commonjava.indy.folo.bind.jaxrs;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.commonjava.indy.bind.jaxrs.IndyDeployment;
import org.commonjava.indy.bind.jaxrs.IndyResources;
import org.commonjava.indy.core.bind.jaxrs.ContentAccessHandler;
import org.commonjava.indy.core.ctl.ContentController;
import org.commonjava.indy.folo.model.TrackingKey;
import org.commonjava.indy.model.core.AccessChannel;
import org.commonjava.maven.galley.event.EventMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
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

import java.net.URI;
import java.nio.file.Paths;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.commonjava.indy.IndyContentConstants.CHECK_CACHE_ONLY;
import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.markDeprecated;
import static org.commonjava.indy.folo.ctl.FoloConstants.ACCESS_CHANNEL;
import static org.commonjava.indy.folo.ctl.FoloConstants.TRACKING_KEY;
import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;

/**
 * Collects a tracking ID in addition to store type and name, then hands this off to
 * {@link ContentAccessHandler} (with {@link EventMetadata} containing the tracking ID), which records artifact accesses.
 *
 * @author jdcasey
 */
@Api( value = "FOLO Tracked Content Access and Storage",
      description = "Tracks retrieval and management of file/artifact content." )
@Path( "/api/folo/track/{id}/{type: (hosted|group|remote)}/{name}" )
public class DeprecatedFoloContentAccessResource
        implements IndyResources
{

    private static final String BASE_PATH = IndyDeployment.API_PREFIX + "/folo/track";

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private ContentAccessHandler handler;

    public DeprecatedFoloContentAccessResource()
    {
    }

    public DeprecatedFoloContentAccessResource( final ContentAccessHandler handler )
    {
        this.handler = handler;
    }

    @ApiOperation( "Store and track file/artifact content under the given artifact store (type/name) and path." )
    @ApiResponses( { @ApiResponse( code = 201, message = "Content was stored successfully" ), @ApiResponse( code = 400,
                                                                                                            message = "No appropriate storage location was found in the specified store (this store, or a member if a group is specified)." ) } )
    @PUT
    @Path( "/{path: (.*)}" )
    public Response doCreate( @ApiParam( "User-assigned tracking session key" ) @PathParam( "id" ) final String id,
                              @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" )
                              final String type, @PathParam( "name" ) final String name,
                              @PathParam( "path" ) final String path, @Context final HttpServletRequest request,
                              @Context final UriInfo uriInfo )
    {
        final TrackingKey tk = new TrackingKey( id );

        EventMetadata metadata =
                new EventMetadata().set( TRACKING_KEY, tk ).set( ACCESS_CHANNEL, AccessChannel.MAVEN_REPO );
        final Supplier<URI> uriSupplier = () -> uriInfo.getBaseUriBuilder()
                                                       .path( getClass() )
                                                       .path( path )
                                                       .build( id, type, name );

        final Consumer<Response.ResponseBuilder> deprecation = builder -> {
            String alt = Paths.get( "/api/folo/track/", id, MAVEN_PKG_KEY, type, name, path ).toString();
            markDeprecated( builder, alt );
        };

        return handler.doCreate( MAVEN_PKG_KEY, type, name, path, request, metadata, uriSupplier, deprecation );
    }

    @ApiOperation( "Store and track file/artifact content under the given artifact store (type/name) and path." )
    @ApiResponses( { @ApiResponse( code=404, message = "Content is not available" ), @ApiResponse( code = 200,
                                   message = "Header metadata for content (or rendered listing when path ends with '/index.html' or '/'" ), } )
    @HEAD
    @Path( "/{path: (.*)}" )
    public Response doHead( @ApiParam( "User-assigned tracking session key" ) @PathParam( "id" ) final String id,
                            @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" )
                            final String type, @PathParam( "name" ) final String name,
                            @PathParam( "path" ) final String path,
                            @QueryParam( CHECK_CACHE_ONLY ) final Boolean cacheOnly,
                            @Context final HttpServletRequest request, @Context final UriInfo uriInfo )
    {
        final TrackingKey tk = new TrackingKey( id );

        final String baseUri = uriInfo.getBaseUriBuilder().path( BASE_PATH ).path( id ).build().toString();

        EventMetadata metadata =
                new EventMetadata().set( TRACKING_KEY, tk ).set( ACCESS_CHANNEL, AccessChannel.MAVEN_REPO );

        final Consumer<Response.ResponseBuilder> deprecation = builder -> {
            String alt = Paths.get( "/api/folo/track/", id, MAVEN_PKG_KEY, type, name, path ).toString();
            markDeprecated( builder, alt );
        };

        return handler.doHead( MAVEN_PKG_KEY, type, name, path, cacheOnly, baseUri, request, metadata, deprecation );
    }

    @ApiOperation( "Retrieve and track file/artifact content under the given artifact store (type/name) and path." )
    @ApiResponses( { @ApiResponse( code=404, message = "Content is not available" ), @ApiResponse( code = 200, response = String.class,
                                   message = "Rendered content listing (when path ends with '/index.html' or '/')" ),
                           @ApiResponse( code = 200, response = StreamingOutput.class, message = "Content stream" ), } )
    @GET
    @Path( "/{path: (.*)}" )
    public Response doGet( @ApiParam( "User-assigned tracking session key" ) @PathParam( "id" ) final String id,
                           @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" )
                           final String type, @PathParam( "name" ) final String name,
                           @PathParam( "path" ) final String path, @Context final HttpServletRequest request,
                           @Context final UriInfo uriInfo )
    {
        final TrackingKey tk = new TrackingKey( id );
        final String baseUri = uriInfo.getBaseUriBuilder().path( BASE_PATH ).path( id ).build().toString();

        EventMetadata metadata =
                new EventMetadata().set( TRACKING_KEY, tk ).set( ACCESS_CHANNEL, AccessChannel.MAVEN_REPO );

        final Consumer<Response.ResponseBuilder> deprecation = builder -> {
            String alt = Paths.get( "/api/folo/track/", id, MAVEN_PKG_KEY, type, name, path ).toString();
            markDeprecated( builder, alt );
        };

        return handler.doGet( MAVEN_PKG_KEY, type, name, path, baseUri, request, metadata, deprecation );
    }

}

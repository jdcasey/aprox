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
package org.commonjava.indy.pkg.npm.jaxrs;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.commonjava.indy.bind.jaxrs.util.REST;
import org.commonjava.indy.core.bind.jaxrs.PackageContentAccessResource;
import org.commonjava.indy.core.bind.jaxrs.util.RequestUtils;
import org.commonjava.indy.model.core.DemoteRequest;
import org.commonjava.indy.pkg.npm.inject.NPMContentHandler;
import org.commonjava.indy.util.ApplicationContent;
import org.commonjava.maven.galley.event.EventMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HEAD;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;
import java.nio.file.Paths;

import static org.commonjava.indy.IndyContentConstants.CHECK_CACHE_ONLY;
import static org.commonjava.indy.pkg.npm.model.NPMPackageTypeDescriptor.NPM_CONTENT_REST_BASE_PATH;
import static org.commonjava.indy.pkg.npm.model.NPMPackageTypeDescriptor.NPM_PKG_KEY;
import static org.commonjava.maven.galley.spi.cache.CacheProvider.STORAGE_PATH;
import static org.commonjava.maven.galley.spi.cache.CacheProvider.STORE_HTTP_HEADERS;

@Api( value = "NPM Content Access and Storage", description = "Handles retrieval and management of NPM artifact content. This is the main point of access for NPM users." )
@Path( "/api/content/npm/{type: (hosted|group|remote)}/{name}" )
@ApplicationScoped
@REST
public class NPMContentAccessResource
                implements PackageContentAccessResource
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

//    private static final String PACKAGE_JSON = "/package.json";

    @Inject
    @NPMContentHandler
    private NPMContentAccessHandler handler;

    public NPMContentAccessResource()
    {
    }

    public NPMContentAccessResource( final NPMContentAccessHandler handler )
    {
        this.handler = handler;
    }

    @Override
    @ApiOperation( "Store NPM artifact content under the given artifact store (type/name) and packageName." )
    @ApiResponses( { @ApiResponse( code = 201, message = "Content was stored successfully" ), @ApiResponse( code = 400, message = "No appropriate storage location was found in the specified store (this store, or a member if a group is specified)." ) } )
    @PUT
    @Path( "/{packageName}" )
    public Response doCreate(
            final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
            final @ApiParam( required = true ) @PathParam( "name" ) String name,
            final @PathParam( "packageName" ) String packageName, final @Context UriInfo uriInfo,
            final @Context HttpServletRequest request )
    {
        EventMetadata eventMetadata =
                new EventMetadata().set( STORE_HTTP_HEADERS, RequestUtils.extractRequestHeadersToMap( request ) );

        Class cls = NPMContentAccessResource.class;
        return handler.doCreate( NPM_PKG_KEY, type, name, packageName, request, eventMetadata,
                                 () -> uriInfo.getBaseUriBuilder()
                                              .path( cls )
                                              .path( packageName )
                                              .build( NPM_PKG_KEY, type, name ) );
    }

    @ApiOperation(
            "Store NPM artifact content under the given artifact store (type/name), packageName and versionTarball (/version or /-/tarball)." )
    @ApiResponses( { @ApiResponse( code = 201, message = "Content was stored successfully" ), @ApiResponse( code = 400,
                                                                                                            message = "No appropriate storage location was found in the specified store (this store, or a member if a group is specified)." ) } )
    @PUT
    @Path( "/{packageName}/{versionTarball: (.*)}" )
    public Response doCreate(
            final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
            final @ApiParam( required = true ) @PathParam( "name" ) String name,
            final @PathParam( "packageName" ) String packageName,
            final @PathParam( "versionTarball" ) String versionTarball, final @Context UriInfo uriInfo,
            final @Context HttpServletRequest request )
    {
        final String path = Paths.get( packageName, versionTarball ).toString();
        Class cls = NPMContentAccessResource.class;
        return handler.doCreate( NPM_PKG_KEY, type, name, path, request, new EventMetadata(), () -> uriInfo.getBaseUriBuilder()
                                                                                                           .path( cls )
                                                                                                           .path( path )
                                                                                                           .build( NPM_PKG_KEY,
                                                                                                                   type,
                                                                                                                   name ) );
    }

    @Override
    @ApiOperation(
            "Delete NPM package and metadata content under the given artifact store (type/name) and packageName." )
    @ApiResponses( { @ApiResponse( code = 404, message = "Content is not available" ),
                    @ApiResponse( code = 204, message = "Content was deleted successfully" ) } )
    @DELETE
    @Path( "/{packageName}" )
    public Response doDelete(
            final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
            final @ApiParam( required = true ) @PathParam( "name" ) String name,
            final @PathParam( "packageName" ) String packageName,
            final @ApiParam( name = CHECK_CACHE_ONLY, value = "true or false" ) @QueryParam( CHECK_CACHE_ONLY ) Boolean cacheOnly )
    {
        return handler.doDelete( NPM_PKG_KEY, type, name, packageName, new EventMetadata() );
    }

    @ApiOperation(
            "Delete NPM package and metadata content under the given artifact store (type/name), packageName and versionTarball (/version or /-/tarball)." )
    @ApiResponses( { @ApiResponse( code = 404, message = "Content is not available" ),
                           @ApiResponse( code = 204, message = "Content was deleted successfully" ) } )
    @DELETE
    @Path( "/{packageName}/{versionTarball: (.*)}" )
    public Response doDelete(
            final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
            final @ApiParam( required = true ) @PathParam( "name" ) String name, final @PathParam( "packageName" ) String packageName,
            final @PathParam( "versionTarball" ) String versionTarball )
    {
        final String path = Paths.get( packageName, versionTarball ).toString();
        return handler.doDelete( NPM_PKG_KEY, type, name, path, new EventMetadata() );
    }

    @Override
    @ApiOperation( "Retrieve NPM package header metadata content under the given artifact store (type/name) and packageName." )
    @ApiResponses( { @ApiResponse( code = 404, message = "Metadata Content is not available" ),
                    @ApiResponse( code = 200, message = "Header metadata for package metadata content" ), } )
    @HEAD
    @Path( "/{packageName}" )
    public Response doHead(
            final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
            final @ApiParam( required = true ) @PathParam( "name" ) String name, final @PathParam( "packageName" ) String packageName,
            @QueryParam( CHECK_CACHE_ONLY ) final Boolean cacheOnly,
            @Context final UriInfo uriInfo, @Context final HttpServletRequest request )
    {
        EventMetadata eventMetadata = new EventMetadata();
        final String baseUri = uriInfo.getBaseUriBuilder().path( NPM_CONTENT_REST_BASE_PATH ).build().toString();
        return handler.doHead( NPM_PKG_KEY, type, name, packageName, cacheOnly, baseUri, request, eventMetadata );
    }

    @ApiOperation(
            "Retrieve NPM package tarball header metadata content under the given artifact store (type/name), packageName and versionTarball (/version or /-/tarball)." )
    @ApiResponses( { @ApiResponse( code = 404, message = "Content is not available" ),
                    @ApiResponse( code = 200, message = "Header metadata for tarball content" ), } )
    @HEAD
    @Path( "/{packageName}/{versionTarball: (.*)}" )
    public Response doHead(
            final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
            final @ApiParam( required = true ) @PathParam( "name" ) String name, final @PathParam( "packageName" ) String packageName,
            final @PathParam( "versionTarball" ) String versionTarball,
            @QueryParam( CHECK_CACHE_ONLY ) final Boolean cacheOnly, @Context final UriInfo uriInfo,
            @Context final HttpServletRequest request )
    {
        final String path = Paths.get( packageName, versionTarball ).toString();
        final String baseUri = uriInfo.getBaseUriBuilder().path( NPM_CONTENT_REST_BASE_PATH ).build().toString();
        return handler.doHead( NPM_PKG_KEY, type, name, path, cacheOnly, baseUri, request,
                               new EventMetadata() );
    }

    @Override
    @ApiOperation( "Retrieve NPM package metadata content under the given artifact store (type/name) and packageName." )
    @ApiResponses( { @ApiResponse( code = 404, message = "Metadata content is not available" ),
                    @ApiResponse( code = 200, response = String.class, message = "Rendered content listing" ),
                    @ApiResponse( code = 200, response = StreamingOutput.class, message = "Content stream" ), } )
    @GET
    @Path( "/{packageName}" )
    public Response doGet(
            final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
            final @ApiParam( required = true ) @PathParam( "name" ) String name,
            final @PathParam( "packageName" ) String packageName, @Context final UriInfo uriInfo,
            @Context final HttpServletRequest request )
    {
        EventMetadata eventMetadata = new EventMetadata();
        final String baseUri = uriInfo.getBaseUriBuilder().path( NPM_CONTENT_REST_BASE_PATH ).build().toString();
        return handler.doGet( NPM_PKG_KEY, type, name, packageName, baseUri, request, eventMetadata );
    }

    @ApiOperation(
            "Retrieve NPM package tarball content under the given artifact store (type/name), packageName and versionTarball (/version or /-/tarball)." )
    @ApiResponses( { @ApiResponse( code = 404, message = "Content is not available" ),
                    @ApiResponse( code = 200, response = StreamingOutput.class, message = "Content stream" ), } )
    @GET
    @Path( "/{packageName}/{versionTarball: (.*)}" )
    public Response doGet(
            final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
            final @ApiParam( required = true ) @PathParam( "name" ) String name,
            final @PathParam( "packageName" ) String packageName,
            final @PathParam( "versionTarball" ) String versionTarball, @Context final UriInfo uriInfo,
            @Context final HttpServletRequest request )
    {
        final String path = Paths.get( packageName, versionTarball ).toString();
        final String baseUri = uriInfo.getBaseUriBuilder().path( NPM_CONTENT_REST_BASE_PATH ).build().toString();
        return handler.doGet( NPM_PKG_KEY, type, name, path, baseUri, request, new EventMetadata() );
    }

    @Override
    @ApiOperation( "Retrieve root listing under the given artifact store (type/name)." )
    @ApiResponses( { @ApiResponse( code = 200, response = String.class, message = "Rendered root content listing" ),
                    @ApiResponse( code = 200, response = StreamingOutput.class, message = "Content stream" ), } )
    @GET
    @Path( "/" )
    public Response doGet(
            final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
            final @ApiParam( required = true ) @PathParam( "name" ) String name, @Context final UriInfo uriInfo,
            @Context final HttpServletRequest request )
    {
        EventMetadata eventMetadata = new EventMetadata();
        eventMetadata.set( STORAGE_PATH, "" );

        final String baseUri = uriInfo.getBaseUriBuilder().path( NPM_CONTENT_REST_BASE_PATH ).build().toString();
        return handler.doGet( NPM_PKG_KEY, type, name, "", baseUri, request, eventMetadata );
    }

    @Override
    @ApiOperation( "Batch delete NPM package content under the given package store (type/name) and paths." )
    @ApiResponse( code=200, message = "Batch delete operation finished." )
    @ApiImplicitParam( name = "body", paramType = "body",
                    value = "JSON object, specifying paths, with other configuration options",
                    required = true, dataType = "org.commonjava.indy.model.core.DemoteRequest" )
    @Path( "/batch/demote" )
    @POST
    @Consumes( ApplicationContent.application_json )
    public Response doDelete(
            final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
            final @ApiParam( required = true ) @PathParam( "name" ) String name,  final DemoteRequest request )
    {
        return handler.doDelete( NPM_PKG_KEY, type, name, request, new EventMetadata(  ));
    }

}

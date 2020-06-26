package org.commonjava.indy.pkg.pypi.jaxrs;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import io.swagger.annotations.ApiResponse;
import io.swagger.annotations.ApiResponses;
import org.commonjava.indy.bind.jaxrs.util.REST;
import org.commonjava.indy.core.bind.jaxrs.ContentAccessHandler;
import org.commonjava.indy.core.bind.jaxrs.PackageContentAccessResource;
import org.commonjava.indy.pkg.pypi.content.UrlGeneratorFunction;
import org.commonjava.indy.util.PathUtils;
import org.commonjava.maven.galley.event.EventMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.StreamingOutput;
import javax.ws.rs.core.UriInfo;

import java.net.MalformedURLException;

import static org.commonjava.indy.pkg.pypi.model.PyPIPackageTypeDescriptor.PYPI_CONTENT_REST_BASE_PATH;
import static org.commonjava.indy.pkg.pypi.model.PyPIPackageTypeDescriptor.PYPI_PKG_KEY;
import static org.commonjava.maven.galley.util.PathUtils.normalize;
import static org.commonjava.util.jhttpc.util.UrlUtils.buildUrl;

@Api( value = "PyPI Content Access and Storage", description = "Handles retrieval and management of PyPI package content. This is the main point of access for PyPI users." )
@Path( "/api/content/pypi/{type: (hosted|group|remote)}/{name}" )
@ApplicationScoped
@REST
public class PyPIContentAccessResource
                implements PackageContentAccessResource
{

    @Inject
    private ContentAccessHandler handler;

    public PyPIContentAccessResource(){}

    public PyPIContentAccessResource( ContentAccessHandler handler )
    {
        this.handler = handler;
    }

    @Override
    @ApiOperation( "Retrieve PyPI artifact content under the given artifact store (type/name) and path." )
    @ApiResponses( { @ApiResponse( code = 404, message = "Content is not available" ),
                    @ApiResponse( code = 200, response = String.class, message = "Rendered content listing (when path ends with '/index.html' or '/')" ),
                    @ApiResponse( code = 200, response = StreamingOutput.class, message = "Content stream" ), } )
    @GET
    @Path( "/{package}" )
    public Response doGet(
                    final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
                    final @ApiParam( required = true ) @PathParam( "name" ) String name,
                    final @PathParam( "package" ) String pkg, @Context final UriInfo uriInfo,
                    @Context final HttpServletRequest request )
    {
        final String baseUri = uriInfo.getBaseUriBuilder().path( PYPI_CONTENT_REST_BASE_PATH ).build().toString();

        EventMetadata evt = new EventMetadata();
        evt.set( UrlGeneratorFunction.KEY, createUrlGeneratorFunction( baseUri, type, name ) );

        return handler.doGet( PYPI_PKG_KEY, type, name, pkg, baseUri, request, evt );
    }

    @GET
    @Path( "/{package}/{file}" )
    public Response doGet(
                    final @ApiParam( allowableValues = "hosted,group,remote", required = true ) @PathParam( "type" ) String type,
                    final @ApiParam( required = true ) @PathParam( "name" ) String name,
                    final @PathParam( "package" ) String pkg, final @PathParam( "file" ) String fname,
                    @Context final UriInfo uriInfo, @Context final HttpServletRequest request )
    {
        final String baseUri = uriInfo.getBaseUriBuilder().path( PYPI_CONTENT_REST_BASE_PATH ).build().toString();

        EventMetadata evt = new EventMetadata();
        evt.set( UrlGeneratorFunction.KEY, createUrlGeneratorFunction( baseUri, type, name ) );

        return handler.doGet( PYPI_PKG_KEY, type, name, normalize( pkg, fname ), baseUri, request, evt );
    }

    private UrlGeneratorFunction createUrlGeneratorFunction( String baseUri, String type, String name )
    {
        return ( path ) -> {
            try
            {
                return buildUrl( baseUri, type, name, path );
            }
            catch ( MalformedURLException e )
            {
                Logger logger = LoggerFactory.getLogger( PyPIContentAccessResource.class );
                logger.error( "Invalid base URL: " + baseUri, e );
            }

            return normalize( baseUri, type, name, path );
        };

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
        final String baseUri = uriInfo.getBaseUriBuilder().path( PYPI_CONTENT_REST_BASE_PATH ).build().toString();

        EventMetadata evt = new EventMetadata();
        evt.set( UrlGeneratorFunction.KEY, createUrlGeneratorFunction( baseUri, type, name ) );

        return handler.doGet( PYPI_PKG_KEY, type, name, "/", baseUri, request, evt );
    }

    @Override
    public Response doCreate( String type, String name, String path, UriInfo uriInfo, HttpServletRequest request )
    {
        return null;
    }

    @Override
    public Response doDelete( String type, String name, String path, Boolean cacheOnly )
    {
        return null;
    }

    @Override
    public Response doHead( String type, String name, String path, Boolean cacheOnly, UriInfo uriInfo,
                            HttpServletRequest request )
    {
        return null;
    }

}

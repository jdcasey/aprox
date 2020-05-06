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
package org.commonjava.indy.core.bind.jaxrs;

import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.bind.jaxrs.IndyResources;
import org.commonjava.indy.bind.jaxrs.util.JaxRsRequestHelper;
import org.commonjava.indy.bind.jaxrs.util.REST;
import org.commonjava.indy.bind.jaxrs.util.ResponseHelper;
import org.commonjava.indy.content.ContentDigester;
import org.commonjava.indy.content.ContentManager;
import org.commonjava.indy.core.bind.jaxrs.util.RequestUtils;
import org.commonjava.indy.core.bind.jaxrs.util.TransferCountingInputStream;
import org.commonjava.indy.core.bind.jaxrs.util.TransferStreamingOutput;
import org.commonjava.indy.core.ctl.ContentController;
import org.commonjava.indy.metrics.IndyMetricsManager;
import org.commonjava.indy.metrics.conf.IndyMetricsConfig;
import org.commonjava.indy.model.core.BatchDeleteRequest;
import org.commonjava.indy.model.core.PackageTypes;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.util.AcceptInfo;
import org.commonjava.indy.util.ApplicationContent;
import org.commonjava.indy.util.ApplicationHeader;
import org.commonjava.indy.util.ApplicationStatus;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.indy.util.UriFormatter;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.io.checksum.ContentDigest;
import org.commonjava.maven.galley.model.SpecialPathInfo;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.spi.io.SpecialPathManager;
import org.commonjava.maven.galley.transport.htcli.model.HttpExchangeMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.commonjava.indy.core.bind.jaxrs.util.RequestUtils.isDirectoryPath;
import static org.commonjava.indy.core.ctl.ContentController.LISTING_HTML_FILE;
import static org.commonjava.indy.metrics.RequestContextHelper.CONTENT_ENTRY_POINT;
import static org.commonjava.indy.metrics.RequestContextHelper.HTTP_STATUS;
import static org.commonjava.indy.metrics.RequestContextHelper.METADATA_CONTENT;
import static org.commonjava.indy.metrics.RequestContextHelper.PACKAGE_TYPE;
import static org.commonjava.indy.metrics.RequestContextHelper.PATH;
import static org.commonjava.indy.metrics.RequestContextHelper.setContext;
import static org.commonjava.indy.pkg.npm.model.NPMPackageTypeDescriptor.NPM_PKG_KEY;

@ApplicationScoped
@REST
public class ContentAccessHandler
        implements IndyResources
{

    protected final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    protected ContentController contentController;

    @Inject
    protected UriFormatter uriFormatter;

    @Inject
    protected JaxRsRequestHelper jaxRsRequestHelper;

    @Inject
    protected IndyMetricsManager metricsManager;

    @Inject
    protected IndyMetricsConfig metricsConfig;

    @Inject
    protected SpecialPathManager specialPathManager;

    @Inject
    private ResponseHelper responseHelper;
    
    @Inject
    ContentDigester contentDigester;


    protected ContentAccessHandler()
    {
    }

    public ContentAccessHandler( final ContentController controller, final UriFormatter uriFormatter,
                                 final JaxRsRequestHelper jaxRsRequestHelper )
    {
        this.contentController = controller;
        this.uriFormatter = uriFormatter;
        this.jaxRsRequestHelper = jaxRsRequestHelper;
    }

    public Response doCreate( final String packageType, final String type, final String name, final String path,
                              final HttpServletRequest request, EventMetadata eventMetadata,
                              final Supplier<URI> uriBuilder )
    {
        return doCreate( packageType, type, name, path, request, eventMetadata, uriBuilder, null );
    }

    public Response doCreate( final String packageType, final String type, final String name, final String path,
                              final HttpServletRequest request, EventMetadata eventMetadata,
                              final Supplier<URI> uriBuilder, final Consumer<ResponseBuilder> builderModifier )
    {
        setContext( PACKAGE_TYPE, packageType );
        setContext( PATH, path );

        final StoreType st = StoreType.get( type );
        StoreKey sk = new StoreKey( packageType, st, name );

        eventMetadata = eventMetadata.set( ContentManager.ENTRY_POINT_STORE, sk );
        setContext( CONTENT_ENTRY_POINT, sk.toString() );

        Response response;
        final Transfer transfer;
        try
        {
            TransferCountingInputStream streamingInputStream =
                    new TransferCountingInputStream( request.getInputStream(), metricsManager, metricsConfig );
            transfer = contentController.store( sk, path, streamingInputStream, eventMetadata );

            final StoreKey storageKey = LocationUtils.getKey( transfer );
            logger.info( "Key for storage location: {}", storageKey );

            final URI uri = uriBuilder.get();

            setContext( HTTP_STATUS, String.valueOf( 201 ) );
            ResponseBuilder builder = Response.created( uri );
            if ( builderModifier != null )
            {
                builderModifier.accept( builder );
            }
            response = builder.build();
        }
        catch ( final IndyWorkflowException | IOException e )
        {
            logger.error( String.format( "Failed to upload: %s to: %s. Reason: %s", path, name, e.getMessage() ), e );

            response = responseHelper.formatResponse( e, builderModifier );
        }

        return response;
    }

    public Response doDelete( final String packageType, final String type, final String name, final String path,
                              EventMetadata eventMetadata )
    {
        return doDelete( packageType, type, name, path, eventMetadata, null );
    }

    public Response doDelete( final BatchDeleteRequest request, EventMetadata eventMetadata )
    {

        StoreKey sk = request.getStoreKey();

        if ( sk == null )
        {
            ResponseBuilder builder = Response.status( 400 );
            return builder.build();
        }

        String packageType = sk.getPackageType();

        if ( !PackageTypes.contains( packageType ) )
        {
            ResponseBuilder builder = Response.status( 400 );
            return builder.build();
        }

        setContext( PACKAGE_TYPE, packageType );

        Set<String> paths = request.getPaths();
        if ( paths.isEmpty() )
        {
            ResponseBuilder builder = Response.status( 400 );
            return builder.build();
        }

        eventMetadata = eventMetadata.set( ContentManager.ENTRY_POINT_STORE, sk );
        setContext( CONTENT_ENTRY_POINT, sk.toString() );

        Response response;
        Map<String, String> results = new HashMap<>();
        for ( final String path : paths )
        {
            setContext( PATH, path );
            try
            {
                final ApplicationStatus result = contentController.delete( sk, path, eventMetadata );
                results.put( path, String.valueOf( result.code() ) );
            }
            catch ( final IndyWorkflowException e )
            {
                logger.error( String.format( "Failed to tryDelete artifact: %s from: %s. Reason: %s", path, sk,
                                             e.getMessage() ), e );
                results.put( path, e.getMessage() );
            }
        }
        ResponseBuilder builder = Response.status( 200 );
        response = builder.entity( results ).build();

        return response;

    }

    public Response doDelete( final String packageType, final String type, final String name, final String path,
                              EventMetadata eventMetadata, final Consumer<ResponseBuilder> builderModifier )
    {
        setContext( PACKAGE_TYPE, packageType );
        setContext( PATH, path );

        if ( !PackageTypes.contains( packageType ) )
        {
            ResponseBuilder builder = Response.status( 400 );
            if ( builderModifier != null )
            {
                builderModifier.accept( builder );
            }
            return builder.build();
        }

        final StoreType st = StoreType.get( type );
        StoreKey sk = new StoreKey( packageType, st, name );

        eventMetadata = eventMetadata.set( ContentManager.ENTRY_POINT_STORE, sk );
        setContext( CONTENT_ENTRY_POINT, sk.toString() );

        Response response;
        try
        {
            final ApplicationStatus result = contentController.delete( sk, path, eventMetadata );

            setContext( HTTP_STATUS, String.valueOf( result.code() ) );
            ResponseBuilder builder = Response.status( result.code() );
            if ( builderModifier != null )
            {
                builderModifier.accept( builder );
            }
            response = builder.build();
        }
        catch ( final IndyWorkflowException e )
        {
            logger.error( String.format( "Failed to tryDelete artifact: %s from: %s. Reason: %s", path, name,
                                         e.getMessage() ), e );
            response = responseHelper.formatResponse( e, builderModifier );
        }
        return response;
    }

    public Response doHead( final String packageType, final String type, final String name, final String path,
                            final Boolean cacheOnly, final String baseUri, final HttpServletRequest request,
                            EventMetadata eventMetadata )
    {
        return doHead( packageType, type, name, path, cacheOnly, baseUri, request, eventMetadata, null );
    }

    public Response doHead( final String packageType, final String type, final String name, final String path,
                            final Boolean cacheOnly, final String baseUri, final HttpServletRequest request,
                            EventMetadata eventMetadata, final Consumer<ResponseBuilder> builderModifier )
    {
        setContext( PACKAGE_TYPE, packageType );
        setContext( PATH, path );

        if ( !PackageTypes.contains( packageType ) )
        {
            ResponseBuilder builder = Response.status( 400 );
            if ( builderModifier != null )
            {
                builderModifier.accept( builder );
            }
            return builder.build();
        }

        final StoreType st = StoreType.get( type );
        final StoreKey sk = new StoreKey( packageType, st, name );

        eventMetadata = eventMetadata.set( ContentManager.ENTRY_POINT_STORE, sk );
        setContext( CONTENT_ENTRY_POINT, sk.toString() );

        Response response = null;

        if ( isDirectoryPath( path, request ) )
        {
            response = RequestUtils.redirectContentListing( packageType, type, name, path, request, builderModifier );
        }
        else
        {
            try
            {
                Transfer item = null;
                logger.info( "Checking existence of: {}:{} (cache only? {})", sk, path, cacheOnly );

                boolean exists;
                if ( Boolean.TRUE.equals( cacheOnly ) )
                {
                    logger.debug( "Calling getTransfer()" );
                    item = contentController.getTransfer( sk, path, TransferOperation.DOWNLOAD );
                    exists = item != null && item.exists();

                    SpecialPathInfo spi = specialPathManager.getSpecialPathInfo( item, packageType );
                    setContext( METADATA_CONTENT, Boolean.toString( spi != null && spi.isMetadata() ) );

                    logger.debug( "Got transfer reference: {}", item );
                }
                else
                {
                    // Use exists for remote repo to avoid downloading file. Use getTransfer for everything else (hosted, cache-only).
                    // Response will be composed of metadata by getHttpMetadata which get metadata from .http-metadata.json (because HTTP transport always writes a .http-metadata.json
                    // file when it makes a request). This file stores the HTTP response status code and headers regardless exist returning true or false.
                    logger.debug( "Calling remote exists()" );
                    exists = contentController.exists( sk, path );
                    logger.debug( "Got remote exists: {}", exists );
                }

                if ( exists )
                {
                    HttpExchangeMetadata httpMetadata = item != null ?
                            contentController.getHttpMetadata( item ) :
                            contentController.getHttpMetadata( sk, path );

                    // For hosted / group repo, artifacts will also have metadata generated. This will fetch the item by content get method.
                    if ( item == null )
                    {
                        logger.debug( "Retrieving: {}:{} for existence test", sk, path );
                        item = contentController.get( sk, path, eventMetadata );
                        logger.debug( "Got retrieved transfer reference: {}", item );
                    }

                    if ( MDC.get( METADATA_CONTENT ) != null )
                    {
                        SpecialPathInfo spi = specialPathManager.getSpecialPathInfo( item, packageType );
                        setContext( METADATA_CONTENT, Boolean.toString( spi != null && spi.isMetadata() ) );
                    }

                    logger.trace( "Building 200 response. Using HTTP metadata: {}", httpMetadata );

                    setContext( HTTP_STATUS, String.valueOf( 200 ) );
                    final ResponseBuilder builder = Response.ok();

                    // restrict the npm header contentType with json to avoid some parsing error
                    String contentType = packageType.equals( NPM_PKG_KEY ) ?
                            MediaType.APPLICATION_JSON :
                            contentController.getContentType( path );

                    responseHelper.setInfoHeaders( builder, item, sk, path, true, contentType,
                                    httpMetadata );
    
                    if(!path.endsWith("/")) {
                        // Content hashing headers
                        builder.header(ApplicationHeader.md5.key(), contentDigester.digest(sk, path, new EventMetadata()).getDigests().get(ContentDigest.MD5));
                        builder.header(ApplicationHeader.sha1.key(), contentDigester.digest(sk, path, new EventMetadata()).getDigests().get(ContentDigest.SHA_1));
                    }
                    
                    if ( builderModifier != null )
                    {
                        builderModifier.accept( builder );
                    }
                    response = builder.build();
                }
                else
                {
                    logger.trace( "Building 404 (or error) response..." );
                    if ( StoreType.remote == st )
                    {
                        final HttpExchangeMetadata metadata = contentController.getHttpMetadata( sk, path );
                        if ( metadata != null )
                        {
                            logger.trace( "Using HTTP metadata to build negative response." );
                            response = responseHelper.formatResponseFromMetadata( metadata );
                        }
                    }

                    if ( response == null )
                    {
                        logger.debug( "No HTTP metadata; building generic 404 response." );
                        setContext( HTTP_STATUS, String.valueOf( 404 ) );
                        ResponseBuilder builder = Response.status( Status.NOT_FOUND );
                        if ( builderModifier != null )
                        {
                            builderModifier.accept( builder );
                        }
                        response = builder.build();
                    }
                }
            }
            catch ( final IndyWorkflowException e )
            {
                logger.error( String.format( "Failed to download artifact: %s from: %s. Reason: %s", path, name,
                                             e.getMessage() ), e );
                response = responseHelper.formatResponse( e, builderModifier );
            }
        }
        return response;
    }

    public Response doGet( final String packageType, final String type, final String name, final String path,
                           final String baseUri, final HttpServletRequest request, EventMetadata eventMetadata )
    {
        return doGet( packageType, type, name, path, baseUri, request, eventMetadata, null );
    }

    public Response doGet( final String packageType, final String type, final String name, String path,
                           final String baseUri, final HttpServletRequest request, EventMetadata eventMetadata,
                           final Consumer<ResponseBuilder> builderModifier )
    {
        setContext( PACKAGE_TYPE, packageType );
        setContext( PATH, path );

        if ( !PackageTypes.contains( packageType ) )
        {
            ResponseBuilder builder = Response.status( 400 );
            if ( builderModifier != null )
            {
                builderModifier.accept( builder );
            }
            return builder.build();
        }

        final StoreType st = StoreType.get( type );
        final StoreKey sk = new StoreKey( packageType, st, name );

        eventMetadata = eventMetadata.set( ContentManager.ENTRY_POINT_STORE, sk );
        setContext( CONTENT_ENTRY_POINT, sk.toString() );

        final AcceptInfo acceptInfo = jaxRsRequestHelper.findAccept( request, ApplicationContent.text_html );
        final String standardAccept = ApplicationContent.getStandardAccept( acceptInfo.getBaseAccept() );

        Response response;

        logger.debug(
                "GET path: '{}' (RAW: '{}')\nIn store: '{}'\nUser addMetadata header is: '{}'\nStandard addMetadata header for that is: '{}'",
                path, request.getPathInfo(), sk, acceptInfo.getRawAccept(), standardAccept );

        if ( isDirectoryPath( path, request ) )
        {
            response = RequestUtils.redirectContentListing( packageType, type, name, path, request, builderModifier );
        }
        else
        {
            try
            {
                logger.debug( "START: retrieval of content: {}:{}", sk, path );
                final Transfer item = contentController.get( sk, path, eventMetadata );

                SpecialPathInfo spi = specialPathManager.getSpecialPathInfo( item, packageType );
                setContext( METADATA_CONTENT, Boolean.toString( spi != null && spi.isMetadata() ) );

                logger.debug( "HANDLE: retrieval of content: {}:{}", sk, path );
                if ( item == null )
                {
                    return handleMissingContentQuery( sk, path, builderModifier );
                }

                boolean handleLocking = false;
                if ( !item.isWriteLocked() )
                {
                    item.lockWrite();
                    handleLocking = true;
                }

                try
                {
                    if ( !item.exists() )
                    {
                        return handleMissingContentQuery( sk, path, builderModifier );
                    }
                    else if ( item.isDirectory() )
                    {
                        logger.debug( "Getting listing at: {}", path + "/" );
                        response = RequestUtils.redirectContentListing( packageType, type, name, path, request, builderModifier );
                    }
                    else
                    {
                        logger.debug( "RETURNING: retrieval of content: {}:{}", sk, path );
                        // open the stream here to prevent deletion while waiting for the transfer back to the user to start...
                        InputStream in = item.openInputStream( true, eventMetadata );
                        final ResponseBuilder builder = Response.ok(
                                new TransferStreamingOutput( in, metricsManager, metricsConfig ) );

                        responseHelper.setInfoHeaders( builder, item, sk, path, true, contentController.getContentType( path ),
                                        contentController.getHttpMetadata( item ) );
                        if ( builderModifier != null )
                        {
                            builderModifier.accept( builder );
                        }
                        response = builder.build();
                    }
                }
                finally
                {
                    if ( handleLocking )
                    {
                        item.unlock();
                    }
                }
            }
            catch ( final IOException | IndyWorkflowException e )
            {
                logger.error( String.format( "Failed to download artifact: %s from: %s. Reason: %s", path, name,
                                             e.getMessage() ), e );
                response = responseHelper.formatResponse( e, builderModifier );
            }
        }

        logger.info( "RETURNING RESULT: {}:{}", sk, path );
        return response;
    }

    protected Response handleMissingContentQuery( final StoreKey sk, final String path,
                                                  final Consumer<ResponseBuilder> builderModifier )
    {
        Response response = null;

        logger.trace( "Transfer not found: {}/{}", sk, path );
        if ( StoreType.remote == sk.getType() )
        {
            logger.trace( "Transfer was from remote repo. Trying to get HTTP metadata for: {}/{}", sk, path );
            try
            {
                final HttpExchangeMetadata metadata = contentController.getHttpMetadata( sk, path );
                if ( metadata != null )
                {
                    logger.trace( "Using HTTP metadata to formulate response status for: {}/{}", sk, path );
                    response = responseHelper.formatResponseFromMetadata( metadata, builderModifier );
                }
                else
                {
                    logger.trace( "No HTTP metadata found!" );
                }
            }
            catch ( final IndyWorkflowException e )
            {
                logger.error( String.format( "Error retrieving status metadata for: %s from: %s. Reason: %s", path,
                                             sk.getName(), e.getMessage() ), e );
                response = responseHelper.formatResponse( e, builderModifier );
            }
        }

        if ( response == null )
        {
            response = responseHelper.formatResponse( ApplicationStatus.NOT_FOUND, null,
                                       "Path " + path + " is not available in store " + sk + ".", builderModifier );
        }

        return response;
    }



}

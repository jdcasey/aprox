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
package org.commonjava.indy.core.bind.jaxrs;

import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.bind.jaxrs.IndyResources;
import org.commonjava.indy.bind.jaxrs.util.JaxRsRequestHelper;
import org.commonjava.indy.content.ContentManager;
import org.commonjava.indy.core.bind.jaxrs.util.TransferStreamingOutput;
import org.commonjava.indy.core.ctl.ContentController;
import org.commonjava.indy.model.core.PackageTypes;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.model.util.HttpUtils;
import org.commonjava.indy.util.AcceptInfo;
import org.commonjava.indy.util.ApplicationContent;
import org.commonjava.indy.util.ApplicationHeader;
import org.commonjava.indy.util.ApplicationStatus;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.indy.util.UriFormatter;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.transport.htcli.model.HttpExchangeMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.Response.Status;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Date;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.formatOkResponseWithEntity;
import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.formatResponse;
import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.formatResponseFromMetadata;
import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.setInfoHeaders;
import static org.commonjava.indy.core.ctl.ContentController.LISTING_HTML_FILE;

public class ContentAccessHandler
        implements IndyResources
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private ContentController contentController;

    @Inject
    private UriFormatter uriFormatter;

    @Inject
    private JaxRsRequestHelper jaxRsRequestHelper;

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
        final StoreType st = StoreType.get( type );
        StoreKey sk = new StoreKey( packageType, st, name );

        eventMetadata = eventMetadata.set( ContentManager.ENTRY_POINT_STORE, sk );

        Response response = null;
        final Transfer transfer;
        try
        {
            transfer =
                    contentController.store( new StoreKey( st, name ), path, request.getInputStream(), eventMetadata );

            final StoreKey storageKey = LocationUtils.getKey( transfer );
            logger.info( "Key for storage location: {}", storageKey );

            final URI uri = uriBuilder.get();

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

            response = formatResponse( e, builderModifier );
        }

        return response;
    }

    public Response doDelete( final String packageType, final String type, final String name, final String path,
                              EventMetadata eventMetadata )
    {
        return doDelete( packageType, type, name, path, eventMetadata, null );
    }

    public Response doDelete( final String packageType, final String type, final String name, final String path,
                              EventMetadata eventMetadata, final Consumer<ResponseBuilder> builderModifier )
    {
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

        Response response;
        try
        {
            final ApplicationStatus result = contentController.delete( st, name, path, eventMetadata );
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
            response = formatResponse( e, builderModifier );
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

        final AcceptInfo acceptInfo = jaxRsRequestHelper.findAccept( request, ApplicationContent.text_html );

        Response response = null;

        if ( path == null || path.equals( "" ) || path.endsWith( "/" ) || path.endsWith( LISTING_HTML_FILE ) )
        {
            try
            {
                logger.info( "Getting listing at: {}", path );
                final String content =
                        contentController.renderListing( acceptInfo.getBaseAccept(), sk, path, baseUri, uriFormatter );

                ResponseBuilder builder = Response.ok()
                                   .header( ApplicationHeader.content_type.key(), acceptInfo.getRawAccept() )
                                   .header( ApplicationHeader.content_length.key(), Long.toString( content.length() ) )
                                   .header( ApplicationHeader.last_modified.key(),
                                            HttpUtils.formatDateHeader( new Date() ) );
                if ( builderModifier != null )
                {
                    builderModifier.accept( builder );
                }
                response = builder.build();
            }
            catch ( final IndyWorkflowException e )
            {
                logger.error(
                        String.format( "Failed to list content: %s from: %s. Reason: %s", path, name, e.getMessage() ),
                        e );
                response = formatResponse( e, builderModifier );
            }
        }
        else
        {
            try
            {
                Transfer item = null;
                logger.info( "Checking existence of: {}:{} (cache only? {})", sk, path, cacheOnly );

                boolean exists = false;
                if ( Boolean.TRUE.equals( cacheOnly ) )
                {
                    logger.debug( "Calling getTransfer()" );
                    item = contentController.getTransfer( sk, path, TransferOperation.DOWNLOAD );
                    exists = item != null && item.exists();
                    logger.debug( "Got transfer reference: {}", item );
                }
                else
                {
                    // Use exists for remote repo to avoid downloading file. Use getTransfer for everything else (hosted, cache-only).
                    // Response will be composed of metadata by getHttpMetadata which get metadata from .http-metadata.json (because HTTP transport always writes a .http-metadata.json
                    // file when it makes a request). This file stores the HTTP response status code and headers regardless exist returning true or false.
                    logger.debug( "Calling exists()" );
                    exists = contentController.exists( sk, path );
                    logger.debug( "Got exists: {}", exists );
                }

                if ( exists )
                {
                    HttpExchangeMetadata httpMetadata = item != null ?
                            contentController.getHttpMetadata( item ) :
                            contentController.getHttpMetadata( sk, path );

                    // TODO: For hosted repo, artifacts do not have metadata generated. Fall to get(). But we need a better fix later on.
                    if ( httpMetadata == null )
                    {
                        logger.info( "Retrieving: {}:{} for existence test", sk, path );
                        item = contentController.get( sk, path, eventMetadata );
                        logger.debug( "Got retrieved transfer reference: {}", item );
                    }

                    logger.debug( "Building 200 response. Using HTTP metadata: {}", httpMetadata );

                    final ResponseBuilder builder = Response.ok();
                    setInfoHeaders( builder, item, sk, path, true, contentController.getContentType( path ),
                                    httpMetadata );
                    if ( builderModifier != null )
                    {
                        builderModifier.accept( builder );
                    }
                    response = builder.build();
                }
                else
                {
                    logger.debug( "Building 404 (or error) response..." );
                    if ( StoreType.remote == st )
                    {
                        final HttpExchangeMetadata metadata = contentController.getHttpMetadata( sk, path );
                        if ( metadata != null )
                        {
                            logger.debug( "Using HTTP metadata to build negative response." );
                            response = formatResponseFromMetadata( metadata );
                        }
                    }

                    if ( response == null )
                    {
                        logger.debug( "No HTTP metadata; building generic 404 response." );
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
                response = formatResponse( e, builderModifier );
            }
        }
        return response;
    }

    public Response doGet( final String packageType, final String type, final String name, final String path,
                           final String baseUri, final HttpServletRequest request, EventMetadata eventMetadata )
    {
        return doGet( packageType, type, name, path, baseUri, request, eventMetadata, null );
    }

    public Response doGet( final String packageType, final String type, final String name, final String path,
                           final String baseUri, final HttpServletRequest request, EventMetadata eventMetadata,
                           final Consumer<ResponseBuilder> builderModifier )
    {
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

        final AcceptInfo acceptInfo = jaxRsRequestHelper.findAccept( request, ApplicationContent.text_html );
        final String standardAccept = ApplicationContent.getStandardAccept( acceptInfo.getBaseAccept() );

        Response response = null;

        logger.info(
                "GET path: '{}' (RAW: '{}')\nIn store: '{}'\nUser addMetadata header is: '{}'\nStandard addMetadata header for that is: '{}'",
                path, request.getPathInfo(), sk, acceptInfo.getRawAccept(), standardAccept );

        if ( path == null || path.equals( "" ) || request.getPathInfo().endsWith( "/" ) || path.endsWith(
                LISTING_HTML_FILE ) )
        {
            try
            {
                logger.info( "Getting listing at: {}", path );
                final String content =
                        contentController.renderListing( standardAccept, st, name, path, baseUri, uriFormatter );

                response = formatOkResponseWithEntity( content, acceptInfo.getRawAccept(), builderModifier );
            }
            catch ( final IndyWorkflowException e )
            {
                logger.error( String.format( "Failed to render content listing: %s from: %s. Reason: %s", path, name,
                                             e.getMessage() ), e );
                response = formatResponse( e, builderModifier );
            }
        }
        else
        {
            try
            {
                logger.info( "START: retrieval of content: {}:{}", sk, path );
                final Transfer item = contentController.get( sk, path, eventMetadata );

                logger.info( "HANDLE: retrieval of content: {}:{}", sk, path );
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
                    else if ( item.isDirectory() || ( path.endsWith( "index.html" ) ) )
                    {
                        try
                        {
                            item.delete( false );

                            logger.info( "Getting listing at: {}", path + "/" );
                            final String content =
                                    contentController.renderListing( standardAccept, st, name, path + "/", baseUri,
                                                                     uriFormatter );

                            response = formatOkResponseWithEntity( content, acceptInfo.getRawAccept(), builderModifier );
                        }
                        catch ( final IndyWorkflowException | IOException e )
                        {
                            logger.error(
                                    String.format( "Failed to render content listing: %s from: %s. Reason: %s", path,
                                                   name, e.getMessage() ), e );
                            response = formatResponse( e, builderModifier );
                        }
                    }
                    else
                    {
                        logger.info( "RETURNING: retrieval of content: {}:{}", sk, path );
                        // open the stream here to prevent deletion while waiting for the transfer back to the user to start...
                        InputStream in = item.openInputStream( true, eventMetadata );
                        final ResponseBuilder builder = Response.ok( new TransferStreamingOutput( in ) );
                        setInfoHeaders( builder, item, sk, path, true, contentController.getContentType( path ),
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
                response = formatResponse( e, builderModifier );
            }
        }

        logger.info( "RETURNING RESULT: {}:{}", sk, path );
        return response;
    }

    private Response handleMissingContentQuery( final StoreKey sk, final String path,
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
                    response = formatResponseFromMetadata( metadata, builderModifier );
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
                response = formatResponse( e, builderModifier );
            }
        }

        if ( response == null )
        {
            response = formatResponse( ApplicationStatus.NOT_FOUND, null,
                                       "Path " + path + " is not available in store " + sk + ".", builderModifier );
        }

        return response;
    }

}

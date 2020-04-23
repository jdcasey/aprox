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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.binary.Base64;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.bind.jaxrs.util.REST;
import org.commonjava.indy.bind.jaxrs.util.ResponseHelper;
import org.commonjava.indy.content.ContentManager;
import org.commonjava.indy.core.bind.jaxrs.ContentAccessHandler;
import org.commonjava.indy.core.bind.jaxrs.util.RequestUtils;
import org.commonjava.indy.core.bind.jaxrs.util.TransferStreamingOutput;
import org.commonjava.indy.core.model.StoreHttpExchangeMetadata;
import org.commonjava.indy.model.core.PackageTypes;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.pkg.npm.content.group.PackageMetadataMerger;
import org.commonjava.indy.pkg.npm.inject.NPMContentHandler;
import org.commonjava.indy.util.AcceptInfo;
import org.commonjava.indy.util.ApplicationContent;
import org.commonjava.indy.util.ApplicationHeader;
import org.commonjava.maven.galley.TransferManager;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.transport.htcli.model.HttpExchangeMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;


@ApplicationScoped
@NPMContentHandler
@REST
public class NPMContentAccessHandler
        extends ContentAccessHandler
{
    protected final Logger logger = LoggerFactory.getLogger( getClass() );

    public static final String TEMP_EXTENSION = ".temp";

    @Inject
    private TransferManager transfers;

    @Inject
    private ObjectMapper mapper;

    @Inject
    private ResponseHelper responseHelper;

    @Inject
    private PackageMetadataMerger packageMetadataMerger;

    @Override
    public Response doCreate( String packageType, String type, String name, String path, HttpServletRequest request,
                              EventMetadata eventMetadata, Supplier<URI> uriBuilder )
    {
        return doCreate( packageType, type, name, path, request, eventMetadata, uriBuilder, null );
    }

    @Override
    public Response doCreate( String packageType, String type, String name, String path, HttpServletRequest request,
                              EventMetadata eventMetadata, Supplier<URI> uriBuilder,
                              Consumer<Response.ResponseBuilder> builderModifier )
    {
        final StoreType st = StoreType.get( type );
        final StoreKey sk = new StoreKey( packageType, st, name );

        eventMetadata = eventMetadata.set( ContentManager.ENTRY_POINT_STORE, sk );

        Response response;
        try
        {
            // store the transfer of new request package.json
            final Transfer metadataFile = contentController.store( sk, path, request.getInputStream(), eventMetadata );

            // generate its relevant files from the metadata file package.json
            List<Transfer> generated = generateNPMContentsFromTransfer( metadataFile, eventMetadata );

            final URI uri = uriBuilder.get();
            response = responseWithBuilder( Response.created( uri ), builderModifier );

            // generate .http-metadata.json for hosted repo to resolve npm header requirements
            generateHttpMetadataHeaders( metadataFile, generated, request, response );
        }
        catch ( final IndyWorkflowException | IOException e )
        {
            logger.error( String.format( "Failed to upload: %s to: %s. Reason: %s", path, name, e.getMessage() ), e );

            response = responseHelper.formatResponse( e, builderModifier );
        }

        return response;
    }

    @Override
    public Response doHead( final String packageType, final String type, final String name, final String path,
                            final Boolean cacheOnly, final String baseUri, final HttpServletRequest request,
                            EventMetadata eventMetadata )
    {
        return doHead( packageType, type, name, path, cacheOnly, baseUri, request, eventMetadata, null );
    }

    @Override
    public Response doHead( final String packageType, final String type, final String name, final String path,
                            final Boolean cacheOnly, final String baseUri, final HttpServletRequest request,
                            EventMetadata eventMetadata, final Consumer<Response.ResponseBuilder> builderModifier )
    {
        if ( !PackageTypes.contains( packageType ) )
        {
            Response.ResponseBuilder builder = Response.status( 400 );
            if ( builderModifier != null )
            {
                builderModifier.accept( builder );
            }
            return builder.build();
        }

        final StoreType st = StoreType.get( type );
        final StoreKey sk = new StoreKey( packageType, st, name );

        eventMetadata = eventMetadata.set( ContentManager.ENTRY_POINT_STORE, sk );

        Response response = null;

        if ( path == null || path.equals( "" ) )
        {
            logger.info( "Getting listing at: {}", path );
            response = RequestUtils.redirectContentListing( packageType, type, name, path, request, builderModifier );
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
                    logger.debug( "Calling remote exists()" );
                    exists = contentController.exists( sk, path );
                    logger.debug( "Got remote exists: {}", exists );
                }

                if ( exists )
                {
                    // for npm will fetch the http-meta as the mapping path directly to get the headers info for further header set
                    HttpExchangeMetadata httpMetadata =
                            contentController.getHttpMetadata( sk, path );

                    if ( item == null )
                    {
                        logger.info( "Retrieving: {}:{} for existence test", sk, path );
                        item = contentController.get( sk, path, eventMetadata );
                        logger.debug( "Got retrieved transfer reference: {}", item );
                    }

                    logger.debug( "Building 200 response. Using HTTP metadata: {}", httpMetadata );

                    final Response.ResponseBuilder builder = Response.ok();

                    responseHelper.setInfoHeaders( builder, item, sk, path, true, getNPMContentType( path ),
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
                            response = responseHelper.formatResponseFromMetadata( metadata );
                        }
                    }

                    if ( response == null )
                    {
                        logger.debug( "No HTTP metadata; building generic 404 response." );
                        Response.ResponseBuilder builder = Response.status( Response.Status.NOT_FOUND );
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

    @Override
    public Response doGet( String packageType, String type, String name, String path, String baseUri,
                           HttpServletRequest request, EventMetadata eventMetadata )
    {
        return doGet( packageType, type, name, path, baseUri, request, eventMetadata, null );
    }

    @Override
    public Response doGet( String packageType, String type, String name, String path, String baseUri,
                           HttpServletRequest request, EventMetadata eventMetadata,
                           Consumer<Response.ResponseBuilder> builderModifier )
    {
        if ( !PackageTypes.contains( packageType ) )
        {
            return responseWithBuilder( Response.status( 400 ), builderModifier );
        }

         // hide npm sensitive user info for publish
        if ( path != null && path.startsWith( "-/user" ) )
         {
             return responseWithBuilder( Response.status( 404 ), builderModifier );
         }

        final StoreType st = StoreType.get( type );
        final StoreKey sk = new StoreKey( packageType, st, name );

        eventMetadata.set( ContentManager.ENTRY_POINT_STORE, sk );
        eventMetadata.set( ContentManager.ENTRY_POINT_BASE_URI, baseUri );

        final AcceptInfo acceptInfo = jaxRsRequestHelper.findAccept( request, ApplicationContent.text_html );
        final String standardAccept = ApplicationContent.getStandardAccept( acceptInfo.getBaseAccept() );

        Response response = null;

        logger.info(
                "GET path: '{}' (RAW: '{}')\nIn store: '{}'\nUser addMetadata header is: '{}'\nStandard addMetadata header for that is: '{}'",
                path, request.getPathInfo(), sk, acceptInfo.getRawAccept(), standardAccept );

        if ( path == null || path.equals( "" ) )
        {
            logger.info( "Getting listing at: {}", path );
            response = RequestUtils.redirectContentListing( packageType, type, name, path, request, builderModifier );
        }
        else
        {
            try
            {
                // NOTE: We do NOT want to map this here. Instead, let's map it when we retrieve a Transfer instance as
                // we access the file storage on this system...we do that via StoragePathCalculator, in pkg-npm/common.
//                if ( eventMetadata.get( STORAGE_PATH ) != null && StoreType.remote != st )
//                {
//                    // make sure the right mapping path for hosted and group when retrieve content
//                    path = PathUtils.storagePath( path, eventMetadata );
//                }

                logger.info( "START: retrieval of content: {}/{}", sk, path );
                Transfer item = contentController.get( sk, path, eventMetadata );

                logger.info( "HANDLE: retrieval of content: {}/{}", sk, path );
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
                    else if ( item.isDirectory() && StoreType.remote != st )
                    {
                        logger.info( "Getting listing at: {}", path + "/" );
                        response = RequestUtils.redirectContentListing( packageType, type, name, path, request, builderModifier );
                    }
                    else
                    {
                        // for remote retrieve, when content has downloaded and cached in the mapping path,
                        // the item here will be a directory, so reassign the path and item as the mapping one

                        // Note: as STORAGE_PATH is not used, these code is also useless now.
//                        if ( item.isDirectory() && StoreType.remote == st )
//                        {
//                            path = PathUtils.storagePath( path, eventMetadata );
//                            origItem = item;
//                            item = contentController.get( sk, path, eventMetadata );
//                        }

//                        if ( item == null )
//                        {
//                            logger.error( "Retrieval of actual storage path: {} FAILED!", path );
//                            responseHelper.throwError( ApplicationStatus.SERVER_ERROR, new NullPointerException( path ), "Retrieval of mapped file from storage failed." );
//                        }

                        logger.info( "RETURNING: retrieval of content: {}:{}", sk, path );
                        // open the stream here to prevent deletion while waiting for the transfer back to the user to start...
                        InputStream in = item.openInputStream( true, eventMetadata );

                        final Response.ResponseBuilder builder =
                                Response.ok( new TransferStreamingOutput( in, metricsManager, metricsConfig ) );

                        responseHelper.setInfoHeaders( builder, item, sk, path, false, getNPMContentType( path ),
                                        contentController.getHttpMetadata( item ) );
                        response = responseWithBuilder( builder, builderModifier );

//                        // generating .http-metadata.json for npm group and remote retrieve to resolve header requirements
//                        // hosted .http-metadata.json will be generated when publish
//                        // only package.json file will generate this customized http meta to satisfy npm client header check
//                        if ( eventMetadata.get( STORAGE_PATH ) != null && StoreType.hosted != st )
//                        {
//                            generateHttpMetadataHeaders( item, request, response );
//                        }
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

    private List<Transfer> generateNPMContentsFromTransfer( final Transfer transfer, final EventMetadata eventMetadata )
    {
        if ( transfer == null || !transfer.exists() )
        {
            return null;
        }

        Transfer versionTarget = null;
        Transfer tarballTarget = null;
        String versionContent = "";
        String tarballContent = "";

        ConcreteResource resource = transfer.getResource();
        try
        {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree( transfer.openInputStream( true ) );

            String versionPath = null;
            String tarballPath = null;
            JsonNode vnode = root.path( "versions" );
            JsonNode anode = root.path( "_attachments" );
            JsonNode idnode = root.path( "_id" );

            if ( vnode.fields().hasNext() )
            {
                Map.Entry<String, JsonNode> entry = vnode.fields().next();
                String version = entry.getKey();
                if ( version == null )
                {
                    return null;
                }
                versionPath = Paths.get( idnode.asText(), version ).toString();
                versionContent = entry.getValue().toString();
            }

            if ( anode.fields().hasNext() )
            {
                String tarball = anode.fields().next().getKey();
                if ( tarball == null )
                {
                    return null;
                }
                tarballPath = Paths.get( idnode.asText(), "-", tarball ).toString();
                tarballContent = anode.findPath( "data" ).asText();
            }

            if ( versionPath == null || tarballPath == null )
            {
                return null;
            }

            versionTarget = transfers.getCacheReference( new ConcreteResource( resource.getLocation(), versionPath ) );
            tarballTarget = transfers.getCacheReference( new ConcreteResource( resource.getLocation(), tarballPath ) );

        }
        catch ( final IOException e )
        {
            logger.error( String.format( "[NPM] Json node parse failed for resource: %s. Reason: %s", resource, e.getMessage() ), e );
        }

        if ( versionTarget == null || tarballTarget == null )
        {
            return null;
        }

        try (OutputStream versionOutputStream = versionTarget.openOutputStream( TransferOperation.UPLOAD, true,
                                                                                eventMetadata );
             OutputStream tarballOutputStream = tarballTarget.openOutputStream( TransferOperation.UPLOAD, true,
                                                                                eventMetadata ))
        {
            logger.info( "STORE {}", versionTarget.getResource() );
            versionOutputStream.write( versionContent.getBytes() );
            logger.info( "STORE {}", tarballTarget.getResource() );
            tarballOutputStream.write( Base64.decodeBase64( tarballContent ) );
            return generateTransfers( versionTarget, tarballTarget );
        }
        catch ( final IOException e )
        {
            logger.error( String.format( "[NPM] Failed to store the generated targets: %s and %s. Reason: %s",
                                         versionTarget.getResource(), tarballTarget.getResource(), e.getMessage() ), e );
        }
        return null;
    }

    private void generateHttpMetadataHeaders( final Transfer transfer, final HttpServletRequest request,
                                              final Response response )
    {
        generateHttpMetadataHeaders( transfer, null, request, response );
    }

    private void generateHttpMetadataHeaders( final Transfer transfer, final List<Transfer> generated,
                                              final HttpServletRequest request, final Response response )
    {
        if ( transfer == null || !transfer.exists() || request == null || response == null )
        {
            return;
        }

        Response customizedResponse = Response.fromResponse( response )
                                              .header( ApplicationHeader.content_length.upperKey(), transfer.length() )
                                              .header( ApplicationHeader.indy_origin.upperKey(), null )
                                              .header( ApplicationHeader.transfer_encoding.upperKey(), null )
                                              .lastModified( new Date( transfer.lastModified() ) )
                                              .build();

        Transfer metaTxfr = transfer.getSiblingMeta( HttpExchangeMetadata.FILE_EXTENSION );
        if ( metaTxfr == null )
        {
            if ( transfer.isDirectory() )
            {
                metaTxfr = transfer.getChild( HttpExchangeMetadata.FILE_EXTENSION );
            }
            else
            {
                return;
            }
        }

        final HttpExchangeMetadata metadata = new StoreHttpExchangeMetadata( request, customizedResponse );

        try (OutputStream out = metaTxfr.openOutputStream( TransferOperation.GENERATE, false ))
        {
            if ( out != null )
            {
                out.write( mapper.writeValueAsBytes( metadata ) );
            }
        }
        catch ( final IOException e )
        {
            logger.error( "Failed to write metadata for HTTP exchange to: {}. Reason: {}", metaTxfr, e );
        }

        // npm will generate .tgz and version json metadata files from the package json file target,
        // which will also need the HttpExchangeMetadata for npm header check.

        if ( generated == null )
        {
            return;
        }
        for ( Transfer t : generated )
        {
            generateHttpMetadataHeaders( t, request, response );
        }
    }

    private List<Transfer> generateTransfers( Transfer... generated )
    {
        List<Transfer> list = new ArrayList<>();
        Collections.addAll( list, generated );
        return list;
    }

    private Response responseWithBuilder( final Response.ResponseBuilder builder,
                                          final Consumer<Response.ResponseBuilder> builderModifier )
    {
        if ( builderModifier != null )
        {
            builderModifier.accept( builder );
        }
        return builder.build();
    }


    private String getNPMContentType( final String path )
    {
        String type = MediaType.APPLICATION_JSON;
        if ( path.endsWith( ".tgz" ) )
        {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        return type;
    }

}

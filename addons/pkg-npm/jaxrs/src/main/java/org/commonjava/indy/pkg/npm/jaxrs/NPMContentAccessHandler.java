/**
 * Copyright (C) 2011-2017 Red Hat, Inc. (https://github.com/Commonjava/indy)
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
import org.commonjava.indy.content.ContentManager;
import org.commonjava.indy.core.bind.jaxrs.ContentAccessHandler;
import org.commonjava.indy.core.bind.jaxrs.util.TransferStreamingOutput;
import org.commonjava.indy.core.model.StoreHttpExchangeMetadata;
import org.commonjava.indy.model.core.PackageTypes;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.pkg.npm.content.group.PackageMetadataMerger;
import org.commonjava.indy.pkg.npm.inject.NPMContentHandler;
import org.commonjava.indy.util.AcceptInfo;
import org.commonjava.indy.util.ApplicationContent;
import org.commonjava.maven.galley.TransferManager;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.transport.htcli.model.HttpExchangeMetadata;
import org.commonjava.maven.galley.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
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

import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.formatOkResponseWithEntity;
import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.formatResponse;
import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.setInfoHeaders;
import static org.commonjava.indy.core.ctl.ContentController.LISTING_HTML_FILE;
import static org.commonjava.maven.galley.spi.cache.CacheProvider.STORAGE_PATH;

@ApplicationScoped
@NPMContentHandler
public class NPMContentAccessHandler
        extends ContentAccessHandler
{
    protected final Logger logger = LoggerFactory.getLogger( getClass() );

    public static final String TEMP_EXTENSION = ".temp";

    @Inject
    private TransferManager transfers;

    @Inject
    private ObjectMapper mapper;

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
        path = PathUtils.storagePath( path, eventMetadata );

        final StoreType st = StoreType.get( type );
        final StoreKey sk = new StoreKey( packageType, st, name );

        eventMetadata = eventMetadata.set( ContentManager.ENTRY_POINT_STORE, sk );

        Response response;

        InputStream stream = null;
        try
        {
            // check the original existed package.json transfer
            final Transfer existed = contentController.get( sk, path, eventMetadata );
            Transfer httpMeta = null;
            Transfer temp = null;

            // copy the existed transfer to temp one
            if ( existed != null && existed.exists() )
            {
                httpMeta = existed.getSiblingMeta( HttpExchangeMetadata.FILE_EXTENSION );
                temp = existed.getSibling( TEMP_EXTENSION );
                temp.copyFrom( existed, eventMetadata );
            }

            // store the transfer of new request package.json
            final Transfer tomerge = contentController.store( sk, path, request.getInputStream(), eventMetadata );

            // generate its relevant files from the new request package.json
            List<Transfer> generated = generateNPMContentsFromTransfer( tomerge, eventMetadata );

            // merged both of the transfers, original existed one and new request one,
            // then store the transfer, delete unuseful temp and meta transfers.
            if ( temp != null && temp.exists() )
            {
                stream = new PackageMetadataMerger().merge( temp, tomerge );
                contentController.store( sk, path, stream, eventMetadata );

                // for npm group, will not replace with the new http meta when re-upload,
                // delete the old http meta, will generate the new one with updated CONTENT-LENGTH when npm install
                httpMeta.delete();
                temp.delete();
            }

            final URI uri = uriBuilder.get();
            response = responseWithBuilder( Response.created( uri ), builderModifier );

            // generate .http-metadata.json for hosted repo to resolve npm header requirements
            generateHttpMetadataHeaders( tomerge, generated, request, response );
        }
        catch ( final IndyWorkflowException | IOException e )
        {
            logger.error( String.format( "Failed to upload: %s to: %s. Reason: %s", path, name, e.getMessage() ), e );

            response = formatResponse( e, builderModifier );
        }
        finally
        {
            closeQuietly( stream );
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

        eventMetadata = eventMetadata.set( ContentManager.ENTRY_POINT_STORE, sk );

        final AcceptInfo acceptInfo = jaxRsRequestHelper.findAccept( request, ApplicationContent.text_html );
        final String standardAccept = ApplicationContent.getStandardAccept( acceptInfo.getBaseAccept() );

        Response response;

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
                        contentController.renderListing( standardAccept, sk, path, baseUri, uriFormatter, eventMetadata );

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
                if ( eventMetadata.get( STORAGE_PATH ) != null && StoreType.remote != st )
                {
                    // make sure the right mapping path for hosted and group when retrieve content
                    path = PathUtils.storagePath( path, eventMetadata );
                }
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
                    else if ( item.isDirectory() )
                    {
                        try
                        {
                            logger.info( "Getting listing at: {}", path + "/" );
                            final String content =
                                    contentController.renderListing( standardAccept, sk, path + "/", baseUri,
                                                                     uriFormatter, eventMetadata );

                            response =
                                    formatOkResponseWithEntity( content, acceptInfo.getRawAccept(), builderModifier );
                        }
                        catch ( final IndyWorkflowException e )
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
//                        InputStream in = item.openInputStream( true, eventMetadata );
                        final Response.ResponseBuilder builder = Response.ok( new TransferStreamingOutput( item, eventMetadata ) );
                        setInfoHeaders( builder, item, sk, path, true, contentController.getContentType( path ),
                                        contentController.getHttpMetadata( item ) );
                        response = responseWithBuilder( builder, builderModifier );
                        // generating .http-metadata.json for npm group retrieve to resolve header requirements
                        if ( eventMetadata.get( STORAGE_PATH ) != null && StoreType.group == st )
                        {
                            generateHttpMetadataHeaders( item, request, response );
                        }
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
            catch ( final IndyWorkflowException e )
            {
                logger.error( String.format( "Failed to download artifact: %s from: %s. Reason: %s", path, name,
                                             e.getMessage() ), e );
                response = formatResponse( e, builderModifier );
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
            logger.error( String.format( "[NPM] Failed to store the generated targets: s% and s%. Reason: s%",
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

        Response responseWithLastModified =
                Response.fromResponse( response ).lastModified( new Date( transfer.lastModified() ) ).build();

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

        final HttpExchangeMetadata metadata = new StoreHttpExchangeMetadata( request, responseWithLastModified );

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
}

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
package org.commonjava.indy.pkg.npm.content.group;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.commons.io.IOUtils;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.commonjava.indy.pkg.npm.model.PackageMetadata;
import org.commonjava.indy.pkg.npm.model.io.PackageSerializerModule;
import org.commonjava.maven.galley.model.Transfer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.commonjava.indy.util.LocationUtils.getKey;

@ApplicationScoped
public class PackageMetadataMerger
{
    public static final String METADATA_NAME = "package.json";

    @Inject
    private Instance<PackageMetadataProvider> metadataProviderInstances;

    @Inject
    private PackageSerializerModule packageSerializerModule;

    @Inject
    private IndyObjectMapper mapper;

    private List<PackageMetadataProvider> metadataProviders;

    public PackageMetadataMerger()
    {
    }

    public PackageMetadataMerger( Iterable<PackageMetadataProvider> providers, IndyObjectMapper mapper )
    {
        metadataProviders = new ArrayList<>();
        providers.forEach( provider -> metadataProviders.add( provider ) );

        this.mapper = mapper;
    }

    @PostConstruct
    public void setupCDI()
    {
        metadataProviders = new ArrayList<>();
        if ( metadataProviderInstances != null )
        {
            metadataProviderInstances.forEach( provider -> metadataProviders.add( provider ) );
        }

        mapper.registerModule( packageSerializerModule );
    }

    public byte[] merge( final Collection<Transfer> sources, final Group group, final String path )
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.debug( "Generating merged metadata in: {}:{}", group.getKey(), path );

        InputStream stream = null;

        boolean merged = false;

        final PackageMetadata packageMetadata = new PackageMetadata();

        for ( final Transfer src : sources )
        {
            if ( !src.exists() )
            {
                continue;
            }

            try
            {
                stream = src.openInputStream();
                String content = IOUtils.toString( stream );
                logger.debug( "Adding in metadata content from: {}\n\n{}\n\n", src, content );

                PackageMetadata md = mapper.readValue( content, PackageMetadata.class );

                merged = packageMetadata.merge( md, true ) || merged;
            }
            catch ( final IOException e )
            {
                final StoreKey key = getKey( src );
                logger.error( String.format( "Cannot read metadata: %s from artifact-store: %s. Reason: %s",
                                             src.getPath(), key, e.getMessage() ), e );
            }
            finally
            {
                closeQuietly( stream );
            }
        }

        if ( metadataProviders != null )
        {
            for ( PackageMetadataProvider provider : metadataProviders )
            {
                try
                {
                    PackageMetadata toMerge = provider.getMetadata( group.getKey(), path );
                    if ( toMerge != null )
                    {
                        merged = packageMetadata.merge( toMerge, true ) || merged;
                    }
                }
                catch ( IndyWorkflowException e )
                {
                    logger.error( String.format( "Cannot read metadata: %s from metadata provider: %s. Reason: %s",
                                                 path, provider.getClass().getSimpleName(), e.getMessage() ), e );
                }
            }
        }

        if ( merged )
        {

            String output = null;
            try
            {
                output = mapper.writeValueAsString( packageMetadata );
            }
            catch ( JsonProcessingException e )
            {
                logger.error( String.format( "Cannot convert from metadata: %s to String. Reason: %s", packageMetadata,
                                             e.getMessage() ), e );
            }
            return output.getBytes();
        }

        return null;
    }
    
    public InputStream merge( final Transfer... sources )
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.debug( "[NPM] Generating merged metadata when publish" );

        InputStream stream = null;

        final PackageMetadata packageMetadata = new PackageMetadata();

        for ( final Transfer src : sources )
        {
            if ( !src.exists() )
            {
                continue;
            }

            try
            {
                stream = src.openInputStream();
                String content = IOUtils.toString( stream );
                logger.debug( "[NPM] Adding in metadata content from: {}\n\n{}\n\n", src, content );

                PackageMetadata md = mapper.readValue( content, PackageMetadata.class );

                packageMetadata.merge( md, false );
            }
            catch ( final IOException e )
            {
                final StoreKey key = getKey( src );
                logger.error( String.format( "[NPM] Cannot read metadata: %s from artifact-store: %s. Reason: %s",
                                             src.getPath(), key, e.getMessage() ), e );
            }
            finally
            {
                closeQuietly( stream );
            }
        }

        String output = null;
        try
        {
            output = mapper.writeValueAsString( packageMetadata );
        }
        catch ( JsonProcessingException e )
        {
            logger.error(
                    String.format( "[NPM] Cannot convert from metadata: %s to String. Reason: %s", packageMetadata,
                                   e.getMessage() ), e );
        }

        if ( output != null )
        {
            return IOUtils.toInputStream( output );
        }

        return null;
    }

}

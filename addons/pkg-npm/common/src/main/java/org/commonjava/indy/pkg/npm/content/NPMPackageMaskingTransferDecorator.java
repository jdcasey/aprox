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
package org.commonjava.indy.pkg.npm.content;

import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.galley.KeyedLocation;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.io.AbstractTransferDecorator;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.util.IdempotentCloseInputStream;
import org.commonjava.maven.galley.util.UrlUtils;
import org.commonjava.propulsor.metrics.MetricsManager;
import org.commonjava.propulsor.metrics.spi.TimingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.commonjava.indy.content.ContentManager.ENTRY_POINT_BASE_URI;
import static org.commonjava.indy.pkg.PackageTypeConstants.PKG_TYPE_NPM;
import static org.commonjava.indy.pkg.npm.content.DecoratorUtils.updatePackageJson;
import static org.jsoup.helper.StringUtil.isBlank;

@ApplicationScoped
public class NPMPackageMaskingTransferDecorator
                extends AbstractTransferDecorator
{
    private final Logger logger = LoggerFactory.getLogger( this.getClass() );

    @Inject
    private MetricsManager metricsManager;

    public NPMPackageMaskingTransferDecorator()
    {
    }

    @Override
    public InputStream decorateRead( final InputStream stream, final Transfer transfer, EventMetadata metadata )
                    throws IOException
    {
        Location loc = transfer.getLocation();
        if ( !( loc instanceof KeyedLocation ) )
        {
            return stream;
        }

        KeyedLocation keyedLocation = (KeyedLocation) loc;
        if ( !( PKG_TYPE_NPM.equals( keyedLocation.getKey().getPackageType() ) ) )
        {
            return stream;
        }

        logger.debug( "Masking decorator decorateRead, transfer: {}", transfer );

        if ( !( transfer.getFullPath().endsWith( "package.json" ) ) )
        {
            return stream;
        }

        String baseURI = (String) metadata.get( ENTRY_POINT_BASE_URI );
        if ( isBlank( baseURI ) )
        {
            logger.trace( "Skip masking decorator (no baseURI), metadata: {}", metadata );
            return stream;
        }

        StoreKey key = keyedLocation.getKey();
        String contextURL = UrlUtils.buildUrl( baseURI, key.getType().name(), key.getName() );
        logger.debug( "Use contextURL: {}", contextURL );
        return new PackageMaskingInputStream( stream, contextURL, metricsManager );
    }

    private static class PackageMaskingInputStream
            extends IdempotentCloseInputStream
    {
        private static final String TIMER = "io.npm.metadata.in.filter";

        final Logger logger = LoggerFactory.getLogger( this.getClass() );

        int position;

        private String contextURL;

        private MetricsManager metricsManager;

        private byte[] bytes;

        boolean masked;

        private PackageMaskingInputStream( final InputStream stream, final String contextURL,
                                           final MetricsManager metricsManager )
        {
            super( stream );
            this.contextURL = contextURL;
            this.metricsManager = metricsManager;
        }

        @Override
        public synchronized int read() throws IOException
        {
            if ( !masked )
            {
                mask( contextURL );
            }
            if ( position < bytes.length )
            {
                return bytes[position++];
            }
            return -1;
        }

        @Override
        public synchronized int read( byte[] b, int off, int len ) throws IOException
        {
            if ( !masked )
            {
                mask( contextURL );
            }
            if ( position >= bytes.length )
            {
                return -1;
            }
            int read = 0;
            for ( int i = 0; i < len; i++ )
            {
                if ( position < bytes.length )
                {
                    b[off + i] = bytes[position++];
                    read++;
                }
                else
                {
                    break;
                }
            }
            return read;
        }

        private void mask( String contextURL ) throws IOException
        {
            TimingContext timer = metricsManager == null ? null : metricsManager.time( TIMER );
            if ( timer != null )
            {
                timer.start();
            }

            try
            {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                int read;
                while ( ( read = super.read() ) >= 0 )
                {
                    bos.write( read );
                }
                byte[] rawBytes = bos.toByteArray();
                String raw = new String( rawBytes, UTF_8 );

                logger.trace( "Mask for raw:\n{}", raw );

                String s = updatePackageJson( raw, contextURL );

                logger.trace( "Masked:\n{}", s );
                bytes = s.getBytes();
                masked = true;
            }
            finally
            {
                if ( timer != null )
                {
                    timer.stop();
                }
            }
        }
    }

}

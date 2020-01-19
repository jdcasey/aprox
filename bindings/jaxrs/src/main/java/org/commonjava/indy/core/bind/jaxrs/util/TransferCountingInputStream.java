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
package org.commonjava.indy.core.bind.jaxrs.util;

import org.apache.commons.io.input.CountingInputStream;
import org.commonjava.maven.galley.util.IdempotentCloseInputStream;
import org.commonjava.propulsor.metrics.MeteringContext;
import org.commonjava.propulsor.metrics.MetricsManager;
import org.commonjava.propulsor.metrics.conf.MetricsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

import static org.commonjava.indy.IndyContentConstants.NANOS_PER_SEC;
import static org.commonjava.propulsor.metrics.MetricsConstants.METER;
import static org.commonjava.propulsor.metrics.MetricsUtils.getDefaultName;
import static org.commonjava.propulsor.metrics.MetricsUtils.getName;

public class TransferCountingInputStream
        extends IdempotentCloseInputStream
{

    private static final String TRANSFER_UPLOAD_METRIC_NAME = "indy.transferred.content.upload";

    private MetricsManager metricsManager;

    private MetricsConfig metricsConfig;

    private long size;

    protected TransferCountingInputStream( final InputStream stream )
    {
        super( new CountingInputStream( stream ) );
    }

    public TransferCountingInputStream( final InputStream stream, final MetricsManager metricsManager,
                                        final MetricsConfig metricsConfig )
    {
        this( stream );
        this.metricsManager = metricsManager;
        this.metricsConfig = metricsConfig;
    }

    @Override
    public void close()
            throws IOException
    {
        long start = System.nanoTime();
        try
        {
            CountingInputStream stream = (CountingInputStream) this.in;
            Logger logger = LoggerFactory.getLogger( getClass() );
            size = stream.getByteCount();
            logger.trace( "Reads: {} bytes", size );

            if ( metricsConfig != null && metricsManager != null )
            {
                String name = getName( metricsConfig.getInstancePrefix(), TRANSFER_UPLOAD_METRIC_NAME,
                                       getDefaultName( TransferCountingInputStream.class, "read" ), METER );

                long end = System.nanoTime();
                double elapsed = (end-start)/NANOS_PER_SEC;

                MeteringContext meter = metricsManager.getMeter( name );
                meter.mark( Math.round( stream.getByteCount() / elapsed ) );
            }
        }
        finally
        {
            super.close();
        }

    }

    long getSize()
    {
        return size;
    }
}

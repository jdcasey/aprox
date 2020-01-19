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

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.CountingOutputStream;
import org.commonjava.propulsor.metrics.MeteringContext;
import org.commonjava.propulsor.metrics.MetricsManager;
import org.commonjava.propulsor.metrics.annotation.Measure;
import org.commonjava.propulsor.metrics.conf.MetricsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.commonjava.indy.IndyContentConstants.NANOS_PER_SEC;
import static org.commonjava.propulsor.metrics.MetricsConstants.METER;
import static org.commonjava.propulsor.metrics.MetricsUtils.getDefaultName;
import static org.commonjava.propulsor.metrics.MetricsUtils.getName;

public class TransferStreamingOutput
    implements StreamingOutput
{

    private static final String TRANSFER_METRIC_NAME = "indy.transferred.content";

    private InputStream stream;

    private MetricsManager metricsManager;

    private MetricsConfig metricsConfig;

    public TransferStreamingOutput( final InputStream stream, final MetricsManager metricsManager,
                                    final MetricsConfig metricsConfig )
    {
        this.stream = stream;
        this.metricsManager = metricsManager;
        this.metricsConfig = metricsConfig;
    }

    @Override
    @Measure
    public void write( final OutputStream out )
        throws IOException, WebApplicationException
    {
        long start = System.nanoTime();
        try
        {
            CountingOutputStream cout = new CountingOutputStream( out );
            IOUtils.copy( stream, cout );

            Logger logger = LoggerFactory.getLogger( getClass() );
            logger.trace( "Wrote: {} bytes", cout.getByteCount() );

            String name = getName( metricsConfig.getInstancePrefix(), TRANSFER_METRIC_NAME,
                                   getDefaultName( TransferStreamingOutput.class, "write" ), METER );

            long end = System.nanoTime();
            double elapsed = (end-start)/NANOS_PER_SEC;

            MeteringContext meter = metricsManager.getMeter( name );
            meter.mark( Math.round( cout.getByteCount() / elapsed ) );
        }
        finally
        {
            IOUtils.closeQuietly( stream );
        }
    }

}

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
package org.commonjava.indy.bind.jaxrs.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.output.CountingOutputStream;
import org.commonjava.propulsor.metrics.MeteringContext;
import org.commonjava.propulsor.metrics.MetricsManager;
import org.commonjava.propulsor.metrics.conf.MetricsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.commonjava.propulsor.metrics.MetricsConstants.METER;
import static org.commonjava.propulsor.metrics.MetricsUtils.getDefaultName;
import static org.commonjava.propulsor.metrics.MetricsUtils.getName;

public class DTOStreamingOutput
        implements StreamingOutput
{
    private static final String TRANSFER_METRIC_NAME = "indy.transferred.dto";

    private static final double NANOS_PER_SEC = 1000000000.0;

    private final ObjectMapper mapper;

    private final Object dto;

    private final MetricsManager metricsManager;

    private final MetricsConfig metricsConfig;

    public DTOStreamingOutput( final ObjectMapper mapper, final Object dto, final MetricsManager metricsManager,
                               final MetricsConfig metricsConfig )
    {
        this.mapper = mapper;
        this.dto = dto;
        this.metricsManager = metricsManager;
        this.metricsConfig = metricsConfig;
    }

    @Override
    public String toString()
    {
        try
        {
            return mapper.writeValueAsString( dto );
        }
        catch ( JsonProcessingException e )
        {
            Logger logger = LoggerFactory.getLogger( getClass() );
            logger.error( "Could not render toString() for DTO: " + dto, e );
            return String.valueOf( dto );
        }
    }

    @Override
    public void write( final OutputStream outputStream )
            throws IOException, WebApplicationException
    {
        AtomicReference<IOException> ioe = new AtomicReference<>();
        metricsManager.wrapWithStandardMetrics( () -> {
            CountingOutputStream cout = new CountingOutputStream( outputStream );
            long start = System.nanoTime();
            try
            {
                mapper.writeValue( cout, dto );
            }
            catch ( IOException e )
            {
                ioe.set( e );
            }
            finally
            {
                Logger logger = LoggerFactory.getLogger( getClass() );
                logger.trace( "Wrote: {} bytes", cout.getByteCount() );

                String name = getName( metricsConfig.getInstancePrefix(), TRANSFER_METRIC_NAME,
                                       getDefaultName( dto.getClass().getSimpleName(), "write" ), METER );

                long end = System.nanoTime();
                double elapsed = (end-start)/NANOS_PER_SEC;

                MeteringContext meter = metricsManager.getMeter( name );
                meter.mark( Math.round( cout.getByteCount() / elapsed ) );
            }

            return null;

        }, () -> null );

        if ( ioe.get() != null )
        {
            throw ioe.get();
        }
    }
}

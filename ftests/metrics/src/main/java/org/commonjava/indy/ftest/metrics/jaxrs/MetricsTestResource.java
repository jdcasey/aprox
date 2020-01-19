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
package org.commonjava.indy.ftest.metrics.jaxrs;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import org.commonjava.indy.bind.jaxrs.IndyResources;
import org.commonjava.indy.bind.jaxrs.util.REST;
import org.commonjava.propulsor.metrics.annotation.Measure;
import org.commonjava.indy.measure.annotation.MetricNamed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.Random;

/**
 * Created by xiabai on 3/22/17.
 */
@Path( "/api/ftest/metrics/" )
@Produces( "application/json" )
@Consumes( "application/json" )
public class MetricsTestResource
        implements IndyResources
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    MetricRegistry metricRegistry;

    @GET
    @Path( "/metricRegistry/timer" )
    public Response getTimerCount()
    {
        Timer timer = metricRegistry.timer( "testTimerRequest.timer" );
        return Response.ok( timer.getCount(), MediaType.APPLICATION_JSON ).build();
    }

    @GET
    @Path( "/metricRegistry/timer/exception" )
    public Response getTimerCountWithException()
    {
        Meter meter = null;
        try
        {
            meter = metricRegistry.meter("testTimerRequestWithException.exception" );
        }
        catch ( Throwable t )
        {
            t.printStackTrace();
        }
        return Response.ok( meter.getCount(), MediaType.APPLICATION_JSON ).build();
    }

    @GET
    @Path( "/metricRegistry/meter" )
    public Response getMeterCount()
    {
        Meter meter = metricRegistry.meter( "testMeterRequest.meter" );
        return Response.ok( meter.getCount(), MediaType.APPLICATION_JSON ).build();
    }

    @GET
    @Path( "/metricRegistry/meter/exception" )
    public Response getMeterCountWithException()
    {
        Meter meter =
                metricRegistry.meter( "testMeterRequestException.exception" );
        return Response.ok( meter.getCount(), MediaType.APPLICATION_JSON ).build();
    }

    @GET
    @Path( "/timer/{isException :[a-zA-Z]+}" )
    @Measure( timers = @MetricNamed( "testTimerRequest" ),
                  exceptions = @MetricNamed( "testTimerRequestWithException" ) )
    public Response getTimer( @PathParam( "isException" ) String isException )
            throws Exception
    {
        if ( isException.equals( "true" ) )
        {
            throw new Exception( "EXPECTED: MetricsTest has a exception" );
        }
        logger.info( "call in method : MetricsTest" );
        Random random = new Random();
        Thread.sleep( random.nextInt( 100 ) );
        return Response.ok( " \"Timer: well done\"", MediaType.APPLICATION_JSON ).build();
    }

    @GET
    @Path( "/meter/{isException :[a-zA-Z]+}" )
    @Measure( meters = @MetricNamed( "testMeterRequest" ),
                  exceptions = @MetricNamed( "testMeterRequestException" ) )
    public Response getMeter( @PathParam( "isException" ) String isException )
            throws Exception
    {
        logger.info( "call in method : getMeter" );
        if ( isException.equals( "true" ) )
        {
            throw new Exception( "EXPECTED: getMeter has a exception" );
        }
        Thread.sleep( 100 );
        return Response.ok( " " + "\"Meter :well done\"", MediaType.APPLICATION_JSON ).build();
    }
}

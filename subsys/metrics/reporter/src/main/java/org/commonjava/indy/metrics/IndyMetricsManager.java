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
package org.commonjava.indy.metrics;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import org.commonjava.cdi.util.weft.ThreadContext;
import org.commonjava.indy.metrics.conf.IndyMetricsConfig;
import org.commonjava.indy.metrics.healthcheck.IndyCompoundHealthCheck;
import org.commonjava.indy.metrics.healthcheck.IndyHealthCheck;
import org.commonjava.indy.metrics.reporter.ReporterIntializer;
import org.commonjava.maven.galley.config.TransportMetricConfig;
import org.commonjava.maven.galley.model.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static com.codahale.metrics.MetricRegistry.name;
import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.commonjava.indy.IndyContentConstants.NANOS_PER_MILLISECOND;
import static org.commonjava.indy.metrics.IndyMetricsConstants.DEFAULT;
import static org.commonjava.indy.metrics.IndyMetricsConstants.EXCEPTION;
import static org.commonjava.indy.metrics.IndyMetricsConstants.SKIP_METRIC;
import static org.commonjava.indy.metrics.IndyMetricsConstants.TIMER;
import static org.commonjava.indy.metrics.IndyMetricsConstants.getDefaultName;
import static org.commonjava.indy.metrics.RequestContextHelper.CUMULATIVE_COUNTS;
import static org.commonjava.indy.metrics.RequestContextHelper.CUMULATIVE_TIMINGS;
import static org.commonjava.indy.metrics.RequestContextHelper.IS_METERED;
import static org.commonjava.indy.metrics.jvm.IndyJVMInstrumentation.registerJvmMetric;
import static org.commonjava.indy.model.core.StoreType.remote;
import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;

/**
 * Created by xiabai on 2/27/17.
 */
@ApplicationScoped
public class IndyMetricsManager
{

    public static final String METRIC_LOGGER_NAME = "org.commonjava.indy.metrics";

    private static final Logger logger = LoggerFactory.getLogger( IndyMetricsManager.class );

    @Inject
    private MetricRegistry metricRegistry;

    @Inject
    private HealthCheckRegistry healthCheckRegistry;

    @Inject
    private Instance<IndyHealthCheck> indyHealthChecks;

    @Inject
    private Instance<IndyCompoundHealthCheck> indyCompoundHealthChecks;

    @Inject
    ReporterIntializer reporter;

    @Inject
    private Instance<MetricSetProvider> metricSetProviderInstances;

    @Inject
    private IndyMetricsConfig config;

    private TransportMetricConfig transportMetricConfig;

    private Random random = new Random();

    @Produces
    public TransportMetricConfig getTransportMetricConfig()
    {
        return transportMetricConfig;
    }

    @PostConstruct
    public void init()
    {
        if ( !config.isMetricsEnabled() )
        {
            logger.info( "Indy metrics subsystem not enabled" );
            return;
        }

        logger.info( "Init metrics subsystem..." );

        registerJvmMetric( config.getNodePrefix(), metricRegistry );

        // Health checks
        indyHealthChecks.forEach( hc -> {
            logger.info( "Registering health check: {}", hc.getName() );
            healthCheckRegistry.register( hc.getName(), hc );
        } );

        indyCompoundHealthChecks.forEach( cc-> {
            Map<String, HealthCheck> healthChecks = cc.getHealthChecks();
            logger.info( "Registering {} health checks from set: {}", healthChecks.size(), cc.getClass().getSimpleName() );
            healthChecks.forEach( (name,check)->{
                logger.info( "Registering health check: {}", name );
                healthCheckRegistry.register( name, check );
            } );
        } );

        metricSetProviderInstances.forEach( ( provider ) -> provider.registerMetricSet( metricRegistry ) );
    }

    public void startReporter() throws Exception
    {
        if ( !config.isMetricsEnabled() )
        {
            return;
        }
        logger.info( "Start metrics reporters" );
        reporter.initReporter( metricRegistry );
    }

    public boolean isMetered( Supplier<Boolean> meteringOverride )
    {
        int meterRatio = config.getMeterRatio();
        if ( meterRatio <= 1 || random.nextInt() % meterRatio == 0 )
        {
            return true;
        }
        else if ( meteringOverride != null && Boolean.TRUE.equals( meteringOverride.get() ) )
        {
            return true;
        }

        return false;
    }

    public Timer getTimer( String name )
    {
        return this.metricRegistry.timer( name );
    }

    public Meter getMeter( String name )
    {
        return metricRegistry.meter( name );
    }

    public void accumulate( String name, final double elapsed )
    {
        ThreadContext ctx = ThreadContext.getContext( true );
        if ( ctx != null )
        {
            if ( !checkMetered( ctx ) )
            {
                return;
            }

            ctx.putIfAbsent( CUMULATIVE_TIMINGS, new ConcurrentHashMap<>() );
            Map<String, Double> timingMap = (Map<String, Double>) ctx.get( CUMULATIVE_TIMINGS );

            timingMap.merge( name, elapsed, ( existingVal, newVal ) -> existingVal + newVal );

            ctx.putIfAbsent( CUMULATIVE_COUNTS, new ConcurrentHashMap<>() );
            Map<String, Integer> countMap =
                    (Map<String, Integer>) ctx.get( CUMULATIVE_COUNTS );

            countMap.merge( name, 1, ( existingVal, newVal ) -> existingVal + 1 );
        }
    }

    public <T> T wrapWithStandardMetrics( final Supplier<T> method, final Supplier<String> classifier )
    {
        String name = classifier.get();
        if ( !checkMetered() || SKIP_METRIC.equals( name ) )
        {
            return method.get();
        }

        String nodePrefix = config.getNodePrefix();

        String metricName = name( nodePrefix, name );
        String startName = name( metricName, "starts"  );

        String timerName = name( metricName, TIMER );
        String errorName = name( name, EXCEPTION );
        String eClassName = null;

        Timer.Context timer = getTimer( timerName ).time();
        logger.trace( "START: {} ({})", metricName, timer );

        long start = System.nanoTime();
        try
        {
            mark( Arrays.asList( startName ) );

            return method.get();
        }
        catch ( Throwable e )
        {
            eClassName = name( name, EXCEPTION, e.getClass().getSimpleName() );
            mark( Arrays.asList( errorName, eClassName ) );

            throw e;
        }
        finally
        {
            stopTimers( Collections.singletonMap( timerName, timer ) );
            mark( Arrays.asList( metricName ) );

            double elapsed = (System.nanoTime() - start) / NANOS_PER_MILLISECOND;
            accumulate( metricName, elapsed );
        }
    }

    public boolean checkMetered()
    {
        return checkMetered( null );
    }

    public boolean checkMetered( ThreadContext ctx )
    {
        if ( ctx == null )
        {
            ctx = ThreadContext.getContext( false );
        }

        return ( ctx == null || ((Boolean) ctx.getOrDefault( IS_METERED, Boolean.TRUE ) ) );
    }

    public void stopTimers( final Map<String, Timer.Context> timers )
    {
        if ( timers != null )
        {
            timers.forEach( (name, timer) ->{
                if ( timer != null )
                {
                    timer.stop();
                }
            } );
        }
    }

    public void mark( final Collection<String> metricNames )
    {
        metricNames.forEach( metricName -> {
            getMeter( metricName ).mark();
        } );
    }

    public void addGauges( Class<?> className, String method, Map<String, Gauge<Integer>> gauges )
    {
        String defaultName = getDefaultName( className, method );
        gauges.forEach( ( k, v ) -> {
            String name = IndyMetricsConstants.getName( config.getNodePrefix(), DEFAULT, defaultName, k );
            metricRegistry.gauge( name, () -> v );
        } );
    }
}

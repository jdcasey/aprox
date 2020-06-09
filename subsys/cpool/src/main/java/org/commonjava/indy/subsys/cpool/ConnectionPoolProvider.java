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
package org.commonjava.indy.subsys.cpool;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import io.agroal.api.AgroalDataSource;
import io.agroal.api.AgroalDataSourceListener;
import io.agroal.api.AgroalDataSourceMetrics;
import io.agroal.api.configuration.AgroalDataSourceConfiguration;
import io.agroal.api.configuration.supplier.AgroalPropertiesReader;
import org.commonjava.indy.action.IndyLifecycleException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.naming.InitialContext;
import javax.naming.NamingException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import static javax.naming.Context.INITIAL_CONTEXT_FACTORY;

@ApplicationScoped
public class ConnectionPoolProvider
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private ConnectionPoolConfig config;

    @Inject
    private MetricRegistry metricRegistry;

    @Inject
    private HealthCheckRegistry healthCheckRegistry;

    public void init()
            throws IndyLifecycleException
    {
        logger.info( "Starting connection pool binding..." );

        Properties properties = System.getProperties();
        properties.setProperty( INITIAL_CONTEXT_FACTORY, CPInitialContextFactory.class.getName() );
        System.setProperties( properties );

        InitialContext ctx;
        try
        {
            ctx = new InitialContext();
        }
        catch ( NamingException e )
        {
            throw new IndyLifecycleException( "Failed to create JNDI InitialContext for binding datasources", e );
        }

        Map<String, ConnectionPoolInfo> poolConfigs = config.getPools();
        logger.info( "Creating bindings for {} pools from config: {}", poolConfigs.size(), config );
        for ( ConnectionPoolInfo poolInfo : poolConfigs.values() )
        {
            try
            {
                AgroalPropertiesReader propertiesReader = new AgroalPropertiesReader( ConnectionPoolConfig.DS_PROPERTY_PREFIX );
                AgroalDataSourceConfiguration config = propertiesReader.readProperties( poolInfo.getProperties() ).get();
                config.setMetricsEnabled( poolInfo.isUseMetrics() );
                AgroalDataSource ds = AgroalDataSource.from( config, new AgroalDataSourceLogger( poolInfo.getName() ) );

                if ( poolInfo.isUseMetrics() )
                {
                    registerMetrics(ds.getMetrics(), poolInfo.getName());
                }
                if ( poolInfo.isUseHealthChecks() )
                {
                    registerHealthChecks(ds, poolInfo.getName());
                }

                String jndiName = "java:/comp/env/jdbc/" + poolInfo.getName();
                logger.info( "Binding datasource: {}", jndiName );
                ctx.rebind( jndiName, ds );
            }
            catch ( NamingException e )
            {
                throw new IndyLifecycleException( "Failed to bind datasource: " + poolInfo.getName(), e );
            }
            catch ( SQLException e )
            {
                throw new IndyLifecycleException( "Failed to start datasource: " + poolInfo.getName(), e );
            }
        }
    }


    private void registerMetrics(AgroalDataSourceMetrics agroalMetrics, String name) {
        metricRegistry.register(MetricRegistry.name(name, "acquireCount"), (Gauge<Long>) agroalMetrics::acquireCount);
        metricRegistry.register(MetricRegistry.name(name, "creationCount"), (Gauge<Long>) agroalMetrics::creationCount);
        metricRegistry.register(MetricRegistry.name(name, "leakDetectionCount"), (Gauge<Long>) agroalMetrics::leakDetectionCount);
        metricRegistry.register(MetricRegistry.name(name, "destroyCount"), (Gauge<Long>) agroalMetrics::destroyCount);
        metricRegistry.register(MetricRegistry.name(name, "flushCount"), (Gauge<Long>) agroalMetrics::flushCount);
        metricRegistry.register(MetricRegistry.name(name, "invalidCount"), (Gauge<Long>) agroalMetrics::invalidCount);
        metricRegistry.register(MetricRegistry.name(name, "reapCount"), (Gauge<Long>) agroalMetrics::reapCount);

        metricRegistry.register(MetricRegistry.name(name, "activeCount"), (Gauge<Long>) agroalMetrics::activeCount);
        metricRegistry.register(MetricRegistry.name(name, "availableCount"), (Gauge<Long>) agroalMetrics::availableCount);
        metricRegistry.register(MetricRegistry.name(name, "maxUsedCount"), (Gauge<Long>) agroalMetrics::maxUsedCount);
        metricRegistry.register(MetricRegistry.name(name, "awaitingCount"), (Gauge<Long>) agroalMetrics::awaitingCount);
        metricRegistry.register(MetricRegistry.name(name, "blockingTimeAverage"), (Gauge<Duration>) agroalMetrics::blockingTimeAverage);
        metricRegistry.register(MetricRegistry.name(name, "blockingTimeMax"), (Gauge<Duration>) agroalMetrics::blockingTimeMax);
        metricRegistry.register(MetricRegistry.name(name, "blockingTimeTotal"), (Gauge<Duration>) agroalMetrics::blockingTimeTotal);
        metricRegistry.register(MetricRegistry.name(name, "creationTimeAverage"), (Gauge<Duration>) agroalMetrics::creationTimeAverage);
        metricRegistry.register(MetricRegistry.name(name, "creationTimeMax"), (Gauge<Duration>) agroalMetrics::creationTimeMax);
        metricRegistry.register(MetricRegistry.name(name, "creationTimeTotal"), (Gauge<Duration>) agroalMetrics::creationTimeTotal);
    }

    private void registerHealthChecks(AgroalDataSource ds, String name)
    {
        healthCheckRegistry.register(name, new HealthCheck()
        {
            @Override protected Result check()
            {
                try ( Connection con = ds.getConnection() )
                {
                    if ( con.isValid(5) )
                    {
                        return Result.healthy();
                    }
                    else
                    {
                        return Result.unhealthy( "validation check failed for DataSource %s", name );
                    }
                }
                catch ( SQLException e )
                {
                    return Result.unhealthy( e );
                }
            }
        });
    }

    private static class AgroalDataSourceLogger implements AgroalDataSourceListener
    {
        private final Logger logger;

        public AgroalDataSourceLogger(String name)
        {
            logger = LoggerFactory.getLogger( AgroalDataSource.class.getName() + ".'" + name + "'" );
        }

        @Override public void onConnectionPooled(Connection connection)
        {
            logger.debug( "Added connection {} to the pool", connection );
        }

        @Override public void onConnectionAcquire(Connection connection)
        {
            logger.debug( "Connection {} acquired", connection );
        }

        @Override public void onConnectionReturn(Connection connection)
        {
            logger.debug( "Connection {} return", connection );
        }

        @Override public void onConnectionLeak(Connection connection, Thread thread)
        {
            logger.info( "Connection {} leak. Acquired by {}", connection, thread );
        }

        @Override public void beforeConnectionValidation(Connection connection)
        {
            logger.debug( "Connection {} about to be validated", connection );
        }

        @Override public void beforeConnectionFlush(Connection connection)
        {
            logger.debug( "Connection {} removed from the pool", connection );
        }

        @Override public void beforeConnectionReap(Connection connection)
        {
            logger.debug( "Connection {} idle", connection );
        }

        @Override public void onWarning(String message)
        {
            logger.warn( message );
        }

        @Override public void onWarning(Throwable throwable)
        {
            logger.warn( "Exception", throwable );
        }

        @Override public void onInfo(String message)
        {
            logger.info( message );
        }
    }
}

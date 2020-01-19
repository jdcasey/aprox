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
package org.commonjava.indy.subsys.infinispan;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.codehaus.plexus.interpolation.PropertiesBasedValueSource;
import org.codehaus.plexus.interpolation.StringSearchInterpolator;
import org.commonjava.indy.action.IndyLifecycleException;
import org.commonjava.indy.action.ShutdownAction;
import org.commonjava.indy.conf.IndyConfiguration;
import org.commonjava.indy.subsys.infinispan.config.ISPNClusterConfiguration;
import org.commonjava.indy.subsys.infinispan.config.ISPNRemoteConfiguration;
import org.commonjava.propulsor.metrics.MetricsManager;
import org.commonjava.propulsor.metrics.conf.MetricsConfig;
import org.infinispan.Cache;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.commons.marshall.MarshallableTypeHints;
import org.infinispan.configuration.ConfigurationManager;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.configuration.parsing.ParserRegistry;
import org.infinispan.manager.DefaultCacheManager;
import org.infinispan.manager.EmbeddedCacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.Externalizable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.commonjava.indy.subsys.infinispan.metrics.IspnCheckRegistrySet.INDY_METRIC_ISPN;
import static org.commonjava.propulsor.metrics.MetricsUtils.getSupername;

/**
 * Created by jdcasey on 3/8/16.
 */
@ApplicationScoped
public class CacheProducer
        implements ShutdownAction
{
    Logger logger = LoggerFactory.getLogger( getClass() );

    private static final String ISPN_XML = "infinispan.xml";
    private static final String ISPN_CLUSTER_XML = "infinispan-cluster.xml";

    private EmbeddedCacheManager cacheManager;

    @Inject
    private ISPNRemoteConfiguration remoteConfiguration;

    @Inject
    private IndyConfiguration indyConfiguration;

    @Inject
    private MetricsManager metricsManager;

    @Inject
    private MetricsConfig metricsConfig;

    @Inject
    private ISPNClusterConfiguration clusterConfiguration;

    private Map<String, BasicCacheHandle> caches = new ConcurrentHashMap<>(); // hold embedded and remote caches

    protected CacheProducer()
    {
    }

    public CacheProducer( IndyConfiguration indyConfiguration, EmbeddedCacheManager cacheManager, ISPNRemoteConfiguration remoteConfiguration )
    {
        this.indyConfiguration = indyConfiguration;
        this.cacheManager = cacheManager;
        this.remoteConfiguration = remoteConfiguration;
    }

    @PostConstruct
    public void start()
    {
        startRemoteManager();
        startEmbeddedManager();
        startClusterManager();
    }

    private RemoteCacheManager remoteCacheManager;

    private EmbeddedCacheManager clusterCacheManager;

    private void startRemoteManager()
    {
        if ( remoteConfiguration == null || !remoteConfiguration.isEnabled() )
        {
            logger.info( "Infinispan remote configuration not enabled. Skip." );
            return;
        }

        ConfigurationBuilder builder = new ConfigurationBuilder();
        builder.addServer().host( remoteConfiguration.getRemoteServer() ).port( remoteConfiguration.getHotrodPort() );
        remoteCacheManager = new RemoteCacheManager( builder.build() );
        logger.info( "Infinispan remote cache manager started." );
    }

    private void startEmbeddedManager()
    {
        cacheManager = startCacheManager( cacheManager, ISPN_XML, false );
    }

    private void startClusterManager()
    {
        if ( clusterConfiguration == null || !clusterConfiguration.isEnabled() )
        {
            logger.info( "Infinispan cluster configuration not enabled. Skip." );
            return;
        }
        clusterCacheManager = startCacheManager( clusterCacheManager, ISPN_CLUSTER_XML, true );
    }

    private EmbeddedCacheManager startCacheManager( EmbeddedCacheManager cacheMgr, String configFile,
                                                    Boolean isCluster )
    {
        // FIXME This is just here to trigger shutdown hook init for embedded log4j in infinispan-embedded-query.
        // FIXES:
        //
        // Thread-15 ERROR Unable to register shutdown hook because JVM is shutting down.
        // java.lang.IllegalStateException: Cannot add new shutdown hook as this is not started. Current state: STOPPED
        //
        new MarshallableTypeHints().getBufferSizePredictor( CacheHandle.class );

        File confDir = indyConfiguration.getIndyConfDir();
        File ispnConf = new File( confDir, configFile );

        EmbeddedCacheManager mgr = cacheMgr;
        try(InputStream resouceStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(
                        configFile ))
        {

            String resourceStr = interpolateStrFromStream( resouceStream, "CLASSPATH:" + configFile );

            if ( ispnConf.exists() )
            {
                try (InputStream confStream = FileUtils.openInputStream( ispnConf ))
                {
                    String confStr = interpolateStrFromStream( confStream, ispnConf.getPath() );
                    mgr = mergedCachesFromConfig( mgr, confStr, "CUSTOMER" );
                    mgr = mergedCachesFromConfig( mgr, resourceStr, "CLASSPATH" );
                }
                catch ( IOException e )
                {
                    throw new RuntimeException( "Cannot read infinispan configuration from file: " + ispnConf, e );
                }
            }
            else
            {
                try
                {
                    logger.info( "Using CLASSPATH resource Infinispan configuration:\n\n{}\n\n", resourceStr );
                    if ( mgr == null )
                    {
                        mgr = new DefaultCacheManager(
                                new ByteArrayInputStream( resourceStr.getBytes( StandardCharsets.UTF_8 ) ) );
                    }

                }
                catch ( IOException e )
                {
                    throw new RuntimeException(
                                    "Failed to construct ISPN cacheManger due to CLASSPATH xml stream read error.", e );
                }
            }

            if ( isCluster )
            {
                String[] cacheNames = mgr.getCacheNames().toArray( new String[]{} );
                logger.info( "Starting cluster caches to make sure they existed: {}", Arrays.toString( cacheNames ) );
                mgr.startCaches( cacheNames );
            }
        }
        catch ( IOException e )
        {
            throw new RuntimeException(
                            "Failed to construct ISPN cacheManger due to CLASSPATH xml stream read error.", e );
        }

        return mgr;
    }

    /**
     * Get a BasicCache instance. If the remote cache is enabled, it will match the named with remote.patterns.
     * If matched, it will create/return a RemoteCache. If not matched, an embedded cache will be created/returned to the caller.
     */
    public synchronized <K, V> BasicCacheHandle<K, V> getBasicCache( String named )
    {
        BasicCacheHandle handle = caches.computeIfAbsent( named, ( k ) -> {
            if ( remoteConfiguration.isEnabled() && remoteConfiguration.isRemoteCache( k ) )
            {
                RemoteCache<K, V> cache = null;
                try
                {
                    cache = remoteCacheManager.getCache( k );
                    if ( cache == null )
                    {
                        logger.warn( "Can not get remote cache, name: {}", k );
                        return null;
                    }
                }
                catch ( Exception e )
                {
                    logger.warn( "Get remote cache failed", e );
                    return null;
                }
                logger.info( "Get remote cache, name: {}", k );
                return new RemoteCacheHandle( k, cache, metricsManager, getCacheMetricPrefix( k ) );
            }
            return null;
        } );

        if ( handle == null )
        {
            handle = getCache( named );
        }

        return handle;
    }

    /**
     * Get named cache and verify that the cache obeys our expectations for clustering.
     * There is no way to find out the runtime type of generic type parameters and we need to pass the k/v class types.
     */
    public synchronized <K, V> CacheHandle<K, V> getClusterizableCache( String named, Class<K> kClass, Class<V> vClass )
    {
        verifyClusterizable( kClass, vClass );
        return getCache( named );
    }

    private <K, V> void verifyClusterizable( Class<K> kClass, Class<V> vClass )
    {
        if ( !Serializable.class.isAssignableFrom( kClass ) && !Externalizable.class.isAssignableFrom( kClass )
                        || !Serializable.class.isAssignableFrom( vClass ) && !Externalizable.class.isAssignableFrom(
                        vClass ) )
        {
            throw new RuntimeException( kClass + " or " + vClass + " is not Serializable/Externalizable" );
        }
    }

    /**
     * Retrieve an embedded cache with a pre-defined configuration (from infinispan.xml) or the default cache configuration.
     */
    public synchronized <K, V> CacheHandle<K, V> getCache( String named )
    {
        logger.debug( "Get embedded cache, name: {}", named );
        return (CacheHandle) caches.computeIfAbsent( named, ( k ) -> {
            Cache<K, V> cache;
            if ( clusterConfiguration != null
                            && clusterConfiguration.isEnabled()
                            && clusterCacheManager.cacheExists( k ) )
            {
                cache = clusterCacheManager.getCache( k );
            }
            else
            {
                cache = cacheManager.getCache( k );
            }
            return new CacheHandle( k, cache, metricsManager, getCacheMetricPrefix( k ) );
        } );
    }

    private String getCacheMetricPrefix( String named )
    {
        return metricsManager == null ? null : getSupername( metricsConfig.getInstancePrefix(), INDY_METRIC_ISPN, named );
    }

    public synchronized Configuration getCacheConfiguration( String name )
    {
        if ( cacheManager == null )
        {
            throw new IllegalStateException( "Cannot access CacheManager. Indy seems to be in a state of shutdown." );
        }
        return cacheManager.getCacheConfiguration( name );
    }

    public synchronized Configuration getDefaultCacheConfiguration()
    {
        if ( cacheManager == null )
        {
            throw new IllegalStateException( "Cannot access CacheManager. Indy seems to be in a state of shutdown." );
        }

        return cacheManager.getDefaultCacheConfiguration();
    }

    public synchronized Configuration setCacheConfiguration( String name, Configuration config )
    {
        if ( cacheManager == null )
        {
            throw new IllegalStateException( "Cannot access CacheManager. Indy seems to be in a state of shutdown." );
        }
        return cacheManager.defineConfiguration( name, config );
    }

    @Override
    public synchronized void stop()
            throws IndyLifecycleException
    {
        logger.info( "Stopping Infinispan caches." );
        caches.forEach( ( name, cacheHandle ) -> cacheHandle.stop() );

        if ( cacheManager != null )
        {
            cacheManager.stop();
            cacheManager = null;
        }

        if ( remoteCacheManager != null )
        {
            remoteCacheManager.stop();
            remoteCacheManager = null;
        }

        if ( clusterCacheManager != null )
        {
            clusterCacheManager.stop();
            clusterCacheManager = null;
        }
    }

    @Override
    public int getShutdownPriority()
    {
        return 10;
    }

    @Override
    public String getId()
    {
        return "infinispan-caches";
    }

    private String interpolateStrFromStream( InputStream inputStream, String path )
    {
        String configuration;
        try
        {
            configuration = IOUtils.toString( inputStream );
        }
        catch ( IOException e )
        {
            throw new RuntimeException( "Cannot read infinispan configuration from : " + path, e );
        }

        StringSearchInterpolator interpolator = new StringSearchInterpolator();
        interpolator.addValueSource( new PropertiesBasedValueSource( System.getProperties() ) );

        try
        {
            configuration = interpolator.interpolate( configuration );
        }
        catch ( InterpolationException e )
        {
            throw new RuntimeException( "Cannot resolve expressions in infinispan configuration from: " + path, e );
        }
        return configuration;
    }

    /**
     * For the ISPN merging, we should involve at least two different xml config scopes here,
     * one is from indy self default resource xml, another one is from customer's config xml.
     *
     * To prevent the error of EmbeddedCacheManager instances configured with same JMX domain,
     * ISPN should enable 'allowDuplicateDomains' attribute for per GlobalConfigurationBuilder build,
     * that will cost more price there for DefaultCacheManager construct and ConfigurationBuilder build.
     *
     * Since what we need here is simply parsing xml inputStreams to the defined configurations that ISPN
     * could accept, then merging the two stream branches into a entire one.
     * What classes this method uses from ISPN are:
     * {@link ConfigurationBuilderHolder}
     * {@link ParserRegistry}
     * {@link ConfigurationManager}
     *
     * @param cacheMgr
     * @param config
     * @param path
     */
    private EmbeddedCacheManager mergedCachesFromConfig( EmbeddedCacheManager cacheMgr, String config, String path )
    {
        logger.debug( "[ISPN xml merge] cache config xml to merge:\n {}", config );
        // FIXME: here may cause ISPN000343 problem if your cache config has enabled distributed cache. Because distributed
        //       cache needs transport support, so if the cache manager does not enable it and then add this type of cache
        //       by defineConfiguration, it will report ISPN000343. So we should ensure the transport has been added by initialization.
        EmbeddedCacheManager mgr = cacheMgr;
        if ( mgr == null )
        {
            try
            {
                logger.info(
                        "Using {} resource Infinispan configuration to construct mergable cache configuration:\n\n{}\n\n", path,
                        config );
                mgr = new DefaultCacheManager(
                        new ByteArrayInputStream( config.getBytes( StandardCharsets.UTF_8 ) ) );
            }
            catch ( IOException e )
            {
                throw new RuntimeException(
                        String.format( "Failed to construct ISPN cacheManger due to %s xml stream read error.", path ),
                        e );
            }
        }

        final ConfigurationBuilderHolder holder = ( new ParserRegistry() ).parse( IOUtils.toInputStream( config ) );
        final ConfigurationManager manager = new ConfigurationManager( holder );

        final Set<String> definedCaches = mgr.getCacheNames();

        for ( String name : manager.getDefinedCaches() )
        {
            if ( definedCaches.isEmpty() || !definedCaches.contains( name ) )
            {
                logger.info( "[ISPN xml merge] Define cache: {} from {} config.", name, path );
                mgr.defineConfiguration( name, manager.getConfiguration( name, false ) );
            }
        }

        return mgr;
    }

    public EmbeddedCacheManager getCacheManager()
    {
        return cacheManager;
    }
}

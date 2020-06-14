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
package org.commonjava.indy.pathmapped.cache;

import com.datastax.driver.core.BoundStatement;
import com.datastax.driver.core.PreparedStatement;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.collect.Lists;
import org.commonjava.indy.action.IndyLifecycleException;
import org.commonjava.indy.action.StartupAction;
import org.commonjava.indy.conf.IndyConfiguration;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.subsys.cassandra.CassandraClient;
import org.commonjava.indy.subsys.infinispan.CacheHandle;
import org.commonjava.indy.subsys.infinispan.CacheProducer;
import org.commonjava.maven.galley.cache.pathmapped.PathMappedCacheProvider;
import org.commonjava.maven.galley.spi.cache.CacheProvider;
import org.commonjava.storage.pathmapped.core.PathMappedFileManager;
import org.commonjava.storage.pathmapped.model.PathMap;
import org.commonjava.storage.pathmapped.spi.PathDB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;

import static java.util.Collections.emptySet;
import static java.util.concurrent.TimeUnit.MINUTES;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.commonjava.indy.pkg.PackageTypeConstants.PKG_TYPE_MAVEN;
import static org.commonjava.indy.subsys.cassandra.util.SchemaUtils.getSchemaCreateKeyspace;
import static org.commonjava.storage.pathmapped.util.PathMapUtils.ROOT_DIR;

@ApplicationScoped
public class PathMappedMavenGACache
                implements StartupAction
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    public static final String SCANNED_STORES = "scanned-stores";

    private static final int SCAN_BATCH_SIZE = 100;

    private static final int TIMER_PERIOD_MINUTES = 60;

    // @formatter:off
    private static String getSchemaCreateTable( String keyspace )
    {
        return "CREATE TABLE IF NOT EXISTS " + keyspace + ".ga ("
                        + "ga varchar,"
                        + "stores set<text>,"
                        + "PRIMARY KEY (ga)"
                        + ");";
    }
    // @formatter:on

    @Inject
    protected IndyConfiguration config;

    @Inject
    private StoreDataManager storeDataManager;

    @Inject
    private CacheProvider cacheProvider;

    private PathMappedFileManager pathMappedFileManager;

    private PreparedStatement preparedQueryByGA, preparedStoresReduction, preparedStoresIncrement;

    @Inject
    private CassandraClient cassandraClient;

    @Inject
    private CacheProducer cacheProducer;

    private CacheHandle<String, Set<String>> inMemoryCache;

    private String gaStorePattern;

    private String keyspace;

    private Session session;

    private boolean started;

    public PathMappedMavenGACache()
    {
    }

    public PathMappedMavenGACache( final IndyConfiguration config, final CacheProducer cacheProducer,
                                   final CassandraClient cassandraClient, final StoreDataManager storeDataManager,
                                   final PathMappedCacheProvider cacheProvider )
    {
        this.config = config;
        this.cacheProducer = cacheProducer;
        this.cassandraClient = cassandraClient;
        this.storeDataManager = storeDataManager;
        this.gaStorePattern = config.getGACacheStorePattern();
        this.keyspace = config.getCacheKeyspace();
        this.cacheProvider = cacheProvider;
        init();
    }

    @PostConstruct
    public void init()
    {
        gaStorePattern = config.getGACacheStorePattern();

        if ( isBlank( gaStorePattern ) )
        {
            logger.info( "GA cache store pattern is null" );
            return;
        }

        logger.info( "Start GA cache, store pattern: {}", gaStorePattern );

        keyspace = config.getCacheKeyspace();

        session = cassandraClient.getSession( keyspace );
        if ( session == null )
        {
            logger.warn( "Get session failed, keyspace: {}", keyspace );
            return;
        }

        inMemoryCache = cacheProducer.getCache( "ga-in-memory-cache" );

        session.execute( getSchemaCreateKeyspace( keyspace ) );
        session.execute( getSchemaCreateTable( keyspace ) );

        preparedQueryByGA = session.prepare( "SELECT stores FROM " + keyspace + ".ga WHERE ga=?;" );

        preparedStoresIncrement = session.prepare( "UPDATE " + keyspace + ".ga SET stores = stores + ? WHERE ga=?;" );
        preparedStoresReduction = session.prepare( "UPDATE " + keyspace + ".ga SET stores = stores - ? WHERE ga=?;" );

        if ( cacheProvider instanceof PathMappedCacheProvider )
        {
            pathMappedFileManager = ( (PathMappedCacheProvider) cacheProvider ).getPathMappedFileManager();
        }
    }

    @Override
    public void start() throws IndyLifecycleException
    {
        if ( isBlank( gaStorePattern ) )
        {
            logger.info( "Skip GA cache start" );
            return;
        }
        fill();
        startTimer();
        started = true;
    }

    public boolean isStarted()
    {
        return started;
    }

    @Override
    public int getStartupPriority()
    {
        return 0;
    }

    @Override
    public String getId()
    {
        return "MavenGACache";
    }

    private void startTimer()
    {
        Timer timer = new Timer( true );
        timer.scheduleAtFixedRate( new TimerTask()
        {
            @Override
            public void run()
            {
                fill();
            }
        }, MINUTES.toMillis( TIMER_PERIOD_MINUTES ), MINUTES.toMillis( TIMER_PERIOD_MINUTES ) );
    }

    public void fill()
    {
        Set<String> matched; // matched stores
        try
        {
            matched = getMatchedStores();
        }
        catch ( IndyDataException e )
        {
            logger.error( "Failed to get matched stores", e );
            return;
        }

        if ( matched.isEmpty() )
        {
            logger.info( "No matched stores" );
            return;
        }

        logger.info( "Fill cache, matched stores: {}", matched );

        // find scanned stores
        Set<String> scanned = getScannedStores();

        // find not-scanned stores, notScanned = matched - scanned
        List<String> notScanned = new ArrayList<>( matched );
        notScanned.removeAll( scanned );

        if ( !notScanned.isEmpty() )
        {
            List<List<String>> batches = Lists.partition( notScanned, SCAN_BATCH_SIZE );
            batches.forEach( batch -> {
                boolean success = scanAndUpdate( batch );
                if ( !success )
                {
                    logger.warn( "Scan failed for: {}", batch ); // just log it, timer task will try the failed later
                }
            } );
            scanned = getScannedStores(); // refresh scanned
        }

        // find deleted stores and remove from 'scanned-stores'
        Set<String> deleted = new HashSet<>( scanned );
        deleted.removeAll( matched );
        if ( !deleted.isEmpty() )
        {
            logger.info( "Find deleted stores, deleted: {}", deleted );
            reduce( SCANNED_STORES, deleted, false );
        }
    }

    private Set<String> getMatchedStores() throws IndyDataException
    {
        Set<String> matched = storeDataManager.query()
                                              .packageType( PKG_TYPE_MAVEN )
                                              .getAllHostedRepositories()
                                              .stream()
                                              .filter( hosted -> hosted.getName().matches( gaStorePattern ) )
                                              .map( hostedRepository -> hostedRepository.getKey().getName() )
                                              .collect( Collectors.toSet() );
        return matched;
    }

    private boolean scanAndUpdate( Collection<String> notScanned )
    {
        logger.info( "Scan and update, notScanned: {}", notScanned );

        Set<String> completed = new HashSet<>();
        Map<String, Set<String>> gaMap = new HashMap<>(); // key: gaPath, value: repos set
        try
        {
            scan( notScanned, gaMap, completed );
        }
        catch ( Exception ex )
        {
            logger.error( "Failed to scan: ", ex );
            return false;
        }

        logger.debug( "Scan complete, completed: {}, gaMap: {}", completed, gaMap );
        gaMap.forEach( ( ga, stores ) -> update( ga, stores ) );
        update( SCANNED_STORES, completed );
        return true;
    }

    private void update( String ga, Set<String> set )
    {
        BoundStatement bound = preparedStoresIncrement.bind();
        bound.setSet( 0, set );
        bound.setString( 1, ga );
        session.execute( bound );
        inMemoryCache.remove( ga ); // clear to force reloading
    }

    public void reduce( String ga, Set<String> set, boolean isAsync )
    {
        BoundStatement bound = preparedStoresReduction.bind();
        bound.setSet( 0, set );
        bound.setString( 1, ga );
        if ( isAsync )
        {
            session.executeAsync( bound );
        }
        else
        {
            session.execute( bound );
        }
        inMemoryCache.remove( ga ); // clear to force reloading
    }

    /**
     * Scan the stores to find all GAs.
     * @param notScanned
     * @param gaMap
     * @param completed
     */
    private void scan( final Collection<String> notScanned, final Map<String, Set<String>> gaMap, final Set<String> completed )
    {
        PathDB pathDB = pathMappedFileManager.getPathDB();
        notScanned.forEach( storeName -> {
            Set<String> gaSet = new HashSet<>();
            pathDB.traverse( "maven:hosted:" + storeName, ROOT_DIR, pathMap -> {
                String gaPath = getGAPath( pathMap );
                if ( isNotBlank( gaPath ) )
                {
                    gaSet.add( gaPath );
                }
            }, 0, PathDB.FileType.file );

            gaSet.forEach( ga -> {
                gaMap.computeIfAbsent( ga, k -> new HashSet<>() ).add( storeName );
            } );
            logger.info( "Scan result, store: {}, gaSet: {}", storeName, gaSet );
            completed.add( storeName );
        } );
    }

    /**
     * Get GA path from pathMap obj. If it is pom file, get parent's parent.
     */
    private static String getGAPath( PathMap pathMap )
    {
        String ret = null;
        String fileName = pathMap.getFilename();
        if ( fileName.endsWith( ".pom" ) )
        {
            String parent = pathMap.getParentPath();
            if ( isNotBlank( parent ) )
            {
                Path ga = Paths.get( parent ).getParent();
                if ( ga != null )
                {
                    ret = ga.toString();
                    if ( ret.startsWith( "/" ) )
                    {
                        ret = ret.substring( 1 ); // remove the leading '/'
                    }
                }
            }
        }
        return ret;
    }

    public Set<String> getScannedStores()
    {
        return getStoresContaining( SCANNED_STORES );
    }

    /**
     * Get stores contain the target gaPath. It checks the inMemoryCache first. If not found, query db
     * and update inMemoryCache.
     */
    public Set<String> getStoresContaining( String gaPath )
    {
        Set<String> ret = inMemoryCache.get( gaPath );
        if ( ret != null )
        {
            return ret;
        }
        // query db
        BoundStatement bound = preparedQueryByGA.bind( gaPath );
        ResultSet result = session.execute( bound );
        Row row = result.one();
        if ( row != null )
        {
            ret = row.getSet( 0, String.class );
        }
        else
        {
            ret = emptySet();
        }
        inMemoryCache.put( gaPath, ret );
        return ret;
    }

    /**
     * Check if any candidate matches. If no one matches, this is a signal to skip the GA filter.
     */
    public boolean matchAny( List<ArtifactStore> concreteStores )
    {
        if ( isBlank( gaStorePattern ) )
        {
            return false;
        }
        for ( ArtifactStore store : concreteStores )
        {
            if ( store.getPackageType().equals( PKG_TYPE_MAVEN ) && store.getType() == StoreType.hosted
                            && store.getName().matches( gaStorePattern ) )
            {
                return true;
            }
        }
        return false;
    }
}

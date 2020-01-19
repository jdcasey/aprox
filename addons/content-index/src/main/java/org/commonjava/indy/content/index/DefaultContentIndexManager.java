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
package org.commonjava.indy.content.index;

import org.commonjava.indy.action.IndyLifecycleException;
import org.commonjava.indy.action.ShutdownAction;
import org.commonjava.indy.content.index.conf.ContentIndexConfig;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.propulsor.metrics.annotation.Measure;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.subsys.infinispan.BasicCacheHandle;
import org.commonjava.indy.subsys.infinispan.CacheHandle;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.spi.io.SpecialPathManager;
import org.infinispan.Cache;
import org.infinispan.query.Search;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Created by jdcasey on 5/2/16.
 */
@ApplicationScoped
@Default
public class DefaultContentIndexManager
        implements ContentIndexManager, ShutdownAction
{
    private static final int ITERATE_RESULT_SIZE = 1000;

    private final Logger logger = LoggerFactory.getLogger( this.getClass() );

    @Inject
    private StoreDataManager storeDataManager;

    @Inject
    private SpecialPathManager specialPathManager;

    @ContentIndexCache
    @Inject
    private CacheHandle<IndexedStorePath, IndexedStorePath> contentIndex;

    //FIXME: Seems no cache register this listener?
    @Inject
    private NFCContentListener listener;

    @Inject
    private Instance<PackageIndexingStrategy> indexingStrategyComponents;

    @Inject
    private ContentIndexConfig config;

    private Map<String, PackageIndexingStrategy> indexingStrategies;

    private QueryFactory queryFactory;

    protected DefaultContentIndexManager()
    {
    }

    public DefaultContentIndexManager( StoreDataManager storeDataManager, SpecialPathManager specialPathManager,
                                CacheHandle<IndexedStorePath, IndexedStorePath> contentIndex,
                                Map<String, PackageIndexingStrategy> indexingStrategies )
    {
        this.storeDataManager = storeDataManager;
        this.specialPathManager = specialPathManager;
        this.contentIndex = contentIndex;
        this.indexingStrategies = indexingStrategies;
    }

    @PostConstruct
    public void constructed()
    {
        if ( !config.isEnabled() )
        {
            logger.debug( "Content indexing is disabled." );
            return;
        }

        if ( indexingStrategyComponents != null )
        {
            Map<String, PackageIndexingStrategy> strats = new HashMap<>();
            indexingStrategyComponents.forEach( comp->{
                strats.put( comp.getPackageType(), comp );
            } );

            this.indexingStrategies = Collections.unmodifiableMap( strats );
        }

        contentIndex.executeCache( (cache) -> {
            cache.addListener( listener );
            queryFactory = Search.getQueryFactory( (Cache) cache ); // Obtain a query factory for the cache
//            maxResultSetSize = config.getNfcMaxResultSetSize();
            return null;
        } );
    }

    @Override
    public String getId()
    {
        return "Indy ContentIndexManager";
    }


    @Override
    public void stop()
            throws IndyLifecycleException
    {
        if ( !config.isEnabled() )
        {
            logger.debug( "Content indexing is disabled." );
            return;
        }

        logger.debug( "Shutdown index cache" );
        contentIndex.stop();
    }

    @Override
    public int getShutdownPriority()
    {
        return 95;
    }

    @Override
    @Measure
    public boolean removeIndexedStorePath( String rawPath, StoreKey key, Consumer<IndexedStorePath> pathConsumer )
    {
        if ( !config.isEnabled() )
        {
            logger.debug( "Content indexing is disabled." );
            return false;
        }

        String path = getStrategyPath( key, rawPath );
        IndexedStorePath topPath = new IndexedStorePath( key, path );
        logger.trace( "Attempting to remove indexed path: {}", topPath );
        if ( contentIndex.remove( topPath ) != null )
        {
            if ( pathConsumer != null )
            {
                pathConsumer.accept( topPath );
            }
            return true;
        }

        logger.trace( "Remove index (NOT FOUND), key: {}", topPath );
        return false;
    }

    public String getStrategyPath( final StoreKey key, final String rawPath )
    {
        if ( !config.isEnabled() )
        {
            logger.debug( "Content indexing is disabled." );
            return rawPath;
        }

        PackageIndexingStrategy strategy = indexingStrategies.get( key.getPackageType() );
        if ( strategy == null )
        {
            logger.trace( "Cannot find indexing strategy for package-type: {}. Using raw path for indexing.",
                          key.getPackageType() );

            return rawPath;
        }

        return strategy.getIndexPath( rawPath );
    }

    @Override
    @Measure
    public void deIndexStorePath( final StoreKey key, final String rawPath )
    {
        if ( !config.isEnabled() )
        {
            logger.debug( "Content indexing is disabled." );
            return;
        }

        String path = getStrategyPath( key, rawPath );
        IndexedStorePath toRemove = new IndexedStorePath( key, path );
        IndexedStorePath val = contentIndex.remove( toRemove );
        logger.trace( "De index{}, key: {}", ( val == null ? " (NOT FOUND)" : "" ), toRemove );
    }

    @Override
    @Measure
    public StoreKey getIndexedStoreKey( final StoreKey key, final String rawPath )
    {
        if ( !config.isEnabled() )
        {
            logger.debug( "Content indexing is disabled." );
            return null;
        }

        String path = getStrategyPath( key, rawPath );
        IndexedStorePath ispKey = new IndexedStorePath( key, path );
        IndexedStorePath val = contentIndex.get( ispKey );
        logger.trace( "Get index{}, key: {}", ( val == null ? " (NOT FOUND)" : "" ), ispKey );
        if ( val == null )
        {
            return null;
        }
        StoreKey ret = val.getOriginStoreKey();
        if ( ret == null )
        {
            ret = val.getStoreKey(); // for self-to-self index
        }
        return ret;
    }

    @Override
    @Measure
    public void indexTransferIn( Transfer transfer, StoreKey...topKeys )
    {
        if ( !config.isEnabled() )
        {
            logger.debug( "Content indexing is disabled." );
            return;
        }

        if ( transfer != null && transfer.exists() )
        {
            StoreKey key = LocationUtils.getKey( transfer );
            String path = getStrategyPath( key, transfer.getPath() );
            indexPathInStores( path, key, topKeys );
        }
    }

    /**
     * When we store or retrieve content, index it for faster reference next time.
     */
    @Override
    @Measure
    public void indexPathInStores( String rawPath, StoreKey originKey, StoreKey... topKeys )
    {
        if ( !config.isEnabled() )
        {
            logger.debug( "Content indexing is disabled." );
            return;
        }

        String path = getStrategyPath( originKey, rawPath );

        IndexedStorePath origin = new IndexedStorePath( originKey, path );
        logger.trace( "Indexing path: {} in: {}", path, originKey );
        contentIndex.put( origin, origin ); // self-to-self index

        Set<StoreKey> keySet = new HashSet<>( Arrays.asList( topKeys ) );
        keySet.forEach( ( key ) -> {
            IndexedStorePath isp = new IndexedStorePath( key, originKey, path );
            logger.trace( "Indexing path: {} in: {} via member: {}", path, key, originKey );
            contentIndex.put( isp, isp );
        } );
    }

    @Override
    @Measure
    public void clearAllIndexedPathInStore( ArtifactStore store )
    {
        if ( !config.isEnabled() )
        {
            logger.debug( "Content indexing is disabled." );
            return;
        }

        StoreKey sk = store.getKey();

        long total = iterateRemove( () -> queryFactory.from( IndexedStorePath.class )
                                                      .maxResults( ITERATE_RESULT_SIZE )
                                                      .having( "packageType" )
                                                      .eq( sk.getPackageType() )
                                                      .and()
                                                      .having( "storeType" )
                                                      .eq( sk.getType().name() )
                                                      .and()
                                                      .having( "storeName" )
                                                      .eq( sk.getName() )
                                                      .toBuilder()
                                                      .build() );

        logger.trace( "Cleared all indices with group: {}, size: {}", sk, total );
    }

    @Override
    @Measure
    public void clearAllIndexedPathWithOriginalStore( ArtifactStore originalStore )
    {
        if ( !config.isEnabled() )
        {
            logger.debug( "Content indexing is disabled." );
            return;
        }

        StoreKey osk = originalStore.getKey();

        long total = iterateRemove( () -> queryFactory.from( IndexedStorePath.class )
                                                      .maxResults( ITERATE_RESULT_SIZE )
                                                      .having( "packageType" )
                                                      .eq( osk.getPackageType() )
                                                      .and()
                                                      .having( "originStoreType" )
                                                      .eq( osk.getType().name() )
                                                      .and()
                                                      .having( "originStoreName" )
                                                      .eq( osk.getName() )
                                                      .toBuilder()
                                                      .build() );

        logger.trace( "Cleared all indices with origin: {}, size: {}", osk, total );
    }

    private long iterateRemove( final Supplier<Query> queryFunction )
    {
        long total = 0;
        Query query;
        long last = -1;
        while( last != 0 )
        {
            query = queryFunction.get();

                    List<IndexedStorePath> all = query.list();
            all.forEach( ( key ) -> {
                logger.debug("Removing from content index: {}", key);
                contentIndex.remove( key );
            } );

            last = all.size();
            total += last;
        }

        return total;
    }

    @Override
    @Measure
    public void clearAllIndexedPathInStoreWithOriginal( ArtifactStore store, ArtifactStore originalStore )
    {
        if ( !config.isEnabled() )
        {
            logger.debug( "Content indexing is disabled." );
            return;
        }

        StoreKey sk = store.getKey();
        StoreKey osk = originalStore.getKey();

        long total = iterateRemove( () -> queryFactory.from( IndexedStorePath.class )
                                                      .maxResults( ITERATE_RESULT_SIZE )
                                                      .having( "packageType" )
                                                      .eq( osk.getPackageType() )
                                                      .and()
                                                      .having( "storeType" )
                                                      .eq( sk.getType().name() )
                                                      .and()
                                                      .having( "storeName" )
                                                      .eq( sk.getName() )
                                                      .and()
                                                      .having( "originStoreType" )
                                                      .eq( osk.getType().name() )
                                                      .and()
                                                      .having( "originStoreName" )
                                                      .eq( osk.getName() )
                                                      .toBuilder()
                                                      .build() );

        logger.trace( "Cleared all indices with origin: {} and group: {}, size: {}", osk, sk, total );
    }

    /**
     * <b>NOT Recursive</b>. This assumes you've recursed the group membership structure beforehand, using
     * {@link StoreDataManager#query()#getGroupsAffectedBy(Collection)} to find the set of {@link Group} instances for which
     * the path should be cleared.
     */
    @Override
    @Measure
    public void clearIndexedPathFrom( String rawPath, Set<Group> groups, Consumer<IndexedStorePath> pathConsumer )
    {
        if ( !config.isEnabled() )
        {
            logger.debug( "Content indexing is disabled." );
            return;
        }

        if ( groups == null || groups.isEmpty() )
        {
            return;
        }

        logger.debug( "Clearing path: '{}' from content index and storage of: {}", rawPath, groups );
        groups.forEach( (group)->{
            String path = getStrategyPath( group.getKey(), rawPath );
            removeIndexedStorePath( path, group.getKey(), pathConsumer );
        } );
    }

}

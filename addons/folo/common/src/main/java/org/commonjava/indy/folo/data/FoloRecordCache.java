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
package org.commonjava.indy.folo.data;

import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.folo.change.FoloBackupListener;
import org.commonjava.indy.folo.change.FoloExpirationWarningListener;
import org.commonjava.indy.folo.model.StoreEffect;
import org.commonjava.indy.folo.model.TrackedContent;
import org.commonjava.indy.folo.model.TrackedContentEntry;
import org.commonjava.indy.folo.model.TrackingKey;
import org.commonjava.propulsor.metrics.annotation.Measure;
import org.commonjava.indy.measure.annotation.MetricNamed;
import org.commonjava.indy.subsys.infinispan.CacheHandle;
import org.infinispan.Cache;
import org.infinispan.commons.api.BasicCache;
import org.infinispan.query.Search;
import org.infinispan.query.dsl.Query;
import org.infinispan.query.dsl.QueryBuilder;
import org.infinispan.query.dsl.QueryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static org.commonjava.indy.measure.annotation.MetricNamed.DEFAULT;

@ApplicationScoped
public class FoloRecordCache
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @FoloInprogressCache
    @Inject
    private CacheHandle<TrackedContentEntry, TrackedContentEntry> inProgressRecordCache;

    @FoloSealedCache
    @Inject
    private CacheHandle<TrackingKey, TrackedContent> sealedRecordCache;

    protected FoloRecordCache()
    {
    }

    @Inject
    private FoloBackupListener foloBackupListener;

    @Inject
    private FoloExpirationWarningListener expirationWarningListener;

    @PostConstruct
    private void init()
    {
        sealedRecordCache.executeCache( (cache) -> {
            cache.addListener( foloBackupListener );
            return null;
        } );

        inProgressRecordCache.executeCache( (cache) ->{
            cache.addListener( expirationWarningListener );
            return null;
        } );
    }

    public FoloRecordCache( final Cache<TrackedContentEntry, TrackedContentEntry> inProgressRecordCache,
                            final Cache<TrackingKey, TrackedContent> sealedRecordCache )
    {
        this.inProgressRecordCache = new CacheHandle("folo-in-progress", inProgressRecordCache);
        this.sealedRecordCache = new CacheHandle( "folo-sealed", sealedRecordCache );
    }

    /**
     * Add a new artifact upload/download item to given affected store within a tracked-content record. If the tracked-content record doesn't exist,
     * or doesn't contain the specified affected store, values will be created on-demand.
     * @param entry The TrackedContentEntry which will be cached
     * @return True if a new record was stored, otherwise false
     */
    @Measure( timers = @MetricNamed( DEFAULT ) )
    public synchronized boolean recordArtifact( final TrackedContentEntry entry )
            throws FoloContentException,IndyWorkflowException
    {
        if ( sealedRecordCache.containsKey( entry.getTrackingKey() ) )
        {
            throw new FoloContentException( "Tracking record: {} is already sealed!", entry.getTrackingKey() );
        }

        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.debug( "Adding tracking entry: {}", entry );
        return inProgressRecordCache.executeCache( (cache)->{
            TrackedContentEntry existing = cache.get( entry );
            if ( existing != null )
            {
                existing.merge( entry );
                cache.put( existing, existing );
            }
            else
            {
                cache.put( entry, entry );
            }

            return true;
        } );
    }

    @Measure( timers = @MetricNamed( DEFAULT ) )
    public synchronized void delete( final TrackingKey key )
    {
        sealedRecordCache.remove( key );
        inProgressByTrackingKey( key, (qb, ch)->{
            qb.build().list().forEach( item -> ch.execute( cache -> cache.remove( item ) ) );
            return false;
        } );
    }

    public synchronized void replaceTrackingRecord( final TrackedContent record )
    {
        sealedRecordCache.put( record.getKey(), record );
    }

    public synchronized boolean hasRecord( final TrackingKey key )
    {
        return hasSealedRecord( key ) || hasInProgressRecord( key );
    }

    public synchronized boolean hasSealedRecord( final TrackingKey key )
    {
        return sealedRecordCache.containsKey( key );
    }

    @Measure( timers = @MetricNamed( DEFAULT ) )
    public synchronized boolean hasInProgressRecord( final TrackingKey key )
    {
        return !sealedRecordCache.containsKey( key ) && inProgressByTrackingKey( key, (qb, cacheHandle)->qb.build().getResultSize() > 0);
    }

    public synchronized TrackedContent get( final TrackingKey key )
    {
        return sealedRecordCache.get( key );
    }

    @Measure( timers = @MetricNamed( DEFAULT ) )
    public TrackedContent seal( final TrackingKey trackingKey )
    {
        TrackedContent record = sealedRecordCache.get( trackingKey );

        Logger logger = LoggerFactory.getLogger( getClass() );
        if ( record != null )
        {
            logger.debug( "Tracking record: {} already sealed! Returning sealed record.", trackingKey );
            return record;
        }

        logger.debug( "Listing unsealed tracking record entries for: {}...", trackingKey );
        return inProgressByTrackingKey( trackingKey, (qb, cacheHandle)-> {
            Query query = qb.build();
            List<TrackedContentEntry> results = query.list();
            TrackedContent created = null;
            if ( results != null )
            {
                logger.debug( "Adding {} entries to record: {}", results.size(), trackingKey );
                Set<TrackedContentEntry> uploads = new TreeSet<>();
                Set<TrackedContentEntry> downloads = new TreeSet<>();
                results.forEach( ( result ) -> {
                    if ( StoreEffect.DOWNLOAD == result.getEffect() )
                    {
                        downloads.add( result );
                    }
                    else if ( StoreEffect.UPLOAD == result.getEffect() )
                    {
                        uploads.add( result );
                    }
                    logger.trace( "Removing in-progress entry: {}", result );
                    inProgressRecordCache.remove( result );
                } );
                created = new TrackedContent( trackingKey, uploads, downloads );
            }

            logger.debug( "Sealing record for: {}", trackingKey );
            sealedRecordCache.put( trackingKey, created );
            return created;
        });
    }

    public Set<TrackingKey> getInProgressTrackingKey()
    {
        return inProgressRecordCache.execute( BasicCache::keySet )
                                    .stream()
                                    .map( TrackedContentEntry::getTrackingKey )
                                    .collect( Collectors.toSet() );
    }

    public Set<TrackingKey> getSealedTrackingKey()
    {
        return sealedRecordCache.execute( BasicCache::keySet );
    }

    public Set<TrackedContent> getSealed()
    {
        return sealedRecordCache.execute( BasicCache::entrySet ).stream().map( (et) -> et.getValue() ).collect( Collectors.toSet() );
    }

    private <R> R inProgressByTrackingKey( final TrackingKey key, final BiFunction<QueryBuilder, CacheHandle<TrackedContentEntry, TrackedContentEntry>, R> operation )
    {
        return inProgressRecordCache.executeCache( ( cache ) -> {
            QueryFactory queryFactory = Search.getQueryFactory( cache );
            QueryBuilder qb = queryFactory.from( TrackedContentEntry.class )
                                             .having( "trackingKey.id" )
                                             .eq( key.getId() )
                                             .toBuilder();
            // FIXME: Ordering breaks the query parser (it expects a LPAREN for some reason, and adding it to the string below doesn't work)
//                                             .orderBy( "index", SortOrder.ASC );

            return operation.apply( qb, inProgressRecordCache );
        } );
    }

    public void addSealedRecord( TrackedContent record )
    {
        sealedRecordCache.put( record.getKey(), record );
    }
}

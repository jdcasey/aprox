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
package org.commonjava.indy.core.expire;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.commonjava.indy.action.IndyLifecycleException;
import org.commonjava.indy.action.ShutdownAction;
import org.commonjava.indy.cluster.IndyNode;
import org.commonjava.indy.cluster.LocalIndyNodeProvider;
import org.commonjava.indy.conf.IndyConfiguration;
import org.commonjava.indy.core.conf.IndySchedulerConfig;
import org.commonjava.indy.core.expire.cache.ScheduleCache;
import org.commonjava.indy.core.expire.cache.ScheduleEventLockCache;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor;
import org.commonjava.indy.spi.pkg.ContentAdvisor;
import org.commonjava.indy.spi.pkg.ContentQuality;
import org.commonjava.indy.subsys.infinispan.CacheHandle;
import org.commonjava.indy.subsys.infinispan.CacheKeyMatcher;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.SpecialPathInfo;
import org.commonjava.maven.galley.spi.io.SpecialPathManager;
import org.infinispan.container.entries.CacheEntry;
import org.infinispan.metadata.Metadata;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryCreated;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryExpired;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryRemoved;
import org.infinispan.notifications.cachelistener.event.CacheEntryCreatedEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryExpiredEvent;
import org.infinispan.notifications.cachelistener.event.CacheEntryRemovedEvent;
import org.infinispan.notifications.cachemanagerlistener.annotation.ViewChanged;
import org.infinispan.notifications.cachemanagerlistener.event.ViewChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Event;
import javax.enterprise.inject.Any;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static org.commonjava.indy.change.EventUtils.fireEvent;
import static org.commonjava.indy.core.change.StoreEnablementManager.DISABLE_TIMEOUT;
import static org.commonjava.indy.core.change.StoreEnablementManager.TIMEOUT_USE_DEFAULT;

/**
 * A ScheduleManager is used to do the schedule time out jobs for the {@link ArtifactStore} to do some time-related jobs, like
 * removing useless artifacts. It used ISPN cache timeout mechanism to implement this type of function.
 * This class is also acted as a cache listener to handle the cache expiration job event to distribute the expiration
 * info through CDI event for the real actor to do the expiration work.
 */
@SuppressWarnings( "RedundantThrows" )
@ApplicationScoped
@Listener(clustered = true)
public class ScheduleManager
        implements ShutdownAction
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    public static final String PAYLOAD = "payload";

    public static final String ANY = "__ANY__";

    public static final String CONTENT_JOB_TYPE = "CONTENT";

    public static final String JOB_TYPE = "JOB_TYPE";

    public static final String SCHEDULE_TIME = "SCHEDULE_TIME";

    public static final String SCHEDULE_UUID = "SCHEDULE_UUID";

    @Inject
    private StoreDataManager dataManager;

    @Inject
    private IndyConfiguration config;

    @Inject
    private IndyObjectMapper objectMapper;

    @Inject
    private IndySchedulerConfig schedulerConfig;

    @Inject
    private SpecialPathManager specialPathManager;

    @Inject
    @ScheduleCache
    private CacheHandle<ScheduleKey, Map> scheduleCache;

    @Inject
    @ScheduleEventLockCache
    private CacheHandle<ScheduleKey, IndyNode> scheduleEventLockCache;

    @Inject
    @Any
    private Instance<ContentAdvisor> contentAdvisor;

    @Inject
    private Event<SchedulerEvent> eventDispatcher;

    @Inject
    private LocalIndyNodeProvider nodeHolder;

    @PostConstruct
    public void init()
    {
        if ( !schedulerConfig.isEnabled() )
        {
            logger.info( "Scheduler disabled. Skipping initialization" );
            return;
        }

        // register this producer as schedule cache listener
        registerCacheListener( scheduleCache );
    }

    private <K,V> void registerCacheListener(CacheHandle<K, V> cache){
        cache.executeCache( c->{
            c.addListener( ScheduleManager.this );
            return null;
        } );
    }

    public void rescheduleSnapshotTimeouts( final HostedRepository deploy )
            throws IndySchedulerException
    {
        if ( !schedulerConfig.isEnabled() )
        {
            logger.debug( "Scheduler disabled." );
            return;
        }

        int timeout = -1;
        if ( deploy.isAllowSnapshots() && deploy.getSnapshotTimeoutSeconds() > 0 )
        {
            timeout = deploy.getSnapshotTimeoutSeconds();
        }

        if ( timeout > 0 )
        {
            final Set<ScheduleKey> canceled =
                    cancelAllBefore( new StoreKeyMatcher( deploy.getKey(), CONTENT_JOB_TYPE ), timeout );

            for ( final ScheduleKey key : canceled )
            {
                final String path = key.getName();
                final StoreKey sk = storeKeyFrom( key.groupName() );

                scheduleContentExpiration( sk, path, timeout );
            }
        }
    }

    public void rescheduleProxyTimeouts( final RemoteRepository repo )
            throws IndySchedulerException
    {
        if ( !schedulerConfig.isEnabled() )
        {
            logger.debug( "Scheduler disabled." );
            return;
        }

        int timeout = -1;
        if ( !repo.isPassthrough() && repo.getCacheTimeoutSeconds() > 0 )
        {
            timeout = repo.getCacheTimeoutSeconds();
        }
        else if ( repo.isPassthrough() )
        {
            timeout = config.getPassthroughTimeoutSeconds();
        }

        if ( timeout > 0 )
        {
            final Set<ScheduleKey> canceled =
                    cancelAllBefore( new StoreKeyMatcher( repo.getKey(), CONTENT_JOB_TYPE ), timeout );
            for ( final ScheduleKey key : canceled )
            {
                final String path = key.getName();
                final StoreKey sk = storeKeyFrom( key.groupName() );

                scheduleContentExpiration( sk, path, timeout );
            }
        }
    }

    public void setProxyTimeouts( final StoreKey key, final String path )
            throws IndySchedulerException
    {
        if ( !schedulerConfig.isEnabled() )
        {
            logger.debug( "Scheduler disabled." );
            return;
        }

        RemoteRepository repo = null;
        try
        {
            repo = (RemoteRepository) dataManager.getArtifactStore( key );
        }
        catch ( final IndyDataException e )
        {
            logger.error( String.format( "Failed to retrieve store for: %s. Reason: %s", key, e.getMessage() ), e );
        }

        if ( repo == null )
        {
            return;
        }

        int timeout = config.getPassthroughTimeoutSeconds();
        final ConcreteResource resource = new ConcreteResource( LocationUtils.toLocation( repo ), path );
        final SpecialPathInfo info = specialPathManager.getSpecialPathInfo( resource, key.getPackageType() );
        if ( !repo.isPassthrough() )
        {
            if ( ( info != null && info.isMetadata() ) && repo.getMetadataTimeoutSeconds() >= 0 )
            {
                if ( repo.getMetadataTimeoutSeconds() == 0 )
                {
                    logger.debug( "Using default metadata timeout for: {}", resource );
                    timeout = config.getRemoteMetadataTimeoutSeconds();
                }
                else
                {
                    logger.debug( "Using metadata timeout for: {}", resource );
                    timeout = repo.getMetadataTimeoutSeconds();
                }
            }
            else
            {
                if ( info == null )
                {
                    logger.debug( "No special path info for: {}", resource );
                }
                else
                {
                    logger.debug( "{} is a special path, but not metadata.", resource );
                }

                timeout = repo.getCacheTimeoutSeconds();
            }
        }

        if ( timeout > 0 )
        {
            //            logger.info( "[PROXY TIMEOUT SET] {}/{}; {}", repo.getKey(), path, new Date( System.currentTimeMillis()
            //                + timeout ) );
            cancel( new StoreKeyMatcher( key, CONTENT_JOB_TYPE ), path );

            scheduleContentExpiration( key, path, timeout );
        }
    }

    public void scheduleForStore( final StoreKey key, final String jobType, final String jobName,
                                               final Object payload, final int startSeconds )
            throws IndySchedulerException
    {
        if ( !schedulerConfig.isEnabled() )
        {
            logger.debug( "Scheduler disabled." );
            return;
        }

        final Map<String, Object> dataMap = new HashMap<>( 3 );
        dataMap.put( JOB_TYPE, jobType );
        try
        {
            dataMap.put( PAYLOAD, objectMapper.writeValueAsString( payload ) );
        }
        catch ( final JsonProcessingException e )
        {
            throw new IndySchedulerException( "Failed to serialize JSON payload: " + payload, e );
        }

        dataMap.put( SCHEDULE_TIME, System.currentTimeMillis() );

        final ScheduleKey cacheKey = new ScheduleKey( key, jobType, jobName );

        scheduleCache.execute( cache -> cache.put( cacheKey, dataMap, startSeconds, TimeUnit.SECONDS ) );
        logger.debug( "Scheduled for the key {} with timeout: {} seconds", cacheKey, startSeconds );
    }

    public void scheduleContentExpiration( final StoreKey key, final String path,
                                                        final int timeoutSeconds )
            throws IndySchedulerException
    {
        if ( !schedulerConfig.isEnabled() )
        {
            logger.debug( "Scheduler disabled." );
            return;
        }

        logger.info( "Scheduling timeout for: {} in: {} in: {} seconds (at: {}).", path, key, timeoutSeconds,
                     new Date( System.currentTimeMillis() + ( timeoutSeconds * 1000 ) ) );

        scheduleForStore( key, CONTENT_JOB_TYPE, path, new ContentExpiration( key, path ), timeoutSeconds );
    }

    public void setSnapshotTimeouts( final StoreKey key, final String path )
            throws IndySchedulerException
    {
        if ( !schedulerConfig.isEnabled() )
        {
            logger.debug( "Scheduler disabled." );
            return;
        }

        HostedRepository deploy = null;
        try
        {
            final ArtifactStore store = dataManager.getArtifactStore( key );
            if ( store == null )
            {
                return;
            }

            if ( store instanceof HostedRepository )
            {
                deploy = (HostedRepository) store;
            }
            else if ( store instanceof Group )
            {
                final Group group = (Group) store;
                deploy = findDeployPoint( group );
            }
        }
        catch ( final IndyDataException e )
        {
            logger.error( String.format( "Failed to retrieve deploy point for: %s. Reason: %s", key, e.getMessage() ),
                          e );
        }

        if ( deploy == null )
        {
            return;
        }

        final ContentAdvisor advisor = StreamSupport.stream(
                Spliterators.spliteratorUnknownSize( contentAdvisor.iterator(), Spliterator.ORDERED ), false )
                                                    .filter( Objects::nonNull )
                                                    .findFirst()
                                                    .orElse( null );
        final ContentQuality quality = advisor == null ? null : advisor.getContentQuality( path );
        if ( quality == null )
        {
            return;
        }

        if ( ContentQuality.SNAPSHOT == quality && deploy.getSnapshotTimeoutSeconds() > 0 )
        {
            final int timeout = deploy.getSnapshotTimeoutSeconds();

            //            //            logger.info( "[SNAPSHOT TIMEOUT SET] {}/{}; {}", deploy.getKey(), path, new Date( timeout ) );
            //            cancel( new StoreKeyMatcher( key, CONTENT_JOB_TYPE ), path );

            scheduleContentExpiration( key, path, timeout );
        }
    }

    public void rescheduleDisableTimeout( final StoreKey key )
            throws IndySchedulerException
    {
        if ( !schedulerConfig.isEnabled() )
        {
            logger.debug( "Scheduler disabled." );
            return;
        }

        ArtifactStore store = null;
        try
        {
            store = dataManager.getArtifactStore( key );
        }
        catch ( final IndyDataException e )
        {
            logger.error( String.format( "Failed to retrieve store for: %s. Reason: %s", key, e.getMessage() ), e );
        }

        if ( store == null )
        {
            return;
        }

        int timeout = store.getDisableTimeout();
        if ( timeout == TIMEOUT_USE_DEFAULT )
        {
            // case TIMEOUT_USE_DEFAULT: will use default timeout configuration
            timeout = config.getStoreDisableTimeoutSeconds();
        }

        // No need to cancel as the job will be cancelled immediately after the re-enable in StoreEnablementManager
//        final Set<ScheduleKey> canceled =
//                cancelAllBefore( new StoreKeyMatcher( store.getKey(), DISABLE_TIMEOUT ),
//                                 timeout );
//        logger.info( "Cancel disable timeout for stores:{}", canceled );

        if ( timeout > TIMEOUT_USE_DEFAULT && store.isDisabled() )
        {
            final StoreKey sk = store.getKey();
            logger.debug( "Set/Reschedule disable timeout for store:{}", sk );
            scheduleForStore( sk, DISABLE_TIMEOUT, DISABLE_TIMEOUT, sk, timeout );
        }
        // Will never consider the TIMEOUT_NEVER_DISABLE case here, will consider this in the calling object(StoreEnablementManager)
    }

    private HostedRepository findDeployPoint( final Group group )
            throws IndyDataException
    {
        for ( final StoreKey key : group.getConstituents() )
        {
            if ( StoreType.hosted == key.getType() )
            {
                return (HostedRepository) dataManager.getArtifactStore( key );
            }
            else if ( StoreType.group == key.getType() )
            {
                final Group grp = (Group) dataManager.getArtifactStore( key );
                final HostedRepository dp = findDeployPoint( grp );
                if ( dp != null )
                {
                    return dp;
                }
            }
        }

        return null;
    }

    public Set<ScheduleKey> cancelAllBefore( final CacheKeyMatcher<ScheduleKey> matcher,
                                                          final long timeout )
            throws IndySchedulerException
    {
        if ( !schedulerConfig.isEnabled() )
        {
            logger.debug( "Scheduler disabled." );
            return Collections.emptySet();
        }

        final Set<ScheduleKey> canceled = new HashSet<>();

        final Date to = new Date( System.currentTimeMillis() + ( timeout * 1000 ) );
        matcher.matches( scheduleCache ).forEach( key -> {
            final Date nextFire = getNextExpireTime( key );
            if ( nextFire == null || !nextFire.after( to ) )
            {
                // former impl uses quartz and here use the unscheduleJob method, but ISPN does not have similar
                // op, so directly did a remove here.
                removeCache( key );
                logger.debug( "Removed cache job for key: {}, before {}", key, to );
                canceled.add( key );
            }
        } );

        return canceled;
    }

    public Set<ScheduleKey> cancelAll( final CacheKeyMatcher<ScheduleKey> matcher )
            throws IndySchedulerException
    {
        return cancel( matcher, ANY );
    }

    public Set<ScheduleKey> cancel( final CacheKeyMatcher<ScheduleKey> matcher, final String name )
            throws IndySchedulerException
    {
        if ( !schedulerConfig.isEnabled() )
        {
            logger.debug( "Scheduler disabled." );
            return Collections.emptySet();
        }

        Set<ScheduleKey> canceled = new HashSet<>();
        final Set<ScheduleKey> keys = matcher.matches( scheduleCache );
        if ( keys != null && !keys.isEmpty() )
        {
            Set<ScheduleKey> unscheduled = null;
            if ( ANY.equals( name ) )
            {
                for ( final ScheduleKey k : keys )
                {
                    removeCache( k );
                }
                unscheduled = keys;
            }
            else
            {
                for ( final ScheduleKey k : keys )
                {
                    if ( k.getName().equals( name ) )
                    {
                        removeCache( k );
                        unscheduled = Collections.singleton( k );
                        break;
                    }
                }
            }

            if ( unscheduled != null )
            {
                canceled = unscheduled;
            }
        }

        return canceled;
    }

    public Expiration findSingleExpiration( final StoreKeyMatcher matcher )
    {
        if ( !schedulerConfig.isEnabled() )
        {
            logger.debug( "Scheduler disabled." );
            return null;
        }

        final Set<ScheduleKey> keys = matcher.matches( scheduleCache );
        if ( keys != null && !keys.isEmpty() )
        {
            ScheduleKey triggerKey = keys.iterator().next();
            return toExpiration( triggerKey );
        }

        return null;
    }

    public ExpirationSet findMatchingExpirations( final CacheKeyMatcher<ScheduleKey> matcher )
    {
        if ( !schedulerConfig.isEnabled() )
        {
            logger.debug( "Scheduler disabled." );
            return null;
        }

        final Set<ScheduleKey> keys = matcher.matches( scheduleCache );
        Set<Expiration> expirations = new HashSet<>( keys.size() );
        if ( !keys.isEmpty() )
        {
            for ( ScheduleKey key : keys )
            {
                expirations.add( toExpiration( key ) );
            }
        }

        return new ExpirationSet( expirations );
    }

    private Expiration toExpiration( final ScheduleKey cacheKey )
    {
        return new Expiration( cacheKey.groupName(), cacheKey.getName(), getNextExpireTime( cacheKey ) );
    }

    private Date getNextExpireTime( final ScheduleKey cacheKey )
    {

        return scheduleCache.executeCache( cache -> {
            final CacheEntry entry = cache.getAdvancedCache().getCacheEntry( cacheKey );
            if ( entry != null )
            {
                final Metadata metadata = entry.getMetadata();
                long expire = metadata.lifespan();
                final long startTimeInMillis = (Long)scheduleCache.get( cacheKey ).get( SCHEDULE_TIME );
                return calculateNextExpireTime( expire, startTimeInMillis );
            }
            return null;
        } );

    }

    static Date calculateNextExpireTime( final long expire, final long start )
    {
        if ( expire > 1 )
        {
            final long duration = System.currentTimeMillis() - start;
            if ( duration < expire )
            {
                final long nextTimeInMillis = expire - duration + System.currentTimeMillis();
                final LocalDateTime time =
                        Instant.ofEpochMilli( nextTimeInMillis ).atZone( ZoneId.systemDefault() ).toLocalDateTime();
                return Date.from( time.atZone( ZoneId.systemDefault() ).toInstant() );
            }
        }
        return null;
    }

    public ScheduleKey findFirstMatchingTrigger( final CacheKeyMatcher<ScheduleKey> matcher )
    {
        if ( !schedulerConfig.isEnabled() )
        {
            logger.debug( "Scheduler disabled." );
            return null;
        }

        final Set<ScheduleKey> keys = matcher.matches( scheduleCache );
        if ( keys != null && !keys.isEmpty() )
        {
            return keys.iterator().next();
        }

        return null;
    }

    public static String groupName( final StoreKey key, final String jobType )
    {
        return key.toString() + groupNameSuffix( jobType );
    }

    public static String groupNameSuffix( final String jobType )
    {
        return "#" + jobType;
    }

    public static StoreKey storeKeyFrom( final String group )
    {
        final String[] parts = group.split( "#" );
        if ( parts.length > 1 )
        {
            final Logger logger = LoggerFactory.getLogger( ScheduleManager.class );
            StoreKey storeKey = null;
            try
            {
                storeKey = StoreKey.fromString( parts[0] );
            }
            catch ( IllegalArgumentException e )
            {
                logger.warn( "Not a store key for string: {}", parts[0] );
            }

            //TODO this part of code may be obsolete, will need further check then remove
            if ( storeKey == null )
            {
                logger.info( "Not a store key for string: {}, will parse as store type", parts[0] );
                final StoreType type = StoreType.get( parts[0] );
                if ( type != null )
                {
                    storeKey = new StoreKey( MavenPackageTypeDescriptor.MAVEN_PKG_KEY, type, parts[1] );
                }
            }
            return storeKey;
        }

        return null;
    }

    @Override
    public String getId()
    {
        return "Indy Scheduler";
    }

    @Override
    public int getShutdownPriority()
    {
        return 95;
    }

    @Override
    public void stop()
            throws IndyLifecycleException
    {
        if ( !schedulerConfig.isEnabled() )
        {
            logger.debug( "Scheduler disabled." );
            return;
        }

        scheduleCache.stop();
    }

    private void removeCache( final ScheduleKey cacheKey )
    {
        if ( scheduleCache.containsKey( cacheKey ) )
        {
            scheduleCache.remove( cacheKey );
        }
    }

    @CacheEntryCreated
    public void scheduled( final CacheEntryCreatedEvent<ScheduleKey, Map> e )
    {
        if ( e == null )
        {
            logger.error( "[FATAL]The infinispan cache created event for indy schedule manager is null.", new NullPointerException( "CacheEntryCreatedEvent is null" ) );
            return;
        }

        if ( !e.isPre() )
        {
            final ScheduleKey expiredKey = e.getKey();
            final Map expiredContent = e.getValue();
            if ( expiredKey != null && expiredContent != null )
            {
                logger.debug( "Expiration Created: {}", expiredKey );
                final String type = (String) expiredContent.get( ScheduleManager.JOB_TYPE );
                final String data = (String) expiredContent.get( ScheduleManager.PAYLOAD );
                fireEvent( eventDispatcher, new SchedulerScheduleEvent( type, data ) );
            }
        }
    }

    @CacheEntryExpired
    public void expired( CacheEntryExpiredEvent<ScheduleKey, Map> e )
    {
        if ( e == null )
        {
            logger.error( "[FATAL]The infinispan cache expired event for indy schedule manager is null.", new NullPointerException( "CacheEntryExpiredEvent is null" ) );
            return;
        }

        if ( !e.isPre() )
        {
            final ScheduleKey expiredKey = e.getKey();
/*
            if ( scheduleEventLockCache.containsKey( expiredKey ) )
            {
                logger.info( "Another instance {} is still handling expiration event for {}", expiredKey,
                             scheduleEventLockCache.containsKey( expiredKey ) );
                return;
            }
*/
            final Map expiredContent = e.getValue();
            if ( expiredKey != null && expiredContent != null )
            {
                logger.debug( "EXPIRED: {}", expiredKey );
                final String type = (String) expiredContent.get( ScheduleManager.JOB_TYPE );
                final String data = (String) expiredContent.get( ScheduleManager.PAYLOAD );
                if ( logger.isInfoEnabled() && ScheduleManager.CONTENT_JOB_TYPE.equals( type ) )
                {
                    ContentExpiration expiration;
                    try
                    {
                        expiration = objectMapper.readValue( data, ContentExpiration.class );
                        final StoreKey key = expiration.getKey();
                        final String path = expiration.getPath();
                        logger.info( "Expiring: {} in: {} at: {}.", path, key,
                                      new Date( System.currentTimeMillis() ) );
                    }
                    catch ( final IOException ioe )
                    {
                        // ignore
                    }
                }
                fireEvent( eventDispatcher, new SchedulerTriggerEvent( type, data ) );
/*
                scheduleEventLockCache.executeCache( cache -> cache.put( expiredKey, nodeHolder.getLocalIndyNode(),
                                                                         schedulerConfig.getClusterLockExpiration(),
                                                                         TimeUnit.SECONDS ) );
*/
            }
        }
    }

    @CacheEntryRemoved
    public void cancelled( CacheEntryRemovedEvent<ScheduleKey, Map> e )
    {
        if ( e == null )
        {
            logger.error( "[FATAL]The infinispan cache removed event for indy schedule manager is null.", new NullPointerException( "CacheEntryRemovedEvent is null" ) );
            return;
        }
        logger.trace( "Cache removed to cancel scheduling, Key is {}, Value is {}", e.getKey(), e.getValue() );
    }

    // This method is only used to check clustered schedule expire cache nodes topology changing
    @ViewChanged
    public void checkClusterChange( ViewChangedEvent event )
    {
        logger.debug( "Schedule cache cluster members changed, old members: {}; new members: {}", event.getOldMembers(),
                      event.getNewMembers() );
    }

}

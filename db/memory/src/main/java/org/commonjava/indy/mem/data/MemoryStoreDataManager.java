/**
 * Copyright (C) 2011 Red Hat, Inc. (jdcasey@commonjava.org)
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
package org.commonjava.indy.mem.data;

import org.commonjava.indy.audit.ChangeSummary;
import org.commonjava.indy.change.event.ArtifactStoreUpdateType;
import org.commonjava.indy.conf.DefaultIndyConfiguration;
import org.commonjava.indy.conf.IndyConfiguration;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.NoOpStoreEventDispatcher;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.data.StoreDataManagerQuery;
import org.commonjava.indy.data.StoreEventDispatcher;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.util.ApplicationStatus;
import org.commonjava.maven.galley.event.EventMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Alternative;
import javax.inject.Inject;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Stream;

import static org.commonjava.indy.model.core.StoreType.hosted;

@ApplicationScoped
@Alternative
public class MemoryStoreDataManager
        implements StoreDataManager
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private final Map<StoreKey, ArtifactStore> stores = new ConcurrentHashMap<>();

    private final Map<StoreKey, ReentrantLock> opLocks = new WeakHashMap<>();

    //    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private StoreEventDispatcher dispatcher;

    @Inject
    private IndyConfiguration config;

    protected MemoryStoreDataManager()
    {
    }

    public MemoryStoreDataManager( final boolean unitTestUsage )
    {
        this.dispatcher = new NoOpStoreEventDispatcher();
        this.config = new DefaultIndyConfiguration();
    }

    public MemoryStoreDataManager( final StoreEventDispatcher dispatcher, final IndyConfiguration config )
    {
        this.dispatcher = dispatcher;
        this.config = config;
    }

    @Override
    public StoreDataManagerQuery<ArtifactStore> query()
    {
        return new MemoryStoreDataManagerQuery<>( this );
    }

    @Override
    public ArtifactStore getArtifactStore( final StoreKey key )
            throws IndyDataException
    {
        return stores.get( key );
    }

    @Override
    public boolean storeArtifactStore( final ArtifactStore store, final ChangeSummary summary,
                                       final boolean skipIfExists, final boolean fireEvents,
                                       final EventMetadata eventMetadata )
            throws IndyDataException
    {
        return store( store, summary, skipIfExists, fireEvents, eventMetadata );
    }

    protected void preStore( final ArtifactStore store, final ArtifactStore original, final ChangeSummary summary,
                             final boolean exists, final boolean fireEvents, final EventMetadata eventMetadata )
            throws IndyDataException
    {
        if ( dispatcher != null && isStarted() && fireEvents )
        {
            logger.debug( "Firing store pre-update event for: {} (originally: {})", store, original );
            dispatcher.updating( exists ? ArtifactStoreUpdateType.UPDATE : ArtifactStoreUpdateType.ADD, eventMetadata,
                                 Collections.singletonMap( store, original ) );

            if ( exists )
            {
                if ( store.isDisabled() && !original.isDisabled() )
                {
                    dispatcher.disabling( eventMetadata, store );
                }
                else if ( !store.isDisabled() && original.isDisabled() )
                {
                    dispatcher.enabling( eventMetadata, store );
                }
            }
        }
    }

    protected void postStore( final ArtifactStore store, final ArtifactStore original, final ChangeSummary summary,
                              final boolean exists, final boolean fireEvents, final EventMetadata eventMetadata )
            throws IndyDataException
    {
        if ( dispatcher != null && isStarted() && fireEvents )
        {
            logger.debug( "Firing store post-update event for: {} (originally: {})", store, original );
            dispatcher.updated( exists ? ArtifactStoreUpdateType.UPDATE : ArtifactStoreUpdateType.ADD, eventMetadata,
                                Collections.singletonMap( store, original ) );

            if ( exists )
            {
                if ( store.isDisabled() && !original.isDisabled() )
                {
                    dispatcher.disabled( eventMetadata, store );
                }
                else if ( !store.isDisabled() && original.isDisabled() )
                {
                    dispatcher.enabled( eventMetadata, store );
                }
            }
        }
    }

    protected void preDelete( final ArtifactStore store, final ChangeSummary summary, final boolean fireEvents,
                              final EventMetadata eventMetadata )
            throws IndyDataException
    {
        if ( dispatcher != null && isStarted() && fireEvents )
        {
            dispatcher.deleting( eventMetadata, store );
        }
    }

    protected void postDelete( final ArtifactStore store, final ChangeSummary summary, final boolean fireEvents,
                               final EventMetadata eventMetadata )
            throws IndyDataException
    {
        if ( dispatcher != null && isStarted() && fireEvents )
        {
            dispatcher.deleted( eventMetadata, store );
        }
    }

    @Override
    public void deleteArtifactStore( final StoreKey key, final ChangeSummary summary,
                                     final EventMetadata eventMetadata )
            throws IndyDataException
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        ReentrantLock opLock = getOpLock( key );
        try
        {
            logger.info( "DELETE operation starting for store: {}", key );
            opLock.lock();

            final ArtifactStore store = stores.get( key );
            if ( store == null )
            {
                logger.warn( "No store found for: {}", key );
                return;
            }

            if ( isReadonly( store ) )
            {
                throw new IndyDataException( ApplicationStatus.METHOD_NOT_ALLOWED.code(),
                                             "The store {} is readonly. If you want to delete this store, please modify it to non-readonly",
                                             store.getKey() );
            }

            preDelete( store, summary, true, eventMetadata );

            ArtifactStore removed = stores.remove( key );
            logger.info( "REMOVED store: {}", removed );

            postDelete( store, summary, true, eventMetadata );
        }
        finally
        {
            opLock.unlock();
            logger.trace( "Delete operation complete: {}", key );
        }
    }

    @Override
    public boolean isReadonly( final ArtifactStore store )
    {

        if ( store != null )
        {
            if ( store.getKey().getType() == hosted && ( (HostedRepository) store ).isReadonly() )
            {
                return true;
            }
            //TODO: currently we only support to check hosted readonly here, to make the hosted has ability to prevent from
            //      unexpected removing of both files and repo itself. This method may be expand to other repos like remote
            //      or group in the future to support some other functions, like remote repo's "deploy-through"
        }
        return false;
    }

    @Override
    public boolean isReadonly( final StoreKey storeKey )
    {
        return isReadonly( stores.get( storeKey ) );
    }

    @Override
    public void install()
            throws IndyDataException
    {
    }

    @Override
    public void clear( final ChangeSummary summary )
            throws IndyDataException
    {
        stores.clear();
    }

    @Override
    public Set<ArtifactStore> getAllArtifactStores()
            throws IndyDataException
    {
        return new HashSet<>( stores.values() );
    }

    @Override
    public Stream<ArtifactStore> streamArtifactStores()
            throws IndyDataException
    {
        return getAllArtifactStores().stream();
    }

    @Override
    public Map<StoreKey, ArtifactStore> getArtifactStoresByKey()
    {
        return new HashMap<>( stores );
    }

    @Override
    public boolean hasArtifactStore( final StoreKey key )
    {
        return stores.containsKey( key );
    }

    @Override
    public void reload()
            throws IndyDataException
    {
    }

    @Override
    public boolean isStarted()
    {
        return true;
    }

    private boolean store( final ArtifactStore store, final ChangeSummary summary, final boolean skipIfExists,
                           final boolean fireEvents, final EventMetadata eventMetadata )
            throws IndyDataException
    {
        ReentrantLock opLock = getOpLock( store.getKey() );
        try
        {
            opLock.lock();

            ArtifactStore original = stores.get( store.getKey() );
            if ( original == store )
            {
                // if they're the same instance, warn that preUpdate events may not work correctly!
                logger.warn(
                        "Storing changes on existing instance of: {}! You forgot to call {}.copyOf(), so preUpdate events may not accurately reflect before/after differences for this change!",
                        store, store.getClass().getSimpleName() );
            }

            if ( !skipIfExists || original == null )
            {
                preStore( store, original, summary, original != null, fireEvents, eventMetadata );
                final ArtifactStore old = stores.put( store.getKey(), store );
                try
                {
                    postStore( store, original, summary, original != null, fireEvents, eventMetadata );
                    return true;
                }
                catch ( final IndyDataException e )
                {
                    logger.error( "postStore() failed for: {}. Rolling back to old value: {}", store, old );
                    stores.put( old.getKey(), old );
                }
            }

            return false;
        }
        finally
        {
            opLock.unlock();
        }
    }

    private ReentrantLock getOpLock( StoreKey key )
    {
        ReentrantLock opLock;
        synchronized ( opLocks )
        {
            opLock = opLocks.computeIfAbsent( key, k -> new ReentrantLock() );
        }

        return opLock;
    }

}

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
package org.commonjava.indy.db.metered;

import org.commonjava.indy.data.ArtifactStoreQuery;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.propulsor.metrics.MetricsManager;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Stream;

public class MeasuringStoreQuery<T extends ArtifactStore>
        implements ArtifactStoreQuery<T>
{
    private final ArtifactStoreQuery<ArtifactStore> query;

    private final MetricsManager metricsManager;

    public MeasuringStoreQuery( final ArtifactStoreQuery<ArtifactStore> query, final MetricsManager metricsManager )
    {
        this.query = query;
        this.metricsManager = metricsManager;
    }

    @Override
    public ArtifactStoreQuery<T> rewrap( final StoreDataManager manager )
    {
        return new MeasuringStoreQuery<T>( query.rewrap( manager ), metricsManager );
    }

    @Override
    public ArtifactStoreQuery<T> packageType( final String packageType )
            throws IndyDataException
    {
        query.packageType( packageType );
        return this;
    }

    @Override
    public <C extends ArtifactStore> ArtifactStoreQuery<C> storeType( final Class<C> storeCls )
    {
        query.storeType( storeCls );
        return (ArtifactStoreQuery<C>) this;
    }

    @Override
    public ArtifactStoreQuery<T> storeTypes( final StoreType... types )
    {
        query.storeTypes( types );
        return this;
    }

    @Override
    public ArtifactStoreQuery<T> concreteStores()
    {
        query.concreteStores();
        return this;
    }

    @Override
    public ArtifactStoreQuery<T> enabledState( final Boolean enabled )
    {
        query.enabledState( enabled );
        return this;
    }

    @Override
    public boolean isEmpty()
    {
        return metricsManager.wrapWithStandardMetrics( () -> query.isEmpty(), () -> "isEmpty" );
    }

    @Override
    public Stream<T> stream()
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        Stream<T> result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return (Stream<T>) query.stream();
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "stream" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public Stream<T> stream( final Predicate<ArtifactStore> filter )
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        Stream<T> result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return (Stream<T>) query.stream( filter );
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "stream-with-filter" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public Stream<StoreKey> keyStream()
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        Stream<StoreKey> result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return query.keyStream();
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "keyStream" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public Stream<StoreKey> keyStream( final Predicate<StoreKey> filterPredicate )
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        Stream<StoreKey> result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return query.keyStream();
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "keyStream-with-filter" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public List<T> getAll()
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        List<T> result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return (List<T>) query.getAll();
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "getAll" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public List<T> getAll( final Predicate<ArtifactStore> filter )
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        List<T> result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return (List<T>) query.getAll( filter );
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "getAll-with-filter" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public List<T> getAllByDefaultPackageTypes()
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        List<T> result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return (List<T>) query.getAllByDefaultPackageTypes();
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "getAllByDefaultPackageTypes" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public T getByName( final String name )
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        T result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return (T) query.getByName( name );
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "getByName" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public boolean containsByName( final String name )
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        boolean result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return query.containsByName( name );
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "containsByName" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public Set<Group> getGroupsContaining( final StoreKey storeKey )
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        Set<Group> result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return query.getGroupsContaining( storeKey );
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "getGroupsContaining" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public List<RemoteRepository> getRemoteRepositoryByUrl( final String url )
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        List<RemoteRepository> result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return query.getRemoteRepositoryByUrl( url );
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "getRemoteRepositoryByUrl" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public List<ArtifactStore> getOrderedConcreteStoresInGroup( final String groupName )
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        List<ArtifactStore> result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return query.getOrderedConcreteStoresInGroup( groupName );
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "getOrderedConcreteStoresInGroup" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public List<ArtifactStore> getOrderedStoresInGroup( final String groupName )
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        List<ArtifactStore> result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return query.getOrderedStoresInGroup( groupName );
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "getOrderedStoresInGroup" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public Set<Group> getGroupsAffectedBy( final StoreKey... keys )
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        Set<Group> result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return query.getGroupsAffectedBy( keys );
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "getGroupsAffectedBy-varargs" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public Set<Group> getGroupsAffectedBy( final Collection<StoreKey> keys )
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        Set<Group> result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return query.getGroupsAffectedBy( keys );
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "getGroupsAffectedBy-collection" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public List<RemoteRepository> getAllRemoteRepositories()
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        List<RemoteRepository> result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return query.getAllRemoteRepositories();
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "getAllRemoteRepositories" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public List<HostedRepository> getAllHostedRepositories()
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        List<HostedRepository> result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return query.getAllHostedRepositories();
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "getAllHostedRepositories" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public List<Group> getAllGroups()
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        List<Group> result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return query.getAllGroups();
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "getAllGroups" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public RemoteRepository getRemoteRepository( final String name )
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        RemoteRepository result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return query.getRemoteRepository( name );
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "getRemoteRepository" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public HostedRepository getHostedRepository( final String name )
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        HostedRepository result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return query.getHostedRepository( name );
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "getHostedRepository" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public Group getGroup( final String name )
            throws IndyDataException
    {
        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        Group result = metricsManager.wrapWithStandardMetrics( ()->{
            try
            {
                return query.getGroup( name );
            }
            catch ( IndyDataException e )
            {
                errorRef.set( e );
            }

            return null;
        }, ()-> "getGroup" );

        IndyDataException error = errorRef.get();
        if ( error != null )
        {
            throw error;
        }

        return result;
    }

    @Override
    public ArtifactStoreQuery<T> noPackageType()
    {
        query.noPackageType();
        return this;
    }
}

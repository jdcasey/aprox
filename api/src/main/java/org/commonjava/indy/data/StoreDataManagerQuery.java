package org.commonjava.indy.data;

import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * This query interface is intended to be reusable across any {@link StoreDataManager} implementation. It contains logic
 * for working with the {@link ArtifactStore}s contained in the StoreDataManager, but this logic is not tied directly to
 * any data manager implementation. Separating these methods out and providing a single {@link StoreDataManager#query()}
 * method to provide access to an instance of this class drastically simplifies the task of implementing a new type of
 * data manager.
 * <p>
 * This class is intended to function as a fluent api. It does keep state on packageType, storeType(s), and
 * enabled / disabled selection. However, methods that pertain to specific types of ArtifactStore (indicated by their
 * names) DO NOT change the internal state of the query instance on which they are called.
 * <p>
 * Created by jdcasey on 5/10/17.
 */
public interface StoreDataManagerQuery<T extends ArtifactStore>
{
    StoreDataManagerQuery<T> packageType( String packageType )
            throws IndyDataException;

    <C extends ArtifactStore> StoreDataManagerQuery<C> storeType( Class<C> storeCls );

    StoreDataManagerQuery<T> storeTypes( StoreType... types );

    StoreDataManagerQuery<T> concreteStores();

    StoreDataManagerQuery<T> enabledState( Boolean enabled );

    List<T> getAll()
            throws IndyDataException;

    Stream<T> stream()
            throws IndyDataException;

    Stream<T> stream( Predicate<ArtifactStore> filter )
            throws IndyDataException;

    List<T> getAll( Predicate<ArtifactStore> filter )
            throws IndyDataException;

    T getByName( String name )
            throws IndyDataException;

    boolean containsByName( String name )
            throws IndyDataException;

    Set<Group> getGroupsContaining( StoreKey storeKey )
            throws IndyDataException;

    RemoteRepository getRemoteRepositoryByUrl( String url )
            throws IndyDataException;

    List<ArtifactStore> getOrderedConcreteStoresInGroup( String groupName )
            throws IndyDataException;

    List<ArtifactStore> getOrderedStoresInGroup( String groupName )
            throws IndyDataException;

    Set<Group> getGroupsAffectedBy( StoreKey... keys )
            throws IndyDataException;

    Set<Group> getGroupsAffectedBy( Collection<StoreKey> keys )
            throws IndyDataException;
}

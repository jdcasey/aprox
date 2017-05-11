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
package org.commonjava.indy.data;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.commonjava.indy.audit.ChangeSummary;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.maven.galley.event.EventMetadata;

/**
 * Data manager used to access and manipulate the configurations for {@link ArtifactStore} instances.
 * @author jdcasey
 *
 */
public interface StoreDataManager
{

    String EVENT_ORIGIN = "event-origin";

    /* DEPRECATED, MAVEN-SPECIFIC METHODS */
    /**
     * Return the {@link HostedRepository} instance corresponding to the given name.
     */
    @Deprecated
    HostedRepository getHostedRepository( final String name )
            throws IndyDataException;

    /**
     * Return the {@link RemoteRepository} instance corresponding to the given name.
     */
    @Deprecated
    RemoteRepository getRemoteRepository( final String name )
            throws IndyDataException;

    /**
     * Return the {@link Group} instance corresponding to the given name.
     */
    @Deprecated
    Group getGroup( final String name )
            throws IndyDataException;

    /**
     * Return the full list of {@link ArtifactStore} instances of a given {@link StoreType} (hosted, remote, or group) available on the system.
     */
    @Deprecated
    List<? extends ArtifactStore> getAllArtifactStores( StoreType type )
            throws IndyDataException;

    /**
     * For a {@link Group} with the given name, return (<b>IN ORDER</b>) the list of
     * non-{@link Group} {@link ArtifactStore} instances that are members of the {@link Group}.
     * <br/>
     * <b>NOTE:</b> If any of the group's members are themselves {@link Group}'s, the method
     * recurses and substitutes that group's place in the list with the ordered, concrete stores
     * it contains.
     *
     * @param enabledOnly include only enabled stores
     */
    @Deprecated
    List<ArtifactStore> getOrderedConcreteStoresInGroup( final String groupName, boolean enabledOnly )
            throws IndyDataException;

    /**
     * For a {@link Group} with the given name, return (<b>IN ORDER</b>) the list of
     * non-{@link Group} {@link ArtifactStore} instances that are members of the {@link Group}.
     * <br/>
     * <b>NOTE:</b> This method does <b>not</b> perform recursion to substitute concrete stores in place
     * of any groups in the list. Groups that are members are returned along with the rest of the membership.
     *
     * @param enabledOnly include only enabled stores
     */
    @Deprecated
    List<ArtifactStore> getOrderedStoresInGroup( final String groupName, boolean enabledOnly )
            throws IndyDataException;

    /**
     * Return true if the system contains a {@link RemoteRepository} with the given name; false otherwise.
     */
    @Deprecated
    boolean hasRemoteRepository( String name );

    /**
     * Return true if the system contains a {@link Group} with the given name; false otherwise.
     */
    @Deprecated
    boolean hasGroup( String name );

    /**
     * Return true if the system contains a {@link HostedRepository} with the given name; false otherwise.
     */
    @Deprecated
    boolean hasHostedRepository( String name );

    /**
     * Find a remote repository with a URL that matches the given one, and return it...or null.
     */
    @Deprecated
    RemoteRepository findRemoteRepository( String url );

    /* END: DEPRECATED, MAVEN-SPECIFIC METHODS */




    /* PACKAGE-SPECIFIC METHODS */
    /**
     * Return the {@link HostedRepository} instance corresponding to the given name.
     */
    HostedRepository getHostedRepository( final String packageType, final String name )
            throws IndyDataException;

    /**
     * Return the {@link RemoteRepository} instance corresponding to the given name.
     */
    RemoteRepository getRemoteRepository( final String packageType, final String name )
            throws IndyDataException;

    /**
     * Return the {@link Group} instance corresponding to the given name.
     */
    Group getGroup( final String packageType, final String name )
            throws IndyDataException;

    /**
     * Return the full list of {@link ArtifactStore} instances of a given {@link StoreType} (hosted, remote, or group) available on the system.
     */
    List<? extends ArtifactStore> getAllArtifactStores( final String packageType, StoreType type )
            throws IndyDataException;

    /**
     * For a {@link Group} with the given name, return (<b>IN ORDER</b>) the list of
     * non-{@link Group} {@link ArtifactStore} instances that are members of the {@link Group}.
     * <br/>
     * <b>NOTE:</b> If any of the group's members are themselves {@link Group}'s, the method
     * recurses and substitutes that group's place in the list with the ordered, concrete stores
     * it contains.
     *
     * @param enabledOnly include only enabled stores
     */
    List<ArtifactStore> getOrderedConcreteStoresInGroup( final String packageType, final String groupName, boolean enabledOnly )
            throws IndyDataException;

    /**
     * For a {@link Group} with the given name, return (<b>IN ORDER</b>) the list of
     * non-{@link Group} {@link ArtifactStore} instances that are members of the {@link Group}.
     * <br/>
     * <b>NOTE:</b> This method does <b>not</b> perform recursion to substitute concrete stores in place
     * of any groups in the list. Groups that are members are returned along with the rest of the membership.
     *
     * @param enabledOnly include only enabled stores
     */
    List<ArtifactStore> getOrderedStoresInGroup( final String packageType, final String groupName, boolean enabledOnly )
            throws IndyDataException;

    /**
     * Return true if the system contains a {@link RemoteRepository} with the given name; false otherwise.
     */
    boolean hasRemoteRepository( String packageType, String name );

    /**
     * Return true if the system contains a {@link Group} with the given name; false otherwise.
     */
    boolean hasGroup( String packageType, String name );

    /**
     * Return true if the system contains a {@link HostedRepository} with the given name; false otherwise.
     */
    boolean hasHostedRepository( String packageType, String name );

    /**
     * Find a remote repository with a URL that matches the given one, and return it...or null.
     */
    RemoteRepository findRemoteRepository( String packageType, String url );

    /**
     * Return the full list of {@link ArtifactStore} instances available on the system.
     */
    List<ArtifactStore> getAllArtifactStores(String packageType)
            throws IndyDataException;

    /**
     * Return the full list of {@link Group} instances available on the system.
     */
    List<Group> getAllGroups(String packageType)
            throws IndyDataException;

    /**
     * Return the full list of {@link RemoteRepository} instances available on the system.
     */
    List<RemoteRepository> getAllRemoteRepositories(String packageType)
            throws IndyDataException;

    /**
     * Return the full list of {@link HostedRepository} instances available on the system.
     */
    List<HostedRepository> getAllHostedRepositories(String packageType)
            throws IndyDataException;

    /**
     * Return the full list of non-{@link Group} instances available on the system.
     */
    List<ArtifactStore> getAllConcreteArtifactStores(String packageType)
            throws IndyDataException;

    /* END: PACKAGE-SPECIFIC METHODS */




    /**
     * Return the {@link ArtifactStore} instance corresponding to the given key, where key is a composite of {@link StoreType}
     * (hosted, remote, or group) and name.
     */
    ArtifactStore getArtifactStore( StoreKey key )
        throws IndyDataException;

    /**
     * Return the full list of {@link ArtifactStore} instances available on the system.
     */
    List<ArtifactStore> getAllArtifactStores()
        throws IndyDataException;

    /**
     * Return the full list of {@link Group} instances available on the system.
     */
    List<Group> getAllGroups()
        throws IndyDataException;

    /**
     * Return the full list of {@link RemoteRepository} instances available on the system.
     */
    List<RemoteRepository> getAllRemoteRepositories()
        throws IndyDataException;

    /**
     * Return the full list of {@link HostedRepository} instances available on the system.
     */
    List<HostedRepository> getAllHostedRepositories()
        throws IndyDataException;

    /**
     * Return the full list of non-{@link Group} instances available on the system.
     */
    List<ArtifactStore> getAllConcreteArtifactStores()
        throws IndyDataException;

    /**
     * Return the set of {@link Group}'s that contain the {@link ArtifactStore} corresponding to the given {@link StoreKey} in their membership.
     */
    Set<Group> getGroupsContaining( final StoreKey repo )
        throws IndyDataException;

    /**
     * Store a modified or new {@link ArtifactStore} instance. This is equivalent to
     * {@link StoreDataManager#storeArtifactStore(ArtifactStore, boolean)} with skip flag <code>false</code>
     */
    boolean storeArtifactStore( ArtifactStore store, final ChangeSummary summary )
        throws IndyDataException;

    /**
     * Store a modified or new {@link ArtifactStore} instance. This is equivalent to
     * {@link StoreDataManager#storeArtifactStore(ArtifactStore, boolean, EventMetadata)} with skip flag <code>false</code>
     * @param eventMetadata TODO
     */
    boolean storeArtifactStore( ArtifactStore store , final ChangeSummary summary , EventMetadata eventMetadata  )
        throws IndyDataException;

    /**
     * Store a modified or new {@link ArtifactStore} instance. If the store already exists, and <code>skipIfExists</code> is true, abort the
     * operation.
     */
    boolean storeArtifactStore( ArtifactStore store, final ChangeSummary summary, boolean skipIfExists )
        throws IndyDataException;

    /**
     * Store a modified or new {@link ArtifactStore} instance. If the store already exists, and <code>skipIfExists</code> is true, abort the
     * operation.
     * @param eventMetadata TODO
     */
    boolean storeArtifactStore( ArtifactStore store , final ChangeSummary summary , boolean skipIfExists , EventMetadata eventMetadata  )
        throws IndyDataException;

    /**
     * Store a modified or new {@link ArtifactStore} instance. If the store already exists, and <code>skipIfExists</code> is true, abort the
     * operation.
     */
    boolean storeArtifactStore( ArtifactStore store, final ChangeSummary summary, boolean skipIfExists, boolean fireEvents )
        throws IndyDataException;

    /**
     * Store a modified or new {@link ArtifactStore} instance. If the store already exists, and <code>skipIfExists</code> is true, abort the
     * operation.
     * @param eventMetadata TODO
     */
    boolean storeArtifactStore( ArtifactStore store , final ChangeSummary summary , boolean skipIfExists , boolean fireEvents , EventMetadata eventMetadata  )
        throws IndyDataException;

    /**
     * Delete the {@link ArtifactStore} corresponding to the given {@link StoreKey}. If the store doesn't exist, simply return (don't fail).
     */
    void deleteArtifactStore( StoreKey key, final ChangeSummary summary )
        throws IndyDataException;

    /**
     * Delete the {@link ArtifactStore} corresponding to the given {@link StoreKey}. If the store doesn't exist, simply return (don't fail).
     * @param eventMetadata TODO
     */
    void deleteArtifactStore( StoreKey key , final ChangeSummary summary , EventMetadata eventMetadata  )
        throws IndyDataException;

    /**
     * Delete all {@link ArtifactStore} instances currently in the system.
     */
    void clear( final ChangeSummary summary )
        throws IndyDataException;

    /**
     * If no {@link ArtifactStore}'s exist in the system, install a couple of defaults:
     * <ul>
     * <li>Remote <code>central</code> pointing to the Maven central repository at http://repo.maven.apache.org/maven2/</li>
     * <li>Hosted <code>local-deployments</code> that can host both releases and snapshots</li>
     * <li>Group <code>public</code> containing <code>central</code> and <code>local-deployments</code> as members</li>
     * </ul>
     */
    void install()
        throws IndyDataException;

    /**
     * Mechanism for clearing all cached {@link ArtifactStore} instances and reloading them from some backing store.
     */
    void reload()
        throws IndyDataException;

    /**
     * Return true if the system contains a {@link ArtifactStore} with the given key (combination of {@link StoreType} and name); false otherwise.
     */
    boolean hasArtifactStore( StoreKey key );

    /**
     * Return true once any post-construction code runs.
     */
    boolean isStarted();

    /**
     * Return the set of {@link Group}s that contain the given {@link StoreKey}s either directly or indirectly.
     */
    Set<Group> getGroupsAffectedBy( StoreKey... keys );

    /**
     * Return the set of {@link Group}s that contain the given {@link StoreKey}s either directly or indirectly.
     */
    Set<Group> getGroupsAffectedBy( Collection<StoreKey> keys );

    /**
     * Check if store is a readonly hosted repository. Return true only when store is a readonly {@link HostedRepository}
     */
    boolean isReadonly( ArtifactStore store );

    /**
     * Check if store is a readonly hosted repository. Return true only when store is a readonly {@link HostedRepository}
     */
    boolean isReadonly( StoreKey storeKey );
}

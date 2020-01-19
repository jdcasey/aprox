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
package org.commonjava.indy.db.common;

import org.apache.commons.lang3.StringUtils;
import org.commonjava.indy.data.ArtifactStoreQuery;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.propulsor.metrics.annotation.Measure;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.PackageTypes;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor;
import org.commonjava.indy.util.UrlInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.commonjava.indy.model.core.StoreType.group;

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
// TODO: Eventually, it should probably be an error if packageType isn't set explicitly
public class DefaultArtifactStoreQuery<T extends ArtifactStore>
        implements ArtifactStoreQuery<T>
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private StoreDataManager dataManager;

    private String packageType = MavenPackageTypeDescriptor.MAVEN_PKG_KEY;

    private Set<StoreType> types;

    private Boolean enabled;

    public DefaultArtifactStoreQuery( StoreDataManager dataManager )
    {
        this.dataManager = dataManager;
    }

    private DefaultArtifactStoreQuery( final StoreDataManager dataManager, final String packageType,
                                       final Boolean enabled, final Class<T> storeCls )
    {
        this.dataManager = dataManager;
        this.packageType = packageType;
        this.enabled = enabled;
        storeType( storeCls );
    }

    @Override
    public ArtifactStoreQuery<T> rewrap( final StoreDataManager manager )
    {
        this.dataManager = manager;
        return this;
    }

    @Override
    public DefaultArtifactStoreQuery<T> packageType( String packageType )
            throws IndyDataException
    {
        if ( !PackageTypes.contains( packageType ) )
        {
            throw new IndyDataException( "Invalid package type: %s. Supported values are: %s", packageType,
                                         PackageTypes.getPackageTypes() );
        }

        this.packageType = packageType;
        return this;
    }

    @Override
    public <C extends ArtifactStore> DefaultArtifactStoreQuery<C> storeType( Class<C> storeCls )
    {
        if ( RemoteRepository.class.equals( storeCls ) )
        {
            this.types = Collections.singleton( StoreType.remote );
        }
        else if ( HostedRepository.class.equals( storeCls ) )
        {
            this.types = Collections.singleton( StoreType.hosted );
        }
        else
        {
            this.types = Collections.singleton( group );
        }

        return (DefaultArtifactStoreQuery<C>) this;
    }

    @Override
    public DefaultArtifactStoreQuery<T> storeTypes( StoreType... types )
    {
        this.types = new HashSet<>( Arrays.asList( types ) );
        return this;
    }

    @Override
    public ArtifactStoreQuery<T> concreteStores()
    {
        return storeTypes( StoreType.remote, StoreType.hosted );
    }

    @Override
    public ArtifactStoreQuery<T> enabledState( Boolean enabled )
    {
        this.enabled = enabled;
        return this;
    }

    @Override
    public boolean isEmpty()
    {
        return this.dataManager.isEmpty();
    }

    @Override
    @Measure
    public List<T> getAll()
            throws IndyDataException
    {
        return stream().collect( Collectors.toList() );
    }

    @Override
    @Measure
    public Stream<T> stream()
            throws IndyDataException
    {
        return stream( store -> true );
    }

    @Override
    @Measure
    public Stream<T> stream( Predicate<ArtifactStore> filter )
            throws IndyDataException
    {
        /* @formatter:off */
        return dataManager.streamArtifactStores().filter( ( store ) ->
        {
            logger.debug( "Checking whether {} is included in stream...", store.getKey() );

            // Tricky condition here: The flag in the store we're checking is true when DISABLED, while the
            // condition we're checking against in this query is true when it's ENABLED. If the two flags equal on another
            // that actually means they DISAGREE about the state vs. desired state of the store.
            if ( enabled != null && enabled == store.isDisabled() )
            {
                logger.debug( "Rejected. Store is {}, and we're only looking for enabled state of: {}", store.isDisabled(), enabled );
                return false;
            }

            if ( packageType != null && !packageType.equals( store.getPackageType() ) )
            {
                logger.debug( "Rejected. Store package type is: {}, and we're only looking for package type of: {}", store.getPackageType(), packageType );
                return false;
            }

            if ( types != null && !types.contains( store.getType() ) )
            {
                logger.debug( "Rejected. Store is of type: {}, and we're only looking for: {}", store.getType(), types );
                return false;
            }

            if ( filter != null && !filter.test( store ))
            {
                logger.debug( "Rejected. Additional filtering failed for store: {}", store.getKey() );
                return false;
            }

            logger.debug( "Store accepted for stream: {}", store.getKey() );
            return true;
        } ).map( store -> (T) store );
        /* @formatter:on */
    }

    @Override
    @Measure
    public List<T> getAll( Predicate<ArtifactStore> filter )
            throws IndyDataException
    {
        return stream( filter ).collect( Collectors.toList() );
    }

    @Override
    @Measure
    public List<T> getAllByDefaultPackageTypes()
            throws IndyDataException
    {
        List<T> result = new ArrayList<>();
        Set<String> defaults = PackageTypes.getPackageTypes();
        for ( String packageType : defaults )
        {
            this.packageType = packageType;
            result.addAll( stream().collect( Collectors.toList() ) );
        }
        return result;
    }

    @Override
    @Measure
    public T getByName( String name )
            throws IndyDataException
    {
        return stream( store -> name.equals( store.getName() ) ).findFirst().orElse( null );
    }

    @Override
    public boolean containsByName( String name )
            throws IndyDataException
    {
        return getByName( name ) != null;
    }

    @Override
    @Measure
    public Set<Group> getGroupsContaining( StoreKey storeKey )
            throws IndyDataException
    {
        return getAllGroups().stream().filter( g -> g.getConstituents().contains( storeKey ) ).collect( Collectors.toSet() );
    }

    @Override
    @Measure
    public List<RemoteRepository> getRemoteRepositoryByUrl( String url )
            throws IndyDataException
    {
        /*
           This filter does these things:
             * First compare ip, if ip same, and the path(without last slash) same too, the repo is found
             * If ip not same, then compare the url without scheme and last slash (if has) to find the repo
         */
        UrlInfo temp = null;
        try
        {
            temp = new UrlInfo( url );
        }
        catch ( IllegalArgumentException error )
        {
            logger.error( "Failed to find repository for: '{}'. Reason: {}", error, url, error.getMessage() );
        }

        final UrlInfo urlInfo = temp;

        List<RemoteRepository> result;

        // first try to find the remote repo by urlWithNoSchemeAndLastSlash
        /* @formatter:off */
        result = new DefaultArtifactStoreQuery<>( dataManager, packageType, enabled, RemoteRepository.class ).stream(
                store -> {
                    if ( ( StoreType.remote == store.getType() ) && urlInfo != null )
                    {
                        final String targetUrl = ( (RemoteRepository) store ).getUrl();
                        UrlInfo targetUrlInfo = null;
                        try
                        {
                            targetUrlInfo = new UrlInfo( targetUrl );
                        }
                        catch ( IllegalArgumentException error )
                        {
                            logger.error( "Failed to find repository for: '{}'. Reason: {}", error, targetUrl, error.getMessage() );
                        }

                        if (  targetUrlInfo != null )
                        {
                            if ( urlInfo.getUrlWithNoSchemeAndLastSlash()
                                        .equals( targetUrlInfo.getUrlWithNoSchemeAndLastSlash() )
                                            && urlInfo.getProtocol().equals( targetUrlInfo.getProtocol() ))
                            {
                                logger.debug( "Repository found because of same host, url is {}, store key is {}", url,
                                              store.getKey() );
                                return true;
                            }
                        }
                    }

                    return false;
                } ).collect( Collectors.toList() );
        /* @formatter:on */


        if ( result.isEmpty() )
        {
            // ...if not found by hostname try to search by IP
            /* @formatter:off */
            result = new DefaultArtifactStoreQuery<>( dataManager, packageType, enabled, RemoteRepository.class ).stream(
                    store -> {
                        if ( ( StoreType.remote == store.getType() ) && urlInfo != null )
                        {
                            final String targetUrl = ( (RemoteRepository) store ).getUrl();
                            UrlInfo targetUrlInfo = null;
                            try
                            {
                                targetUrlInfo = new UrlInfo( targetUrl );
                            }
                            catch ( IllegalArgumentException error )
                            {
                                logger.error( "Failed to find repository for: '{}'. Reason: {}", error, targetUrl, error.getMessage() );
                            }

                            if (  targetUrlInfo != null )
                            {
                                String ipForUrl = null;
                                String ipForTargetUrl = null;
                                try
                                {
                                    ipForUrl = urlInfo.getIpForUrl();
                                    ipForTargetUrl = targetUrlInfo.getIpForUrl();
                                    if ( ipForUrl != null && ipForUrl.equals( ipForTargetUrl )
                                            && urlInfo.getPort() == targetUrlInfo.getPort()
                                            && urlInfo.getFileWithNoLastSlash().equals( targetUrlInfo.getFileWithNoLastSlash() ) )
                                    {
                                        logger.debug( "Repository found because of same ip, url is {}, store key is {}", url,
                                                      store.getKey() );
                                        return true;
                                    }
                                }
                                catch ( UnknownHostException ue )
                                {
                                    logger.warn( "Failed to filter remote: ip fetch error.", ue );
                                }

                                logger.debug( "ip not same: ip for url:{}-{}; ip for searching repo: {}-{}", url, ipForUrl,
                                              store.getKey(), ipForTargetUrl );
                            }
                        }

                        return false;
                    } ).collect(Collectors.toList());
            /* @formatter:on */
        }

        return result;
    }

    @Override
    @Measure
    public List<ArtifactStore> getOrderedConcreteStoresInGroup( final String groupName )
            throws IndyDataException
    {
        return getGroupOrdering( groupName, false, true );
    }

    @Override
    @Measure
    public List<ArtifactStore> getOrderedStoresInGroup( final String groupName )
            throws IndyDataException
    {
        return getGroupOrdering( groupName, true, false );
    }

    @Override
    @Measure
    public Set<Group> getGroupsAffectedBy( StoreKey... keys )
            throws IndyDataException
    {
        return getGroupsAffectedBy( Arrays.asList( keys ) );
    }

    @Override
    @Measure
    public Set<Group> getGroupsAffectedBy( Collection<StoreKey> keys )
            throws IndyDataException
    {
        return dataManager.affectedBy( keys );
    }

    public Stream<StoreKey> keyStream()
    {
        return keyStream( null );
    }

    public Stream<StoreKey> keyStream( Predicate<StoreKey> filterPredicate )
    {
        final Stream<StoreKey> storeKeys;
        if ( StringUtils.isNotBlank( this.packageType ) )
        {
            storeKeys = dataManager.getStoreKeysByPkg( this.packageType ).stream();
        }
        else
        {
            storeKeys = dataManager.streamArtifactStoreKeys();
        }
        return storeKeys.filter(key -> {
            if ( types != null && !types.isEmpty() && !types.contains( key.getType() ) )
            {
                return false;
            }

            return filterPredicate == null || filterPredicate.test(key);
        });
    }

    @Override
    @Measure
    public List<RemoteRepository> getAllRemoteRepositories()
            throws IndyDataException
    {
        return getAllOfType( RemoteRepository.class );
    }

    @Override
    @Measure
    public List<HostedRepository> getAllHostedRepositories()
            throws IndyDataException
    {
        return getAllOfType( HostedRepository.class );
    }

    @Override
    @Measure
    public List<Group> getAllGroups()
            throws IndyDataException
    {
        return getAllOfType( Group.class );
    }

    private <T extends ArtifactStore> List<T> getAllOfType(Class<T> type)
            throws IndyDataException
    {
        List<StoreKey> keys = new DefaultArtifactStoreQuery<>( dataManager, packageType, enabled, type ).keyStream()
                                                                                                        .collect(
                                                                                                                Collectors
                                                                                                                        .toList() );

        List<T> stores = new ArrayList<>();
        for ( StoreKey key : keys )
        {
            T store = (T) dataManager.getArtifactStore( key );
            if ( store != null && ( enabled == null || store.isDisabled() == !enabled ) )
            {
                stores.add( store );
            }
        }

        return stores;
    }

    @Override
    public RemoteRepository getRemoteRepository( final String name )
            throws IndyDataException
    {
        return (RemoteRepository) dataManager.getArtifactStore( new StoreKey( packageType, StoreType.remote, name ) );
    }

    @Override
    public HostedRepository getHostedRepository( final String name )
            throws IndyDataException
    {
        return (HostedRepository) dataManager.getArtifactStore( new StoreKey( packageType, StoreType.hosted, name ) );
    }

    @Override
    public Group getGroup( final String name )
            throws IndyDataException
    {
        return (Group) dataManager.getArtifactStore( new StoreKey( packageType, group, name ) );
    }

    @Override
    public DefaultArtifactStoreQuery<T> noPackageType()
    {
        packageType = null;
        return this;
    }

    private List<ArtifactStore> getGroupOrdering( final String groupName,
                                                  final boolean includeGroups, final boolean recurseGroups )
            throws IndyDataException
    {
        if ( packageType == null )
        {
            throw new IndyDataException( "packageType must be set on the query before calling this method!" );
        }

        final Group master = (Group) dataManager.getArtifactStore( new StoreKey( packageType, group, groupName ) );
        if ( master == null )
        {
            return Collections.emptyList();
        }

        final List<ArtifactStore> result = new ArrayList<>();

        AtomicReference<IndyDataException> errorRef = new AtomicReference<>();
        LinkedList<Group> toCheck = new LinkedList<>();
        Set<StoreKey> seen = new HashSet<>();
        toCheck.add( master );

        while ( !toCheck.isEmpty() )
        {
            Group next = toCheck.removeFirst();

            if ( next == null || next.isDisabled() && Boolean.TRUE.equals( enabled ) )
            {
                continue;
            }

            List<StoreKey> members = new ArrayList<>( next.getConstituents() );
            if ( includeGroups )
            {
                result.add( next );
            }

            members.forEach( ( key ) ->
                             {
                                 if ( !seen.contains( key ) )
                                 {
                                     seen.add( key );
                                     final StoreType type = key.getType();
                                     try
                                     {
                                         if ( recurseGroups && type == group )
                                         {
                                             // if we're here, we're definitely recursing groups...
                                             Group group = (Group) dataManager.getArtifactStore( key );
                                             toCheck.addFirst( group );
                                         }
                                         else
                                         {
                                             final ArtifactStore store = dataManager.getArtifactStore( key );
                                             if ( store != null && !( store.isDisabled() && Boolean.TRUE.equals( enabled ) ) )
                                             {
                                                 result.add( store );
                                             }
                                         }
                                     }
                                     catch ( IndyDataException e )
                                     {
                                         errorRef.set(e);
                                     }
                                 }
                             } );

            IndyDataException error = errorRef.get();
            if ( error != null )
            {
                throw error;
            }
        }

        return result;
    }

}

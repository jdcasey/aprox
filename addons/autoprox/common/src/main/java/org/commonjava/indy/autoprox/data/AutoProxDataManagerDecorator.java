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
package org.commonjava.indy.autoprox.data;

import java.util.ArrayList;
import java.util.List;

import javax.decorator.Decorator;
import javax.decorator.Delegate;
import javax.enterprise.inject.Any;
import javax.inject.Inject;

import org.commonjava.indy.audit.ChangeSummary;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.mem.data.MemoryStoreDataManager;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.maven.galley.TransferException;
import org.commonjava.maven.galley.TransferManager;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Decorator
public abstract class AutoProxDataManagerDecorator
        implements StoreDataManager
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Delegate
    @Any
    @Inject
    private StoreDataManager dataManager;

    @Inject
    private AutoProxCatalogManager catalog;

    @Inject
    private TransferManager transferManager;

    protected AutoProxDataManagerDecorator()
    {
    }

    public AutoProxDataManagerDecorator( final MemoryStoreDataManager dataManager, final AutoProxCatalogManager catalog,
                                         final TransferManager transferManager )
    {
        this.dataManager = dataManager;
        this.catalog = catalog;
        this.transferManager = transferManager;
    }

    protected final StoreDataManager getDelegate()
    {
        return dataManager;
    }

    @Override
    public Group getGroup( final String packageType, final String name )
            throws IndyDataException
    {
        return getGroup( packageType, name, null );
    }

    private Group getGroup( final String packageType, final String name, final StoreKey impliedBy )
            throws IndyDataException
    {
        logger.debug( "DECORATED (getGroup: {})", name );
        Group g = dataManager.getGroup( packageType, name );

        if ( !catalog.isEnabled() )
        {
            logger.debug( "AutoProx decorator disabled; returning: {}", g );
            return g;
        }

        logger.debug( "AutoProx decorator active" );
        if ( g == null )
        {
            logger.debug( "AutoProx: creating repository for: {}", name );
            if ( !checkValidity( name ) )
            {
                return null;
            }

            try
            {
                g = catalog.createGroup( name );
            }
            catch ( final AutoProxRuleException e )
            {
                throw new IndyDataException(
                        "[AUTOPROX] Failed to create new group from factory matching: '%s'. Reason: %s", e, name,
                        e.getMessage() );
            }

            if ( g != null )
            {
                logger.info( "Validating group: {}", g );
                for ( final StoreKey key : new ArrayList<>( g.getConstituents() ) )
                {
                    final ArtifactStore store = getArtifactStore( key, impliedBy == null ? g.getKey() : impliedBy );
                    if ( store == null )
                    {
                        g.removeConstituent( key );
                    }
                }

                if ( g.getConstituents().isEmpty() )
                {
                    return null;
                }

                final Group group = g;
                dataManager.storeArtifactStore( group, new ChangeSummary( ChangeSummary.SYSTEM_USER,
                                                                          "AUTOPROX: Creating group for: '" + name
                                                                                  + "'" ),
                                                new EventMetadata().set( StoreDataManager.EVENT_ORIGIN,
                                                                         AutoProxConstants.STORE_ORIGIN )
                                                                   .set( AutoProxConstants.ORIGINATING_STORE,
                                                                         impliedBy == null ? g.getKey() : impliedBy ) );
            }
        }

        return g;
    }

    /**
     * Validates the remote connection, produced from rule-set for given name,
     * for a remote repo or group containing a remote. If:
     *
     * <ul>
     *   <li>rule.isValidationEnabled() == false, return true</li>
     *   <li>rule.getValidationRemote() == null, return true</li>
     *   <li>
     *     rule.getRemoteValidationPath() != null, validate remote.getUrl() + validationPath
     *     <ul>
     *       <li>if response code is 200 OK, then return true</li>
     *       <li>otherwise, return false</li>
     *     </ul>
     *   </li>
     * </ul>
     * @throws IndyDataException if the selected rule encounters an error while creating the new group/repository instance(s).
     */
    private boolean checkValidity( final String name )
            throws IndyDataException
    {
        if ( catalog.isValidationEnabled( name ) )
        {
            try
            {
                final RemoteRepository validationRepo = catalog.createValidationRemote( name );
                if ( validationRepo == null )
                {
                    logger.info( "No validation repository was created: assuming {} is valid.", name );
                    return true;
                }

                String path = catalog.getRemoteValidationPath( name );
                if ( path == null )
                {
                    path = PathUtils.ROOT;
                }

                logger.debug( "\n\n\n\n\n[AutoProx] Checking path: {} under remote URL: {}", path, validationRepo.getUrl() );
                boolean result = false;
                try
                {
                    result = transferManager.exists(
                            new ConcreteResource( LocationUtils.toLocation( validationRepo ), path ) );
                }
                catch ( final TransferException e )
                {
                    logger.warn( "[AutoProx] Cannot connect to target repository: '{}'. Reason: {}", validationRepo, e.getMessage() );
                    logger.debug( "[AutoProx] exception from validation attempt for: " + validationRepo, e );
                }

                logger.debug( "Validation result for: {} is: {}", validationRepo, result );

                return result;
            }
            catch ( final AutoProxRuleException e )
            {
                throw new IndyDataException(
                        "[AUTOPROX] Failed to create new group from factory matching: '%s'. Reason: %s", e, name,
                        e.getMessage() );
            }
        }

        return true;
    }

    @Override
    public RemoteRepository getRemoteRepository( final String name )
            throws IndyDataException
    {
        return getRemoteRepository( name, null );
    }

    private RemoteRepository getRemoteRepository( final String name, final StoreKey impliedBy )
            throws IndyDataException
    {
        logger.debug( "DECORATED (getRemoteRepository: {})", name );
        RemoteRepository repo = dataManager.getRemoteRepository( name );
        if ( !catalog.isEnabled() )
        {
            logger.debug( "AutoProx decorator disabled; returning: {}", repo );
            return repo;
        }

        logger.debug( "AutoProx decorator active" );
        if ( repo == null )
        {
            logger.info( "AutoProx: creating repository for: {}", name );

            try
            {
                repo = catalog.createRemoteRepository( name );
                if ( repo != null )
                {
                    if ( !checkValidity( name ) )
                    {
                        return null;
                    }

                    final RemoteRepository remote = repo;
                    dataManager.storeArtifactStore( remote, new ChangeSummary( ChangeSummary.SYSTEM_USER,
                                                                               "AUTOPROX: Creating remote repository for: '"
                                                                                       + name + "'" ),
                                                    new EventMetadata().set( StoreDataManager.EVENT_ORIGIN,
                                                                             AutoProxConstants.STORE_ORIGIN )
                                                                       .set( AutoProxConstants.ORIGINATING_STORE,
                                                                             impliedBy == null ?
                                                                                     repo.getKey() :
                                                                                     impliedBy ) );
                }
            }
            catch ( final AutoProxRuleException e )
            {
                throw new IndyDataException(
                        "[AUTOPROX] Failed to create new remote repository from factory matching: '%s'. Reason: %s", e,
                        name, e.getMessage() );
            }
        }

        return repo;
    }

    @Override
    public HostedRepository getHostedRepository( final String name )
            throws IndyDataException
    {
        return getHostedRepository( name, null );
    }

    private HostedRepository getHostedRepository( final String name, final StoreKey impliedBy )
            throws IndyDataException
    {
        logger.debug( "DECORATED (getHostedRepository: {})", name );
        HostedRepository repo = dataManager.getHostedRepository( name );
        if ( !catalog.isEnabled() )
        {
            logger.debug( "AutoProx decorator disabled; returning: {}", repo );
            return repo;
        }

        logger.debug( "AutoProx decorator active" );
        if ( repo == null )
        {
            logger.info( "AutoProx: creating repository for: {}", name );

            try
            {
                repo = catalog.createHostedRepository( name );
            }
            catch ( final AutoProxRuleException e )
            {
                throw new IndyDataException(
                        "[AUTOPROX] Failed to create new hosted repository from factory matching: '%s'. Reason: %s", e,
                        name, e.getMessage() );
            }

            if ( repo != null )
            {
                final HostedRepository hosted = repo;
                dataManager.storeArtifactStore( hosted, new ChangeSummary( ChangeSummary.SYSTEM_USER,
                                                                           "AUTOPROX: Creating remote repository for: '"
                                                                                   + name + "'" ),
                                                new EventMetadata().set( StoreDataManager.EVENT_ORIGIN,
                                                                         AutoProxConstants.STORE_ORIGIN )
                                                                   .set( AutoProxConstants.ORIGINATING_STORE,
                                                                         impliedBy == null ?
                                                                                 repo.getKey() :
                                                                                 impliedBy ) );
            }
        }

        return repo;
    }

    @Override
    public ArtifactStore getArtifactStore( final StoreKey key )
            throws IndyDataException
    {
        return getArtifactStore( key, null );
    }

    private ArtifactStore getArtifactStore( final StoreKey key, final StoreKey impliedBy )
            throws IndyDataException
    {
        if ( key == null )
        {
            return null;
        }

        if ( key.getType() == StoreType.group )
        {
            return getGroup( key.getName(), impliedBy );
        }
        else if ( key.getType() == StoreType.remote )
        {
            return getRemoteRepository( key.getName(), impliedBy );
        }
        else
        {
            return getHostedRepository( key.getName(), impliedBy );
        }
    }

    @Override
    public List<ArtifactStore> getOrderedConcreteStoresInGroup( final String groupName, final boolean enabledOnly )
            throws IndyDataException
    {
        getGroup( groupName );
        return dataManager.getOrderedConcreteStoresInGroup( groupName, enabledOnly );
    }

    @Override
    public List<ArtifactStore> getOrderedStoresInGroup( final String groupName, final boolean enabledOnly )
            throws IndyDataException
    {
        getGroup( groupName );
        return dataManager.getOrderedStoresInGroup( groupName, enabledOnly );
    }

    @Override
    public boolean hasRemoteRepository( final String name )
    {
        try
        {
            return getRemoteRepository( name ) != null;
        }
        catch ( final IndyDataException e )
        {
            logger.error( String.format( "Failed to retrieve/create remote: %s. Reason: %s", name, e.getMessage() ),
                          e );
        }

        return false;
    }

    @Override
    public boolean hasHostedRepository( final String name )
    {
        try
        {
            return getHostedRepository( name ) != null;
        }
        catch ( final IndyDataException e )
        {
            logger.error( String.format( "Failed to retrieve/create hosted: %s. Reason: %s", name, e.getMessage() ),
                          e );
        }

        return false;
    }

    @Override
    public boolean hasGroup( final String name )
    {
        try
        {
            return getGroup( name ) != null;
        }
        catch ( final IndyDataException e )
        {
            logger.error( String.format( "Failed to retrieve/create group: %s. Reason: %s", name, e.getMessage() ), e );
        }

        return false;
    }

    @Override
    public boolean hasArtifactStore( final StoreKey key )
    {
        try
        {
            return getArtifactStore( key ) != null;
        }
        catch ( final IndyDataException e )
        {
            logger.error( String.format( "Failed to retrieve/create: %s. Reason: %s", key, e.getMessage() ), e );
        }

        return false;
    }

}

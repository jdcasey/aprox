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
package org.commonjava.indy.repo.proxy.create;

import org.commonjava.indy.audit.ChangeSummary;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.repo.proxy.RepoProxyAddon;
import org.commonjava.indy.repo.proxy.RepoProxyException;
import org.commonjava.indy.repo.proxy.conf.RepoProxyConfig;
import org.commonjava.indy.subsys.datafile.DataFile;
import org.commonjava.indy.subsys.datafile.DataFileManager;
import org.commonjava.maven.galley.event.EventMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Optional.empty;
import static java.util.Optional.of;

@ApplicationScoped
public class ProxyRepoCreateManager
{
    private final Logger logger = LoggerFactory.getLogger( this.getClass() );

    private static final String REPO_PROXY_ORIGIN = RepoProxyAddon.ADDON_NAME;

    @Inject
    private RepoProxyConfig config;

    @Inject
    private DataFileManager ffManager;

    @Inject
    private ScriptRuleParser ruleParser;

    @Inject
    private StoreDataManager storeManager;

    private List<ProxyRepoCreateRule> rules;

    // This cache should be a very small cache in real system, so used a simple in-mem Map here.
    private final Map<StoreKey, StoreKey> proxiedRepoCache = Collections.synchronizedMap( new HashMap<>() );

    @PostConstruct
    public void init()
    {
        try
        {
            parseRules();
        }
        catch ( final RepoProxyException e )
        {
            logger.error( "Failed to parse repo creator rule: " + e.getMessage(), e );
        }
    }

    public Optional<RemoteRepository> createProxyRemote( final StoreKey origKey )
            throws RepoProxyException
    {
        if ( !enabled() )
        {
            throw new RepoProxyException( "Repo proxy addon is disabled" );
        }

        StoreKey cachedRemoteKey = proxiedRepoCache.get( origKey );
        RemoteRepository newProxyToRemote = null;
        if ( cachedRemoteKey == null )
        {
            if ( rules != null )
            {
                for ( ProxyRepoCreateRule rule : rules )
                {
                    if ( rule.matches( origKey ) )
                    {
                        logger.info( "[{}] Found rule {} to create repo {}", RepoProxyAddon.ADDON_NAME, rule, origKey );
                        try
                        {
                            Optional<RemoteRepository> repo = rule.createRemote( origKey );
                            if ( repo.isPresent() )
                            {
                                newProxyToRemote = repo.get();
                                cachedRemoteKey = repo.get().getKey();
                                proxiedRepoCache.put( origKey, repo.get().getKey() );
                            }
                        }
                        catch ( MalformedURLException e )
                        {
                            logger.warn( "Repo creation failed for key {} with rule {}, Reason: {}", origKey, rule,
                                         e.getMessage() );
                        }
                        break;
                    }
                }
            }
        }
        if ( cachedRemoteKey != null )
        {
            Optional<RemoteRepository> existedRepo = existsAndGetStore( cachedRemoteKey );
            if ( existedRepo.isPresent() )
            {
                return existedRepo;
            }
            if ( newProxyToRemote != null )
            {
                newProxyToRemote.setMetadata( ArtifactStore.METADATA_ORIGIN, REPO_PROXY_ORIGIN );
                boolean created = false;
                try
                {
                    created = storeManager.storeArtifactStore( newProxyToRemote,
                                                               new ChangeSummary( ChangeSummary.SYSTEM_USER,
                                                                                  "[Repository proxy] create remote proxy" ),
                                                               true, false, new EventMetadata() );
                }
                catch ( IndyDataException e )
                {
                    logger.warn( "[{}] Repo creation not succeed for key {} because of: {}.", RepoProxyAddon.ADDON_NAME,
                                 newProxyToRemote.getKey(), e.getMessage() );
                }
                if ( created )
                {
                    logger.info( "[{}] Repo {} found or created to do proxy to", RepoProxyAddon.ADDON_NAME,
                                 newProxyToRemote.getKey() );
                    return of( newProxyToRemote );
                }
                else
                {
                    logger.warn( "[{}] Repo {} not created successfully to do proxy to", RepoProxyAddon.ADDON_NAME,
                                 newProxyToRemote.getKey() );
                }
            }
        }

        //Tweak: will find and check a same-named remote repo for the original one if all other rules failed to get the matched remote
        final StoreKey sameNamedRemoteKey = StoreKey.fromString(
                String.format( "%s:%s:%s", origKey.getPackageType(), StoreType.remote.singularEndpointName(),
                               origKey.getName() ) );
        return existsAndGetStore( sameNamedRemoteKey );
    }

    private Optional<RemoteRepository> existsAndGetStore( final StoreKey key )
    {
        ArtifactStore repo = null;
        try
        {
            repo = storeManager.getArtifactStore( key );
            if ( repo == null )
            {
                logger.debug( "Store {} not found for repo-proxy", key );
            }
        }
        catch ( IndyDataException e )
        {
            logger.debug( "Cannot find store {} for pre-check with exception: {}", key, e.getMessage() );
        }
        return repo == null ? empty() : of( (RemoteRepository) repo );
    }

    public synchronized void parseRules()
            throws RepoProxyException
    {
        if ( !enabled() )
        {
            return;
        }

        final List<ProxyRepoCreateRule> rules = new ArrayList<>();

        final DataFile dataDir = ffManager.getDataFile( config.getRepoCreatorRuleBaseDir() );
        logger.info( "Scanning {} for repo creator rules...", dataDir );
        if ( dataDir.exists() )
        {
            final DataFile[] scripts = dataDir.listFiles( pathname -> {
                logger.debug( "Checking for repo creator script in: {}", pathname );
                return pathname.getName().endsWith( ".groovy" );
            } );

            for ( final DataFile script : scripts )
            {
                logger.info( "Reading repo creator rule from: {}", script );
                final ProxyRepoCreateRule rule = ruleParser.parseRule( script );
                if ( rule != null )
                {
                    rules.add( rule );
                }
            }
        }
        else
        {
            logger.info( "Datadir {} not found, no rule scripts scanned", dataDir );
        }

        this.rules = rules;
    }

    private boolean enabled()
    {
        if ( !config.isEnabled() )
        {
            this.rules = new ArrayList<>();
            logger.debug( "Autoprox is disabled." );
            return false;
        }

        return true;
    }
}

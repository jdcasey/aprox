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
package org.commonjava.indy.client.core;

import com.fasterxml.jackson.databind.Module;
import org.commonjava.indy.client.core.auth.IndyClientAuthenticator;
import org.commonjava.indy.client.core.module.IndyContentClientModule;
import org.commonjava.indy.client.core.module.IndyMaintenanceClientModule;
import org.commonjava.indy.client.core.module.IndyNfcClientModule;
import org.commonjava.indy.client.core.module.IndySchedulerClientModule;
import org.commonjava.indy.client.core.module.IndyStatsClientModule;
import org.commonjava.indy.client.core.module.IndyStoresClientModule;
import org.commonjava.indy.inject.IndyVersioningProvider;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.commonjava.indy.stats.IndyVersioning;
import org.commonjava.util.jhttpc.auth.PasswordManager;
import org.commonjava.util.jhttpc.model.SiteConfig;

import java.io.Closeable;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.commonjava.indy.IndyRequestConstants.HEADER_COMPONENT_ID;

public class Indy
        implements Closeable
{

    private String apiVersion;

    private final IndyClientHttp http;

    private final Set<IndyClientModule> moduleRegistry;

    @Deprecated
    public Indy( final String baseUrl, final IndyClientModule... modules )
            throws IndyClientException
    {
        this( null, null, Arrays.asList( modules ), IndyClientHttp.defaultSiteConfig( baseUrl ));
    }

    @Deprecated
    public Indy( final String baseUrl, final IndyClientAuthenticator authenticator, final IndyClientModule... modules )
            throws IndyClientException
    {
        this( authenticator, null, Arrays.asList( modules ), IndyClientHttp.defaultSiteConfig( baseUrl ) );
    }

    @Deprecated
    public Indy( final String baseUrl, final IndyObjectMapper mapper, final IndyClientModule... modules )
            throws IndyClientException
    {
        this( null, mapper, Arrays.asList( modules ), IndyClientHttp.defaultSiteConfig( baseUrl ) );
    }

    @Deprecated
    public Indy( final String baseUrl, final IndyClientAuthenticator authenticator, final IndyObjectMapper mapper,
                 final IndyClientModule... modules )
            throws IndyClientException
    {
        this( authenticator, mapper, Arrays.asList( modules ), IndyClientHttp.defaultSiteConfig( baseUrl ) );
    }

    @Deprecated
    public Indy( final String baseUrl, final Collection<IndyClientModule> modules )
            throws IndyClientException
    {
        this( null, null, modules, IndyClientHttp.defaultSiteConfig( baseUrl ) );
    }

    @Deprecated
    public Indy( final String baseUrl, final IndyClientAuthenticator authenticator,
                 final Collection<IndyClientModule> modules )
            throws IndyClientException
    {
        this( authenticator, null, modules, IndyClientHttp.defaultSiteConfig( baseUrl ) );
    }

    @Deprecated
    public Indy( final String baseUrl, final IndyObjectMapper mapper, final Collection<IndyClientModule> modules )
            throws IndyClientException
    {
        this( null, mapper, modules, IndyClientHttp.defaultSiteConfig( baseUrl ) );
    }

    @Deprecated
    public Indy( final String baseUrl, final IndyClientAuthenticator authenticator, final IndyObjectMapper mapper,
                 final Collection<IndyClientModule> modules )
            throws IndyClientException
    {
        this( authenticator, mapper, modules, IndyClientHttp.defaultSiteConfig( baseUrl ) );
    }

    @Deprecated
    public Indy( final IndyClientAuthenticator authenticator, final IndyObjectMapper mapper,
                 final Collection<IndyClientModule> modules, SiteConfig location )
            throws IndyClientException
    {
        this( location, authenticator, mapper,
              modules == null ? new IndyClientModule[0] : modules.toArray( new IndyClientModule[modules.size()] ) );
    }

    @Deprecated
    public Indy( final SiteConfig location, final IndyClientAuthenticator authenticator, final IndyObjectMapper mapper, final IndyClientModule... modules )
    throws IndyClientException
    {
        this(location, authenticator, mapper, Collections.emptyMap(), modules);
    }

    /**
     *
     * @param location
     * @param authenticator
     * @param mapper
     * @param mdcCopyMappings a map of fields to copy from LoggingMDC to http request headers where key=MDCMey and value=headerName
     * @param modules
     * @throws IndyClientException
     */
    public Indy( final SiteConfig location, final IndyClientAuthenticator authenticator, final IndyObjectMapper mapper, final Map<String, String> mdcCopyMappings, final IndyClientModule... modules )
    throws IndyClientException
    {
        loadApiVersion();
        this.http = new IndyClientHttp( authenticator, mapper == null ? new IndyObjectMapper( true ) : mapper, location,
                                        getApiVersion(), mdcCopyMappings );

        this.moduleRegistry = new HashSet<>();

        setupStandardModules();
        for ( final IndyClientModule module : modules )
        {
            module.setup( this, http );
            moduleRegistry.add( module );
        }
    }

    public Indy( SiteConfig location, PasswordManager passwordManager, IndyClientModule... modules )
            throws IndyClientException
    {
        this( location, passwordManager, null, modules );
    }

    public Indy( SiteConfig location, PasswordManager passwordManager, IndyObjectMapper objectMapper, IndyClientModule... modules )
            throws IndyClientException
    {
        loadApiVersion();
        this.http = new IndyClientHttp( passwordManager,
                                        objectMapper == null ? new IndyObjectMapper( true ) : objectMapper, location,
                                        getApiVersion() );

        this.moduleRegistry = new HashSet<>();

        setupStandardModules();
        for ( final IndyClientModule module : modules )
        {
            module.setup( this, http );
            moduleRegistry.add( module );
        }
    }

    public void setupExternal( final IndyClientModule module )
    {
        setup( module );
    }

    /**
     * Not used since migration to jHTTPc library
     */
    @Deprecated
    public Indy connect()
    {
        return this;
    }

    @Override
    public void close()
    {
        http.close();
    }

    public IndyVersioning getVersionInfo()
            throws IndyClientException
    {
        return http.get( "/stats/version-info", IndyVersioning.class );
    }

    public IndyStoresClientModule stores()
            throws IndyClientException
    {
        return module( IndyStoresClientModule.class );
    }

    public IndySchedulerClientModule schedules()
            throws IndyClientException
    {
        return module( IndySchedulerClientModule.class );
    }

    public IndyContentClientModule content()
            throws IndyClientException
    {
        return module( IndyContentClientModule.class );
    }

    public IndyStatsClientModule stats()
            throws IndyClientException
    {
        return module( IndyStatsClientModule.class );
    }

    public IndyMaintenanceClientModule maint()
             throws IndyClientException
    {
        return module( IndyMaintenanceClientModule.class );
    }

    public <T extends IndyClientModule> T module( final Class<T> type )
            throws IndyClientException
    {
        for ( final IndyClientModule module : moduleRegistry )
        {
            if ( type.isInstance( module ) )
            {
                return type.cast( module );
            }
        }

        throw new IndyClientException( "Module not found: %s.", type.getName() );
    }

    public boolean hasModule( Class<?> type )
    {
        for ( final IndyClientModule module : moduleRegistry )
        {
            if ( type.isInstance( module ) )
            {
                return true;
            }
        }

        return false;
    }

    public String getBaseUrl()
    {
        return http.getBaseUrl();
    }

    private void setupStandardModules()
    {
        final Set<IndyClientModule> standardModules = new HashSet<>();
        standardModules.add( new IndyStoresClientModule() );
        standardModules.add( new IndyContentClientModule() );
        standardModules.add( new IndySchedulerClientModule() );
        standardModules.add( new IndyStatsClientModule() );
        standardModules.add( new IndyNfcClientModule() );
        standardModules.add( new IndyMaintenanceClientModule() );

        for ( final IndyClientModule module : standardModules )
        {
            setup( module );
        }
    }

    private void setup( IndyClientModule module )
    {
        module.setup( this, http );
        moduleRegistry.add( module );

        Iterable<Module> serMods = module.getSerializerModules();
        if ( serMods != null )
        {
            http.getObjectMapper().registerModules( serMods );
        }
    }

    // DA, Builder, Orchestrator, etc. If available, this will be sent as a request header.
    public void setComponentId( String componentId )
    {
        http.addDefaultHeader( HEADER_COMPONENT_ID, componentId );
    }

    // Default headers will be sent along with each request
    public void addDefaultHeader( String key, String value )
    {
        http.addDefaultHeader( key, value );
    }

    public String getApiVersion()
    {
        return apiVersion;
    }

    private void loadApiVersion() throws IndyClientException
    {
        this.apiVersion = new IndyVersioningProvider().getVersioningInstance().getApiVersion();
    }
}

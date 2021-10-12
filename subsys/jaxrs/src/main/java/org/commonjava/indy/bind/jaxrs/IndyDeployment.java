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
package org.commonjava.indy.bind.jaxrs;

import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.FilterInfo;
import io.undertow.servlet.api.ServletInfo;
import io.undertow.servlet.util.ImmediateInstanceFactory;

import org.commonjava.indy.bind.jaxrs.jackson.CDIJacksonProvider;
import org.commonjava.indy.bind.jaxrs.metrics.FlightRecorderFilter;
import org.commonjava.indy.bind.jaxrs.ui.UIServlet;
import org.commonjava.indy.bind.jaxrs.util.CdiInjectorFactoryImpl;
import org.commonjava.indy.bind.jaxrs.util.DeploymentInfoUtils;
import org.commonjava.indy.bind.jaxrs.util.RequestScopeListener;
import org.commonjava.indy.conf.UIConfiguration;
import org.commonjava.indy.stats.IndyVersioning;
import org.commonjava.indy.subsys.honeycomb.TraceManagerProducer;
import org.commonjava.o11yphant.metrics.servlet.GoldenSignalsFilter;
import org.commonjava.o11yphant.trace.servlet.TraceFilter;
import org.jboss.resteasy.plugins.server.servlet.HttpServlet30Dispatcher;
import org.jboss.resteasy.spi.ResteasyDeployment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.servlet.DispatcherType;
import javax.servlet.Servlet;
import javax.ws.rs.core.Application;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

@ApplicationScoped
public class IndyDeployment
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    public static final String API_PREFIX = "api";

    @Inject
    private Instance<IndyResources> resources;

    @Inject
    private Instance<RestProvider> providerInstances;

    @Inject
    private Instance<IndyDeploymentProvider> deployments;

    @Inject
    private UIServlet ui;

    @Inject
    private UIConfiguration uiConfiguration;

    @Inject
    private SlashTolerationFilter slashTolerationFilter;

    @Inject
    private ThreadContextFilter threadContextFilter;

    @Inject
    private ResourceManagementFilter resourceManagementFilter;

    @Inject
    private ApiVersioningFilter apiVersioningFilter;

    @Inject
    private TraceFilter traceFilter;

    // Adding to ensure it gets initialized
    @Inject
    private TraceManagerProducer traceManagerProducer;

    @Inject
    private GoldenSignalsFilter goldenSignalsFilter;

    @Inject
    private IndyVersioning versioning;

    private Set<Class<? extends IndyResources>> resourceClasses;

    private Set<Class<? extends RestProvider>> providerClasses;

    private Set<IndyDeploymentProvider> deploymentProviders;

    protected IndyDeployment()
    {
    }

    public IndyDeployment( final Set<Class<? extends IndyResources>> resourceClasses,
                           final Set<Class<? extends RestProvider>> restProviders,
                           final Set<IndyDeploymentProvider> deploymentProviders, final UIServlet ui,
                           final TraceFilter traceFilter,
                           final ResourceManagementFilter resourceManagementFilter, final IndyVersioning versioning )
    {
        this.resourceClasses = resourceClasses;
        this.deploymentProviders = deploymentProviders;
        this.ui = ui;
        this.resourceManagementFilter = resourceManagementFilter;
        this.versioning = versioning;
        this.traceFilter = traceFilter;
        this.apiVersioningFilter = new ApiVersioningFilter( versioning );
        this.providerClasses = Collections.emptySet();
        this.classes = getClasses();
    }

    @PostConstruct
    public void cdiInit()
    {
        providerClasses = new HashSet<>();
        resourceClasses = new HashSet<>();
        for ( final IndyResources indyResources : resources )
        {
            resourceClasses.add( indyResources.getClass() );
        }

        for ( final RestProvider restProvider : providerInstances )
        {
            providerClasses.add( restProvider.getClass() );
        }

        deploymentProviders = new HashSet<>();
        for ( final IndyDeploymentProvider fac : deployments )
        {
            logger.info( "Found deployment provider: {}", fac );
            deploymentProviders.add( fac );
        }

        classes = getClasses();
    }

    private Set<Class<?>> classes;

    public DeploymentInfo getDeployment( final String contextRoot )
    {
        final ResteasyDeployment deployment = new ResteasyDeployment();

        Application application = new Application()
        {
            public Set<Class<?>> getClasses()
            {
                return classes;
            }
        };
        deployment.setApplication( application );

        deployment.setInjectorFactoryClass( CdiInjectorFactoryImpl.class.getName() );

        final ServletInfo resteasyServlet = Servlets.servlet( "REST", HttpServlet30Dispatcher.class )
                                                    .setAsyncSupported( true )
                                                    .setLoadOnStartup( 1 )
                                                    .addMapping( "/api*" )
                                                    .addMapping( "/api/*" )
                                                    .addMapping( "/api-docs*" )
                                                    .addMapping( "/api-docs/*" );

        final FilterInfo slashTolerationFilter =
                        Servlets.filter("SlashToleration", SlashTolerationFilter.class,
                                 new ImmediateInstanceFactory<>(
                                         this.slashTolerationFilter ) );

        final FilterInfo traceFilter =
                        Servlets.filter( "O11yphant-Trace", TraceFilter.class,
                                 new ImmediateInstanceFactory<>(
                                         this.traceFilter ) );

        final FilterInfo threadContextFilter =
                Servlets.filter( "ThreadContext Management", ThreadContextFilter.class,
                                 new ImmediateInstanceFactory<>(
                                         this.threadContextFilter ) );

        final FilterInfo goldenSignalsFilter = Servlets.filter( "Golden-Signals", GoldenSignalsFilter.class,
                                                                new ImmediateInstanceFactory<>(
                                                                        this.goldenSignalsFilter ) );

        final FilterInfo resourceManagementFilter =
                Servlets.filter( "Naming and Resource Management", ResourceManagementFilter.class,
                                 new ImmediateInstanceFactory<ResourceManagementFilter>(
                                         this.resourceManagementFilter ) );
        final FilterInfo apiVersioningFilter =
                        Servlets.filter( "ApiVersioning", ApiVersioningFilter.class,
                                         new ImmediateInstanceFactory<ApiVersioningFilter>(
                                                         this.apiVersioningFilter ) );

        final DeploymentInfo di = new DeploymentInfo().addListener( Servlets.listener( RequestScopeListener.class ) )
                                                      //.addInitParameter( "resteasy.scan", Boolean.toString( true ) )
                                                      .setContextPath( contextRoot )
                                                      .addServletContextAttribute( ResteasyDeployment.class.getName(),
                                                                                   deployment )

                                                      .addServlet( resteasyServlet )

                                                      .addFilter( apiVersioningFilter )
                                                      .addFilterServletNameMapping( apiVersioningFilter.getName(),
                                                                                    resteasyServlet.getName(),
                                                                                    DispatcherType.REQUEST )

                                                      .addFilter( slashTolerationFilter )
                                                      .addFilterServletNameMapping( slashTolerationFilter.getName(),
                                                                                    resteasyServlet.getName(),
                                                                                    DispatcherType.REQUEST )

                                                      .addFilter( threadContextFilter )
                                                      .addFilterUrlMapping( threadContextFilter.getName(), "/api/*",
                                                                            DispatcherType.REQUEST )

                                                      .addFilter( traceFilter )
                                                      .addFilterUrlMapping( traceFilter.getName(), "/api/*",
                                                                            DispatcherType.REQUEST )

                                                      .addFilter( goldenSignalsFilter )
                                                      .addFilterUrlMapping( goldenSignalsFilter.getName(),
                                                                            "/api/folo/*", DispatcherType.REQUEST )
                                                      .addFilterUrlMapping( goldenSignalsFilter.getName(),
                                                                            "/api/content/*", DispatcherType.REQUEST )
                                                      .addFilterUrlMapping( goldenSignalsFilter.getName(),
                                                                            "/api/promotion/*", DispatcherType.REQUEST )
                                                      .addFilterUrlMapping( goldenSignalsFilter.getName(),
                                                                            "/api/admin/stores/*",
                                                                            DispatcherType.REQUEST )
                                                      .addFilterUrlMapping( goldenSignalsFilter.getName(),
                                                                            "/api/browse/*", DispatcherType.REQUEST )
                                                      .addFilterUrlMapping( goldenSignalsFilter.getName(),
                                                                            "/api/remote/*", DispatcherType.REQUEST )
                                                      .addFilterUrlMapping( goldenSignalsFilter.getName(),
                                                                            "/api/hosted/*", DispatcherType.REQUEST )
                                                      .addFilterUrlMapping( goldenSignalsFilter.getName(),
                                                                            "/api/group/*", DispatcherType.REQUEST )

                                                      .addFilter( resourceManagementFilter )
                                                      .addFilterUrlMapping( resourceManagementFilter.getName(),
                                                                            "/api/*", DispatcherType.REQUEST )

                                                      .setDeploymentName( "Indy" )
                                                      .setClassLoader( ClassLoader.getSystemClassLoader() );
                                                      //.addOuterHandlerChainWrapper( new HeaderDebugger().new
        // Wrapper() );

        if ( deploymentProviders != null )
        {
            DeploymentInfoUtils.mergeFromProviders( di, deploymentProviders, contextRoot, application );
        }

        if ( uiConfiguration.getEnabled() )
        {
            // Add UI servlet at the end so its mappings don't obscure any from add-ons.
            final ServletInfo uiServlet = Servlets.servlet( "UI", UIServlet.class )
                                                  .setAsyncSupported( true )
                                                  .setLoadOnStartup( 99 )
                                                  .addMappings( UIServlet.PATHS );

            uiServlet.setInstanceFactory( new ImmediateInstanceFactory<Servlet>( ui ) );
            di.addServlet( uiServlet );
        }


        return di;
    }

    public Set<Class<?>> getClasses()
    {
        final Set<Class<?>> classes = new LinkedHashSet<>();
        classes.addAll( providerClasses );
        classes.addAll( resourceClasses );
        deploymentProviders.forEach( di -> classes.addAll( di.getAdditionalClasses() ) );
        classes.add( CDIJacksonProvider.class );
        classes.add( UnhandledWebApplicationExceptionHandler.class );
        classes.add( UnhandledThrowableHandler.class );
        classes.add( FlightRecorderFilter.class );
        return classes;
    }

}

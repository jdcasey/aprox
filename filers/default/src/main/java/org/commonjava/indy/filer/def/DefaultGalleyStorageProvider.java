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
package org.commonjava.indy.filer.def;

import org.commonjava.cdi.util.weft.ExecutorConfig;
import org.commonjava.cdi.util.weft.WeftScheduledExecutor;
import org.commonjava.indy.conf.IndyConfiguration;
import org.commonjava.indy.content.IndyChecksumAdvisor;
import org.commonjava.indy.content.SpecialPathSetProducer;
import org.commonjava.indy.filer.def.conf.DefaultStorageProviderConfiguration;
import org.commonjava.maven.galley.GalleyInitException;
import org.commonjava.maven.galley.cache.CacheProviderFactory;
import org.commonjava.maven.galley.cache.partyline.PartyLineCacheProviderFactory;
import org.commonjava.maven.galley.config.TransportManagerConfig;
import org.commonjava.maven.galley.io.ChecksummingTransferDecorator;
import org.commonjava.maven.galley.io.NoCacheTransferDecorator;
import org.commonjava.maven.galley.io.TransferDecoratorManager;
import org.commonjava.maven.galley.io.checksum.ChecksummingDecoratorAdvisor;
import org.commonjava.maven.galley.io.checksum.Md5GeneratorFactory;
import org.commonjava.maven.galley.io.checksum.Sha1GeneratorFactory;
import org.commonjava.maven.galley.io.checksum.Sha256GeneratorFactory;
import org.commonjava.maven.galley.io.checksum.TransferMetadataConsumer;
import org.commonjava.maven.galley.model.FilePatternMatcher;
import org.commonjava.maven.galley.model.SpecialPathInfo;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.spi.cache.CacheProvider;
import org.commonjava.maven.galley.spi.event.FileEventManager;
import org.commonjava.maven.galley.spi.io.PathGenerator;
import org.commonjava.maven.galley.spi.io.SpecialPathManager;
import org.commonjava.maven.galley.spi.io.TransferDecorator;
import org.commonjava.maven.galley.spi.metrics.TimingProvider;
import org.commonjava.maven.galley.transport.htcli.UploadMetadataGenTransferDecorator;
import org.commonjava.propulsor.metrics.MeteringContext;
import org.commonjava.propulsor.metrics.MetricsManager;
import org.commonjava.propulsor.metrics.spi.TimingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.Instance;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.commonjava.maven.galley.io.checksum.ChecksummingDecoratorAdvisor.ChecksumAdvice.CALCULATE_AND_WRITE;
import static org.commonjava.maven.galley.io.checksum.ChecksummingDecoratorAdvisor.ChecksumAdvice.NO_DECORATE;

@ApplicationScoped
public class DefaultGalleyStorageProvider
{
    private final Logger logger = LoggerFactory.getLogger( this.getClass() );

    @Inject
    private DefaultStorageProviderConfiguration config;

    @Inject
    private IndyConfiguration indyConfiguration;

    @Inject
    private FileEventManager fileEventManager;

    @Inject
    private PathGenerator pathGenerator;

    @Inject
    private Instance<SpecialPathSetProducer> specialPathSetProducers;

    @Inject
    private SpecialPathManager specialPathManager;

    @ExecutorConfig( named = "galley-delete-executor", threads = 5, priority = 2 )
    @WeftScheduledExecutor
    @Inject
    private ScheduledExecutorService deleteExecutor;

    @Inject
    private TransferMetadataConsumer contentMetadataConsumer;

    @Inject
    private Instance<IndyChecksumAdvisor> checksumAdvisors;

    @Inject
    private Instance<TransferDecorator> transferDecorators;

    @Inject
    private MetricsManager metricsManager;

    private TransportManagerConfig transportManagerConfig;

    private TransferDecoratorManager transferDecorator;

    private CacheProvider cacheProvider;

    private CacheProviderFactory cacheProviderFactory;

    public DefaultGalleyStorageProvider()
    {
    }

    public DefaultGalleyStorageProvider( final File storageRoot, final File nfsStorageRoot )
    {
        this.config = new DefaultStorageProviderConfiguration( storageRoot, nfsStorageRoot );
        setup();
    }

    @PostConstruct
    public void setup()
    {
        SpecialPathInfo infoSpi = SpecialPathInfo.from( new FilePatternMatcher( ".+\\.info" ) )
                                                 .setDecoratable( false )
                                                 .setDeletable( false )
                                                 .setListable( false )
                                                 .setPublishable( false )
                                                 .setRetrievable( false )
                                                 .setStorable( false )
                                                 .build();

        specialPathManager.registerSpecialPathInfo( infoSpi );

        if ( specialPathSetProducers != null )
        {
            specialPathSetProducers.forEach(
                    producer -> {
                        logger.trace( "Adding special paths from: {}", producer.getClass().getName() );
                        specialPathManager.registerSpecialPathSet( producer.getSpecialPathSet() );
                    } );
        }

        setupTransferDecoratorPipeline();

        final File storeRoot = config.getStorageRootDirectory();

        // Apply partyline gloable lock manager if in cluster Env
        cacheProviderFactory = new PartyLineCacheProviderFactory( storeRoot, deleteExecutor );

        // TODO: Tie this into a config file!
        transportManagerConfig = new TransportManagerConfig();
    }

    /**
     * The order is important. We put the checksum decorator at the last because some decorators may change the content.
     */
    private void setupTransferDecoratorPipeline()
    {
        List<TransferDecorator> decorators = new ArrayList<>();
        decorators.add( new IOLatencyDecorator( timerProvider(), meterProvider(), cumulativeTimer() ));
        decorators.add( new NoCacheTransferDecorator( specialPathManager ) );
        decorators.add( new UploadMetadataGenTransferDecorator( specialPathManager, galleyTimerProvider() ) );
        for ( TransferDecorator decorator : transferDecorators )
        {
            decorators.add( decorator );
        }
        decorators.add( getChecksummingTransferDecorator() );
        transferDecorator = new TransferDecoratorManager( decorators );
    }

    private Function<String, TimingProvider> galleyTimerProvider()
    {
        return name -> new TimingProvider()
        {
            private TimingContext context;

            @Override
            public void start( String name )
            {
                this.context = metricsManager.time( name );
                this.context.start();
            }

            @Override
            public long stop()
            {
                return context.stop().stream().findFirst().get();
            }
        };
    }

    private BiConsumer<String, Double> cumulativeTimer()
    {
        return (name, elapsed) -> metricsManager.accumulate( name, elapsed );
    }

    private Function<String, MeteringContext> meterProvider()
    {
        return (name)->metricsManager.getMeter( name );
    }

    private Function<String, TimingContext> timerProvider()
    {
        return (name)->{
            TimingContext timingContext = metricsManager.time( name );
            timingContext.start();

            return timingContext;
        };
    }

    private ChecksummingTransferDecorator getChecksummingTransferDecorator()
    {
        ChecksummingDecoratorAdvisor readAdvisor = ( transfer, op, eventMetadata ) -> {
            ChecksummingDecoratorAdvisor.ChecksumAdvice result = NO_DECORATE;
            if ( checksumAdvisors != null )
            {
                for ( IndyChecksumAdvisor advisor : checksumAdvisors )
                {
                    Optional<ChecksummingDecoratorAdvisor.ChecksumAdvice> advice =
                                    advisor.getChecksumReadAdvice( transfer, op, eventMetadata );

                    if ( advice.isPresent() )
                    {
                        ChecksummingDecoratorAdvisor.ChecksumAdvice checksumAdvice = advice.get();

                        if ( checksumAdvice.ordinal() > result.ordinal() )
                        {
                            result = checksumAdvice;
                            if ( checksumAdvice == CALCULATE_AND_WRITE )
                            {
                                break;
                            }
                        }
                    }
                }
            }

            logger.debug( "Advising {} for {} of: {}", result, op, transfer );
            return result;
        };

        ChecksummingDecoratorAdvisor writeAdvisor = ( transfer, op, eventMetadata ) -> {
            ChecksummingDecoratorAdvisor.ChecksumAdvice result = NO_DECORATE;
            if ( TransferOperation.GENERATE == op )
            {
                result = CALCULATE_AND_WRITE;
            }
            else if ( checksumAdvisors != null )
            {
                for ( IndyChecksumAdvisor advisor : checksumAdvisors )
                {
                    Optional<ChecksummingDecoratorAdvisor.ChecksumAdvice> advice =
                                    advisor.getChecksumWriteAdvice( transfer, op, eventMetadata );

                    if ( advice.isPresent() )
                    {
                        ChecksummingDecoratorAdvisor.ChecksumAdvice checksumAdvice = advice.get();
                        if ( checksumAdvice.ordinal() > result.ordinal() )
                        {
                            result = checksumAdvice;
                            if ( checksumAdvice == CALCULATE_AND_WRITE )
                            {
                                break;
                            }
                        }
                    }
                }
            }

            logger.debug( "Advising {} for {} of: {}", result, op, transfer );
            return result;
        };

        return new ChecksummingTransferDecorator( readAdvisor, writeAdvisor, specialPathManager, galleyTimerProvider(),
                                                  contentMetadataConsumer, new Md5GeneratorFactory(),
                                                  new Sha1GeneratorFactory(), new Sha256GeneratorFactory() );
    }

    @Produces
    @Default
    public TransportManagerConfig getTransportManagerConfig()
    {
        return transportManagerConfig;
    }

/*
    @Produces
    @Default
    public TransferDecorator getTransferDecorator()
    {
        return transferDecorator;
    }
*/

    @Produces
    @Default
    public synchronized CacheProvider getCacheProvider()
    {
        if ( cacheProvider == null )
        {
            try
            {
                cacheProvider = cacheProviderFactory.create( pathGenerator, transferDecorator, fileEventManager );
                logger.debug( "Using cache provider {}", cacheProvider );
                return cacheProvider;
            }
            catch ( GalleyInitException e )
            {
                logger.error( "[Indy] Can not create CacheProvider for some error.", e );
            }
        }

        return cacheProvider;
    }
}

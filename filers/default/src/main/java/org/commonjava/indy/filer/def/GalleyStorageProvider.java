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
package org.commonjava.indy.filer.def;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;

import org.commonjava.cdi.util.weft.ExecutorConfig;
import org.commonjava.indy.filer.def.conf.DefaultStorageProviderConfiguration;
import org.commonjava.maven.galley.cache.partyline.PartyLineCacheProvider;
import org.commonjava.maven.galley.cache.partyline.PartyLineCacheProviderConfig;
import org.commonjava.maven.galley.io.ChecksummingTransferDecorator;
import org.commonjava.maven.galley.io.checksum.Md5GeneratorFactory;
import org.commonjava.maven.galley.io.checksum.Sha1GeneratorFactory;
import org.commonjava.maven.galley.model.FilePatternMatcher;
import org.commonjava.maven.galley.model.SpecialPathInfo;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.spi.event.FileEventManager;
import org.commonjava.maven.galley.spi.io.PathGenerator;
import org.commonjava.maven.galley.spi.io.SpecialPathManager;
import org.commonjava.maven.galley.spi.io.TransferDecorator;

@ApplicationScoped
public class GalleyStorageProvider
{

    @Inject
    private DefaultStorageProviderConfiguration config;

    @Inject
    private FileEventManager fileEventManager;

    @Inject
    private PathGenerator pathGenerator;

    @Inject
    private SpecialPathManager specialPathManager;

    @Inject
    @ExecutorConfig( named = "fast-storage-transfers", threads = 4, priority = 4 )
    private ExecutorService fastStorageTransfers;

    private TransferDecorator transferDecorator;

    private PartyLineCacheProvider cacheProvider;

    public GalleyStorageProvider()
    {
    }

    public GalleyStorageProvider( final File storageRoot )
    {
        this.config = new DefaultStorageProviderConfiguration( storageRoot );
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

        transferDecorator =
            new ChecksummingTransferDecorator( Collections.singleton( TransferOperation.GENERATE ), specialPathManager,
                                               new Md5GeneratorFactory(),
                                               new Sha1GeneratorFactory() );

        final PartyLineCacheProviderConfig cacheProviderConfig =
            new PartyLineCacheProviderConfig( config.getStorageRootDirectory() );

        this.cacheProvider =
            new PartyLineCacheProvider( cacheProviderConfig, pathGenerator, fileEventManager, transferDecorator, fastStorageTransfers );
    }

    @Produces
    @Default
    public TransferDecorator getTransferDecorator()
    {
        return transferDecorator;
    }

    @Produces
    @Default
    public PartyLineCacheProvider getCacheProvider()
    {
        return cacheProvider;
    }
}

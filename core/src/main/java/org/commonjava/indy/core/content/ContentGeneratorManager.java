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
package org.commonjava.indy.core.content;

import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.content.ContentGenerator;
import org.commonjava.indy.content.StoreResource;
import org.commonjava.indy.measure.annotation.Measure;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.spi.io.PathGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

@ApplicationScoped
public class ContentGeneratorManager
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private Instance<ContentGenerator> contentGeneratorInstance;

    private Set<ContentGenerator> contentGenerators;

    @Inject
    private PathGenerator pathGenerator;

    public ContentGeneratorManager()
    {
        contentGenerators = new HashSet<>();
    }

    @PostConstruct
    public void initialize()
    {
        if ( contentGeneratorInstance != null )
        {
            for ( final ContentGenerator producer : contentGeneratorInstance )
            {
                contentGenerators.add( producer );
            }
        }
    }

    @Measure
    public Transfer generateFileContentAnd( final ArtifactStore store, final String path,
                                            final EventMetadata eventMetadata, Consumer<Transfer> consumer )
                    throws IndyWorkflowException
    {
        Transfer item = null;
        for ( final ContentGenerator generator : contentGenerators )
        {
            logger.trace( "Attempting to generate content, path: {}, store: {}, via: {}", path, store, generator );
            item = generator.generateFileContent( store, path, eventMetadata );
            if ( item != null )
            {
                consumer.accept( item );
                break;
            }
        }
        return item;
    }

    @Measure
    public Transfer generateGroupFileContent( Group group, List<ArtifactStore> members, String path,
                                              EventMetadata eventMetadata ) throws IndyWorkflowException
    {
        Transfer item = null;
        String storagePath =
                        pathGenerator.getPath( new ConcreteResource( LocationUtils.toLocation( group ), path ) );
        for ( final ContentGenerator generator : contentGenerators )
        {
            final boolean canProcess =  generator.canProcess( path ) || generator.canProcess( storagePath );
            if ( canProcess )
            {
                item = generator.generateGroupFileContent( group, members, path, eventMetadata );
                logger.trace( "From content {}.generateGroupFileContent: {} (exists? {})",
                              generator.getClass().getSimpleName(), item, item != null && item.exists() );
                if ( item != null && item.exists() )
                {
                    break;
                }
            }
        }
        return item;
    }

    @Measure
    public void generateGroupFileContentAnd( Group group, List<ArtifactStore> members, String path,
                                             EventMetadata eventMetadata, Consumer<Transfer> consumer )
                    throws IndyWorkflowException
    {
        for ( final ContentGenerator generator : contentGenerators )
        {
            final Transfer txfr = generator.generateGroupFileContent( group, members, path, eventMetadata );
            if ( txfr != null )
            {
                consumer.accept( txfr );
            }
        }
    }

    @Measure
    public void handleContentStorage( ArtifactStore transferStore, String path, Transfer txfr,
                                      EventMetadata eventMetadata ) throws IndyWorkflowException
    {
        for ( final ContentGenerator generator : contentGenerators )
        {
            logger.debug( "{} Handling content storage of: {} in: {}", generator, path, transferStore.getKey() );
            generator.handleContentStorage( transferStore, path, txfr, eventMetadata );
        }
    }

    @Measure
    public void handleContentDeletion( ArtifactStore member, String path, EventMetadata eventMetadata )
                    throws IndyWorkflowException
    {
        for ( final ContentGenerator generator : contentGenerators )
        {
            generator.handleContentDeletion( member, path, eventMetadata );
        }
    }

    @Measure
    public void generateGroupDirectoryContentAnd( Group group, List<ArtifactStore> members, String path,
                                                  EventMetadata eventMetadata, Consumer<List<StoreResource>> consumer )
                    throws IndyWorkflowException
    {
        for ( final ContentGenerator generator : contentGenerators )
        {
            final List<StoreResource> generated =
                            generator.generateGroupDirectoryContent( group, members, path, eventMetadata );
            if ( generated != null )
            {
                consumer.accept( generated );
            }
        }
    }

    @Measure
    public void generateDirectoryContentAnd( ArtifactStore store, String path, List<StoreResource> listed,
                                             EventMetadata metadata, Consumer<List<StoreResource>> consumer )
                    throws IndyWorkflowException
    {
        for ( final ContentGenerator producer : contentGenerators )
        {
            final List<StoreResource> produced = producer.generateDirectoryContent( store, path, listed, metadata );
            if ( produced != null )
            {
                consumer.accept( produced );
            }
        }
    }
}

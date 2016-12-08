package org.commonjava.indy.core.change;

import org.apache.commons.io.IOUtils;
import org.commonjava.cdi.util.weft.ExecutorConfig;
import org.commonjava.cdi.util.weft.WeftManaged;
import org.commonjava.indy.content.ContentManager;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.galley.KeyedLocation;
import org.commonjava.indy.content.RelationshipSet;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.maven.atlas.graph.rel.ProjectRelationship;
import org.commonjava.maven.atlas.ident.ref.ArtifactRef;
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.atlas.ident.util.ArtifactPathInfo;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.event.FileAccessEvent;
import org.commonjava.maven.galley.event.FileEvent;
import org.commonjava.maven.galley.event.FileStorageEvent;
import org.commonjava.maven.galley.maven.GalleyMavenException;
import org.commonjava.maven.galley.maven.model.view.DocRef;
import org.commonjava.maven.galley.maven.model.view.MavenPomView;
import org.commonjava.maven.galley.maven.parse.MavenPomReader;
import org.commonjava.maven.galley.maven.rel.MavenModelProcessor;
import org.commonjava.maven.galley.maven.rel.ModelProcessorConfig;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Created by jdcasey on 12/6/16.
 */
@ApplicationScoped
public class RelationshipParsingPomListener
{
    public static final String SERIALIZED_RELATIONSHIPS_EXT = ".rels.ser";

    @Inject
    private StoreDataManager storeDataManager;

    @Inject
    private MavenPomReader pomReader;

    @Inject
    private MavenModelProcessor modelProcessor;

    @Inject
    @WeftManaged
    @ExecutorConfig( threads = 4, priority = 1, named = "pom-relationship-parser", daemon = true )
    private Executor executor;

    public void onPomAccess( @Observes FileAccessEvent evt )
    {
        parseRelationships( evt );
    }

    public void onPomStorage( @Observes FileStorageEvent evt )
    {
        parseRelationships( evt );
    }

    private void parseRelationships( FileEvent evt )
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        Transfer transfer = evt.getTransfer();
        String path = transfer.getPath();

        if ( !path.endsWith( ".pom" ) )
        {
            logger.debug( "Not a pom. Skipping POM parse of: {}", transfer );
            return;
        }

        logger.trace( "Parsing/storing relationships contained in POM: {}", path );

        ArtifactPathInfo pathInfo = ArtifactPathInfo.parse( path );
        ArtifactRef artifactRef = pathInfo == null ? null : pathInfo.getArtifact();
        if ( artifactRef == null )
        {
            logger.trace( "{} is not a valid artifact reference. Skipping.", path );
            return;
        }

        Transfer serializeTo = transfer.getSiblingMeta( SERIALIZED_RELATIONSHIPS_EXT );

        // open stream OUTSIDE THE RUNNABLE to provide a joinable point where readers will wait for the relationships.
        // This should act like a natural sync point.
        ObjectOutputStream stream = null;
        try
        {
            stream = new ObjectOutputStream(
                    serializeTo.openOutputStream( TransferOperation.GENERATE ) );

            final ObjectOutputStream out = stream;
            executor.execute( new Runnable()
            {
                public void run()
                {
                    Logger logger = LoggerFactory.getLogger( getClass() );
                    try
                    {

                        StoreKey key = ( (KeyedLocation) transfer.getLocation() ).getKey();

                        EventMetadata eventMetadata = evt.getEventMetadata();
                        StoreKey originKey = null;
                        if ( eventMetadata != null )
                        {
                            originKey = (StoreKey) eventMetadata.get( ContentManager.ENTRY_POINT_STORE );
                        }

                        if ( originKey == null )
                        {
                            KeyedLocation kl = (KeyedLocation) transfer.getLocation();
                            originKey = kl.getKey();
                        }

                        try
                        {
                            ArtifactStore origin = storeDataManager.getArtifactStore( originKey );
                            KeyedLocation originLocation = LocationUtils.toLocation( origin );

                            MavenPomView pomView = pomReader.read( artifactRef.asProjectVersionRef(), transfer,
                                                                   Collections.singletonList( originLocation ),
                                                                   MavenPomView.ALL_PROFILES );

                            URI source = new URI( "indy:" + key.getType().name() + ":" + key.getName() );

                            ModelProcessorConfig config = new ModelProcessorConfig().setIncludeBuildSection( true )
                                                                                    .setIncludeManagedDependencies(
                                                                                            true );

                            Set<ProjectRelationship<?, ?>> relationships =
                                    modelProcessor.readRelationships( pomView, source, config ).getAllRelationships();

                            Map<ProjectVersionRef, StoreKey> pomSources = new LinkedHashMap<>();
                            Consumer<DocRef<ProjectVersionRef>> consumer = ( dr ) -> pomSources.put( dr.getRef(),
                                                                                                     ( (KeyedLocation) dr
                                                                                                             .getSource() )
                                                                                                             .getKey() );

                            pomView.getDocRefStack().forEach( consumer );
                            pomView.getMixins()
                                   .forEach( ( mixin ) -> mixin.getMixin().getDocRefStack().forEach( consumer ) );

                            out.writeObject( new RelationshipSet( artifactRef, pomSources, relationships ) );
                        }
                        catch ( final URISyntaxException e )
                        {
                            logger.error(
                                    "Failed to construct URI for ArtifactStore: " + key + ". Reason: " + e.getMessage(),
                                    e );
                        }
                        catch ( IndyDataException e )
                        {
                            logger.error(
                                    String.format( "Cannot retrieve ArtifactStore for request origin: %s. Reason: %s",
                                                   originKey, e.getMessage() ), e );
                        }
                        catch ( GalleyMavenException e )
                        {
                            logger.error(
                                    String.format( "Cannot parse relationships from POM: %s. Reason: %s", transfer,
                                                   e.getMessage() ), e );
                        }
                        catch ( IOException e )
                        {
                            logger.error( String.format( "Cannot serialize relationships for: %s to: %s. Reason: %s",
                                                         transfer, serializeTo, e.getMessage() ), e );
                        }
                    }
                    catch ( RuntimeException e )
                    {
                        logger.error(
                                String.format( "Cannot parse relationships from POM: %s. Reason: %s", evt.getTransfer(),
                                               e.getMessage() ), e );
                    }
                    finally
                    {
                        IOUtils.closeQuietly( out );
                    }
                }
            } );
        }
        catch ( IOException e )
        {
            logger.error(
                    String.format( "Cannot serialize relationships for: %s to: %s. Reason: %s", transfer, serializeTo,
                                   e.getMessage() ), e );
            IOUtils.closeQuietly( stream );
        }
    }

    public static RelationshipSet readRelationshipsDerivedFrom( Transfer pomTransfer )
            throws IOException
    {
        if ( pomTransfer != null )
        {
            Transfer relTransfer = pomTransfer.getSiblingMeta( SERIALIZED_RELATIONSHIPS_EXT );
            if ( relTransfer != null && relTransfer.exists() )
            {
                try(ObjectInputStream in = new ObjectInputStream( relTransfer.openInputStream( false ) ) )
                {
                    return (RelationshipSet) in.readObject();
                }
                catch ( ClassNotFoundException e )
                {
                    throw new IOException(
                            "Cannot read relationships for: " + pomTransfer + ". Reason: " + e.getMessage(), e );
                }
            }
        }

        return null;
    }
}

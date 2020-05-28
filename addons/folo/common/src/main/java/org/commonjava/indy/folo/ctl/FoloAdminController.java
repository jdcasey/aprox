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
package org.commonjava.indy.folo.ctl;

import org.commonjava.cdi.util.weft.DrainingExecutorCompletionService;
import org.commonjava.cdi.util.weft.ExecutorConfig;
import org.commonjava.cdi.util.weft.SingleThreadedExecutorService;
import org.commonjava.cdi.util.weft.WeftExecutorService;
import org.commonjava.cdi.util.weft.WeftManaged;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.content.ContentDigester;
import org.commonjava.indy.content.ContentManager;
import org.commonjava.indy.folo.conf.FoloConfig;
import org.commonjava.indy.folo.data.FoloContentException;
import org.commonjava.indy.folo.data.FoloFiler;
import org.commonjava.indy.folo.data.FoloRecordCache;
import org.commonjava.indy.folo.dto.TrackedContentDTO;
import org.commonjava.indy.folo.dto.TrackedContentEntryDTO;
import org.commonjava.indy.folo.dto.TrackingIdsDTO;
import org.commonjava.indy.folo.model.StoreEffect;
import org.commonjava.indy.folo.model.TrackedContent;
import org.commonjava.indy.folo.model.TrackedContentEntry;
import org.commonjava.indy.folo.model.TrackingKey;
import org.commonjava.indy.model.core.AccessChannel;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.util.ApplicationStatus;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.io.checksum.ContentDigest;
import org.commonjava.maven.galley.io.checksum.TransferMetadata;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.util.UrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.apache.commons.io.IOUtils.closeQuietly;
import static org.apache.commons.io.IOUtils.copy;
import static org.commonjava.indy.core.ctl.PoolUtils.detectOverloadVoid;
import static org.commonjava.indy.folo.FoloUtils.backupTrackedContent;
import static org.commonjava.indy.folo.FoloUtils.readZipInputStreamAnd;
import static org.commonjava.indy.folo.FoloUtils.toInputStream;
import static org.commonjava.indy.folo.FoloUtils.zipTrackedContent;
import static org.commonjava.indy.folo.ctl.FoloConstants.TRACKING_TYPE.SEALED;

@ApplicationScoped
public class FoloAdminController
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private FoloConfig config;

    @Inject
    private FoloRecordCache recordManager;

    @Inject
    private FoloFiler filer;

    @Inject
    private ContentManager contentManager;

    @Inject
    private ContentDigester contentDigester;

    @Inject
    @WeftManaged
    @ExecutorConfig( threads = 50, priority = 4, named = "folo-recalculator", maxLoadFactor = 100, loadSensitive = ExecutorConfig.BooleanLiteral.TRUE )
    private WeftExecutorService recalculationExecutor;

    protected FoloAdminController()
    {
    }

    public FoloAdminController( final FoloConfig config, final FoloRecordCache recordManager, final FoloFiler filer,
                                final ContentManager contentManager, final ContentDigester contentDigester )
    {
        this.config = config;
        this.recordManager = recordManager;
        this.filer = filer;
        this.contentManager = contentManager;
        this.contentDigester = contentDigester;
        this.recalculationExecutor = new SingleThreadedExecutorService( "folo-recalculator" );
    }

    public TrackedContentDTO seal( final String id, final String baseUrl )
    {
        TrackingKey tk = new TrackingKey( id );
        return constructContentDTO( recordManager.seal( tk ), baseUrl );
    }

    public void importRecordZip( InputStream stream ) throws IndyWorkflowException
    {
        try
        {
            int count = readZipInputStreamAnd( stream, (record) -> recordManager.addSealedRecord( record ) );
            logger.debug( "Import records done, size: {}", count );
        }
        catch ( Exception e )
        {
            throw new IndyWorkflowException("Failed to import zip file", e);
        }
    }

    public File renderReportZip() throws IndyWorkflowException
    {
        Set<TrackedContent> sealed = recordManager.getSealed(); // only care about sealed records
        try
        {
            File file = filer.getSealedZipFile().getDetachedFile();
            if ( file.exists() )
            {
                file.delete();
            }
            file.getParentFile().mkdirs(); // make dirs if not exist

            zipTrackedContent( file, sealed );

            return file;
        }
        catch ( IOException e )
        {
            throw new IndyWorkflowException("Failed to create zip file", e);
        }
    }

    public void doInitialBackUpForSealed() throws IndyWorkflowException
    {
        Set<TrackedContent> sealed = recordManager.getSealed();
        File dir = filer.getBackupDir( SEALED.getValue() ).getDetachedFile(); // data/folo/bak/sealed
        try
        {
            backupTrackedContent( dir, sealed );
        }
        catch ( IOException e )
        {
            throw new IndyWorkflowException("Failed to backup sealed", e);
        }
    }

    public File renderRepositoryZip( final String id )
            throws IndyWorkflowException
    {
        final TrackingKey tk = new TrackingKey( id );

        File file = filer.getRepositoryZipFile( tk ).getDetachedFile();
        file.getParentFile().mkdirs();
        logger.debug( "Retrieving tracking record for: {}", tk );
        final TrackedContent record = recordManager.get( tk );
        logger.debug( "Got: {}", record );

        if ( record == null )
        {
            throw new IndyWorkflowException( ApplicationStatus.NOT_FOUND.code(),
                                             "No tracking record available for: %s. Maybe you forgot to seal it?", tk );
        }

        final Set<String> seenPaths = new HashSet<>();
        final List<Transfer> items = new ArrayList<>();

        addTransfers( record.getUploads(), items, id, seenPaths );
        addTransfers( record.getDownloads(), items, id, seenPaths );

        logger.debug( "Retrieved {} files. Creating zip.", items.size() );

        Collections.sort( items, ( f, s ) -> f.getPath().compareTo( s.getPath() ) );

        try (ZipOutputStream stream = new ZipOutputStream( new FileOutputStream( file ) ))
        {
            for ( final Transfer item : items )
            {
                // logger.info( "Adding: {}", item );
                if ( item != null )
                {
                    final String path = item.getPath();
                    final ZipEntry ze = new ZipEntry( path );
                    stream.putNextEntry( ze );

                    InputStream itemStream = null;
                    try
                    {
                        itemStream = item.openInputStream();
                        copy( itemStream, stream );
                    }
                    finally
                    {
                        closeQuietly( itemStream );
                    }
                }
            }
        }
        catch ( final IOException e )
        {
            throw new IndyWorkflowException( "Failed to generate repository zip from tracking record: {}. Reason: {}",
                                             e, id, e.getMessage() );
        }

        return file;
    }

    private void addTransfers( final Set<TrackedContentEntry> entries, final List<Transfer> items,
                               final String trackingId, final Set<String> seenPaths )
            throws IndyWorkflowException
    {
        if ( entries != null && !entries.isEmpty() )
        {
            for ( final TrackedContentEntry entry : entries )
            {
                final String path = entry.getPath();
                if ( path == null || seenPaths.contains( path ) )
                {
                    continue;
                }
                final StoreKey sk = entry.getStoreKey();
                Transfer transfer = contentManager.getTransfer( sk, path, TransferOperation.DOWNLOAD );
                if ( transfer == null )
                {
                    Logger logger = LoggerFactory.getLogger( getClass() );
                    logger.warn( "While creating Folo repo zip for: {}, cannot find: {} in: {}", trackingId, path, sk );
                }
                else
                {
                    seenPaths.add( path );
                    items.add( transfer );
                }
            }
        }
    }

    public TrackedContentDTO renderReport( final String id, final String apiBaseUrl )
            throws IndyWorkflowException
    {
        final TrackingKey tk = new TrackingKey( id );
        logger.debug( "Retrieving tracking record for: {}", tk );
        final TrackedContentDTO record = constructContentDTO( recordManager.get( tk ), apiBaseUrl );
        logger.debug( "Got: {}", record );

        return record;
    }

    public TrackedContentDTO getRecord( final String id, String baseUrl )
            throws IndyWorkflowException
    {
        final TrackingKey tk = new TrackingKey( id );
        return constructContentDTO( recordManager.get( tk ), baseUrl );
    }

    public void clearRecord( final String id )
            throws FoloContentException
    {
        final TrackingKey tk = new TrackingKey( id );
        recordManager.delete( tk );
    }

    private TrackedContentDTO constructContentDTO( final TrackedContent content, final String baseUrl )
    {
        if ( content == null )
        {
            return null;
        }
        final Set<TrackedContentEntryDTO> uploads = new TreeSet<>();
        for ( TrackedContentEntry entry : content.getUploads() )
        {
            uploads.add( constructContentEntryDTO( entry, baseUrl ) );
        }

        final Set<TrackedContentEntryDTO> downloads = new TreeSet<>();
        for ( TrackedContentEntry entry : content.getDownloads() )
        {
            downloads.add( constructContentEntryDTO( entry, baseUrl ) );
        }
        return new TrackedContentDTO( content.getKey(), uploads, downloads );
    }

    private TrackedContentEntryDTO constructContentEntryDTO( final TrackedContentEntry entry, String apiBaseUrl )
    {
        if ( entry == null )
        {
            return null;
        }
        TrackedContentEntryDTO entryDTO =
                new TrackedContentEntryDTO( entry.getStoreKey(), entry.getAccessChannel(), entry.getPath() );

        try
        {
            entryDTO.setLocalUrl( UrlUtils.buildUrl( apiBaseUrl, "content", entryDTO.getStoreKey().getPackageType(),
                                                     entryDTO.getStoreKey().getType().singularEndpointName(),
                                                     entryDTO.getStoreKey().getName(), entryDTO.getPath() ) );
        }
        catch ( MalformedURLException e )
        {
            logger.warn( String.format( "Cannot formulate local URL!\n  Base URL: %s"
                                                + "\n  Store: %s\n  Path: %s\n  Record: %s\n  Reason: %s", apiBaseUrl,
                                        entry.getStoreKey(), entry.getPath(), entry.getTrackingKey(), e.getMessage() ),
                         e );
        }

        entryDTO.setOriginUrl( entry.getOriginUrl() );
        entryDTO.setMd5( entry.getMd5() );
        entryDTO.setSha1( entry.getSha1() );
        entryDTO.setSha256( entry.getSha256() );
        entryDTO.setSize( entry.getSize() );
        entryDTO.setTimestamps( entry.getTimestamps() );
        return entryDTO;
    }

    public boolean hasRecord( final String id )
    {
        return recordManager.hasRecord( new TrackingKey( id ) );
    }

    public TrackingIdsDTO getTrackingIds( final Set<FoloConstants.TRACKING_TYPE> types )
    {

        Set<String> inProgress = null;
        if ( types.contains( FoloConstants.TRACKING_TYPE.IN_PROGRESS ) )
        {
            inProgress = recordManager.getInProgressTrackingKey()
                                      .stream()
                                      .map( TrackingKey::getId )
                                      .collect( Collectors.toSet() );
        }
        Set<String> sealed = null;
        if ( types.contains( FoloConstants.TRACKING_TYPE.SEALED ) )
        {
            sealed = recordManager.getSealedTrackingKey()
                                  .stream()
                                  .map( TrackingKey::getId )
                                  .collect( Collectors.toSet() );
        }
        if ( ( inProgress != null && !inProgress.isEmpty() ) || ( sealed != null && !sealed.isEmpty() ) )
        {
            return new TrackingIdsDTO( inProgress, sealed );
        }
        return null;
    }

    public TrackedContentDTO recalculateRecord( final String id, final String baseUrl )
            throws IndyWorkflowException
    {
        TrackingKey trackingKey = new TrackingKey( id );
        TrackedContent record = recordManager.get( trackingKey );

        AtomicBoolean failed = new AtomicBoolean( false );

        Set<TrackedContentEntry> recalculatedUploads = recalculateEntrySet( record.getUploads(), id, failed );
        Set<TrackedContentEntry> recalculatedDownloads = null;
        if ( !failed.get() )
        {
            recalculatedDownloads = recalculateEntrySet( record.getDownloads(), id, failed );
        }

        if ( failed.get() )
        {
            throw new IndyWorkflowException(
                    "Failed to recalculate tracking record: %s. See Indy logs for more information", id );
        }

        TrackedContent recalculated = new TrackedContent( record.getKey(), recalculatedUploads, recalculatedDownloads );
        recordManager.replaceTrackingRecord( recalculated );

        return constructContentDTO( recalculated, baseUrl );
    }

    private Set<TrackedContentEntry> recalculateEntrySet( final Set<TrackedContentEntry> entries,
                                                          final String id, final AtomicBoolean failed )
            throws IndyWorkflowException
    {
        if ( entries == null )
        {
            return null;
        }

        DrainingExecutorCompletionService<TrackedContentEntry> recalculateService =
                new DrainingExecutorCompletionService<>( recalculationExecutor );

        detectOverloadVoid( () -> entries.forEach( entry -> recalculateService.submit( () -> {
            try
            {
                return recalculate( entry );
            }
            catch ( IndyWorkflowException e )
            {
                logger.error( String.format( "Tracking record: %s : Failed to recalculate: %s/%s (%s). Reason: %s", id,
                                             entry.getStoreKey(), entry.getPath(), entry.getEffect(), e.getMessage() ),
                              e );

                failed.set( true );
            }
            return null;
        } ) ) );

        Set<TrackedContentEntry> result = new HashSet<>();
        try
        {
            recalculateService.drain( entry-> {
                if ( entry != null )
                {
                    result.add( entry );
                }
            } );
        }
        catch ( InterruptedException | ExecutionException e )
        {
            logger.error( "Failed to recalculate metadata for Folo tracked content entries in: " + id, e );
            failed.set( true );
        }

        return result;
    }

    private TrackedContentEntry recalculate( final TrackedContentEntry entry )
            throws IndyWorkflowException
    {
        StoreKey affectedStore = entry.getStoreKey();
        String path = entry.getPath();
        AccessChannel channel = entry.getAccessChannel();

        Transfer transfer = contentManager.getTransfer( affectedStore, path, entry.getEffect() == StoreEffect.UPLOAD ?
                TransferOperation.UPLOAD :
                TransferOperation.DOWNLOAD );

        if ( transfer == null )
        {
            return entry;
        }

        contentDigester.removeMetadata( transfer );

        TransferMetadata artifactData =
                contentDigester.digest( affectedStore, path, new EventMetadata( affectedStore.getPackageType() ) );

        Map<ContentDigest, String> digests = artifactData.getDigests();
        return new TrackedContentEntry( entry.getTrackingKey(), affectedStore, channel, entry.getOriginUrl(), path,
                                        entry.getEffect(), artifactData.getSize(), digests.get( ContentDigest.MD5 ),
                                        digests.get( ContentDigest.SHA_1 ), digests.get( ContentDigest.SHA_256 ) );
    }

    public void saveToSerialized( TrackingKey key, TrackedContent value ) throws IOException
    {
        File dir = filer.getBackupDir( SEALED.getValue() ).getDetachedFile();
        File file = new File( dir, key.getId() );
        try ( OutputStream fos = new FileOutputStream( file ) )
        {
            copy( toInputStream( value ), fos );
        }
    }

    public void removeFromSerialized( TrackingKey key )
    {
        File dir = filer.getBackupDir( SEALED.getValue() ).getDetachedFile();
        File file = new File( dir, key.getId() );
        if ( file.exists() )
        {
            file.delete();
        }
    }
}

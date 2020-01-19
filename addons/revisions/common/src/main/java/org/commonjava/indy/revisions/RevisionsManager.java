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
package org.commonjava.indy.revisions;

import static org.apache.commons.lang.StringUtils.isEmpty;
import static org.apache.commons.lang.StringUtils.join;
import static org.commonjava.indy.audit.ChangeSummary.SYSTEM_USER;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import org.apache.commons.io.FileUtils;
import org.commonjava.indy.audit.ChangeSummary;
import org.commonjava.indy.change.event.IndyLifecycleEvent;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.propulsor.metrics.annotation.Measure;
import org.commonjava.indy.measure.annotation.MetricNamed;

import static org.commonjava.indy.flat.data.DataFileStoreUtils.INDY_STORE;
import static org.commonjava.indy.metrics.IndyMetricsConstants.DEFAULT;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.revisions.conf.RevisionsConfig;
import org.commonjava.indy.subsys.datafile.DataFile;
import org.commonjava.indy.subsys.datafile.DataFileManager;
import org.commonjava.indy.subsys.datafile.change.DataFileEvent;
import org.commonjava.indy.subsys.datafile.change.DataFileEventType;
import org.commonjava.indy.subsys.git.GitConfig;
import org.commonjava.indy.subsys.git.GitManager;
import org.commonjava.indy.subsys.git.GitSubsystemException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class RevisionsManager
{

    private static final String[] DATA_DIR_GITIGNORES = { "depgraph", "scheduler" };

    public static final String CATCHUP_CHANGELOG_MODIFIED = "Add files modified outside of the Indy UI.";

    public static final String CATCHUP_CHANGELOG_DELETED = "Delete files removed outside of the Indy UI.";

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    private GitManager dataFileGit;

    private boolean started;

    @Inject
    private RevisionsConfig revisionsConfig;

    @Inject
    private DataFileManager dataFileManager;

    @Inject
    private StoreDataManager storeManager;

    protected RevisionsManager()
    {
    }

    public RevisionsManager( final RevisionsConfig revisionsConfig, final DataFileManager dataFileManager,
                             final StoreDataManager storeManager )
        throws GitSubsystemException, IOException
    {
        this.revisionsConfig = revisionsConfig;
        this.dataFileManager = dataFileManager;
        this.storeManager = storeManager;
        setup();
    }

    @PostConstruct
    public void setup()
    {
        if ( !revisionsConfig.isEnabled() )
        {
            return;
        }
        
        try
        {
            final File dataDir = dataFileManager.getDetachedDataBasedir();
            final File gitignore = new File( dataDir, ".gitignore" );

            dataDir.mkdirs();
            FileUtils.write( gitignore, join( DATA_DIR_GITIGNORES, "\n" ) );

            final GitConfig dataConf =
                new GitConfig( dataDir, revisionsConfig.getDataUpstreamUrl(), true ).setRemoteBranchName( revisionsConfig.getBranchName() )
                                                                                    .setUserEmail( revisionsConfig.getUserEmail() );

            dataFileGit = new GitManager( dataConf );

            // we need a TimerTask that will commit modifications periodically
            Timer timer = new Timer( true);
            timer.scheduleAtFixedRate( new TimerTask()
            {
                @Override
                public void run()
                {
                    try
                    {
                        int committed = commitDataUpdates();
                        if ( committed > 0 )
                        {
                            logger.info( "Commit and push data updates, size: " + committed );
                            pushDataUpdates();
                        }
                    }
                    catch ( GitSubsystemException e )
                    {
                        logger.warn( "Failed to push data updates", e );
                    }
                }
            }, 1000, 60 * 1000 ); // every 1 min
        }
        catch ( GitSubsystemException | IOException e )
        {
            throw new IllegalStateException( "Failed to start revisions manager: " + e.getMessage(), e );
        }
        finally
        {
        }
    }

    public void onLifecycleEvent( @Observes final IndyLifecycleEvent event )
    {
        if ( !revisionsConfig.isEnabled() )
        {
            return;
        }

        if ( IndyLifecycleEvent.Type.started == event.getType() )
        {
            started = true;

            try
            {
                logger.info( "Indy started; committing externally changed files." );

                ChangeSummary summary = new ChangeSummary( SYSTEM_USER, CATCHUP_CHANGELOG_MODIFIED );
                dataFileGit.addExternallyChangedFiles( summary );

                summary = new ChangeSummary( SYSTEM_USER, CATCHUP_CHANGELOG_DELETED );
                dataFileGit.deleteExternallyRemovedFiles( summary );

                dataFileGit.commit();

                if ( revisionsConfig.isPushEnabled() )
                {
                    dataFileGit.pushUpdates();
                }
            }
            catch ( final GitSubsystemException e )
            {
                logger.error( "Failed to commit pre-existing uncommitted changes in revisions manager: "
                                  + e.getMessage(), e );
            }
        }
    }

    public void onDataFileEvent( @Observes final DataFileEvent event )
    {
        if ( !revisionsConfig.isEnabled() )
        {
            return;
        }

        if ( !started )
        {
            logger.debug( "Indy system is not marked as started. Skipping data file events in revisions manager." );
            return;
        }

        try
        {
            if ( event.getType() == DataFileEventType.accessed )
            {
                return;
            }

            addOrDeleteFiles( event );
        }
        catch ( final GitSubsystemException e )
        {
            logger.error( String.format( "Failed to commit changes: %s. Reason: %s", event, e.getMessage() ), e );
        }
    }

    @Measure( timers = @MetricNamed( DEFAULT ) )
    private void addOrDeleteFiles( DataFileEvent event ) throws GitSubsystemException
    {
        if ( event.getType() == DataFileEventType.deleted )
        {
            dataFileGit.delete( event.getSummary(), event.getFile() );
        }
        else
        {
            dataFileGit.addFiles( event.getSummary(), event.getFile() );
        }
    }

    public void pullDataUpdates()
        throws GitSubsystemException
    {
        if ( !revisionsConfig.isEnabled() )
        {
            return;
        }

        dataFileGit.pullUpdates( revisionsConfig.getConflictStrategy() );

        // FIXME: fire events to signal data owners to reload...
    }

    @Measure( timers = @MetricNamed( DEFAULT ) )
    public int commitDataUpdates()
                    throws GitSubsystemException
    {
        if ( !revisionsConfig.isEnabled() )
        {
            return 0;
        }

        return dataFileGit.commit();
    }

    @Measure( timers = @MetricNamed( DEFAULT ) )
    public void pushDataUpdates()
        throws GitSubsystemException
    {
        if ( !revisionsConfig.isEnabled() )
        {
            return;
        }

        if ( revisionsConfig.isPushEnabled() )
        {
            dataFileGit.pushUpdates();
        }
    }

    public List<ChangeSummary> getDataChangeLog( final StoreKey key, final int start, final int count )
        throws GitSubsystemException
    {
        if ( !revisionsConfig.isEnabled() )
        {
            return Collections.emptyList();
        }

        final DataFile dataFile = getDataFile( key );
        return dataFileGit.getChangelog( dataFile.getDetachedFile(), start, count );
    }

    private DataFile getDataFile( final StoreKey key )
    {
        return dataFileManager.getDataFile( INDY_STORE, key.getType().singularEndpointName(), key.getName() + ".json" );
    }

    public List<ChangeSummary> getDataChangeLog( String path, final int start, final int length )
        throws GitSubsystemException
    {
        if ( !revisionsConfig.isEnabled() )
        {
            return Collections.emptyList();
        }

        final File basedir = dataFileManager.getDetachedDataBasedir();
        if ( new File( path ).isAbsolute() )
        {
            if ( !path.startsWith( basedir.getPath() ) )
            {
                throw new GitSubsystemException( "Cannot reference path outside of data basedir." );
            }

            path = Paths.get( basedir.toURI() )
                        .relativize( Paths.get( path ) )
                        .toString();
        }

        final File file;
        if ( isEmpty( path ) || path.equals( "/" ) )
        {
            file = basedir;
        }
        else
        {
            file = dataFileManager.getDataFile( path )
                                  .getDetachedFile();
        }

        return dataFileGit.getChangelog( file, start, length );
    }

    public List<ChangeSummary> getDataChangeLog( final File f, final int start, final int count )
        throws GitSubsystemException
    {
        if ( !revisionsConfig.isEnabled() )
        {
            return Collections.emptyList();
        }

        return dataFileGit.getChangelog( f, start, count );
    }

}

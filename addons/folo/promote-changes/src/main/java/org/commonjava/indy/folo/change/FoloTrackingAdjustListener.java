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
package org.commonjava.indy.folo.change;

import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.folo.conf.FoloConfig;
import org.commonjava.indy.folo.data.FoloRecordCache;
import org.commonjava.indy.folo.model.TrackedContent;
import org.commonjava.indy.folo.model.TrackedContentEntry;
import org.commonjava.indy.folo.model.TrackingKey;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.promote.change.PromoteChangeManager;
import org.commonjava.indy.promote.change.event.PathsPromoteCompleteEvent;
import org.commonjava.indy.promote.change.event.PromoteCompleteEvent;
import org.commonjava.indy.promote.model.PathsPromoteRequest;
import org.commonjava.indy.promote.model.PathsPromoteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import java.util.Set;

@ApplicationScoped
public class FoloTrackingAdjustListener
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private FoloConfig foloConfig;

    @Inject
    private FoloRecordCache recordManager;

    @Inject
    private PromoteChangeManager promoteChangeManager;

    @Inject
    private StoreDataManager storeManager;

    public void onPromoteComplete( @Observes final PromoteCompleteEvent event )
    {
        logger.trace( "Promote COMPLETE: {}", event );

        if ( !foloConfig.isEnabled() )
        {
            return;
        }

        if ( event instanceof PathsPromoteCompleteEvent )
        {
            handlePathsPromoteComplete( ( (PathsPromoteCompleteEvent) event ).getPromoteResult() );
        }
    }

    private void handlePathsPromoteComplete( PathsPromoteResult promoteResult )
    {
        String error = promoteResult.getError();
        if ( error != null )
        {
            logger.trace( "Error in promoteResult, skip adjust" );
            return;
        }

        Set<String> paths = promoteResult.getCompletedPaths();
        if ( paths.isEmpty() )
        {
            logger.trace( "No completedPaths, skip adjust" );
            return;
        }

        PathsPromoteRequest req = promoteResult.getRequest();
        StoreKey source = req.getSource();
        StoreKey target = req.getTarget();

        TrackingKey trackingKey = getTrackingKey( source );
        if ( trackingKey == null )
        {
            logger.trace( "No tracking key found to: {}", source );
            return;
        }

        // Get the sealed record, client MUST seal the record before promote
        TrackedContent trackedContent = recordManager.get( trackingKey );
        if ( trackedContent == null )
        {
            logger.trace( "No sealed record found, trackingKey: {}", trackingKey );
            return;
        }

        adjustTrackedContent( trackedContent, source, target );

        recordManager.replaceTrackingRecord( trackedContent );
    }

    private void adjustTrackedContent( TrackedContent trackedContent, StoreKey source, StoreKey target )
    {
        Set<TrackedContentEntry> uploads = trackedContent.getUploads();
        uploads.forEach( entry ->
                         {
                             entry.setStoreKey( target );
                         } );
    }

    private TrackingKey getTrackingKey( StoreKey source )
    {
        if ( source.getType() == StoreType.hosted )
        {
            return new TrackingKey( promoteChangeManager.getTrackingIdFormatter().format( source ) );
        }
        else
        {
            /* TODO: For remote, we can not get the tracking id by solely source repo name.
             * E.g., we promote ant from { "storeKey" : "maven:remote:central", "path" : "/ant/ant-launcher/1.6.5/ant-launcher-1.6.5.jar" }
             * into some hosted repo shared-imports, we really need to adjust all of those tracking records.
             *
             * One workaround is not to promote any remote repo artifact to hosted, or promote it with purse as false so the original
             * paths were still valid for a reproducer build.
             */
            return null;
        }
    }

}

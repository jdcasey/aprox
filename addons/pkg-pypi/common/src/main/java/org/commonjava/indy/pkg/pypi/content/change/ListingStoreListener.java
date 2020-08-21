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
package org.commonjava.indy.pkg.pypi.content.change;

import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.galley.KeyedLocation;
import org.commonjava.indy.pkg.pypi.content.cdn.CDNRedirectionDatabase;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.event.FileDeletionEvent;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.model.Transfer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.event.Observes;
import javax.inject.Inject;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.commonjava.indy.IndyContentConstants.CHECK_CACHE_ONLY;
import static org.commonjava.indy.pkg.pypi.model.PyPIPackageTypeDescriptor.PYPI_PKG_KEY;

@ApplicationScoped
public class ListingStoreListener
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private CDNRedirectionDatabase redirectionDatabase;

    @Inject
    private StoreDataManager storeManager;

    public void onMetadataFileDelete( @Observes final FileDeletionEvent event )
    {
        EventMetadata eventMetadata = event.getEventMetadata();
        if ( !Boolean.TRUE.equals( eventMetadata.get( CHECK_CACHE_ONLY ) ) )
        {
            return;
        }

        logger.trace( "Got file-delete event: {}", event );

        Transfer transfer = event.getTransfer();
        Location loc = transfer.getLocation();

        if ( !( loc instanceof KeyedLocation ) )
        {
            logger.trace( "Ignore FileDeletionEvent, not a KeyedLocation, location: {}", loc );
            return;
        }

        KeyedLocation keyedLocation = (KeyedLocation) loc;
        StoreKey storeKey = keyedLocation.getKey();
        if ( PYPI_PKG_KEY != storeKey.getPackageType() )
        {
            return;
        }

        String path = transfer.getPath();
        if ( !path.endsWith( ".html" ) )
        {
            logger.trace( "Not .html , path: {}", path );
            return;
        }

        redirectionDatabase.clear( storeKey, path );
    }

}

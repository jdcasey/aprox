package org.commonjava.indy.core.bind.jaxrs.util;

import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.spi.cache.CacheProvider;
import org.commonjava.maven.galley.util.PathUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MaintenanceController
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private CacheProvider cacheProvider;

    @Inject
    private StoreDataManager storeDataManager;

    public Set<StoreKey> getTombstoneStores( String packageType ) throws IndyDataException
    {
        List<HostedRepository> stores = storeDataManager.query().packageType( packageType ).getAllHostedRepositories();
        Set<StoreKey> tombstoneStores = new HashSet<>();
        for ( HostedRepository hosted : stores )
        {
            StoreKey key = hosted.getKey();
            ConcreteResource root = new ConcreteResource( LocationUtils.toLocation( hosted ), PathUtils.ROOT );
            String[] files = cacheProvider.list( root );
            if ( files == null || files.length == 0 )
            {
                logger.debug( "Empty store: {}", key );
                Set<Group> affected = storeDataManager.affectedBy( Arrays.asList( key ) );
                if ( affected == null || affected.isEmpty() )
                {
                    logger.info( "Find tombstone store (no content and not in any group): {}", key );
                    tombstoneStores.add( key );
                }
            }
        }
        return tombstoneStores;
    }

}

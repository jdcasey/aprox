package org.commonjava.indy.pkg.pypi.content.cdn;

import org.commonjava.indy.model.galley.KeyedLocation;
import org.commonjava.maven.galley.TransferException;
import org.commonjava.maven.galley.TransferManager;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.transport.htcli.HttpClientTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import static org.commonjava.indy.pkg.pypi.model.PyPIPackageTypeDescriptor.PYPI_PKG_KEY;

@ApplicationScoped
public class CDNTransport
                extends HttpClientTransport
{
    @Inject
    private CDNRedirectionDatabase cdn;

    @Inject
    private TransferManager transferManager;

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Override
    public boolean handles( Location location )
    {
        // NOTE: We'll allow the transport manager decorator to determine this!
        return false;
    }

    @Override
    protected String getUrl( ConcreteResource resource ) throws TransferException
    {
        String url = getCDNUrl( resource );
        return url == null ? super.getUrl( resource ) : url;
    }

    public String getCDNUrl( ConcreteResource resource ) throws TransferException
    {
        if ( !( resource.getLocation() instanceof KeyedLocation ) )
        {
            return null;
        }

        KeyedLocation kl = (KeyedLocation) resource.getLocation();
        if ( !PYPI_PKG_KEY.equals( kl.getKey().getPackageType() ) )
        {
            return null;
        }

        String url = cdn.lookup( kl.getKey(), resource.getPath() );

        // FIXME: Factor this out into a package-type-specific CDN populator!
        if ( url == null && !resource.isRoot() && resource.getPath().indexOf('.') > -1 )
        {
            logger.debug( "No CDN URL found for: {}:{}. Retrieving parent to initialize CDN mappings...", kl.getKey(), resource.getPath() );
            if ( resource.isRoot() )
            {
                logger.debug( "Cannot lookup parent, this is the root path! {}", resource );
            }
            else{
                ConcreteResource parent = resource.getParent();
                parent = new ConcreteResource( parent.getLocation(), parent.getPath() + "/" );
                logger.debug( "Looking up: {}", parent );
                transferManager.retrieve( parent );
                logger.debug( "CDN should be populated; re-resolving redirect for: {}", resource );
                url = cdn.lookup( kl.getKey(), resource.getPath() );
            }
        }

        return url;
    }
}

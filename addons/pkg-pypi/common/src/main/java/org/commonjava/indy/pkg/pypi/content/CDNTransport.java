package org.commonjava.indy.pkg.pypi.content;

import org.commonjava.indy.model.galley.KeyedLocation;
import org.commonjava.maven.galley.TransferException;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.transport.htcli.HttpClientTransport;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import static org.commonjava.indy.pkg.pypi.model.PyPIPackageTypeDescriptor.PYPI_PKG_KEY;

@ApplicationScoped
public class CDNTransport
                extends HttpClientTransport
{
    @Inject
    private CDNRedirectionDatabase cdn;

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

    public String getCDNUrl( ConcreteResource resource )
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

        return cdn.lookup( kl.getKey(), resource.getPath() );
    }
}

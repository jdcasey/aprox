package org.commonjava.indy.pkg.pypi.content;

import org.commonjava.maven.galley.TransferException;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.spi.transport.Transport;
import org.commonjava.maven.galley.spi.transport.TransportManager;

import javax.decorator.Decorator;
import javax.decorator.Delegate;
import javax.enterprise.inject.Any;
import javax.inject.Inject;

@Decorator
public abstract class CDNTransportRedirector
    implements TransportManager
{

//    @Any
//    @Delegate
//    @Inject
    private TransportManager delegate;

//    @Inject
    private CDNTransport cdnTransport;

    @Inject
    public CDNTransportRedirector( @Any @Delegate TransportManager delegate, CDNTransport cdnTransport )
    {
        this.delegate = delegate;
        this.cdnTransport = cdnTransport;
    }

    @Override
    public Transport getTransport( ConcreteResource resource ) throws TransferException
    {
        String cdnUrl = cdnTransport.getCDNUrl( resource );
        return cdnUrl == null ? delegate.getTransport( resource ) : cdnTransport;
    }
}

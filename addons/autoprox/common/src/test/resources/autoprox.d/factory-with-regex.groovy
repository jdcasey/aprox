import org.commonjava.indy.model.core.*;
import org.commonjava.indy.autoprox.data.*;

class FactoryWithRegexRule extends AbstractAutoProxRule
{

    boolean matches( String packageType, String named )
    {
        true;
    }
    
    RemoteRepository createRemoteRepository( String packageType, String named )
        throws MalformedURLException
    {
        def match = (named =~ /prod-([^0-9]+)([0-9])(.+)/)[0]
        new RemoteRepository( packageType, name: named, url: "http://repository.myco.com/products/${match[1] + match[2]}/${match[2] + match[3]}/" )
    }

    HostedRepository createHostedRepository( String packageType, String named )
    {
        new HostedRepository( packageType, named )
    }

    Group createGroup( String packageType, String named )
    {
        Group g = new Group( packageType, named );
        g.addConstituent( new StoreKey( StoreType.remote, "central" ) )
        g.addConstituent( new StoreKey( StoreType.remote, named ) )
        g.addConstituent( new StoreKey( StoreType.hosted, named ) )
        
        g
    }
}
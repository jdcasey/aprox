import org.commonjava.indy.model.core.*;
import org.commonjava.indy.autoprox.data.*;

class TestFactory extends AbstractAutoProxRule
{

    RemoteRepository createRemoteRepository( final String packageType, final String named )
        throws MalformedURLException
    {
        String baseUrl = System.getProperty( "baseUrl" );
        new RemoteRepository( packageType, named, baseUrl + "/target/" + named );
    }

    HostedRepository createHostedRepository( final String packageType, final String named )
    {
        new HostedRepository( packageType, named );
    }

    Group createGroup( String packageType, final String named )
    {
        List<StoreKey> constituents = new ArrayList<StoreKey>();
        
        constituents.add( new StoreKey( packageType, StoreType.hosted, named ) );
        constituents.add( new StoreKey( packageType, StoreType.remote, named ) );
        constituents.add( new StoreKey( packageType, StoreType.remote, "first" ) );
        constituents.add( new StoreKey( packageType, StoreType.remote, "second" ) );
        
        new Group( packageType, named, constituents );
    }

    boolean matches( final String packageType, final String name )
    {
        "test".equals( name );
    }
    
}
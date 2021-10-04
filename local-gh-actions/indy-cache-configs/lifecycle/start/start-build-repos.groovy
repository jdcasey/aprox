import org.commonjava.indy.action.IndyLifecycleException
import org.commonjava.indy.action.StartupAction;
import org.commonjava.indy.model.core.*;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.audit.ChangeSummary;
import org.commonjava.maven.galley.event.EventMetadata;

import javax.inject.Inject;

import static org.commonjava.indy.model.core.StoreType.group;
import static org.commonjava.indy.model.core.StoreType.hosted;
import static org.commonjava.indy.model.core.StoreType.remote;
import static org.commonjava.indy.pkg.PackageTypeConstants.PKG_TYPE_MAVEN;

public class StartBuildReposAction implements StartupAction {

    @Inject
    private StoreDataManager stores;

    public String getId(){
        return "start-build-repos";
    }

    public int getStartupPriority(){
        return 0;
    }

    public void start() throws IndyLifecycleException {
        try {
            ChangeSummary cs = new ChangeSummary( ChangeSummary.SYSTEM_USER, "setting up repos for maven build" );

            StoreKey ldKey = new StoreKey( PKG_TYPE_MAVEN, hosted, "local-deployments" );
            HostedRepository localDeployments = (HostedRepository) stores.getArtifactStore( ldKey );
            if ( localDeployments == null ){
                localDeployments = new HostedRepository( PKG_TYPE_MAVEN, "local-deployments" );
                stores.storeArtifactStore( localDeployments, cs, true, false, new EventMetadata() );
            }

            StoreKey sKey = new StoreKey( PKG_TYPE_MAVEN, remote, "oss-snapshots" );
            RemoteRepository snaps = (RemoteRepository) stores.getArtifactStore( sKey );
            if ( snaps == null ){
                snaps = new RemoteRepository( PKG_TYPE_MAVEN, sKey.getName(), "https://oss.sonatype.org/content/repositories/snapshots/");
                snaps.setAllowReleases( false );
                snaps.setAllowSnapshots( true );
                snaps.setTimeoutSeconds( 86400 );

                stores.storeArtifactStore( snaps, cs, true, false, new EventMetadata() );
            }

            StoreKey cKey = new StoreKey( PKG_TYPE_MAVEN, remote, "central" );

            StoreKey pKey = new StoreKey( PKG_TYPE_MAVEN, group, "public" );
            Group pGrp = (Group) stores.getArtifactStore( pKey );
            pGrp.setConstituents( Arrays.asList( cKey, ldKey, sKey ) );
            stores.storeArtifactStore( pGrp, cs, true, false, new EventMetadata() );
        }
        catch(Exception e) {
            throw new IndyLifecycleException( "Failed to setup build repos.", e );
        }
        
    }

}
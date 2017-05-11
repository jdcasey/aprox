package org.commonjava.indy.implrepo;

import org.commonjava.indy.implrepo.change.ImpliedRepositoryCreator
import org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor
import org.commonjava.maven.atlas.ident.ref.ProjectVersionRef;
import org.commonjava.maven.galley.maven.model.view.RepositoryView;
import org.commonjava.indy.model.core.RemoteRepository;
import org.slf4j.Logger;

class RepoCreator implements ImpliedRepositoryCreator
{
    @Override
    RemoteRepository createFrom(ProjectVersionRef implyingGAV, RepositoryView repo, Logger logger) {
        String id = "i-" + repo.getId().replaceAll( "[^\\p{Alnum}]", "-" )
        RemoteRepository rr = new RemoteRepository( MavenPackageTypeDescriptor.MAVEN_PKG_KEY, id, repo.getUrl() );

        rr.setAllowSnapshots( repo.isSnapshotsEnabled() );
        rr.setAllowReleases( repo.isReleasesEnabled() );

        rr.setDescription( "Implicitly created repo for: " + repo.getName() + " (" + repo.getId()
                + ") from repository declaration in POM: " + implyingGAV );

        rr
    }

}
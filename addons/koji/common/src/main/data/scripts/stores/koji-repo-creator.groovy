package org.commonjava.indy.koji;

import org.commonjava.indy.koji.content.KojiRepositoryCreator
import org.commonjava.indy.model.core.RemoteRepository

class RepoCreator implements KojiRepositoryCreator
{
    @Override
    RemoteRepository createRemoteRepository(String packageType, String name, String url, Integer downloadTimeoutSeconds) {
        RemoteRepository remote = new RemoteRepository( packageType, name, url );
        remote.setTimeoutSeconds( downloadTimeoutSeconds );

        remote
    }

}
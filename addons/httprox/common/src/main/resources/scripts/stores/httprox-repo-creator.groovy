package org.commonjava.indy.httprox;

import org.commonjava.indy.httprox.handler.ProxyRepositoryCreator
import org.commonjava.indy.model.core.GenericPackageTypeDescriptor
import org.commonjava.indy.model.core.RemoteRepository
import org.commonjava.indy.subsys.http.util.UserPass
import org.commonjava.indy.util.UrlInfo
import org.slf4j.Logger

import static org.commonjava.indy.model.core.PathStyle.hashed

class RepoCreator implements ProxyRepositoryCreator
{
    @Override
    RemoteRepository create(String name, String baseUrl, UrlInfo info, UserPass up, Logger logger) {
        RemoteRepository remote = new RemoteRepository( GenericPackageTypeDescriptor.GENERIC_PKG_KEY, name, baseUrl );
        remote.setDescription( "HTTProx proxy based on: " + info.getUrl() );

        remote.setPathStyle( hashed );
        if ( up != null )
        {
            remote.setUser( up.getUser() );
            remote.setPassword( up.getPassword() );
        }

        remote
    }
}
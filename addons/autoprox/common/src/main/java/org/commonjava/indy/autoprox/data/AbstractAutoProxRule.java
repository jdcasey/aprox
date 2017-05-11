/**
 * Copyright (C) 2011 Red Hat, Inc. (jdcasey@commonjava.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.indy.autoprox.data;

import java.net.MalformedURLException;

import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;

import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;

public abstract class AbstractAutoProxRule
    implements AutoProxRule
{

    @Override
    public boolean isValidationEnabled()
    {
        return true;
    }

    @Deprecated
    public boolean matches( String named )
    {
        return false;
    }

    public boolean matches( String packageType, String named )
    {
        return matches( named );
    }

    @Deprecated
    public RemoteRepository createRemoteRepository( final String named )
            throws AutoProxRuleException, MalformedURLException
    {
        return null;
    }

    @Override
    public RemoteRepository createRemoteRepository( final String packageType, final String named )
        throws AutoProxRuleException, MalformedURLException
    {
        return createRemoteRepository( named );
    }

    @Deprecated
    public HostedRepository createHostedRepository( final String named )
    {
        return null;
    }

    @Override
    public HostedRepository createHostedRepository( final String packageType, final String named )
    {
        return createHostedRepository( named );
    }

    @Deprecated
    public Group createGroup( final String named )
    {
        return null;
    }

    @Override
    public Group createGroup( final String packageType, final String named )
    {
        return createGroup( named );
    }

    @Override
    public String getRemoteValidationPath()
    {
        return null;
    }

    public RemoteRepository createValidationRemote( final String name )
            throws AutoProxRuleException, MalformedURLException
    {
        return createRemoteRepository( name );
    }

    @Override
    public RemoteRepository createValidationRemote( final String packageType, final String name )
        throws AutoProxRuleException, MalformedURLException
    {
        return createValidationRemote( name );
    }

}

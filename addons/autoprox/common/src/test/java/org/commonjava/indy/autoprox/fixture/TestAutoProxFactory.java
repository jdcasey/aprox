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
package org.commonjava.indy.autoprox.fixture;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;

import org.commonjava.indy.autoprox.data.AutoProxRule;
import org.commonjava.indy.autoprox.data.AutoProxRuleException;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.test.http.expect.ExpectationServer;

public class TestAutoProxFactory
    implements AutoProxRule
{

    private final ExpectationServer http;

    public TestAutoProxFactory( final ExpectationServer http )
    {
        this.http = http;
    }

    @Override
    public RemoteRepository createRemoteRepository( final String packageType, final String named )
        throws AutoProxRuleException
    {
        return new RemoteRepository( packageType, named, http.formatUrl( "target", named ) );
    }

    @Override
    public HostedRepository createHostedRepository( final String packageType, final String named )
    {
        return new HostedRepository( packageType, named );
    }

    @Override
    public Group createGroup( final String packageType, final String named )
    {
        final List<StoreKey> constituents = new ArrayList<StoreKey>();

        constituents.add( new StoreKey( packageType, StoreType.hosted, named ) );
        constituents.add( new StoreKey( packageType, StoreType.remote, named ) );
        constituents.add( new StoreKey( packageType, StoreType.remote, "first" ) );
        constituents.add( new StoreKey( packageType, StoreType.remote, "second" ) );

        return new Group( packageType, named, constituents );
    }

    @Override
    public String getRemoteValidationPath()
    {
        return null;
    }

    @Override
    public boolean matches( final String packageType, final String name )
    {
        return true;
    }

    @Override
    public boolean isValidationEnabled()
    {
        return true;
    }

    @Override
    public RemoteRepository createValidationRemote( final String packageType, final String name )
        throws AutoProxRuleException, MalformedURLException
    {
        return createRemoteRepository( packageType, name );
    }

}

/**
 * Copyright (C) 2011-2020 Red Hat, Inc. (https://github.com/Commonjava/indy)
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
package org.commonjava.indy.repo.proxy.create;

import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.model.core.StoreKey;

import java.util.Optional;

import static java.util.Optional.empty;

public abstract class AbstractProxyRepoCreateRule
        implements ProxyRepoCreateRule
{

    @Override
    public boolean matches( final StoreKey key )
    {
        return false;
    }

    @Override
    public Optional<RemoteRepository> createRemote( final StoreKey key )
    {
        return empty();
    }

    public String getTargetIndyUrl(){
        return "";
    }
}

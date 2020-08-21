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
package org.commonjava.indy.pkg.pypi.content.cdn;

import org.commonjava.indy.subsys.infinispan.CacheHandle;
import org.commonjava.indy.subsys.infinispan.CacheProducer;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import java.util.Map;

@ApplicationScoped
public class PyPICacheProducer
{

    private static final String CDN_REDIRECTS = "pypi-cdn-redirects";

    @Inject
    private CacheProducer cacheProducer;

    @PyPICDNCache
    @Produces
    @ApplicationScoped
    public CacheHandle<CDNContentKey, Map<String, String>> cdnRedirectsCache()
    {
        return cacheProducer.getCache( CDN_REDIRECTS );
    }
}

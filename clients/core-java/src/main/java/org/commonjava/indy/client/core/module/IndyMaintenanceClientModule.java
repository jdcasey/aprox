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
package org.commonjava.indy.client.core.module;

import org.apache.commons.lang.StringUtils;
import org.commonjava.indy.client.core.IndyClientException;
import org.commonjava.indy.client.core.IndyClientModule;
import org.commonjava.indy.client.core.util.UrlUtils;
import org.commonjava.indy.model.core.BatchDeleteRequest;
import org.commonjava.indy.model.core.StoreType;

public class IndyMaintenanceClientModule
        extends IndyClientModule
{

    private static final String BASE_URL = "/admin/maint";

    public void rescan( final String packageType, final StoreType storeType, final String name )
            throws IndyClientException
    {
        getHttp().getRaw(
                UrlUtils.buildUrl( BASE_URL, "rescan", packageType, storeType.singularEndpointName(), name ) );
    }

    public void rescanAll()
            throws IndyClientException
    {
        getHttp().getRaw( UrlUtils.buildUrl( BASE_URL, "rescan", "all" ) );
    }

    public void deleteAllInPath( final String path )
            throws IndyClientException
    {
        getHttp().delete( UrlUtils.buildUrl( BASE_URL, "content", "all", StringUtils.isBlank( path ) ? null : path ) );
    }

    public void deleteFilesFromStore( final BatchDeleteRequest request )
            throws IndyClientException
    {
        getHttp().postRaw( UrlUtils.buildUrl( BASE_URL, "content/batch/delete" ), request );
    }

}

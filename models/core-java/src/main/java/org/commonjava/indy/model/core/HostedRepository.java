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
package org.commonjava.indy.model.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.wordnik.swagger.annotations.ApiModel;

@ApiModel( description = "Hosts artifact content on the local system", parent = ArtifactStore.class )
public class HostedRepository
    extends ArtifactStore
{

    private static final long serialVersionUID = 1L;

    private String storage;

    @JsonProperty( "allow_snapshots" )
    private boolean allowSnapshots = false;

    @JsonProperty( "allow_releases" )
    private boolean allowReleases = true;

    private int snapshotTimeoutSeconds;

    @JsonProperty( "fast_storage" )
    private String fastStorage;

    HostedRepository()
    {
    }

    public HostedRepository( final String name )
    {
        super( name );
    }

    public boolean isAllowSnapshots()
    {
        return allowSnapshots;
    }

    public void setAllowSnapshots( final boolean allowSnapshots )
    {
        this.allowSnapshots = allowSnapshots;
    }

    public boolean isAllowReleases()
    {
        return allowReleases;
    }

    public void setAllowReleases( final boolean allowReleases )
    {
        this.allowReleases = allowReleases;
    }

    @Override
    public String toString()
    {
        return String.format( "HostedRepository [allowSnapshots=%s, allowReleases=%s, key=%s, storage-directory=%s]",
                              allowSnapshots, allowReleases, getKey(), getStorage() );
    }

    public int getSnapshotTimeoutSeconds()
    {
        return snapshotTimeoutSeconds;
    }

    public void setSnapshotTimeoutSeconds( final int snapshotTimeoutSeconds )
    {
        this.snapshotTimeoutSeconds = snapshotTimeoutSeconds;
    }

    public String getStorage()
    {
        return storage;
    }

    public void setStorage( final String storage )
    {
        this.storage = storage;
    }

    @Override
    protected StoreKey initKey( final String name )
    {
        return new StoreKey( StoreType.hosted, name );
    }

    public String getFastStorage()
    {
        return fastStorage;
    }

    public void setFastStorage( String fastStorage )
    {
        this.fastStorage = fastStorage;
    }

}

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

import java.util.Set;

public abstract class AbstractRepository
    extends ArtifactStore
{

    private static final long serialVersionUID = 1L;

    @JsonProperty( "allow_snapshots" )
    private boolean allowSnapshots = false;

    @JsonProperty( "allow_releases" )
    private boolean allowReleases = true;

    @JsonProperty( "path_mask_patterns" )
    private Set<String> pathMaskPatterns;

    AbstractRepository()
    {
    }

    protected AbstractRepository( final String packageType, final StoreType type, final String name )
    {
        super( packageType, type, name );
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

    public Set<String> getPathMaskPatterns() { return pathMaskPatterns; }

    public void setPathMaskPatterns(Set<String> pathMaskPatterns) { this.pathMaskPatterns = pathMaskPatterns; }

    protected void copyRestrictions( AbstractRepository repo )
    {
        repo.setAllowReleases( isAllowReleases() );
        repo.setAllowSnapshots( isAllowSnapshots() );
    }

}

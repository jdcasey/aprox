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
package org.commonjava.indy.model.core.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;

/**
 * Lightweight view of an {@link ArtifactStore} designed for external use. Each instance includes the name and type of the store, plus a resource-uri
 * for accessing content on that endpoint.
 */
@ApiModel( "Artifact store information necessary for accessing or referencing (for replicating from another Indy instance, or from another repository manager)" )
public final class EndpointView
    implements Comparable<EndpointView>
{
    private String packageType;

    private String name;

    private String type;

    @ApiModelProperty( "URL for accessing this artifact store" )
    @JsonProperty( "resource_uri" )
    private String resourceUri;

    public EndpointView()
    {
    }

    public EndpointView( final ArtifactStore store, final String resourceUri )
    {
        final StoreKey key = store.getKey();
        this.name = key.getName();

        this.type = key.getType()
                       .name();

        this.packageType = key.getPackageType();


        this.resourceUri = resourceUri;
    }

    public final String getName()
    {
        return name;
    }

    public final String getType()
    {
        return type;
    }

    public final String getPackageType()
    {
        return packageType;
    }

    public final String getResourceUri()
    {
        return resourceUri;
    }

    public StoreType getStoreType()
    {
        return StoreType.get( type );
    }

    public StoreKey getStoreKey()
    {
        return new StoreKey( packageType, StoreType.get( type ), name );
    }

    public final String getKey()
    {
        return packageType + ":" + type + ":" + name;
    }

    @Override
    public int compareTo( final EndpointView point )
    {
        return getKey().compareTo( point.getKey() );
    }

    @Override
    public int hashCode()
    {
        final int prime = 31;
        int result = 1;
        result = prime * result + ( ( resourceUri == null ) ? 0 : resourceUri.hashCode() );
        return result;
    }

    @Override
    public boolean equals( final Object obj )
    {
        if ( this == obj )
        {
            return true;
        }
        if ( obj == null )
        {
            return false;
        }
        if ( getClass() != obj.getClass() )
        {
            return false;
        }
        final EndpointView other = (EndpointView) obj;
        if ( resourceUri == null )
        {
            if ( other.resourceUri != null )
            {
                return false;
            }
        }
        else if ( !resourceUri.equals( other.resourceUri ) )
        {
            return false;
        }
        return true;
    }

    public void setResourceUri( final String resourceUri )
    {
        this.resourceUri = resourceUri;
    }

    public void setName( final String name )
    {
        this.name = name;
    }

    public void setType( final String type )
    {
        this.type = type;
    }

    public void setPackageType( final String packageType )
    {
        this.packageType = packageType;
    }
}

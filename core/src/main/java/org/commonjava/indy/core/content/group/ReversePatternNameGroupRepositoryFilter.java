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
package org.commonjava.indy.core.content.group;

import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.StoreType;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reverse pattern is specifically useful. It excludes repos matching the filterPattern if pathPattern not matches.
 * e.g, if path not contains '-rh', exclude those with name like 'rh-build'.
 */
public abstract class ReversePatternNameGroupRepositoryFilter
                extends AbstractGroupRepositoryFilter
{
    protected Pattern pathPattern;

    protected Pattern filterPattern;

    public ReversePatternNameGroupRepositoryFilter( String pathPattern, String filterPattern )
    {
        this.pathPattern = Pattern.compile( pathPattern );
        this.filterPattern = Pattern.compile( filterPattern );
    }

    @Override
    public List<ArtifactStore> filter( String path, Group group, List<ArtifactStore> concreteStores )
    {
        Matcher matcher = pathPattern.matcher( path );
        if ( !matcher.matches() )
        {
            return concreteStores.stream()
                                 .filter( store -> store.getType() == StoreType.remote || !filterPattern.matcher(
                                                 store.getName() ).matches() )
                                 .collect( Collectors.toList() );
        }
        return concreteStores;
    }
}

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

import org.commonjava.indy.content.GroupRepositoryFilter;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;

import java.util.List;

public abstract class AbstractGroupRepositoryFilter
                implements GroupRepositoryFilter
{
    @Override
    public boolean canProcess( String path, Group group )
    {
        return true;
    }

    @Override
    public List<ArtifactStore> filter( String path, Group group, List<ArtifactStore> concreteStores )
    {
        return concreteStores;
    }

    @Override
    public int compareTo( GroupRepositoryFilter groupRepositoryFilter )
    {
        int other = groupRepositoryFilter.getPriority();
        if ( getPriority() > other )
        {
            return 1;
        }
        else if ( getPriority() < other )
        {
            return -1;
        }
        else
        {
            return 0;
        }
    }
}

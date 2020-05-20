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

import org.apache.commons.lang.StringUtils;
import org.commonjava.indy.conf.IndyConfiguration;
import org.commonjava.indy.content.GroupRepositoryFilter;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.subsys.datafile.DataFile;
import org.commonjava.indy.subsys.datafile.DataFileManager;
import org.commonjava.indy.subsys.template.ScriptEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.commonjava.indy.subsys.template.ScriptEngine.SCRIPTS_SUBDIR;

@ApplicationScoped
public class GroupRepositoryFilterManager
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    public static final String REPO_FILTER = "repofilter"; // groovy scripts are under "data/scripts/repofilter"

    @Inject
    private DataFileManager dataFileManager;

    @Inject
    private IndyConfiguration indyConfiguration;

    @Inject
    private ScriptEngine scriptEngine;

    @Inject
    private Instance<GroupRepositoryFilter> filters;

    private List<GroupRepositoryFilter> groupRepositoryFilters = new ArrayList<>();

    public GroupRepositoryFilterManager()
    {
    }

    @PostConstruct
    void setup()
    {
        if ( !indyConfiguration.isRepositoryFilterEnabled() )
        {
            logger.info( "Repository filters disabled" );
            return;
        }

        if ( filters != null )
        {
            filters.forEach( f -> groupRepositoryFilters.add( f ) );
        }
        loadFilterScripts();
        Collections.sort( groupRepositoryFilters, Collections.reverseOrder() ); // priority is important
        logger.info( "Set up group repository filters: {}", groupRepositoryFilters );
    }

    private void loadFilterScripts()
    {
        DataFile filtersDir = dataFileManager.getDataFile( SCRIPTS_SUBDIR, REPO_FILTER );
        logger.info( "Scanning for repo filters, filtersDir: {}", filtersDir );
        if ( filtersDir.exists() )
        {
            DataFile[] scripts = filtersDir.listFiles( ( file ) -> file.getName().endsWith( ".groovy" ) );
            if ( scripts != null && scripts.length > 0 )
            {
                for ( final DataFile script : scripts )
                {
                    logger.info( "Loading repo filter: {}", script );
                    GroupRepositoryFilter filter = parseFilter( script );
                    if ( filter != null )
                    {
                        groupRepositoryFilters.add( filter );
                    }
                }
            }
            else
            {
                logger.info( "No repo filter scripts found in {}", filtersDir.getPath() );
            }
        }
    }

    private GroupRepositoryFilter parseFilter( DataFile script )
    {
        try
        {
            String spec = script.readString();
            return scriptEngine.parseScriptInstance( spec, GroupRepositoryFilter.class );
        }
        catch ( Exception e )
        {
            logger.error( "Parse repo filter failed", e );
            return null;
        }
    }

    /**
     * Get member repositories that might contain the specified path.
     *
     * @param path
     * @param group
     * @param orderedConcreteStores concrete stores in this group and sub groups
     */
    public List<ArtifactStore> filter( String path, Group group, List<ArtifactStore> orderedConcreteStores )
    {
        if ( !indyConfiguration.isRepositoryFilterEnabled() )
        {
            return orderedConcreteStores;
        }

        if ( logger.isDebugEnabled() )
        {
            logger.debug( "Filter group members, group: {}, orderedConcreteStores: {}", group.getName(),
                          format( orderedConcreteStores ) );
        }

        List<ArtifactStore> ret = orderedConcreteStores;
        for ( GroupRepositoryFilter repositoryFilter : groupRepositoryFilters )
        {
            logger.debug( "Try filter: {}", repositoryFilter.getClass().getSimpleName() );
            long begin = System.currentTimeMillis();
            if ( repositoryFilter.canProcess( path, group ) )
            {
                ret = repositoryFilter.filter( path, group, ret );
                if ( logger.isDebugEnabled() )
                {
                    logger.debug( "Filter processed, filter: {}, elapse: {}, ret: {}",
                                  repositoryFilter.getClass().getSimpleName(), ( System.currentTimeMillis() - begin ),
                                  format( ret ) );
                }
            }
            else
            {
                logger.debug( "Can not process, filter: {}", repositoryFilter.getClass().getSimpleName() );
            }
        }
        return ret;
    }

    private String format( List<ArtifactStore> stores )
    {
        if ( stores == null )
        {
            return "null";
        }
        return stores.stream().map( store -> store.getKey().toString() ).collect( Collectors.toList() ).toString();
    }

}

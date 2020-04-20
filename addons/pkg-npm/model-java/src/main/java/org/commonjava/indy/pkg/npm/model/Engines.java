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
package org.commonjava.indy.pkg.npm.model;

import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;

import java.util.HashMap;
import java.util.Map;

@ApiModel( description = "Specify the version of node / npm that the stuff works on." )
public class Engines
{

    @ApiModelProperty( value = "Specify the version of node that your stuff works on." )
    private static final String NODE = "node";

    @ApiModelProperty( value = "Specify which versions of npm are capable of properly installing the program." )
    private static final String NPM = "npm";

    private Map<String, String> enginesMap = new HashMap<String, String>();

    protected Engines()
    {
    }

    protected Engines( String engine )
    {
        String[] strings = engine.split( String.valueOf( Character.SPACE_SEPARATOR ) );

        if ( strings.length != 2  )
        {
            return;
        }

        if ( NPM.equals( strings[0] ) )
        {
            enginesMap.put( NPM, strings[1] );
        }
        else if ( NODE.equals( strings[0] ) )
        {
            enginesMap.put( NODE, strings[1] );
        }
    }

    public String getNode()
    {
        return enginesMap.get( NODE );
    }

    public void setNode( String node )
    {
        enginesMap.put( NODE, node );
    }

    public String getNpm()
    {
        return enginesMap.get( NPM );
    }

    public void setNpm( String npm )
    {
        enginesMap.put( NPM, npm );
    }

    public Map<String, String> fetchEnginesMap()
    {
        return enginesMap;
    }

    public String getEngine( String name )
    {
        return enginesMap.get( name );
    }

    public void putEngine( String name, String value )
    {
        enginesMap.put( name, value );
    }
}

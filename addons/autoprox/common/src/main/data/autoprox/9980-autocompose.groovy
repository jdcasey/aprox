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

import org.commonjava.indy.autoprox.data.*;
import java.net.MalformedURLException;
import org.commonjava.indy.model.core.*;

class ComplexGroupsRule extends AbstractAutoProxRule
{
    boolean matches( String name ){
        name =~ /.+\+.+/
    }

    Group createGroup( String packageType, String named )
    {
        String[] parts = named.split("\\+")
        
        Group g = null
        if ( parts.length > 1 ){
            g = new Group( packageType, named )
            parts.each{
              int idx = it.indexOf('_')
              
              String type = 'remote'
              String name = null
              if ( idx < 1 ){
                name = it
              }
              else{
                type = it.substring(0,idx)
                name = it.substring(idx+1)
              }
              
              g.addConstituent( new StoreKey( StoreType.get( type ), name ) );
            }
        }
        
        g
    }
}

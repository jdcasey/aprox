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
package org.commonjava.indy.pkg.npm.model.io;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.commonjava.indy.pkg.npm.model.Bugs;
import org.commonjava.indy.pkg.npm.model.Directories;
import org.commonjava.indy.pkg.npm.model.Dist;
import org.commonjava.indy.pkg.npm.model.Engines;
import org.commonjava.indy.pkg.npm.model.License;
import org.commonjava.indy.pkg.npm.model.Repository;
import org.commonjava.indy.pkg.npm.model.UserInfo;
import org.commonjava.indy.pkg.npm.model.VersionMetadata;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public class VersionMetadataDeserializer extends StdDeserializer<VersionMetadata>
{

    public VersionMetadataDeserializer()
    {
        this(null);
    }

    protected VersionMetadataDeserializer( Class<?> vc )
    {
        super( vc );
    }

    @Override
    public VersionMetadata deserialize( JsonParser jsonParser, DeserializationContext deserializationContext )
                    throws IOException
    {
        final ObjectMapper mapper = (ObjectMapper) jsonParser.getCodec();
        final JsonNode vNode = mapper.readTree( jsonParser );

        VersionMetadata vm = new VersionMetadata( parseValue( vNode, "name" ), parseValue( vNode, "version" ) );
        vm.setDescription( parseValue( vNode, "description" ) );

        JsonNode repoNode = vNode.get( "repository" );
        if ( repoNode instanceof ArrayNode )
        {
            repoNode = repoNode.get( 0 );
        }
        vm.setRepository( new Repository( parseValue( repoNode, "type"), parseValue( repoNode, "url") ) );

        vm.setAuthor( parseObject( mapper, vNode.get( "author" ), UserInfo.class ));
        vm.setBugs( parseObject( mapper, vNode.get( "bugs" ), Bugs.class ) );
        vm.setDist( parseObject( mapper, vNode.get( "dist" ), Dist.class ) );
        vm.setDirectories( parseObject( mapper, vNode.get( "directories" ), Directories.class ) );
        vm.setKeywords( parseList( mapper, vNode.get( "keywords" ), String.class ) );
        vm.setLicense( parseObject( mapper, vNode.get( "license" ), License.class ) );
        vm.setMain( parseValue( vNode, "main" ) );
        vm.setUrl( parseValue( vNode, "url" ) );

        vm.setContributors( parseList( mapper, vNode.get( "contributors" ), UserInfo.class ));
        vm.setEngines( parseList( mapper, vNode.get( "engines" ), Engines.class ));

        vm.setDependencies( parseObject( mapper, vNode.get( "dependencies" ), Map.class ) );
        vm.setDevDependencies( parseObject( mapper, vNode.get( "devDependencies" ), Map.class ) );

        vm.setMaintainers( parseList( mapper, vNode.get( "maintainers" ), UserInfo.class));
        vm.setLicenses( parseList( mapper, vNode.get( "licenses" ), License.class ) );

        vm.setScripts( parseObject( mapper, vNode.get( "scripts" ), Map.class ) );

        return vm;
    }

    private <T> T parseObject( ObjectMapper mapper, JsonNode node, Class<T> tClass ) throws JsonProcessingException
    {
        return mapper.treeToValue( node, tClass );
    }

    public <T> List<T> parseList( ObjectMapper mapper, JsonNode node, Class<T> tClass ) throws IOException
    {
        if ( node == null )
        {
            return null;
        }

        return mapper.readValue( mapper.treeAsTokens( node ),
                                 mapper.getTypeFactory().constructCollectionType( List.class, tClass ) );
    }

    private String parseValue( JsonNode repoNode, String key )
    {
        if ( repoNode!=null && repoNode.get( key ) != null )
        {
            return repoNode.get( key ).asText();
        }
        return null;
    }
}

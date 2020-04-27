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

import org.apache.commons.io.IOUtils;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.commonjava.indy.pkg.npm.model.io.PackageSerializerModule;
import org.junit.Test;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

public class VersionMetadataTest
{
    @Test
    public void roundTripJson() throws Exception
    {
        final IndyObjectMapper mapper = new IndyObjectMapper( true );
        final VersionMetadata metadata = new VersionMetadata( "test", "1.1.0" );
        final String json = mapper.writeValueAsString( metadata );

        System.out.println( json );

        final VersionMetadata result = mapper.readValue( json, VersionMetadata.class );
        assertThat( result.getName(), equalTo( metadata.getName() ) );
        assertThat( result.getVersion(), equalTo( metadata.getVersion() ) );
    }

    @Test
    public void ignoreCouchDBJsonDataTest() throws Exception
    {

        final IndyObjectMapper mapper = new IndyObjectMapper( true );
        String json = IOUtils.toString(
                        Thread.currentThread().getContextClassLoader().getResourceAsStream( "test-package.json" ) );

        final PackageMetadata result = mapper.readValue( json, PackageMetadata.class );
        final VersionMetadata version = result.getVersions().get( "1.5.1" );
        final String jsonResult = mapper.writeValueAsString( version );

        assertThat( jsonResult.contains( "_id" ), equalTo( false ) );

    }

    @Test
    public void customDeserializerTest() throws Exception
    {
        final IndyObjectMapper mapper = new IndyObjectMapper( true );
        mapper.registerModule( new PackageSerializerModule() );
        String json = IOUtils.toString(
                        Thread.currentThread().getContextClassLoader().getResourceAsStream( "test-package-tmp.json" ) );

        final PackageMetadata result = mapper.readValue( json, PackageMetadata.class );
        final VersionMetadata version = result.getVersions().get( "0.0.4" );

        assertThat( result.getVersions().size(), equalTo( 5 ) );

        assertThat( version.getName(), equalTo( "tmp" ) );
        assertThat( version.getVersion(), equalTo( "0.0.4" ) );
        assertThat( version.getRepository().getType(), equalTo("git"));
        assertThat( version.getEngines().get( 0 ).getNode(), equalTo( "0.4.10" ) );
        assertThat( version.getScripts().get( "test" ), equalTo( "vows test/*-test.js" ) );
        assertThat( version.getLicenses().get( 0 ).getType(), equalTo( "GPLv2" ) );
        assertThat( version.getDist().getTarball(), equalTo( "https://registry.npmjs.org/tmp/-/tmp-0.0.4.tgz" ) );
    }
}

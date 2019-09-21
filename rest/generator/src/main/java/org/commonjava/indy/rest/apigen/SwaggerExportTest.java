/**
 * Copyright (C) 2011-2019 Red Hat, Inc. (https://github.com/Commonjava/indy)
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
package org.commonjava.indy.rest.apigen;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.commonjava.indy.client.core.util.UrlUtils;
import org.commonjava.indy.ftest.core.AbstractIndyFunctionalTest;
import org.commonjava.indy.util.ApplicationHeader;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

/**
 * <b>THIS IS NOT JUST A TEST.</b>
 *
 * This actually orchestrates a special execution of Indy, where the additional runtime Swagger classes are added
 * to the deployment. This enables Swagger to scan the annotations on REST resources and model classes, and produce
 * YAML / JSON files, which are then downloaded by THIS TEST, and saved to the project target/ directory.
 *
 * Once written to the target directory, the build-helper-maven-plugins picks them up and attaches them to the
 * Maven project for installation and deployment to Maven repositories...and for use as dependencies by other
 * Indy modules.
 */
public class SwaggerExportTest
        extends AbstractIndyFunctionalTest
{
    @Test
    public void downloadApiFiles()
    {
        CloseableHttpClient client = HttpClientBuilder.create().build();

        Arrays.asList( "yaml", "json").forEach( ext->{
            HttpGet get = new HttpGet(
                    UrlUtils.buildUrl( "http://localhost:" + fixture.getBootOptions().getPort(), "swagger." + ext ) );

            get.setHeader( ApplicationHeader.accept.key(), "application/" + ext );
            try(CloseableHttpResponse response = client.execute( get ))
            {
                assertThat( response.getStatusLine().getStatusCode(), equalTo( 200 ) );

                String content = IOUtils.toString( response.getEntity().getContent() );
                FileUtils.write( new File( "target/classes/indy-rest-api." + ext ), content );
            }
            catch ( IOException e )
            {
                e.printStackTrace();
                fail( "failed to retrieve swagger." + ext );
            }
        } );
    }
}

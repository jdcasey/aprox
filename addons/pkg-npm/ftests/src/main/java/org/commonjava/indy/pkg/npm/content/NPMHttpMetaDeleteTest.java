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
package org.commonjava.indy.pkg.npm.content;

import org.commonjava.indy.ftest.core.AbstractContentManagementTest;
import org.commonjava.indy.model.core.HostedRepository;
import org.junit.Test;

import java.io.InputStream;

import static org.commonjava.indy.pkg.npm.model.NPMPackageTypeDescriptor.NPM_PKG_KEY;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

/**
 * This case tests the http-meta json deletion when the package is removed.
 * when: <br />
 * <ul>
 *      <li>creates a hosted repo, stores files in the hosted repo</li>
 *      <li>*.http-metadata.json file will be generated correspondingly</li>
 *      <li>remove the files from the hosted repo</li>
 * </ul>
 * then: <br />
 * <ul>
 *     <li>*.http-metadata.json file will be removed correspondingly</li>
 * </ul>
 */

public class NPMHttpMetaDeleteTest
        extends AbstractContentManagementTest
{

    private static final String HOSTED = "HOSTED";

    private static final String PATH = "jquery";

    private static final String PACKAGE_HTTP_META_PATH = "jquery/package.json.http-metadata.json";

    @Test
    public void test()
            throws Exception
    {
        final InputStream content =
                Thread.currentThread().getContextClassLoader().getResourceAsStream( "package-1.5.1.json" );

        final HostedRepository hosted = new HostedRepository( NPM_PKG_KEY, HOSTED );
        client.stores().create( hosted, "adding npm hosted repo", HostedRepository.class );
        client.content().store( hosted.getKey(), PATH, content );
        assertThat(client.content().exists( hosted.getKey(), PACKAGE_HTTP_META_PATH ), equalTo( true ));

        client.content().delete( hosted.getKey(), PATH );
        assertThat(client.content().exists( hosted.getKey(), PACKAGE_HTTP_META_PATH ), equalTo( false ));

        content.close();
    }

    @Override
    protected boolean createStandardTestStructures()
    {
        return false;
    }
}

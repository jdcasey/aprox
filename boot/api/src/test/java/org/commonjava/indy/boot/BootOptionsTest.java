/**
 * Copyright (C) 2011-2017 Red Hat, Inc. (https://github.com/Commonjava/indy)
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
package org.commonjava.indy.boot;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertThat;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.codehaus.plexus.interpolation.InterpolationException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BootOptionsTest
{

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void replaceIndyHomeInValue()
        throws InterpolationException, IOException
    {
        final File bootProps = temp.newFile( "boot.properties" );
        FileUtils.writeStringToFile( bootProps, "" );

        final IndyBootOptions opts = new IndyBootOptions( bootProps, "/path/to/indy" );

        final String val = opts.resolve( "${indy.home}/etc/indy/main.conf" );

        assertThat( val, equalTo( "/path/to/indy/etc/indy/main.conf" ) );
    }

    @Test
    public void replaceOtherPropReferencingIndyHomeInValue()
        throws InterpolationException, IOException
    {
        final File bootProps = temp.newFile( "boot.properties" );
        FileUtils.writeStringToFile( bootProps, "myDir = ${indy.home}/custom" );

        final IndyBootOptions opts = new IndyBootOptions( bootProps, "/path/to/indy" );

        final String val = opts.resolve( "${myDir}/etc/indy/main.conf" );

        assertThat( val, equalTo( "/path/to/indy/custom/etc/indy/main.conf" ) );
    }

}

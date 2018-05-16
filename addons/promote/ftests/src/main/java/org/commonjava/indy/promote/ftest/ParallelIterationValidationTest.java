/**
 * Copyright (C) 2011-2018 Red Hat, Inc. (https://github.com/Commonjava/indy)
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
package org.commonjava.indy.promote.ftest;

import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor;
import org.commonjava.indy.promote.client.IndyPromoteClientModule;
import org.commonjava.indy.promote.model.GroupPromoteRequest;
import org.commonjava.indy.promote.model.GroupPromoteResult;
import org.commonjava.indy.promote.model.ValidationResult;
import org.commonjava.indy.test.fixture.core.CoreServerFixture;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Map;

import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class ParallelIterationValidationTest
    extends AbstractPromotionManagerTest
{

    private static final String PATH1 = "org/foo/bar/1/bar-1.jar";
    private static final String PATH2 = "org/foo/baz/2/baz-2.jar";

    @Test
    public void run()
        throws Exception
    {
        client.content().store( source.getKey(), PATH1, new ByteArrayInputStream( "This is test 1".getBytes() ) );
        client.content().store( source.getKey(), PATH2, new ByteArrayInputStream( "This is test 2".getBytes() ) );

        final GroupPromoteResult result = client.module( IndyPromoteClientModule.class )
                                           .promoteToGroup(
                                                   new GroupPromoteRequest( source.getKey(), target.getName() ) );

        assertFalse( result.succeeded() );

        assertThat( result.getRequest().getSource(), equalTo( source.getKey() ) );
        assertThat( result.getRequest()
                          .getTargetGroup(), equalTo( target.getName() ) );

        assertThat( result.getError(), nullValue() );

        ValidationResult validations = result.getValidations();
        assertThat( validations, notNullValue() );

        Map<String, String> errors = validations.getValidatorErrors();
        assertThat( errors, notNullValue() );

        String error = errors.get( "test.groovy" );
        System.out.println( error );
        assertThat( error, notNullValue() );

        assertThat( client.content().exists( target.getKey(), first ), equalTo( false ) );
        assertThat( client.content().exists( target.getKey(), second ), equalTo( false ) );

        Group g = client.stores().load( target.getKey(), Group.class );
        assertThat( g.getConstituents().contains( source.getKey() ), equalTo( false ) );
    }

    @Override
    protected ArtifactStore createTarget( String changelog )
            throws Exception
    {
        return client.stores().create( new Group( MAVEN_PKG_KEY, "test" ), changelog, Group.class );
    }

    @Override
    protected void initTestData( CoreServerFixture fixture )
            throws IOException
    {
        writeDataFile( "promote/rules/test.groovy", readTestResource( getClass().getSimpleName() + "/test.groovy" ) );
        writeDataFile( "promote/rule-sets/test.json", readTestResource( getClass().getSimpleName() + "/test.json" ) );
    }
}

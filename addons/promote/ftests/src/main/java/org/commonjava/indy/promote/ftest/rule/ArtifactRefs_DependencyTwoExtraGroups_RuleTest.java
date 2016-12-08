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
package org.commonjava.indy.promote.ftest.rule;

import org.apache.commons.io.IOUtils;
import org.commonjava.indy.ftest.core.category.EventDependent;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.promote.model.GroupPromoteRequest;
import org.commonjava.indy.promote.model.GroupPromoteResult;
import org.commonjava.indy.promote.model.ValidationResult;
import org.commonjava.indy.promote.model.ValidationRuleSet;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;

/**
 * Created by jdcasey on 9/23/15.
 */
public class ArtifactRefs_DependencyTwoExtraGroups_RuleTest
        extends AbstractValidationRuleTest<Group>
{

    private static final String RULE = "artifact-refs-via.groovy";

    private static final String PREFIX = "artifact-refs-via/";

    private HostedRepository otherSource;

    private Group other;

    private HostedRepository thirdSource;

    private Group third;

    @Test
    @Category( EventDependent.class )
    public void run()
            throws Exception
    {
        String invalid = "org/foo/invalid/1/invalid-1.pom";
        String valid = "org/foo/valid/1.1/valid-1.1.pom";

        String dep1Pom = "org/bar/dep/1.0/dep-1.0.pom";
        String dep1Jar = "org/bar/dep/1.0/dep-1.0.jar";

        String dep2Pom = "org/blat/dep2/1.0/dep2-1.0.pom";
        String dep2Jar = "org/blat/dep2/1.0/dep2-1.0.jar";

        String content = "this is some content";

        client.content().store( otherSource.getKey(), dep1Pom, new ByteArrayInputStream( content.getBytes() ) );
        client.content().store( otherSource.getKey(), dep1Jar, new ByteArrayInputStream( content.getBytes() ) );

        client.content().store( thirdSource.getKey(), dep2Pom, new ByteArrayInputStream( content.getBytes() ) );
        client.content().store( thirdSource.getKey(), dep2Jar, new ByteArrayInputStream( content.getBytes() ) );

        InputStream stream = client.content().get( other.getKey(), dep1Pom );
        String retrieved = IOUtils.toString( stream );
        stream.close();

        assertThat( dep1Pom + " invalid from: " + other.getKey(), retrieved, equalTo( content ) );

        stream = client.content().get( other.getKey(), dep1Jar );
        retrieved = IOUtils.toString( stream );
        stream.close();

        assertThat( dep1Jar + " invalid from: " + other.getKey(), retrieved, equalTo( content ) );

        deployResource( invalid, PREFIX + "invalid-external-dep.pom.xml");
        deployResource( valid, PREFIX + "valid-two-external-deps.pom.xml" );

        waitForEventPropagation();
        waitForEventPropagation();

        GroupPromoteRequest request = new GroupPromoteRequest( source.getKey(), target.getName() );
        GroupPromoteResult result = module.promoteToGroup( request );
        assertThat( result, notNullValue() );

        ValidationResult validations = result.getValidations();
        assertThat( validations, notNullValue() );

        Map<String, String> validatorErrors = validations.getValidatorErrors();
        assertThat( validatorErrors, notNullValue() );

        System.out.println(validatorErrors);

        String errors = validatorErrors.get( RULE );
        assertThat( errors, notNullValue() );

        System.out.println(validatorErrors);
        assertThat( errors.contains( valid ), equalTo( false ) );
        assertThat( errors.contains( invalid ), equalTo( true ) );
    }

    public ArtifactRefs_DependencyTwoExtraGroups_RuleTest()
    {
        super( Group.class );
    }

    @Override
    protected String getRuleScriptFile()
    {
        return RULE;
    }

    @Override
    protected String getRuleScriptContent()
            throws IOException
    {
        String path = "promote/rules/" + RULE;
        return readTestResource( path );
    }

    @Override
    protected ValidationRuleSet getRuleSet()
    {
        ValidationRuleSet ruleSet = new ValidationRuleSet();
        ruleSet.setName( "test" );
        ruleSet.setStoreKeyPattern( "group:target" );
        ruleSet.setRuleNames( Collections.singletonList( getRuleScriptFile() ) );

        ruleSet.setValidationParameters( Collections.singletonMap( "availableInStores", "group:other,group:third" ) );

        return ruleSet;
    }

    @Override
    public void start()
            throws Throwable
    {
        super.start();

        otherSource = new HostedRepository( "otherSource" );
        otherSource = client.stores().create( otherSource, "Creating secondary content source", HostedRepository.class );

        other = new Group( "other", otherSource.getKey() );
        other = client.stores().create( other, "Creating secondary content group", Group.class );

        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.info( "{} contains members: {}", other, other.getConstituents() );

        thirdSource = new HostedRepository( "thirdSource" );
        thirdSource = client.stores().create( thirdSource, "Creating tertiary content source", HostedRepository.class );

        third = new Group( "third", thirdSource.getKey() );
        third = client.stores().create( third, "Creating tertiary content group", Group.class );

        logger.info( "{} contains members: {}", third, third.getConstituents() );
    }
}

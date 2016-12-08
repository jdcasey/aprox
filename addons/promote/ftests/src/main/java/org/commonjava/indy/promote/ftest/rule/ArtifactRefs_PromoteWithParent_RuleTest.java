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
import org.apache.commons.lang.StringUtils;
import org.commonjava.indy.ftest.core.category.EventDependent;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.promote.model.PathsPromoteRequest;
import org.commonjava.indy.promote.model.PathsPromoteResult;
import org.commonjava.indy.promote.model.ValidationResult;
import org.commonjava.indy.promote.model.ValidationRuleSet;
import org.commonjava.maven.galley.model.Transfer;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.stream.Collectors;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

/**
 * Created by gli on 16-11-14.
 */
public class ArtifactRefs_PromoteWithParent_RuleTest
    extends AbstractValidationRuleTest<HostedRepository>
{
    private static final String RULE = "artifact-refs-via.groovy";

    private static final String PREFIX = "artifact-refs-via/";

    @Test
    @Category( EventDependent.class )
    public void run()
            throws Exception
    {
        String child = "org/foo/child/1/child-1.pom";
        String parent = "org/foo/parent/1/parent-1.pom";

        deployResource( child, PREFIX + "child.pom.xml" );
        deployResource( parent, PREFIX + "parent.pom.xml" );

        waitForEventPropagation();

        InputStream stream = client.content().get( source.getKey(), child );
        String childRerived = IOUtils.toString( stream );
        stream.close();
        logger.debug( "promote with parent in source: child content: {}", childRerived );
        assertThat( childRerived , containsString( "<artifactId>child</artifactId>" ));

        stream = client.content().get( source.getKey(), parent );
        String parentRetrived = IOUtils.toString( stream );
        stream.close();
        logger.debug( "promote with parent in source: parent content: {}", parentRetrived );
        assertThat( parentRetrived, containsString( "<artifactId>parent</artifactId>" ) );

        waitForEventPropagation();
        waitForEventPropagation();

        PathsPromoteRequest request = new PathsPromoteRequest( source.getKey(), target.getKey(), child, parent );
        PathsPromoteResult result = module.promoteByPath( request );
        assertThat( result, notNullValue() );

        ValidationResult validations = result.getValidations();
        System.out.println(validations);

        assertThat( validations, notNullValue() );
        assertThat( validations.isValid(), equalTo( true ) );

        stream = client.content().get( target.getKey(), child );
        childRerived = IOUtils.toString( stream );
        stream.close();
        logger.debug( "promote with parent in target: child content: {}", childRerived );
        assertThat( childRerived , containsString( "<artifactId>child</artifactId>" ));

        stream = client.content().get( target.getKey(), parent );
        parentRetrived = IOUtils.toString( stream );
        stream.close();
        logger.debug( "promote with parent in target: parent content: {}", parentRetrived );
        assertThat( parentRetrived, containsString( "<artifactId>parent</artifactId>" ) );
    }

    public ArtifactRefs_PromoteWithParent_RuleTest()
    {
        super( HostedRepository.class );
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
        ruleSet.setStoreKeyPattern( "hosted:target" );
        ruleSet.setRuleNames( Collections.singletonList( getRuleScriptFile() ) );
        return ruleSet;
    }

}

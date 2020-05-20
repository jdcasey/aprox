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
package org.commonjava.indy.repo.proxy.create;

import org.apache.commons.io.FileUtils;
import org.commonjava.indy.repo.proxy.RepoProxyAddon;
import org.commonjava.indy.repo.proxy.RepoProxyException;
import org.commonjava.indy.subsys.datafile.DataFile;
import org.commonjava.indy.subsys.template.IndyGroovyException;
import org.commonjava.indy.subsys.template.ScriptEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;

import static org.commonjava.indy.repo.proxy.RepoProxyAddon.ADDON_NAME;

@ApplicationScoped
public class ScriptRuleParser
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private ScriptEngine scriptEngine;

    protected ScriptRuleParser()
    {
    }

    public ScriptRuleParser( final ScriptEngine scriptEngine )
    {
        this.scriptEngine = scriptEngine;
    }

    public ProxyRepoCreateRule parseRule( final DataFile script )
            throws RepoProxyException
    {
        String spec = null;
        try
        {
            spec = script.readString();
        }
        catch ( final IOException e )
        {
            logger.error( String.format( "[%s] Cannot load repo proxy factory from: %s. Reason: %s", ADDON_NAME, script,
                                         e.getMessage() ), e );
        }

        return parseRule( spec, script.getName() );
    }

    public ProxyRepoCreateRule parseRule( final File script )
            throws RepoProxyException
    {
        String spec = null;
        try
        {
            spec = FileUtils.readFileToString( script );
        }
        catch ( final IOException e )
        {
            logger.error( String.format( "[%s] Cannot load repo proxy factory from: %s. Reason: %s", ADDON_NAME, script,
                                         e.getMessage() ), e );
        }

        return parseRule( spec, script.getName() );
    }

    public ProxyRepoCreateRule parseRule( final String spec, final String scriptName )
            throws RepoProxyException
    {
        if ( spec == null )
        {
            return null;
        }

        ProxyRepoCreateRule rule;
        try
        {
            rule = scriptEngine.parseScriptInstance( spec, ProxyRepoCreateRule.class );
        }
        catch ( final IndyGroovyException e )
        {
            throw new RepoProxyException(
                    "[{}] Cannot load repo-proxy factory from: {} as an instance of: {}. Reason: {}", e, ADDON_NAME,
                    scriptName, ProxyRepoCreateRule.class.getSimpleName(), e.getMessage() );
        }

        if ( rule != null )
        {
            return rule;
        }

        logger.warn( "Rule named: {} parsed to null ProxyRepoCreateRule instance. Spec was:\n\n{}\n\n", scriptName,
                     spec );
        return null;
    }

}


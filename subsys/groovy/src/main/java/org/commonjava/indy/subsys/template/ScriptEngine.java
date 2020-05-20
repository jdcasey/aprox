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
package org.commonjava.indy.subsys.template;

import groovy.lang.GroovyClassLoader;
import org.apache.commons.io.IOUtils;
import org.codehaus.groovy.control.CompilationFailedException;
import org.commonjava.indy.subsys.datafile.DataFile;
import org.commonjava.indy.subsys.datafile.DataFileManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.AnnotatedType;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.inject.spi.InjectionTarget;
import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;
import java.util.Enumeration;
import java.lang.reflect.InvocationTargetException;

@ApplicationScoped
public class ScriptEngine
{
    private static final Logger logger = LoggerFactory.getLogger( ScriptEngine.class.getName() );
    public static final String SCRIPTS_SUBDIR = "scripts";

    public enum StandardScriptType
    {
        store_creators( "stores" );

        private String subdir;

        StandardScriptType( String subdir )
        {
            this.subdir = subdir;
        }

        public String subdir()
        {
            return subdir;
        }
    }

    ;

    private final GroovyClassLoader groovyClassloader = new GroovyClassLoader();
    private final Map<String, Class<?>> scriptClassMap = new ConcurrentHashMap(25, 1);

    @Inject
    private DataFileManager dataFileManager;

    protected ScriptEngine()
    {
    }

    public ScriptEngine( DataFileManager dataFileManager )
    {
        this.dataFileManager = dataFileManager;
    }

    /**
     * Provide a standard set of places to store common types of scripts used throughout Indy, such as
     * {@link org.commonjava.indy.model.core.ArtifactStore} creators. This is better from a config maintenance
     * perspective than having these spread out across the whole data/ subdirectory structure, particularly since it's
     * likely if one script needs to be revised, they all will.
     *
     * If the script doesn't exist in the data/scripts directory structure, this method will search the classpath
     * for the same path (scripts/[type]/[name]).
     */
    public <T> T parseStandardScriptInstance( final StandardScriptType scriptType, final String name,
                                              final Class<T> type )
            throws IndyGroovyException
    {
        return parseStandardScriptInstance( scriptType, name, type, false );
    }

    /**
     * Provide a standard set of places to store common types of scripts used throughout Indy, such as
     * {@link org.commonjava.indy.model.core.ArtifactStore} creators. This is better from a config maintenance
     * perspective than having these spread out across the whole data/ subdirectory structure, particularly since it's
     * likely if one script needs to be revised, they all will.
     *
     * If the script doesn't exist in the data/scripts directory structure, this method will search the classpath
     * for the same path (scripts/[type]/[name]).
     */
    public <T> T parseStandardScriptInstance( final StandardScriptType scriptType, final String name,
                                              final Class<T> type, boolean processCdiInjections )
            throws IndyGroovyException
    {
        AtomicReference<Exception> err = new AtomicReference<>();
        Class<?> clazz = scriptClassMap.computeIfAbsent( name, (n) ->
        {
            String script = null;
            String scriptPath = Paths.get( SCRIPTS_SUBDIR, scriptType.subdir(), name ).toString(); 
            DataFile dataFile = dataFileManager.getDataFile( scriptPath );
            if ( dataFile == null || !dataFile.exists() || dataFile.isDirectory() )
            {
                URL resource = Thread.currentThread()
                     .getContextClassLoader()
                     .getResource( scriptPath );

                if ( resource == null )
                {
                    err.set( new IndyGroovyException( "Cannot read standard script from: %s/%s/%s", SCRIPTS_SUBDIR,
                                                  scriptType.subdir(), name ) );
                    return null;
                }
                else
                {
                    logger.debug( "Loading script: {}/{}/{} for class: {} from classpath resource: {}", SCRIPTS_SUBDIR,
                                  scriptType, name, type.getName(), resource );
                    try (InputStream in = resource.openStream())
                    {
                        script = IOUtils.toString( in );
                    }
                    catch ( IOException e )
                    {
                        err.set( new IndyGroovyException( "Cannot read standard script from classpath: %s/%s/%s. Reason: %s",
                               e, SCRIPTS_SUBDIR, scriptType.subdir(), name, e.getMessage() ) );
                    }
                }
            }
            else
            {
                try
                {
                    script = dataFile.readString();
                }
                catch ( IOException e )
                {
                    err.set( new IndyGroovyException( "Failed to read standard script from: %s/%s. Reason: %s", e,
                                                  scriptType.subdir(), name, e.getMessage() ) );
                    return null;
                }
            }
            Class<?> scriptClazz = null;
            try
            {
                scriptClazz = groovyClassloader.parseClass( script );
            }
            catch ( final CompilationFailedException e )
            {
                err.set( new IndyGroovyException( "Failed to compile groovy script: '%s'. Reason: %s", e, script,
                                              e.getMessage()) );
            }
            return scriptClazz;
        } );
        Exception e = err.get();
        if ( e != null )
        {
           throw ( IndyGroovyException )e;
        }
        Object instance = null;
        T result = null;
        try 
        {
            instance = clazz.getDeclaredConstructor().newInstance();

            result = type.cast( instance );
        } catch ( final InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException ie )
        {
           throw new IndyGroovyException( "Cannot instantiate class parsed from script: '%s'. Reason: %s", name,
                                          e.getMessage() );
        }catch ( final ClassCastException cce )
        {
           throw new IndyGroovyException( "Script: '%s' instance: %s cannot be cast as: %s", name, instance,
                                          type.getName() );
        }
        return processCdiInjections ? inject( result ) : result;
    }

    public <T> T parseScriptInstance( final File script, final Class<T> type )
            throws IndyGroovyException
    {
        return parseScriptInstance( script, type, false );
    }

    public <T> T parseScriptInstance( final File script, final Class<T> type, boolean processCdiInjections )
            throws IndyGroovyException
    {
        Object instance = null;
        try
        {
            final Class<?> clazz = groovyClassloader.parseClass( script );
            instance = clazz.newInstance();

            T result = type.cast( instance );
            return processCdiInjections ? inject( result ) : result;
        }
        catch ( final CompilationFailedException e )
        {
            throw new IndyGroovyException( "Failed to compile groovy script: '%s'. Reason: %s", e, script,
                                           e.getMessage() );
        }
        catch ( final IOException e )
        {
            throw new IndyGroovyException( "Failed to read groovy script: '%s'. Reason: %s", e, script,
                                           e.getMessage() );
        }
        catch ( final InstantiationException | IllegalAccessException e )
        {
            throw new IndyGroovyException( "Cannot instantiate class parsed from script: '%s'. Reason: %s", e, script,
                                           e.getMessage() );
        }
        catch ( final ClassCastException e )
        {
            throw new IndyGroovyException( "Script: '%s' instance: %s cannot be cast as: %s", e, script, instance,
                                           type.getName() );
        }
    }

    public <T> T parseScriptInstance( final String script, final Class<T> type )
            throws IndyGroovyException
    {
        return parseScriptInstance( script, type, false );
    }

    public <T> T parseScriptInstance( final String script, final Class<T> type, boolean processCdiInjections )
            throws IndyGroovyException
    {
        Object instance = null;
        try
        {
            final Class<?> clazz = groovyClassloader.parseClass( script );
            instance = clazz.newInstance();

            Logger logger = LoggerFactory.getLogger( getClass() );
            logger.info( "Parsed: {} (type: {}, interfaces: {})", instance, instance.getClass(),
                         Arrays.asList( instance.getClass().getInterfaces() ) );

            T result = type.cast( instance );
            return processCdiInjections ? inject( result ) : result ;
        }
        catch ( final CompilationFailedException e )
        {
            throw new IndyGroovyException( "Failed to compile groovy script: '%s'. Reason: %s", e, script,
                                           e.getMessage() );
        }
        catch ( final InstantiationException | IllegalAccessException e )
        {
            throw new IndyGroovyException( "Cannot instantiate class parsed from script: '%s'. Reason: %s", e, script,
                                           e.getMessage() );
        }
        catch ( final ClassCastException e )
        {
            throw new IndyGroovyException( "Script: '%s' instance: %s cannot be cast as: %s", e, script, instance,
                                           type.getName() );
        }
    }

    // TODO: scripts that can use CDI injection will need to use this method to inject their fields.
    @Inject
    private BeanManager beanManager;

    private <T> T inject( T bean )
    {
        CreationalContext<T> ctx = beanManager.createCreationalContext( null );

        AnnotatedType<T> annotatedType =
                beanManager.createAnnotatedType( (Class<T>) bean.getClass() );

        InjectionTarget<T> injectionTarget =
                beanManager.createInjectionTarget( annotatedType );

        injectionTarget.inject( bean, ctx );

        return bean;
    }
}

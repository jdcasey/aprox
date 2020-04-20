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
package org.commonjava.indy.model.core.io;

import com.fasterxml.jackson.annotation.JsonInclude.Include;
import com.fasterxml.jackson.core.JsonGenerator.Feature;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.atlas.maven.graph.jackson.ProjectRelationshipSerializerModule;
import org.commonjava.atlas.maven.ident.jackson.ProjectVersionRefSerializerModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.inject.Alternative;
import javax.enterprise.inject.Instance;
import javax.inject.Inject;
import javax.inject.Named;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;

@Alternative
@Named
public class IndyObjectMapper
    extends ObjectMapper
{

    private static final long serialVersionUID = 1L;

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private Instance<Module> injectedModules;

    @Inject
    private Instance<ModuleSet> injectedModuleSets;

    private final Iterable<Module> ctorModules;

    private final Iterable<ModuleSet> ctorModuleSets;

    private Set<String> registeredModules = new HashSet<>();

    protected IndyObjectMapper()
    {
        ctorModules = null;
        ctorModuleSets = null;
    }

    public IndyObjectMapper( final boolean unused, final Module... additionalModules )
    {
        final Set<Module> mods = new HashSet<Module>();
        mods.add( ApiSerializerModule.INSTANCE );
        mods.add( ProjectVersionRefSerializerModule.INSTANCE );
        mods.add( ProjectRelationshipSerializerModule.INSTANCE );
        mods.addAll( Arrays.asList( additionalModules ) );
        this.ctorModules = mods;
        this.ctorModuleSets = null;

        init();
    }

    public IndyObjectMapper( final Iterable<Module> modules )
    {
        final Set<Module> mods = new HashSet<Module>();
        mods.add( new ApiSerializerModule() );
        mods.add( new ProjectVersionRefSerializerModule() );
        mods.add( new ProjectRelationshipSerializerModule() );
        for ( final Module module : modules )
        {
            mods.add( module );
        }
        this.ctorModules = mods;
        this.ctorModuleSets = null;

        init();
    }

    public IndyObjectMapper( Instance<Module> modules, Instance<ModuleSet> moduleSets )
    {
        final Set<Module> mods = new HashSet<Module>();
        mods.add( new ApiSerializerModule() );
        mods.add( new ProjectVersionRefSerializerModule() );
        mods.add( new ProjectRelationshipSerializerModule() );
        for ( final Module module : modules )
        {
            mods.add( module );
        }
        this.ctorModules = mods;
        this.ctorModuleSets = moduleSets;

        init();
    }

    @PostConstruct
    public void init()
    {
        setSerializationInclusion( Include.NON_EMPTY );
        configure( Feature.AUTO_CLOSE_JSON_CONTENT, true );
        configure( DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);

        enable( SerializationFeature.INDENT_OUTPUT, SerializationFeature.USE_EQUALITY_FOR_OBJECT_ID );

        enable( MapperFeature.AUTO_DETECT_FIELDS );
        //        disable( MapperFeature.AUTO_DETECT_GETTERS );

        disable( SerializationFeature.WRITE_NULL_MAP_VALUES, SerializationFeature.WRITE_EMPTY_JSON_ARRAYS );

        disable( DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES );

        inject( injectedModules, injectedModuleSets );
        inject( ctorModules, ctorModuleSets );
    }

    private void inject( Iterable<Module> modules, Iterable<ModuleSet> moduleSets )
    {
        Set<Module> injected = new HashSet<>();

        Logger logger = LoggerFactory.getLogger( getClass() );
        if ( modules != null )
        {
            for ( final Module module : modules )
            {
                injected.add( module );
            }
        }

        if ( moduleSets != null )
        {
            for ( ModuleSet moduleSet : moduleSets )
            {
                logger.trace("Adding module-set to object mapper..." );

                Set<Module> set = moduleSet.getModules();
                if ( set != null )
                {
                    for ( Module module : set )
                    {
                        injected.add( module );
                    }
                }
            }
        }

        for ( Module module : injected )
        {
            injectSingle( module );
        }

    }

    private void injectSingle( Module module )
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.info("Registering object-mapper module: {}", module );

        registerModule( module );
        registeredModules.add( module.getClass().getSimpleName() );

        if ( module instanceof IndySerializerModule )
        {
            ( (IndySerializerModule) module ).register( this );
        }
    }

    public Set<String> getRegisteredModuleNames()
    {
        return registeredModules;
    }

    public String patchLegacyStoreJson( final String json )
        throws JsonProcessingException, IOException
    {
        final JsonNode tree = readTree( json );
        logger.debug( "Patching JSON tree: {}", tree );

        final JsonNode keyNode = tree.get( ArtifactStore.KEY_ATTR );
        StoreKey key;
        try
        {
            key = StoreKey.fromString( keyNode.textValue() );
        }
        catch ( IllegalArgumentException e )
        {
            throw new IndySerializationException(
                    "Cannot patch store JSON. StoreKey 'key' attribute has invalid packageType (first segment)!", null,
                    e );
        }

        boolean changed = false;
        if ( key == null )
        {
            throw new IndySerializationException( "Cannot patch store JSON. No StoreKey 'key' attribute found!", null );
        }
        else if ( !keyNode.textValue().equals( key.toString() ) )
        {
            logger.trace( "Patching key field in JSON for: {}", key );
            ( (ObjectNode) tree ).put( ArtifactStore.KEY_ATTR, key.toString() );
            changed = true;
        }

        JsonNode field = tree.get( ArtifactStore.TYPE_ATTR );
        if ( field == null )
        {
            logger.trace( "Patching type field in JSON for: {}", key );
            ( (ObjectNode) tree ).put( ArtifactStore.TYPE_ATTR, key.getType()
                                                                   .singularEndpointName() );
            changed = true;
        }

        field = tree.get( ArtifactStore.PKG_TYPE_ATTR );
        if ( field == null )
        {
            logger.trace( "Patching packageType field in JSON for: {}", key );
            ( (ObjectNode) tree ).put( ArtifactStore.PKG_TYPE_ATTR, key.getPackageType() );
            changed = true;
        }

        if ( changed )
        {
            String patched = writeValueAsString( tree );
            logger.trace( "PATCHED store definition:\n\n{}\n\n", patched );
            return patched;
        }

        return json;
    }

}

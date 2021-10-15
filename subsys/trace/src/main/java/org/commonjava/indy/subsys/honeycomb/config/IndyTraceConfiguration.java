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
package org.commonjava.indy.subsys.honeycomb.config;

import org.commonjava.indy.conf.IndyConfigInfo;
import org.commonjava.indy.conf.IndyConfiguration;
import org.commonjava.indy.subsys.honeycomb.TracerPlugin;
import org.commonjava.o11yphant.honeycomb.HoneycombConfiguration;
import org.commonjava.o11yphant.otel.OtelConfiguration;
import org.commonjava.o11yphant.trace.TracerConfiguration;
import org.commonjava.propulsor.config.ConfigurationException;
import org.commonjava.propulsor.config.annotation.SectionName;
import org.commonjava.propulsor.config.section.MapSectionListener;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;

@SectionName( "trace" )
@ApplicationScoped
public class IndyTraceConfiguration
                extends MapSectionListener
                implements IndyConfigInfo, TracerConfiguration, OtelConfiguration, HoneycombConfiguration
{
    private static final TracerPlugin DEFAULT_TRACER = TracerPlugin.honeycomb;

    @Inject
    private IndyConfiguration indyConfiguration;

    private static final String ENABLED = "enabled";

    private static final String TRACER = "tracer";

    private static final String CONSOLE_TRANSPORT = "console.transport";

    private static final String WRITE_KEY = "honeycomb.write.key";

    private static final String DATASET = "honeycomb.dataset";

    private static final String OTEL_GRPC_URI = "otel.grpc.uri";

    private static final String OTEL_GRPC_HEADERS = "otel.grpc.headers";

    private static final String FIELDS = "fields";

    private static final String BASE_SAMPLE_RATE = "base.sample.rate";

    private static final String SAMPLE_PREFIX = "sample.";

    private static final String ENVIRONMENT_MAPPINGS = "environment.mappings";

    private static final String CP_NAMES = "cp.names";

    private static final Integer DEFAULT_BASE_SAMPLE_RATE = 100;

    private boolean enabled;

    private TracerPlugin tracer;

    private boolean consoleTransport;

    private String writeKey;

    private String dataset;

    private Integer baseSampleRate;

    private Map<String, Integer> spanRates = new HashMap<>();

    private Set<String> fields;

    private String environmentMappings;

    private String cpNames;

    private String grpcUri;

    private Map<String, String> grpcHeaders = new HashMap<>();

    public IndyTraceConfiguration()
    {
    }

    @Override
    public Map<String, Integer> getSpanRates()
    {
        return spanRates;
    }

    @Override
    public boolean isEnabled()
    {
        return enabled;
    }

    @Override
    public boolean isConsoleTransport()
    {
        return consoleTransport;
    }

    @Override
    public String getServiceName()
    {
        return "indy";
    }

    @Override
    public void sectionStarted( final String name ) throws ConfigurationException
    {
        // NOP; just block map init in the underlying implementation.
    }

    @Override
    public void parameter( final String name, final String value ) throws ConfigurationException
    {
        switch ( name )
        {
            case ENABLED:
                this.enabled = Boolean.TRUE.equals( Boolean.parseBoolean( value.trim() ) );
                break;
            case TRACER:
                this.tracer = TracerPlugin.valueOf( value.trim().toLowerCase() );
                break;
            case WRITE_KEY:
                this.writeKey = value.trim();
                break;
            case DATASET:
                this.dataset = value.trim();
                break;
            case BASE_SAMPLE_RATE:
                this.baseSampleRate = Integer.parseInt( value.trim() );
                break;
            case ENVIRONMENT_MAPPINGS:
                this.environmentMappings = value.trim();
                break;
            case CP_NAMES:
                this.cpNames = value.trim();
                break;
            case FIELDS:
                this.fields = Collections.unmodifiableSet(
                                new HashSet<>( Arrays.asList( value.trim().split( "\\s*,\\s*" ) ) ) );
                break;
            case CONSOLE_TRANSPORT:
                this.consoleTransport = Boolean.parseBoolean( value.trim() );
                break;
            case OTEL_GRPC_URI:
                this.grpcUri = value.trim();
                break;
            case OTEL_GRPC_HEADERS:
                String[] kvs = value.trim().split( "\\s*,\\s*" );
                Stream.of( kvs )
                      .map( kv -> kv.split( "\\s*=\\s*" ) )
                      .filter( kv -> kv.length > 1 )
                      .forEach( kv -> grpcHeaders.put( kv[0], kv[1] ) );
                break;
            default:
                if ( name.startsWith( SAMPLE_PREFIX ) && name.length() > SAMPLE_PREFIX.length() )
                {
                    spanRates.put( name.substring( SAMPLE_PREFIX.length() ).trim(), Integer.parseInt( value ) );
                }
        }
    }

    public TracerPlugin getTracer()
    {
        return tracer == null ? DEFAULT_TRACER : tracer;
    }

    @Override
    public String getWriteKey()
    {
        return writeKey;
    }

    @Override
    public String getDataset()
    {
        return dataset;
    }

    @Override
    public boolean isHoneycombConfigValid()
    {
        return isNotEmpty( getWriteKey() ) && isNotEmpty( getDataset() );
    }

    @Override
    public String getDefaultConfigFileName()
    {
        return "trace.conf";
    }

    @Override
    public InputStream getDefaultConfig()
    {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream( "default-trace.conf" );
    }

    @Override
    public Integer getBaseSampleRate()
    {
        return baseSampleRate == null ? DEFAULT_BASE_SAMPLE_RATE : baseSampleRate;
    }

    @Override
    public Set<String> getFieldSet()
    {
        return fields == null ? DEFAULT_FIELDS : fields;
    }

    @Override
    public String getEnvironmentMappings()
    {
        return environmentMappings;
    }

    @Override
    public String getCPNames()
    {
        return cpNames;
    }

    @Override
    public String getNodeId()
    {
        return indyConfiguration.getNodeId();
    }

    @Override
    public Map<String, String> getGrpcHeaders()
    {
        return grpcHeaders;
    }

    @Override
    public String getGrpcEndpointUri()
    {
        return grpcUri == null ? DEFAULT_GRPC_URI : grpcUri;
    }

    public void validateForHoneycomb() throws ConfigurationException
    {
        Set<String> ret = new HashSet<>();
        if ( isEmpty( writeKey ) )
        {
            ret.add( WRITE_KEY );
        }

        if ( isEmpty( dataset ) )
        {
            ret.add( DATASET );
        }

        if ( !ret.isEmpty() )
        {
            throw new ConfigurationException( "Cannot initialize Honeycomb tracer. Missing configuration fields: {}",
                                              ret );
        }
    }

}

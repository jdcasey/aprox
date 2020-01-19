package org.commonjava.indy.metrics;

import com.codahale.metrics.MetricRegistry;
import org.commonjava.indy.metrics.conf.IndyMetricsConfig;
import org.commonjava.maven.galley.config.TransportMetricConfig;
import org.commonjava.maven.galley.model.Location;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.apache.commons.lang.StringUtils.isNotBlank;
import static org.commonjava.indy.model.core.StoreType.remote;
import static org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor.MAVEN_PKG_KEY;

@ApplicationScoped
public class IndyTransportMetricConfigProvider
{

    private TransportMetricConfig transportMetricConfig;

    @Inject
    private IndyMetricsConfig config;

    @Inject
    private MetricRegistry metricRegistry;

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Produces
    public TransportMetricConfig getTransportMetricConfig()
    {
        return transportMetricConfig;
    }

    @PostConstruct
    public void init()
    {
        if ( config.isMeasureTransport() )
        {
            logger.info( "Adding transport metrics to registry: {}", metricRegistry );
            final String measureRepos = config.getMeasureTransportRepos();
            final List<String> list = new ArrayList<>();
            if ( isNotBlank( measureRepos ) )
            {
                String[] toks = measureRepos.split( "," );
                for ( String s : toks )
                {
                    s = s.trim();
                    if ( isNotBlank( s ) )
                    {
                        if ( s.indexOf( ":" ) < 0 )
                        {
                            s = MAVEN_PKG_KEY + ":" + remote.singularEndpointName() + ":" + s; // use default
                        }
                        list.add( s );
                    }
                }
            }
            transportMetricConfig = new TransportMetricConfig()
            {
                @Override
                public boolean isEnabled()
                {
                    return true;
                }

                @Override
                public String getNodePrefix()
                {
                    return config.getNodePrefix();
                }

                @Override
                public String getMetricUniqueName( Location location )
                {
                    String locationName = location.getName();
                    for ( String s : list )
                    {
                        if ( s.equals( locationName ) )
                        {
                            return normalizeName( s );
                        }

                        if ( s.endsWith( "*" ) ) // handle wildcard
                        {
                            String prefix = s.substring( 0, s.length() - 1 );
                            if ( locationName.startsWith( prefix ) )
                            {
                                return normalizeName( prefix );
                            }
                        }
                    }
                    return null;
                }
            };
        }
    }

    private String normalizeName( String name )
    {
        return name.replaceAll( ":", "." );
    }
}

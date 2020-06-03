package org.commonjava.indy.subsys.honeycomb;

import com.codahale.metrics.Meter;
import io.honeycomb.libhoney.EventPostProcessor;
import io.honeycomb.libhoney.eventdata.EventData;
import org.commonjava.indy.metrics.IndyMetricsManager;
import org.commonjava.indy.metrics.conf.IndyMetricsConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import static org.commonjava.indy.metrics.IndyMetricsConstants.METER;
import static org.commonjava.indy.metrics.IndyMetricsConstants.getDefaultName;
import static org.commonjava.indy.metrics.IndyMetricsConstants.getName;

@ApplicationScoped
public class IndyEventPostProcessor implements EventPostProcessor
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private IndyMetricsManager metricsManager;

    @Inject
    private IndyMetricsConfig metricsConfig;

    private final static String TRANSFER_HONEYCOMB_EVENT = "indy.transferred.honeycomb.event";

    @Override
    public void process( EventData<?> eventData )
    {
        if ( metricsConfig != null && metricsManager != null )
        {
            String name = getName( metricsConfig.getNodePrefix(), TRANSFER_HONEYCOMB_EVENT,
                                   getDefaultName( IndyEventPostProcessor.class, "process" ), METER );
            Meter meter = metricsManager.getMeter( name );
            meter.mark();
        }
    }
}

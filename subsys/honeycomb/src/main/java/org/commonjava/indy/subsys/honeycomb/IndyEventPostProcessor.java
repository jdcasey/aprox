package org.commonjava.indy.subsys.honeycomb;

import io.honeycomb.libhoney.EventPostProcessor;
import io.honeycomb.libhoney.eventdata.EventData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class IndyEventPostProcessor implements EventPostProcessor
{

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Override
    public void process( EventData<?> eventData )
    {
        //TODO
    }
}

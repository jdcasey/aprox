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
package org.commonjava.indy.core.inject;

import org.commonjava.propulsor.metrics.annotation.Measure;
import org.commonjava.indy.measure.annotation.MetricNamed;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.Location;
import org.commonjava.maven.galley.spi.nfc.NotFoundCache;

import javax.decorator.Decorator;
import javax.decorator.Delegate;
import javax.enterprise.inject.Any;
import javax.inject.Inject;
import java.util.Map;
import java.util.Set;

@Decorator
public abstract class NFCMetricsDecorator
        implements NotFoundCache
{
    @Delegate
    @Any
    @Inject
    private NotFoundCache delegate;

    @Measure( timers = @MetricNamed( "indy.nfc.addMissing" ) )
    @Override
    public void addMissing( final ConcreteResource resource )
    {
        delegate.addMissing( resource );
    }

    @Measure( timers = @MetricNamed( "indy.nfc.isMissing" ) )
    @Override
    public boolean isMissing( final ConcreteResource resource )
    {
        return delegate.isMissing( resource );
    }

    @Measure( timers = @MetricNamed( "indy.nfc.clearMissing.location" ) )
    @Override
    public void clearMissing( final Location location )
    {
        delegate.clearMissing( location );
    }

    @Measure( timers = @MetricNamed( "indy.nfc.clearMissing.resource" ) )
    @Override
    public void clearMissing( final ConcreteResource resource )
    {
        delegate.clearMissing( resource );
    }

    @Measure( timers = @MetricNamed( "indy.nfc.clearAllMissing" ) )
    @Override
    public void clearAllMissing()
    {
        delegate.clearAllMissing();
    }

    @Measure( timers = @MetricNamed( "indy.nfc.getAllMissing" ) )
    @Override
    public Map<Location, Set<String>> getAllMissing()
    {
        return delegate.getAllMissing();
    }

    @Measure( timers = @MetricNamed( "indy.nfc.getMissing.location" ) )
    @Override
    public Set<String> getMissing( final Location location )
    {
        return delegate.getMissing( location );
    }
}

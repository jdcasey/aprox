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
package org.commonjava.indy.promote.metrics;

import org.commonjava.indy.promote.model.PathsPromoteResult;
import org.commonjava.propulsor.metrics.MetricsManager;
import org.commonjava.propulsor.metrics.conf.MetricsConfig;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.commonjava.propulsor.metrics.MetricsUtils.getDefaultName;

@ApplicationScoped
public class PathGauges
{
    @Inject
    private MetricsManager metricsManager;

    @Inject
    private MetricsConfig metricsConfig;

    private AtomicInteger total = new AtomicInteger();

    private AtomicInteger completed = new AtomicInteger();

    private AtomicInteger skipped = new AtomicInteger();

    public PathGauges()
    {
    }

    @PostConstruct
    public void init()
    {
        registerPathPromotionGauges( metricsManager );
    }

    private void registerPathPromotionGauges( MetricsManager metricsManager )
    {
        String defaultName = getDefaultName( getClass(), "last");

        Map<String, Supplier<Integer>> gauges = new HashMap<>();
        gauges.put( "total", () -> getTotal() );
        gauges.put( "completed", () -> getCompleted() );
        gauges.put( "skipped", () -> getSkipped() );
        metricsManager.registerGauges( defaultName, gauges );
    }

    public int getTotal()
    {
        return total.get();
    }

    public int getCompleted()
    {
        return completed.get();
    }

    public int getSkipped()
    {
        return skipped.get();
    }

    public void setTotal( int total )
    {
        this.total.set( total );
    }

    public void setSkipped( int size )
    {
        skipped.set( size );
    }

    public void setCompleted( int size )
    {
        completed.set( size );
    }

    public void update( int total, PathsPromoteResult result )
    {
        setTotal( total );
        setCompleted( result.getCompletedPaths().size() );
        setSkipped( result.getSkippedPaths().size() );
    }
}

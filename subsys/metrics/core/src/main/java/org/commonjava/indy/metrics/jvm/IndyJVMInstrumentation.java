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
package org.commonjava.indy.metrics.jvm;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.jvm.BufferPoolMetricSet;
import com.codahale.metrics.jvm.CachedThreadStatesGaugeSet;
import com.codahale.metrics.jvm.ClassLoadingGaugeSet;
import com.codahale.metrics.jvm.FileDescriptorRatioGauge;
import com.codahale.metrics.jvm.GarbageCollectorMetricSet;
import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import com.codahale.metrics.jvm.ThreadStatesGaugeSet;

import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;

import static com.codahale.metrics.MetricRegistry.name;

/**
 * Created by xiabai on 3/10/17.
 */
public class IndyJVMInstrumentation
{
    private static final String JVM_MEMORY = "jvm.memory";

    private static final String JVM_GARBAGE = "jvm.garbage";

    private static final String JVM_THREADS = "jvm.threads";

    private static final String JVM_FILES = "jvm.files";

    private static final String JVM_BUFFERS = "jvm.buffers";

    private static final String JVM_CLASSLOADING = "jvm.classloading";

    public static void registerJvmMetric( String nodePrefix, MetricRegistry registry )
    {
        registry.register( name( nodePrefix, JVM_MEMORY ), new MemoryUsageGaugeSet() );
        registry.register( name( nodePrefix, JVM_GARBAGE ), new GarbageCollectorMetricSet() );
        registry.register( name( nodePrefix, JVM_THREADS ), new CachedThreadStatesGaugeSet( 60, TimeUnit.SECONDS ));
        registry.register( name( nodePrefix, JVM_FILES ), new FileDescriptorRatioGauge() );
        registry.register( name( nodePrefix, JVM_CLASSLOADING ), new ClassLoadingGaugeSet() );
        registry.register( name( nodePrefix, JVM_BUFFERS ),
                           new BufferPoolMetricSet( ManagementFactory.getPlatformMBeanServer() ) );
    }
}

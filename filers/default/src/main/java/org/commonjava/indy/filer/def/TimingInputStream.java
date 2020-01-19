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
package org.commonjava.indy.filer.def;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import org.apache.commons.io.input.CountingInputStream;
import org.commonjava.indy.metrics.RequestContextHelper;
import org.commonjava.maven.galley.util.IdempotentCloseInputStream;
import org.commonjava.propulsor.metrics.MeteringContext;
import org.commonjava.propulsor.metrics.spi.TimingContext;

import java.io.IOException;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static org.commonjava.indy.IndyContentConstants.NANOS_PER_MILLISECOND;
import static org.commonjava.indy.IndyContentConstants.NANOS_PER_SEC;

public class TimingInputStream
        extends IdempotentCloseInputStream
{
    private static final String RAW_IO_READ = "io.raw.read";
    private static final String RAW_IO_READ_TIMER = RAW_IO_READ + ".timer";

    private static final String RAW_IO_READ_RATE = RAW_IO_READ + ".rate";

    private Long nanos;

    private Function<String, TimingContext> timerProvider;

    private final Function<String, MeteringContext> meterProvider;

    private BiConsumer<String, Double> cumulativeTimer;

    private TimingContext timer;

    private MeteringContext meter;

    public TimingInputStream( final CountingInputStream stream, final Function<String, TimingContext> timerProvider,
                              final Function<String, MeteringContext> meterProvider,
                              final BiConsumer<String, Double> cumulativeTimer )
    {
        super( stream );
        this.timerProvider = timerProvider == null ? ( s ) -> null : timerProvider;
        this.meterProvider = meterProvider;
        this.cumulativeTimer = cumulativeTimer;
    }

    @Override
    public int read()
            throws IOException
    {
        initMetrics();
        return super.read();
    }

    @Override
    public int read( final byte[] b )
            throws IOException
    {
        initMetrics();
        return super.read( b );
    }

    @Override
    public int read( final byte[] b, final int off, final int len )
            throws IOException
    {
        initMetrics();
        return super.read( b, off, len );
    }

    @Override
    public void close()
            throws IOException
    {
        super.close();

        if ( nanos != null )
        {
            long elapsed = System.nanoTime() - nanos;
            RequestContextHelper.setContext( RequestContextHelper.RAW_IO_WRITE_NANOS, elapsed );

            if ( timer != null )
            {
                timer.stop();
            }

            if ( meter != null )
            {
                meter.mark( (long) ( ( (CountingInputStream) this.in ).getByteCount() / ( elapsed / NANOS_PER_SEC ) ) );
            }

            cumulativeTimer.accept( RAW_IO_READ, elapsed / NANOS_PER_MILLISECOND );
        }
    }

    private void initMetrics()
    {
        if ( nanos == null )
        {
            nanos = System.nanoTime();
            timer = timerProvider.apply( RAW_IO_READ_TIMER );
            meter = meterProvider.apply( RAW_IO_READ_RATE );
        }
    }
}

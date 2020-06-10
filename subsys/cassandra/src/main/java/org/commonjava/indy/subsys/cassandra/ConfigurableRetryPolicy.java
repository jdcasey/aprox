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
package org.commonjava.indy.subsys.cassandra;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ConsistencyLevel;
import com.datastax.driver.core.Statement;
import com.datastax.driver.core.WriteType;
import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.policies.DefaultRetryPolicy;
import com.datastax.driver.core.policies.RetryPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConfigurableRetryPolicy
                implements RetryPolicy
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    final private RetryPolicy delegate = DefaultRetryPolicy.INSTANCE;

    final int readRetries;

    final int writeRetries;

    public ConfigurableRetryPolicy( int readRetries, int writeRetries )
    {
        this.readRetries = readRetries;
        this.writeRetries = writeRetries;
    }

    /**
     * Defines whether to retry and at which consistency level on a read timeout. {@link DefaultRetryPolicy}
     * @param nbRetry the number of retry already performed for this operation.
     */
    @Override
    public RetryDecision onReadTimeout( Statement statement, ConsistencyLevel cl, int requiredResponses,
                                        int receivedResponses, boolean dataRetrieved, int nbRetry )
    {
        logger.warn( "ReadTimeout, statement: {}, nbRetry: {}, readRetries: {}", statement, nbRetry, readRetries );
        if ( nbRetry >= readRetries )
        {
            return RetryDecision.rethrow();
        }
        return RetryDecision.tryNextHost( cl );
    }

    @Override
    public RetryDecision onWriteTimeout( Statement statement, ConsistencyLevel cl, WriteType writeType,
                                         int requiredAcks, int receivedAcks, int nbRetry )
    {
        logger.warn( "WriteTimeout, statement: {}, nbRetry: {}, writeRetries: {}", statement, nbRetry, writeRetries );
        if ( nbRetry >= writeRetries )
        {
            return RetryDecision.rethrow();
        }
        return RetryDecision.tryNextHost( cl );
    }

    @Override
    public RetryDecision onUnavailable( Statement statement, ConsistencyLevel cl, int requiredReplica, int aliveReplica,
                                        int nbRetry )
    {
        return delegate.onUnavailable( statement, cl, requiredReplica, aliveReplica, nbRetry );
    }

    @Override
    public RetryDecision onRequestError( Statement statement, ConsistencyLevel cl, DriverException e, int nbRetry )
    {
        return delegate.onRequestError( statement, cl, e, nbRetry );
    }

    @Override
    public void init( Cluster cluster )
    {
        delegate.init( cluster );
    }

    @Override
    public void close()
    {
        delegate.close();
    }
}

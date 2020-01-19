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
package org.commonjava.indy.subsys.infinispan;

import org.apache.commons.lang.StringUtils;
import org.commonjava.propulsor.metrics.MetricsManager;
import org.infinispan.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.HeuristicMixedException;
import javax.transaction.HeuristicRollbackException;
import javax.transaction.NotSupportedException;
import javax.transaction.RollbackException;
import javax.transaction.SystemException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Holder class that helps manage the shutdown process for things that use Infinispan.
 */
public class CacheHandle<K,V> extends BasicCacheHandle<K,V>
{
    protected CacheHandle(){}

    public CacheHandle( String named, Cache<K, V> cache, MetricsManager metricsManager, String metricPrefix )
    {
        super( named, cache, metricsManager, metricPrefix );
    }

    public CacheHandle( String named, Cache<K, V> cache )
    {
        this( named, cache, null, null );
    }

    /**
     * This is needed to pass the raw cache to lower level components, like Partyline.
     * @return
     */
    public Cache<K,V> getCache()
    {
        return (Cache) this.cache;
    }

    public <R> R executeCache( Function<Cache<K, V>, R> operation )
    {
        return doExecuteCache( "execute", operation );
    }

    public <R> R executeCache( Function<Cache<K, V>, R> operation, String metric )
    {
        if ( StringUtils.isBlank( metric ) )
        {
            return executeCache( operation );
        }
        return doExecuteCache( metric, operation );
    }

    // TODO: Why is this separate from {@link BasicCacheHandle#execute(Function)}?
    protected <R> R doExecuteCache( String metricName, Function<Cache<K, V>, R> operation )
    {
        Supplier<R> execution = executionFor( operation );
        if ( metricsManager != null )
        {
            return metricsManager.wrapWithStandardMetrics( execution, () -> getMetricName( metricName ) );
        }

        return execution.get();
    }

    private <R> Supplier<R> executionFor( final Function<Cache<K,V>,R> operation )
    {
        Logger logger = LoggerFactory.getLogger( getClass() );

        return () -> {
            if ( !isStopped() )
            {
                try
                {
                    return (R) operation.apply( (Cache) cache );
                }
                catch ( RuntimeException e )
                {
                    logger.error( "Failed to complete operation: " + e.getMessage(), e );
                }
            }
            else
            {
                logger.error( "Cannot complete operation. Cache {} is shutting down.", getName() );
            }

            return null;
        };
    }

    public void beginTransaction()
            throws NotSupportedException, SystemException
    {
        AtomicReference<NotSupportedException> suppEx = new AtomicReference<>();
        AtomicReference<SystemException> sysEx = new AtomicReference<>();
        doExecuteCache( "beginTransaction", ( c ) -> {
            try
            {
                c.getAdvancedCache().getTransactionManager().begin();
            }
            catch ( NotSupportedException e )
            {
                suppEx.set( e );
            }
            catch ( SystemException e )
            {
                sysEx.set( e );
            }

            return null;
        } );

        if ( suppEx.get() != null )
        {
            throw suppEx.get();
        }

        if ( sysEx.get() != null )
        {
            throw sysEx.get();
        }
    }

    public void rollback()
            throws SystemException
    {
        AtomicReference<SystemException> sysEx = new AtomicReference<>();
        doExecuteCache( "rollback", ( c ) -> {
            try
            {
                c.getAdvancedCache().getTransactionManager().rollback();
            }
            catch ( SystemException e )
            {
                sysEx.set( e );
            }

            return null;
        } );

        if ( sysEx.get() != null )
        {
            throw sysEx.get();
        }
    }

    public void commit()
            throws SystemException, HeuristicMixedException, HeuristicRollbackException, RollbackException
    {
        AtomicReference<SystemException> sysEx = new AtomicReference<>();
        AtomicReference<HeuristicMixedException> hmEx = new AtomicReference<>();
        AtomicReference<HeuristicRollbackException> hrEx = new AtomicReference<>();
        AtomicReference<RollbackException> rEx = new AtomicReference<>();

        doExecuteCache( "commit", ( c ) -> {
            try
            {
                c.getAdvancedCache().getTransactionManager().commit();
            }
            catch ( SystemException e )
            {
                sysEx.set( e );
            }
            catch ( HeuristicMixedException e )
            {
                hmEx.set( e );
            }
            catch ( HeuristicRollbackException e )
            {
                hrEx.set( e );
            }
            catch ( RollbackException e )
            {
                rEx.set( e );
            }

            return null;
        } );

        if ( sysEx.get() != null )
        {
            throw sysEx.get();
        }

        if ( hmEx.get() != null )
        {
            throw hmEx.get();
        }

        if ( hrEx.get() != null )
        {
            throw hrEx.get();
        }

        if ( rEx.get() != null )
        {
            throw rEx.get();
        }
    }

    public int getTransactionStatus()
            throws SystemException
    {
        AtomicReference<SystemException> sysEx = new AtomicReference<>();

        Integer result = doExecuteCache( "getTransactionStatus", ( c ) -> {
            try
            {
                return c.getAdvancedCache().getTransactionManager().getStatus();
            }
            catch ( SystemException e )
            {
                sysEx.set( e );
            }

            return null;
        } );

        if ( sysEx.get() != null )
        {
            throw sysEx.get();
        }

        return result;
    }

    public Object getLockOwner( K key )
    {
        return doExecuteCache( "getLockOwner", ( c ) -> c.getAdvancedCache().getLockManager().getOwner( key ) );
    }

    public boolean isLocked( K key )
    {
        return doExecuteCache( "isLocked", ( c ) -> c.getAdvancedCache().getLockManager().isLocked( key ) );
    }

    public void lock( K... keys )
    {
        doExecuteCache( "lock", ( c ) -> c.getAdvancedCache().lock( keys ) );
    }

}

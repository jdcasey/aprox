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
import com.datastax.driver.core.Session;
import com.datastax.driver.core.SocketOptions;
import org.commonjava.indy.subsys.cassandra.config.CassandraConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.commons.lang.StringUtils.isNotBlank;

@ApplicationScoped
public class CassandraClient
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private CassandraConfig config;

    private String host;

    private int port;

    private String username;

    final private Map<String, Session> sessions = new ConcurrentHashMap<>();

    private Cluster cluster;

    public CassandraClient()
    {
    }

    public CassandraClient( CassandraConfig config )
    {
        this.config = config;
        init();
    }

    @PostConstruct
    private void init()
    {
        if ( !config.isEnabled() )
        {
            logger.info( "Cassandra client not enabled" );
            return;
        }

        host = config.getCassandraHost();
        port = config.getCassandraPort();
        SocketOptions socketOptions = new SocketOptions();
        socketOptions.setConnectTimeoutMillis( config.getConnectTimeoutMillis() );
        socketOptions.setReadTimeoutMillis( config.getReadTimeoutMillis() );
        Cluster.Builder builder = Cluster.builder()
                                         .withoutJMXReporting()
                                         .withRetryPolicy( new ConfigurableRetryPolicy( config.getReadRetries(),
                                                                                        config.getWriteRetries() ) )
                                         .addContactPoint( host )
                                         .withPort( port )
                                         .withSocketOptions( socketOptions );
        username = config.getCassandraUser();
        String password = config.getCassandraPass();
        if ( isNotBlank( username ) && isNotBlank( password ) )
        {
            logger.info( "Build with credentials, user: {}, pass: ****", username );
            builder.withCredentials( username, password );
        }
        cluster = builder.build();
    }

    public Session getSession( String keyspace )
    {
        if ( !config.isEnabled() )
        {
            logger.info( "Cassandra client not enabled" );
            return null;
        }

        return sessions.computeIfAbsent( keyspace, key -> {
            logger.info( "Connect to Cassandra, host: {}, port: {}, user: {}, keyspace: {}", host, port, username,
                         key );
            try
            {
                return cluster.connect();
            }
            catch ( Exception e )
            {
                logger.error( "Connecting to Cassandra failed", e );
            }
            return null;
        } );
    }

    private volatile boolean closed;

    public void close()
    {
        if ( !closed && cluster != null && sessions != null )
        {
            logger.info( "Close cassandra client" );
            sessions.entrySet().forEach( e -> e.getValue().close() );
            sessions.clear();
            cluster.close();
            cluster = null;
            closed = true;
        }
    }
}

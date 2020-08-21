// This empty file is necessary for cassandra-maven-plugin. If missing, the plugin start-up will fail.
CREATE  KEYSPACE IF NOT EXISTS test WITH REPLICATION = {
    'class' : 'SimpleStrategy',
    'replication_factor' : 1
};
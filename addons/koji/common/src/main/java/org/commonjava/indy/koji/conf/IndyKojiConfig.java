/**
 * Copyright (C) 2011-2018 Red Hat, Inc. (https://github.com/Commonjava/indy)
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
package org.commonjava.indy.koji.conf;

import com.redhat.red.build.koji.config.KojiConfig;
import org.commonjava.indy.conf.IndyConfigInfo;
import org.commonjava.util.jhttpc.model.SiteConfig;
import org.commonjava.util.jhttpc.model.SiteConfigBuilder;
import org.commonjava.util.jhttpc.model.SiteTrustType;
import org.commonjava.web.config.ConfigurationException;
import org.commonjava.web.config.annotation.SectionName;
import org.commonjava.web.config.section.MapSectionListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.apache.commons.io.FileUtils.readFileToString;
import static org.commonjava.util.jhttpc.model.SiteConfig.DEFAULT_CONNECTION_POOL_TIMEOUT_SECONDS;
import static org.commonjava.util.jhttpc.model.SiteConfig.DEFAULT_MAX_CONNECTIONS;
import static org.commonjava.util.jhttpc.model.SiteConfig.DEFAULT_PROXY_PORT;
import static org.commonjava.util.jhttpc.model.SiteConfig.DEFAULT_REQUEST_TIMEOUT_SECONDS;

/**
 * Created by jdcasey on 5/20/16.
 */
@SectionName( IndyKojiConfig.SECTION_NAME )
@ApplicationScoped
public class IndyKojiConfig
        extends MapSectionListener
        implements IndyConfigInfo, KojiConfig
{
    private static final String KOJI_SITE_ID = "koji";

    private static final String DEFAULT_CONFIG_FILE_NAME = "default-koji.conf";

    public static final String SECTION_NAME = "koji";

    private static final String TARGET_KEY_PREFIX = "target.";

    private static final String TARGET_BINARY_KEY_PREFIX = "target.binary.";

    private static final boolean DEFAULT_ENABLED = false;

    private static final Integer DEFAULT_DOWNLOAD_TIMEOUT_SECONDS = 600;

    public static final long DEFAULT_LOCK_TIMEOUT_SECONDS = 30;

    public static final long DEFAULT_METADATA_TIMEOUT_SECONDS = 86400;

    private static final boolean DEFAULT_TAG_PATTERNS_ENABLED = false;

    private static final boolean DEFAULT_PROXY_BINARY_BUILDS = false;

    private static final Boolean DEFAULT_QUERY_CACHE_ENABLED = true;

    private static final Boolean DEFAULT_CONSOLIDATION_ENABLED = false;

    private static final int DEFAULT_QUERY_CACHE_EXPIRATION_HOURS = 3; // 3 hours for volatile caches

    private Boolean enabled;

    private String url;

    private String clientPemPath;

    private String serverPemPath;

    private String keyPassword;

    private Integer maxConnections;

    private String proxyHost;

    private Integer proxyPort;

    private String proxyUser;

    private Integer requestTimeoutSeconds;

    private String siteTrustType;

    private String proxyPassword;

    private String storageRootUrl;

    private Boolean tagPatternsEnabled;

    private Boolean queryCacheEnabled;

    private Boolean proxyBinaryBuilds;

    private List<String> tagPatterns;

    private Map<String, String> targetGroups = new LinkedHashMap<>();

    private Map<String, String> targetBinaryGroups = new LinkedHashMap<>();

    private String namingFormat = "koji-${nvr}"; // default

    private String binayNamingFormat = "koji-binary-${name}-${version}"; // default

    private Integer downloadTimeoutSeconds;

    private Integer queryCacheTimeoutHours;

    private Long lockTimeoutSeconds;

    private Long metadataTimeoutSeconds;

    private String artifactAuthorityStore;

    private Integer connectionPoolTimeoutSeconds;

    private String versionFilter;

    private Boolean consolidationEnabled;

    @Override
    public SiteConfig getKojiSiteConfig()
            throws IOException
    {
        return new SiteConfigBuilder().withId( getKojiSiteId() )
                                      .withKeyCertPem( getClientPemContent() )
                                      .withServerCertPem( getServerPemContent() )
                                      .withUri( getKojiURL() )
                                      .withMaxConnections( getMaxConnections() )
                                      .withProxyHost( getProxyHost() )
                                      .withProxyPort( getProxyPort() )
                                      .withProxyUser( getProxyUser() )
                                      .withRequestTimeoutSeconds( getRequestTimeoutSeconds() )
                                      .withConnectionPoolTimeoutSeconds( getConnectionPoolTimeoutSeconds() )
                                      .withTrustType( SiteTrustType.getType( getSiteTrustType() ) )
                                      .build();
    }

    public String getServerPemContent()
            throws IOException
    {
        return readPemContent( getServerPemPath() );
    }

    public String getClientPemContent()
            throws IOException
    {
        return readPemContent( getClientPemPath() );
    }

    private String readPemContent( String pemPath )
            throws IOException
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.trace( "Reading PEM content from path: '{}'", pemPath );

        if ( pemPath == null )
        {
            return null;
        }

        File f = new File( pemPath );
        if ( !f.exists() || f.isDirectory() )
        {
            return null;
        }

        String pem =  readFileToString( f );

        logger.trace( "Got PEM content:\n\n{}\n\n", pem );

        return pem;
    }

    @Override
    public String getKojiURL()
    {
        return getUrl();
    }

    @Override
    public String getKojiClientCertificatePassword()
    {
        return keyPassword;
    }

    @Override
    public String getKojiSiteId()
    {
        return KOJI_SITE_ID;
    }

    // TODO: Implement kerberos support...
    @Override
    public String getKrbCCache()
    {
        return null;
    }

    @Override
    public String getKrbKeytab()
    {
        return null;
    }

    @Override
    public String getKrbPassword()
    {
        return null;
    }

    @Override
    public String getKrbPrincipal()
    {
        return null;
    }

    @Override
    public String getKrbService()
    {
        return null;
    }
    // TODO: END: Implement kerberos support...

    public Integer getMaxConnections()
    {
        return maxConnections == null ? DEFAULT_MAX_CONNECTIONS : maxConnections;
    }

    public String getServerPemPath()
    {
        return serverPemPath;
    }

    public String getClientPemPath()
    {
        return clientPemPath;
    }

    public String getProxyHost()
    {
        return proxyHost;
    }

    public Integer getProxyPort()
    {
        return proxyPort == null ? DEFAULT_PROXY_PORT : proxyPort;
    }

    public String getProxyUser()
    {
        return proxyUser;
    }

    public Integer getRequestTimeoutSeconds()
    {
        return requestTimeoutSeconds == null ? DEFAULT_REQUEST_TIMEOUT_SECONDS : requestTimeoutSeconds;
    }

    public Integer getDownloadTimeoutSeconds()
    {
        return downloadTimeoutSeconds == null ? DEFAULT_DOWNLOAD_TIMEOUT_SECONDS : downloadTimeoutSeconds;
    }

    public String getSiteTrustType()
    {
        return siteTrustType;
    }

    public String getUrl()
    {
        return url;
    }

    public String getKeyPassword()
    {
        return keyPassword;
    }

    public String getProxyPassword()
    {
        return proxyPassword;
    }

    public String getStorageRootUrl()
    {
        return storageRootUrl;
    }

    public void setUrl( String url )
    {
        this.url = url;
    }

    public void setClientPemPath( String clientPemPath )
    {
        this.clientPemPath = clientPemPath;
    }

    public void setServerPemPath( String serverPemPath )
    {
        this.serverPemPath = serverPemPath;
    }

    public void setKeyPassword( String keyPassword )
    {
        this.keyPassword = keyPassword;
    }

    public void setMaxConnections( Integer maxConnections )
    {
        this.maxConnections = maxConnections;
    }

    public void setProxyHost( String proxyHost )
    {
        this.proxyHost = proxyHost;
    }

    public void setProxyPort( Integer proxyPort )
    {
        this.proxyPort = proxyPort;
    }

    public void setProxyUser( String proxyUser )
    {
        this.proxyUser = proxyUser;
    }

    public void setRequestTimeoutSeconds( Integer requestTimeoutSeconds )
    {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    public void setSiteTrustType( String siteTrustType )
    {
        this.siteTrustType = siteTrustType;
    }

    public void setProxyPassword( String proxyPassword )
    {
        this.proxyPassword = proxyPassword;
    }

    public void setStorageRootUrl( String storageRootUrl )
    {
        this.storageRootUrl = storageRootUrl;
    }

    public List<String> getTagPatterns()
    {
        return tagPatterns;
    }

    public void setTagPatterns( List<String> tagPatterns )
    {
        this.tagPatterns = tagPatterns;
    }

    public Map<String, String> getTargetGroups()
    {
        return targetGroups;
    }

    public void setTargetGroups( Map<String, String> targetGroups )
    {
        this.targetGroups = targetGroups;
    }

    public Boolean getEnabled()
    {
        return enabled == null ? DEFAULT_ENABLED : enabled;
    }

    public void setEnabled( Boolean enabled )
    {
        this.enabled = enabled;
    }

    public boolean isEnabled()
    {
        return getEnabled();
    }

    public Boolean getConsolidationEnabled()
    {
        return consolidationEnabled == null ? DEFAULT_CONSOLIDATION_ENABLED : consolidationEnabled;
    }

    public void setConsolidationEnabled( Boolean enabled )
    {
        this.consolidationEnabled = enabled;
    }

    public boolean isConsolidationEnabled()
    {
        return getConsolidationEnabled();
    }

    public Boolean isQueryCacheEnabled()
    {
        return queryCacheEnabled == null ? DEFAULT_QUERY_CACHE_ENABLED : queryCacheEnabled;
    }

    public void setQueryCacheEnabled( Boolean queryCacheEnabled )
    {
        this.queryCacheEnabled = queryCacheEnabled;
    }

    public Integer getQueryCacheTimeoutHours()
    {
        return queryCacheTimeoutHours == null ? DEFAULT_QUERY_CACHE_EXPIRATION_HOURS : queryCacheTimeoutHours;
    }

    public void setQueryCacheTimeoutHours( Integer queryCacheTimeoutHours )
    {
        this.queryCacheTimeoutHours = queryCacheTimeoutHours;
    }

    public Boolean getTagPatternsEnabled()
    {
        return tagPatternsEnabled == null ? DEFAULT_TAG_PATTERNS_ENABLED : tagPatternsEnabled;
    }

    public boolean isTagPatternsEnabled()
    {
        return getTagPatternsEnabled();
    }

    public void setTagPatternsEnabled( Boolean tagPatternsEnabled )
    {
        this.tagPatternsEnabled = tagPatternsEnabled;
    }

    public Boolean getProxyBinaryBuilds()
    {
        return proxyBinaryBuilds == null ? DEFAULT_PROXY_BINARY_BUILDS : proxyBinaryBuilds;
    }

    public boolean isProxyBinaryBuilds()
    {
        return getProxyBinaryBuilds();
    }

    public void setProxyBinaryBuilds( Boolean proxyBinaryBuilds )
    {
        this.proxyBinaryBuilds = proxyBinaryBuilds;
    }

    public boolean isTagAllowed( String name )
    {
        if ( !isTagPatternsEnabled() )
        {
            return true;
        }

        Optional<String> result = tagPatterns.stream().filter( ( pattern ) -> name.matches( pattern ) ).findFirst();

        return result.isPresent();
    }

    public String getVersionFilter()
    {
        return versionFilter;
    }

    public void setVersionFilterer( String versionFilter )
    {
        this.versionFilter = versionFilter;
    }

    @Override
    public void parameter( final String name, final String value )
            throws ConfigurationException
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        logger.trace( "Got koji config parameter: '{}' with value: '{}'", name, value );
        switch ( name )
        {
            case "enabled":
            {
                this.enabled = Boolean.valueOf( value.trim() );
                break;
            }
            case "consolidation.enabled":
                this.consolidationEnabled = Boolean.valueOf( value.trim() );
                break;
            case "tag.patterns.enabled":
            {
                this.tagPatternsEnabled = Boolean.valueOf( value.trim() );
                break;
            }
            case "query.cache.enabled":
            {
                this.queryCacheEnabled = Boolean.valueOf( value.trim() );
                break;
            }
            case "query.cache.timeout.hours":
            {
                this.queryCacheTimeoutHours = Integer.valueOf( value );
                break;
            }
            case "proxy.binary.builds":
            {
                this.proxyBinaryBuilds = Boolean.valueOf( value.trim() );
                break;
            }
            case "tag.pattern":
            {
                if ( tagPatterns == null )
                {
                    tagPatterns = new ArrayList<>();
                }

                this.tagPatterns.add( value );
                break;
            }
            case "lock.timeout.seconds":
            {
                this.lockTimeoutSeconds = Long.parseLong( value );
                break;
            }
            case "metadata.timeout.seconds":
            {
                this.metadataTimeoutSeconds = Long.parseLong( value );
                break;
            }
            case "storage.root.url":
            {
                this.storageRootUrl = value;
                break;
            }
            case "proxy.password":
            {
                this.proxyPassword = value;
                break;
            }
            case "proxy.user":
            {
                this.proxyUser = value;
                break;
            }
            case "proxy.host":
            {
                this.proxyHost = value;
                break;
            }
            case "proxy.port":
            {
                this.proxyPort = Integer.valueOf( value );
                break;
            }
            case "client.pem.password":
            {
                this.keyPassword = value;
                break;
            }
            case "url":
            {
                this.url = value;
                break;
            }
            case "ssl.trust.type":
            {
                this.siteTrustType = value;
                break;
            }
            case "connection.pool.timeout.seconds":
            {
                this.connectionPoolTimeoutSeconds= Integer.valueOf( value );
                break;
            }
            case "request.timeout.seconds":
            {
                this.requestTimeoutSeconds = Integer.valueOf( value );
                break;
            }
            case "client.pem.path":
            {
                this.clientPemPath = value;
                break;
            }
            case "server.pem.path":
            {
                this.serverPemPath = value;
                break;
            }
            case "max.connections":
            {
                this.maxConnections = Integer.valueOf( value );
                break;
            }
            case "artifact.authorityStore":
            {
                this.artifactAuthorityStore = value;
                break;
            }
            case "naming.format":
            {
                this.namingFormat = value;
                break;
            }
            case "naming.format.binary":
            {
                this.binayNamingFormat = value;
                break;
            }
            case "version.filter":
            {
                this.versionFilter = value;
                break;
            }
            default:
            {
                if ( name.startsWith( TARGET_KEY_PREFIX ) && name.length() > TARGET_KEY_PREFIX.length() )
                {
                    if ( name.startsWith( TARGET_BINARY_KEY_PREFIX )
                            && name.length() > TARGET_BINARY_KEY_PREFIX.length() )
                    {
                        String source = name.substring( TARGET_BINARY_KEY_PREFIX.length(), name.length() );
                        logger.trace( "KOJI: Group {} targets binary group {}", source, value );
                        targetBinaryGroups.put( source, value );
                    }
                    else
                    {
                        String source = name.substring( TARGET_KEY_PREFIX.length(), name.length() );
                        logger.trace( "KOJI: Group {} targets group {}", source, value );
                        targetGroups.put( source, value );
                    }
                }
                else
                {
                    throw new ConfigurationException(
                            "Invalid parameter: '%s'.",
                            value, name, SECTION_NAME );
                }
            }
        }
    }

    @Override
    public String getDefaultConfigFileName()
    {
        return new File( IndyConfigInfo.CONF_INCLUDES_DIR, DEFAULT_CONFIG_FILE_NAME ).getPath();
    }

    @Override
    public InputStream getDefaultConfig()
    {
        return Thread.currentThread().getContextClassLoader().getResourceAsStream( DEFAULT_CONFIG_FILE_NAME );
    }

    public String getTargetGroup( String name )
    {
        for ( String key : targetGroups.keySet() )
        {
            if ( name.matches( key ) )
            {
                return targetGroups.get( key );
            }
        }
        return null;
    }

    public String getTargetBinaryGroup ( String name )
    {
        for ( String key : targetBinaryGroups.keySet() )
        {
            if ( name.matches( key ) )
            {
                return targetBinaryGroups.get( key );
            }
        }
        return null;
    }

    public boolean isEnabledFor( String name )
    {
        Logger logger = LoggerFactory.getLogger( getClass() );
        if ( targetGroups == null )
        {
            logger.warn( "No target groups defined for Koji access!" );
            return false;
        }

        for ( String key : targetGroups.keySet() )
        {
            logger.trace( "Checking target pattern '{}' against group name: '{}'", key, name );
            if ( name.equals(key) || name.matches( key ) )
            {
                logger.debug( "Target group for {} is {}", name, targetGroups.get( key ) );
                return true;
            }
        }

        logger.warn( "No target group found for: {}", name );
        return false;
    }

    public Long getLockTimeoutSeconds()
    {
        return lockTimeoutSeconds == null ? DEFAULT_LOCK_TIMEOUT_SECONDS : lockTimeoutSeconds;
    }

    public void setLockTimeoutSeconds( long lockTimeoutSeconds )
    {
        this.lockTimeoutSeconds = lockTimeoutSeconds;
    }

    public Long getMetadataTimeoutSeconds()
    {
        return metadataTimeoutSeconds == null ? DEFAULT_METADATA_TIMEOUT_SECONDS : metadataTimeoutSeconds;
    }

    public void setMetadataTimeoutSeconds( long metadataTimeoutSeconds )
    {
        this.metadataTimeoutSeconds = metadataTimeoutSeconds;
    }

    public String getArtifactAuthorityStore()
    {
        return artifactAuthorityStore;
    }

    public void setArtifactAuthorityStore( String artifactAuthorityStore )
    {
        this.artifactAuthorityStore = artifactAuthorityStore;
    }

    public String getNamingFormat() {
        return namingFormat;
    }

    public String getBinayNamingFormat()
    {
        return binayNamingFormat;
    }

    public Integer getConnectionPoolTimeoutSeconds()
    {
        return connectionPoolTimeoutSeconds == null ? DEFAULT_CONNECTION_POOL_TIMEOUT_SECONDS : connectionPoolTimeoutSeconds;
    }

    public void setConnectionPoolTimeoutSeconds( final Integer connectionPoolTimeoutSeconds )
    {
        this.connectionPoolTimeoutSeconds = connectionPoolTimeoutSeconds;
    }
}

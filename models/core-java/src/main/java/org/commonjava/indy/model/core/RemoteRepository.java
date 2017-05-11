/**
 * Copyright (C) 2011 Red Hat, Inc. (jdcasey@commonjava.org)
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
package org.commonjava.indy.model.core;

import java.net.MalformedURLException;
import java.net.URL;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonProperty;

import static org.commonjava.indy.model.core.StoreType.remote;

@ApiModel( description = "Proxy to a remote server's artifact content, with local cache storage.",
           parent = ArtifactStore.class )
public class RemoteRepository
        extends AbstractRepository
{
    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LoggerFactory.getLogger( RemoteRepository.class );

    @ApiModelProperty( required = true, value = "The remote URL to proxy" )
    @JsonProperty( "url" )
    private String url;

    @JsonProperty( "timeout_seconds" )
    private int timeoutSeconds;

    @JsonProperty( "nfc_timeout_seconds" )
    private int nfcTimeoutSeconds;

    private String host;

    private int port;

    private String user;

    private String password;

    @JsonProperty( "is_passthrough" )
    private boolean passthrough;

    @JsonProperty( "cache_timeout_seconds" )
    private int cacheTimeoutSeconds;

    @JsonProperty( "metadata_timeout_seconds" )
    private int metadataTimeoutSeconds;

    @JsonProperty( "key_password" )
    private String keyPassword;

    @JsonProperty( "key_certificate_pem" )
    private String keyCertificatePem;

    @JsonProperty( "server_certificate_pem" )
    private String serverCertificatePem;

    @JsonProperty( "proxy_host" )
    private String proxyHost;

    @JsonProperty( "proxy_port" )
    private int proxyPort;

    @JsonProperty( "proxy_user" )
    private String proxyUser;

    @JsonProperty( "proxy_password" )
    private String proxyPassword;

    @JsonProperty( "server_trust_policy" )
    private String serverTrustPolicy;

    RemoteRepository()
    {
    }

    @Deprecated
    public RemoteRepository( final String name, final String remoteUrl )
    {
        this( MavenPackageTypeDescriptor.MAVEN_PKG_KEY, name, remoteUrl );
    }

    public RemoteRepository( final String packageType, String name, String remoteUrl )
    {
        super( packageType, remote, name );
        this.url = remoteUrl;
        calculateFields();
    }

    public String getUrl()
    {
        calculateIfNeeded();
        return url;
    }

    public void setUrl( final String url )
    {
        this.url = url;
        calculateFields();
    }

    public String getUser()
    {
        calculateIfNeeded();
        return user;
    }

    private void calculateIfNeeded()
    {
        if ( host == null )
        {
            calculateFields();
        }
    }

    public void setUser( final String user )
    {
        this.user = user;
    }

    public String getPassword()
    {
        calculateIfNeeded();
        return password;
    }

    public void setPassword( final String password )
    {
        this.password = password;
    }

    public String getHost()
    {
        calculateIfNeeded();
        return host;
    }

    public void setHost( final String host )
    {
        this.host = host;
    }

    public int getPort()
    {
        calculateIfNeeded();
        return port;
    }

    public void setPort( final int port )
    {
        this.port = port;
    }

    public void calculateFields()
    {
        URL url = null;
        try
        {
            url = new URL( this.url );
        }
        catch ( final MalformedURLException e )
        {
            LOGGER.error( "Failed to parse repository URL: '{}'. Reason: {}", e, this.url, e.getMessage() );
        }

        if ( url == null )
        {
            return;
        }

        final String userInfo = url.getUserInfo();
        if ( userInfo != null && user == null && password == null )
        {
            user = userInfo;
            password = null;

            int idx = userInfo.indexOf( ':' );
            if ( idx > 0 )
            {
                user = userInfo.substring( 0, idx );
                password = userInfo.substring( idx + 1 );

                final StringBuilder sb = new StringBuilder();
                idx = this.url.indexOf( "://" );
                sb.append( this.url.substring( 0, idx + 3 ) );

                idx = this.url.indexOf( "@" );
                if ( idx > 0 )
                {
                    sb.append( this.url.substring( idx + 1 ) );
                }

                this.url = sb.toString();
            }
        }

        host = url.getHost();
        if ( url.getPort() < 0 )
        {
            port = url.getProtocol().equals( "https" ) ? 443 : 80;
        }
        else
        {
            port = url.getPort();
        }
    }

    public int getTimeoutSeconds()
    {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds( final int timeoutSeconds )
    {
        this.timeoutSeconds = timeoutSeconds;
    }

    @Override
    public String toString()
    {
        return String.format(
                "RemoteRepository [%s, %s]",
                getName(), url );
    }

    public boolean isPassthrough()
    {
        return passthrough;
    }

    public void setPassthrough( final boolean passthrough )
    {
        this.passthrough = passthrough;
    }

    public int getCacheTimeoutSeconds()
    {
        return cacheTimeoutSeconds;
    }

    public void setCacheTimeoutSeconds( final int cacheTimeoutSeconds )
    {
        this.cacheTimeoutSeconds = cacheTimeoutSeconds;
    }

    public int getMetadataTimeoutSeconds()
    {
        return metadataTimeoutSeconds;
    }

    public void setMetadataTimeoutSeconds( int metadataTimeoutSeconds )
    {
        this.metadataTimeoutSeconds = metadataTimeoutSeconds;
    }

    public void setKeyPassword( final String keyPassword )
    {
        this.keyPassword = keyPassword;
    }

    @JsonIgnore
    public String getKeyPassword()
    {
        return keyPassword;
    }

    public void setKeyCertPem( final String keyCertificatePem )
    {
        this.keyCertificatePem = keyCertificatePem;
    }

    @JsonIgnore
    public String getKeyCertPem()
    {
        return keyCertificatePem;
    }

    public void setServerCertPem( final String serverCertificatePem )
    {
        this.serverCertificatePem = serverCertificatePem;
    }

    @JsonIgnore
    public String getServerCertPem()
    {
        return serverCertificatePem;
    }

    public String getProxyHost()
    {
        return proxyHost;
    }

    public int getProxyPort()
    {
        return proxyPort;
    }

    public String getProxyUser()
    {
        return proxyUser;
    }

    public String getProxyPassword()
    {
        return proxyPassword;
    }

    public void setProxyHost( final String proxyHost )
    {
        this.proxyHost = proxyHost;
    }

    public void setProxyPort( final int proxyPort )
    {
        this.proxyPort = proxyPort;
    }

    public void setProxyUser( final String proxyUser )
    {
        this.proxyUser = proxyUser;
    }

    public void setProxyPassword( final String proxyPassword )
    {
        this.proxyPassword = proxyPassword;
    }

    public int getNfcTimeoutSeconds()
    {
        return nfcTimeoutSeconds;
    }

    public void setNfcTimeoutSeconds( final int nfcTimeoutSeconds )
    {
        this.nfcTimeoutSeconds = nfcTimeoutSeconds;
    }

    public String getServerTrustPolicy()
    {
        return serverTrustPolicy;
    }

    public void setServerTrustPolicy( final String serverTrustPolicy )
    {
        this.serverTrustPolicy = serverTrustPolicy;
    }

    @Override
    public RemoteRepository copyOf()
    {
        RemoteRepository repo = new RemoteRepository( getName(), getUrl() );
        repo.setServerTrustPolicy( getServerTrustPolicy() );
        repo.setKeyPassword( getKeyPassword() );
        repo.setKeyCertPem( getKeyCertPem() );
        repo.setServerCertPem( getServerCertPem() );
        repo.setCacheTimeoutSeconds( getCacheTimeoutSeconds() );
        repo.setMetadataTimeoutSeconds( getMetadataTimeoutSeconds() );
        repo.setNfcTimeoutSeconds( getNfcTimeoutSeconds() );
        repo.setPassthrough( isPassthrough() );
        repo.setProxyHost( getProxyHost() );
        repo.setHost( getHost() );
        repo.setPort( getPort() );
        repo.setProxyPort( getProxyPort() );
        repo.setProxyPassword( getProxyPassword() );
        repo.setProxyUser( getProxyUser() );
        repo.setTimeoutSeconds( getTimeoutSeconds() );

        copyRestrictions( repo );
        copyBase( repo );

        return repo;
    }
}

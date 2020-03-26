package org.commonjava.indy.repo.proxy;

import org.commonjava.indy.model.core.StoreKey;
import org.junit.Test;

import java.util.Optional;

import static org.commonjava.indy.repo.proxy.RepoProxyUtils.extractPath;
import static org.commonjava.indy.repo.proxy.RepoProxyUtils.getOriginalStoreKeyFromPath;
import static org.commonjava.indy.repo.proxy.RepoProxyUtils.getProxyTo;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class RepoProxyUtilsTest
{
    @Test
    public void testGetOriginalStoreKeyFromPath()
    {
        String path = "/api/content/maven/group/abc/org/commonjava/indy/maven-metadata.xml";
        Optional<String> storeKeyStr = getOriginalStoreKeyFromPath( path );
        assertTrue( storeKeyStr.isPresent() );
        assertThat( storeKeyStr.get(), equalTo( "maven:group:abc" ) );

        path = "/api/content/maven/hosted/def";
        storeKeyStr = getOriginalStoreKeyFromPath( path );
        assertTrue( storeKeyStr.isPresent() );
        assertThat( storeKeyStr.get(), equalTo( "maven:hosted:def" ) );

        path = "/api/content/npm/group/ghi/";
        storeKeyStr = getOriginalStoreKeyFromPath( path );
        assertTrue( storeKeyStr.isPresent() );
        assertThat( storeKeyStr.get(), equalTo( "npm:group:ghi" ) );

        path = "/api/content/npm/groupe/ghi/";
        storeKeyStr = getOriginalStoreKeyFromPath( path );
        assertFalse( storeKeyStr.isPresent() );
    }

    @Test
    public void testExtractPath()
    {
        String fullPath = "/api/content/maven/group/abc/org/commonjava/indy/maven-metadata.xml";
        String repoPath = "maven/group/abc";
        String path = extractPath( fullPath, repoPath );
        assertThat( path, equalTo( "/org/commonjava/indy/maven-metadata.xml" ) );

        fullPath = "/api/content/maven/hosted/def/org/commonjava/indy/1.0/";
        repoPath = "/maven/hosted/def";
        path = extractPath( fullPath, repoPath );
        assertThat( path, equalTo( "/org/commonjava/indy/1.0/" ) );

        fullPath = "/api/content/npm/group/ghi/org/commonjava/indy/indy-api/1.0/indy-api-1.0.pom";
        repoPath = "npm/group/ghi/";
        path = extractPath( fullPath, repoPath );
        assertThat( path, equalTo( "/org/commonjava/indy/indy-api/1.0/indy-api-1.0.pom" ) );

        fullPath = "/api/content/npm/hosted/jkl/org/commonjava/indy/indy-api/2.0/indy-api-2.0.jar";
        repoPath = "/npm/hosted/jkl/";
        path = extractPath( fullPath, repoPath );
        assertThat( path, equalTo( "/org/commonjava/indy/indy-api/2.0/indy-api-2.0.jar" ) );

        fullPath = "/api/content/npm/hosted/jkl/";
        repoPath = "/npm/hosted/jkl/";
        path = extractPath( fullPath, repoPath );
        assertThat( path, equalTo( "" ) );

        fullPath = "/api/content/npm/hosted/jkl";
        repoPath = "/npm/hosted/jkl/";
        path = extractPath( fullPath, repoPath );
        assertThat( path, equalTo( "" ) );
    }

    @Test
    public void testProxyTo()
    {
        String fullPath = "/api/content/maven/group/abc/org/commonjava/indy/maven-metadata.xml";
        Optional<String> proxyTo = getProxyTo( fullPath, StoreKey.fromString( "maven:remote:group-abc" ) );
        assertTrue( proxyTo.isPresent() );
        assertThat( proxyTo.get(), equalTo( "/api/content/maven/remote/group-abc/org/commonjava/indy/maven-metadata.xml" ) );

        fullPath = "/api/browse/content/maven/hosted/def/org/commonjava/indy/1.0/";
        proxyTo = getProxyTo( fullPath, StoreKey.fromString( "maven:remote:hosted-def" ) );
        assertTrue( proxyTo.isPresent() );
        assertThat( proxyTo.get(), equalTo( "/api/browse/content/maven/remote/hosted-def/org/commonjava/indy/1.0/" ) );

        fullPath = "/api/folo/track/npm/group/ghi/jquery";
        proxyTo = getProxyTo( fullPath, StoreKey.fromString( "npm:remote:group-ghi" ) );
        assertTrue( proxyTo.isPresent() );
        assertThat( proxyTo.get(), equalTo( "/api/folo/track/npm/remote/group-ghi/jquery" ) );

        fullPath = "/api/folo/track/npm/hosted/jkl/@react/core";
        proxyTo = getProxyTo( fullPath, StoreKey.fromString( "npm:remote:hosted-jkl") );
        assertTrue( proxyTo.isPresent() );
        assertThat( proxyTo.get(), equalTo( "/api/folo/track/npm/remote/hosted-jkl/@react/core" ) );
    }

}

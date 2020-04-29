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
package org.commonjava.indy.pkg.npm.content.group;

import org.apache.commons.io.IOUtils;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.content.IndyPathGenerator;
import org.commonjava.indy.model.core.Group;
import org.commonjava.indy.model.core.HostedRepository;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.io.IndyObjectMapper;
import org.commonjava.indy.pkg.npm.content.NPMStoragePathCalculator;
import org.commonjava.indy.pkg.npm.model.Bugs;
import org.commonjava.indy.pkg.npm.model.DistTag;
import org.commonjava.indy.pkg.npm.model.PackageMetadata;
import org.commonjava.indy.pkg.npm.model.Repository;
import org.commonjava.indy.pkg.npm.model.UserInfo;
import org.commonjava.indy.pkg.npm.model.VersionMetadata;
import org.commonjava.indy.pkg.npm.model.io.PackageSerializerModule;
import org.commonjava.indy.util.LocationUtils;
import org.commonjava.maven.galley.cache.FileCacheProvider;
import org.commonjava.maven.galley.event.NoOpFileEventManager;
import org.commonjava.maven.galley.io.NoOpTransferDecorator;
import org.commonjava.maven.galley.io.TransferDecoratorManager;
import org.commonjava.maven.galley.model.ConcreteResource;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;
import org.commonjava.maven.galley.spi.cache.CacheProvider;
import org.commonjava.maven.galley.spi.io.PathGenerator;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.commonjava.indy.pkg.npm.model.NPMPackageTypeDescriptor.NPM_PKG_KEY;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.fail;

public class PackageMetadataMergerTest
{
    private static final String VERSION_META = "metadata/";

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    private CacheProvider cacheProvider;

    private PackageSerializerModule module;

    @Before
    public void setup()
            throws Exception
    {
        cacheProvider = new FileCacheProvider( temp.newFolder( "cache" ), new IndyPathGenerator(
                Collections.singleton( new NPMStoragePathCalculator() ) ), new NoOpFileEventManager(),
                                               new TransferDecoratorManager( new NoOpTransferDecorator() ), false );

        module = new PackageSerializerModule();
    }

    @Test
    public void mergeTwoSimplePackageMetadataFiles() throws Exception
    {
        String path = "jquery";
        HostedRepository h1 = new HostedRepository( NPM_PKG_KEY, "test-hosted-1" );
        HostedRepository h2 = new HostedRepository( NPM_PKG_KEY, "test-hosted-2" );

        Transfer t1 = cacheProvider.getTransfer( new ConcreteResource( LocationUtils.toLocation( h1 ), path ) );
        initTestData( t1, VERSION_META + "package-1.json" );

        Transfer t2 = cacheProvider.getTransfer( new ConcreteResource( LocationUtils.toLocation( h2 ), path ) );
        initTestData( t2, VERSION_META + "package-2.json" );

        Group g = new Group( NPM_PKG_KEY, "test-group", h1.getKey(), h2.getKey() );

        List<Transfer> sources = Arrays.asList( t1, t2 );

        byte[] output = new PackageMetadataMerger( Collections.emptyList(), module ).merge( sources, g, path );
        IndyObjectMapper mapper = new IndyObjectMapper( true );
        PackageMetadata merged = mapper.readValue( IOUtils.toString( new ByteArrayInputStream( output ) ),
                                                   PackageMetadata.class );

        // normal object fields merging verification
        assertThat( merged.getName(), equalTo( "jquery" ) );
        assertThat( merged.getAuthor().getName(), equalTo( "JS Foundation and other contributors" ) );
        assertThat( merged.getRepository().getType(), equalTo( "git" ) );
        assertThat( merged.getRepository().getUrl(), equalTo( "git+https://github.com/jquery/jquery.git" ) );
        assertThat( merged.getReadmeFilename(), equalTo( "README.md" ) );
        assertThat( merged.getHomepage(), equalTo( "https://jquery.com" ) );
        assertThat( merged.getBugs().getUrl(), equalTo( "https://github.com/jquery/jquery/issues" ) );
        assertThat( merged.getLicense(), equalTo( "MIT" ) );

        // dist-tags object merging verification
        assertThat( merged.getDistTags().getBeta(), equalTo( "3.2.1-beta.1" ) );
        assertThat( merged.getDistTags().getLatest(), equalTo( "3.2.1" ) );

        // versions map merging verification
        Map<String, VersionMetadata> versions = merged.getVersions();
        assertThat( versions, notNullValue() );
        assertThat( versions.size(), equalTo( 2 ) );
        assertThat( versions.get( "1.5.1" ).getVersion(), equalTo( "1.5.1" ) );
        assertThat( versions.get( "1.6.2" ).getVersion(), equalTo( "1.6.2" ) );

        // maintainers list merging verification
        List<UserInfo> maintainers = merged.getMaintainers();
        assertThat( maintainers, notNullValue() );
        assertThat( maintainers.size(), equalTo( 3 ) );
        assertThat( maintainers.get( 0 ).getName(), equalTo( "dmethvin" ) );
        assertThat( maintainers.get( 1 ).getName(), equalTo( "mgol" ) );
        assertThat( maintainers.get( 2 ).getName(), equalTo( "scott.gonzalez" ) );

        // time map merging verification
        Map<String, String> times = merged.getTime();
        assertThat( times, notNullValue() );
        assertThat( times.size(), equalTo( 8 ) );
        assertThat( times.get( "modified" ), equalTo( "2017-05-23T10:57:14.309Z" ) );
        assertThat( times.get( "created" ), equalTo( "2011-04-19T07:19:56.392Z" ) );

        // users map merging verification
        Map<String, Boolean> users = merged.getUsers();
        assertThat( users, notNullValue() );
        assertThat( users.size(), equalTo( 10 ) );
        assertThat( users.get( "fgribreau" ), equalTo( true ) );

        // keywords list merging verification
        List<String> keywords = merged.getKeywords();
        assertThat( keywords, notNullValue() );
        assertThat( keywords.size(), equalTo( 4 ) );
        assertThat( keywords.contains( "javascript" ), equalTo( true ) );
    }

    @Test
    public void mergeWhenOneTransferIsMissing() throws Exception
    {
        String path = "jquery";
        HostedRepository h1 = new HostedRepository( NPM_PKG_KEY, "test-hosted-1" );
        HostedRepository h2 = new HostedRepository( NPM_PKG_KEY, "test-hosted-2" );

        Transfer t1 = cacheProvider.getTransfer( new ConcreteResource( LocationUtils.toLocation( h1 ), path ) );
        initTestData( t1, VERSION_META + "package-1.json" );

        Transfer t2 = cacheProvider.getTransfer( new ConcreteResource( LocationUtils.toLocation( h2 ), path ) );

        Group g = new Group( NPM_PKG_KEY, "test-group", h1.getKey(), h2.getKey() );

        List<Transfer> sources = Arrays.asList( t1, t2 );

        byte[] output = new PackageMetadataMerger( Collections.emptyList(), module ).merge( sources, g, path );
        IndyObjectMapper mapper = new IndyObjectMapper( true );
        PackageMetadata merged = mapper.readValue( IOUtils.toString( new ByteArrayInputStream( output ) ),
                                                   PackageMetadata.class );

        // normal object fields merging verification
        assertThat( merged.getName(), equalTo( "jquery" ) );
        assertThat( merged.getAuthor().getName(), equalTo( "JS Foundation and other contributors" ) );
        assertThat( merged.getRepository().getType(), equalTo( "git" ) );
        assertThat( merged.getRepository().getUrl(), equalTo( "git+https://github.com/jquery1/jquery1.git" ) );
        assertThat( merged.getReadmeFilename(), equalTo( "README1.md" ) );
        assertThat( merged.getHomepage(), equalTo( "https://jquery1.com" ) );
        assertThat( merged.getBugs().getUrl(), equalTo( "https://github.com/jquery1/jquery1/issues" ) );
        assertThat( merged.getLicense(), equalTo( "MIT1" ) );

        // dist-tags object merging verification
        assertThat( merged.getDistTags().getBeta(), equalTo( "2.2.1" ) );
        assertThat( merged.getDistTags().getLatest(), equalTo( "2.2.1" ) );

        // error, versions map merging verification
        Map<String, VersionMetadata> versions = merged.getVersions();
        assertThat( versions, notNullValue() );
        assertThat( versions.size(), equalTo( 1 ) );
        assertThat( versions.get( "1.5.1" ).getVersion(), equalTo( "1.5.1" ) );
    }

    @Test
    public void mergeWhenOneTransferIsInvalidXml() throws Exception
    {
        String path = "jquery";
        HostedRepository h1 = new HostedRepository( NPM_PKG_KEY, "test-hosted-1" );
        HostedRepository h2 = new HostedRepository( NPM_PKG_KEY, "test-hosted-2" );

        Transfer t1 = cacheProvider.getTransfer( new ConcreteResource( LocationUtils.toLocation( h1 ), path ) );
        initTestData( t1, VERSION_META + "package-1.json" );

        Transfer t2 = cacheProvider.getTransfer( new ConcreteResource( LocationUtils.toLocation( h2 ), path ) );
        initTestData( t2, VERSION_META + "invalid.json" );

        Group g = new Group( NPM_PKG_KEY, "test-group", h1.getKey(), h2.getKey() );

        List<Transfer> sources = Arrays.asList( t1, t2 );

        byte[] output = new PackageMetadataMerger( Collections.emptyList(), module ).merge( sources, g, path );
        IndyObjectMapper mapper = new IndyObjectMapper( true );
        PackageMetadata merged = mapper.readValue( IOUtils.toString( new ByteArrayInputStream( output ) ),
                                                   PackageMetadata.class );

        // normal object fields merging verification
        assertThat( merged.getName(), equalTo( "jquery" ) );
        assertThat( merged.getAuthor().getName(), equalTo( "JS Foundation and other contributors" ) );
        assertThat( merged.getRepository().getType(), equalTo( "git" ) );
        assertThat( merged.getRepository().getUrl(), equalTo( "git+https://github.com/jquery1/jquery1.git" ) );
        assertThat( merged.getReadmeFilename(), equalTo( "README1.md" ) );
        assertThat( merged.getHomepage(), equalTo( "https://jquery1.com" ) );
        assertThat( merged.getBugs().getUrl(), equalTo( "https://github.com/jquery1/jquery1/issues" ) );
        assertThat( merged.getLicense(), equalTo( "MIT1" ) );

        // dist-tags object merging verification
        assertThat( merged.getDistTags().getBeta(), equalTo( "2.2.1" ) );
        assertThat( merged.getDistTags().getLatest(), equalTo( "2.2.1" ) );

        // error, versions map merging verification
        Map<String, VersionMetadata> versions = merged.getVersions();
        assertThat( versions, notNullValue() );
        assertThat( versions.size(), equalTo( 1 ) );
        assertThat( versions.get( "1.5.1" ).getVersion(), equalTo( "1.5.1" ) );
    }

    @Test
    public void mergeOneTransferWithProviderContent() throws Exception
    {
        String path = "jquery";
        HostedRepository h1 = new HostedRepository( NPM_PKG_KEY, "test-hosted-1" );
        HostedRepository h2 = new HostedRepository( NPM_PKG_KEY, "test-hosted-2" );

        Transfer t1 = cacheProvider.getTransfer( new ConcreteResource( LocationUtils.toLocation( h1 ), path ) );
        initTestData( t1, VERSION_META + "package-1.json" );

        Transfer t2 = cacheProvider.getTransfer( new ConcreteResource( LocationUtils.toLocation( h2 ), path ) );
        initTestData( t2, VERSION_META + "package-2.json" );

        Group g = new Group( NPM_PKG_KEY, "test-group", h1.getKey(), h2.getKey() );

        List<Transfer> sources = Arrays.asList( t1, t2 );

        PackageMetadata provided = new PackageMetadata( "jquery" );
        provided.setDescription( "provider description test" );
        provided.setRepository( new Repository( "svn", "svn+https://svn.host/jquery" ) );
        provided.setBugs( new Bugs( "https://github.com/jquery2/jquery2/issues", "jquery2@bug.com" ) );
        List<UserInfo> added = new ArrayList<UserInfo>();
        added.add( new UserInfo( "testa", "testa@test.com" ) );
        provided.setMaintainers( added );
        TestPackageMetadataProvider testProvider = new TestPackageMetadataProvider( provided );

        byte[] output = new PackageMetadataMerger( Collections.singletonList( testProvider ), module ).merge( sources, g,
                                                                                                      path );
        IndyObjectMapper mapper = new IndyObjectMapper( true );
        PackageMetadata merged = mapper.readValue( IOUtils.toString( new ByteArrayInputStream( output ) ),
                                                   PackageMetadata.class );

        // normal object fields merging verification
        assertThat( merged.getName(), equalTo( "jquery" ) );
        assertThat( merged.getDescription(), equalTo( "provider description test" ) );
        assertThat( merged.getRepository().getType(), equalTo( "svn" ) );
        assertThat( merged.getRepository().getUrl(), equalTo( "svn+https://svn.host/jquery" ) );
        assertThat( merged.getBugs().getUrl(), equalTo( "https://github.com/jquery2/jquery2/issues" ) );
        assertThat( merged.getBugs().getEmail(), equalTo( "jquery2@bug.com" ) );

        // maintainers list merging verification
        List<UserInfo> maintainers = merged.getMaintainers();
        assertThat( maintainers, notNullValue() );
        assertThat( maintainers.size(), equalTo( 4 ) );
        assertThat( maintainers.get( 0 ).getName(), equalTo( "dmethvin" ) );
        assertThat( maintainers.get( 1 ).getName(), equalTo( "mgol" ) );
        assertThat( maintainers.get( 2 ).getName(), equalTo( "scott.gonzalez" ) );
        assertThat( maintainers.get( 3 ).getName(), equalTo( "testa" ) );
    }

    @Test
    public void mergeProviderWithDistTagVersionSorted() throws Exception
    {
        String path = "jquery";
        HostedRepository h1 = new HostedRepository( NPM_PKG_KEY, "test-hosted-1" );

        Transfer t1 = cacheProvider.getTransfer( new ConcreteResource( LocationUtils.toLocation( h1 ), path ) );
        initTestData( t1, VERSION_META + "package-dist-tag-version.json" );

        Group g = new Group( NPM_PKG_KEY, "test-group", h1.getKey() );

        List<Transfer> sources = Arrays.asList( t1 );

        PackageMetadata provided = new PackageMetadata( "jquery" );
        DistTag distTag = new DistTag();
        distTag.putTag( DistTag.LATEST, "3.2.3-rc.3" );
        distTag.putTag( DistTag.STABLE, "2.0" );
        distTag.putTag( DistTag.BETA, "3.2.1-beta.2" );
        distTag.putTag( DistTag.DEV, "3.2.3-alpha" );
        provided.setDistTags( distTag );

        TestPackageMetadataProvider testProvider = new TestPackageMetadataProvider( provided );

        byte[] output = new PackageMetadataMerger( Collections.singletonList( testProvider ), module ).merge( sources, g,
                                                                                                      path );
        IndyObjectMapper mapper = new IndyObjectMapper( true );
        PackageMetadata merged = mapper.readValue( IOUtils.toString( new ByteArrayInputStream( output ) ),
                                                   PackageMetadata.class );

        assertThat( merged.getDistTags().getLatest(), equalTo( "3.2.3-rc.3" ) );
        assertThat( merged.getDistTags().getStable(), equalTo( "3.2.1" ) );
        assertThat( merged.getDistTags().getBeta(), equalTo( "3.2.1-beta.2" ) );
        assertThat( merged.getDistTags().getDev(), equalTo( "3.2.3-alpha" ) );
    }

    @Test
    public void mergeProviderWithSameVersionMetadata() throws Exception
    {
        String path = "jquery";
        HostedRepository h1 = new HostedRepository( NPM_PKG_KEY, "test-hosted-1" );

        Transfer t1 = cacheProvider.getTransfer( new ConcreteResource( LocationUtils.toLocation( h1 ), path ) );
        initTestData( t1, VERSION_META + "package-1.json" );

        Group g = new Group( NPM_PKG_KEY, "test-group", h1.getKey() );

        List<Transfer> sources = Arrays.asList( t1 );

        PackageMetadata provided = new PackageMetadata( "jquery" );
        VersionMetadata versionMetadata = new VersionMetadata( "jquery", "1.5.1" );
        versionMetadata.setUrl( "jquery.new.com" );

        TestPackageMetadataProvider testProvider = new TestPackageMetadataProvider( provided );

        byte[] output = new PackageMetadataMerger( Collections.singletonList( testProvider ), module ).merge( sources, g,
                                                                                                      path );
        IndyObjectMapper mapper = new IndyObjectMapper( true );
        PackageMetadata merged = mapper.readValue( IOUtils.toString( new ByteArrayInputStream( output ) ),
                                                   PackageMetadata.class );

        assertThat( merged.getVersions().get( "1.5.1" ), notNullValue() );
        assertThat( merged.getVersions().get( "1.5.1" ).getUrl(), equalTo( "jquery.com" ) );
    }

    @Test
    public void mergeOneTransferWithProviderError() throws Exception
    {
        String path = "jquery";
        HostedRepository h1 = new HostedRepository( NPM_PKG_KEY, "test-hosted-1" );

        Transfer t1 = cacheProvider.getTransfer( new ConcreteResource( LocationUtils.toLocation( h1 ), path ) );
        initTestData( t1, VERSION_META + "package-1.json" );

        Group g = new Group( NPM_PKG_KEY, "test-group", h1.getKey() );

        List<Transfer> sources = Arrays.asList( t1 );

        TestPackageMetadataProvider testProvider = new TestPackageMetadataProvider(
                        new IndyWorkflowException( "Failed to get provider content" ) );

        byte[] output = new PackageMetadataMerger( Collections.singletonList( testProvider ), module ).merge( sources, g,
                                                                                                      path );
        IndyObjectMapper mapper = new IndyObjectMapper( true );
        PackageMetadata merged = mapper.readValue( IOUtils.toString( new ByteArrayInputStream( output ) ),
                                                   PackageMetadata.class );

        // normal object fields merging verification
        assertThat( merged.getName(), equalTo( "jquery" ) );
        assertThat( merged.getAuthor().getName(), equalTo( "JS Foundation and other contributors" ) );
        assertThat( merged.getRepository().getType(), equalTo( "git" ) );
        assertThat( merged.getRepository().getUrl(), equalTo( "git+https://github.com/jquery1/jquery1.git" ) );
        assertThat( merged.getReadmeFilename(), equalTo( "README1.md" ) );
        assertThat( merged.getHomepage(), equalTo( "https://jquery1.com" ) );
        assertThat( merged.getBugs().getUrl(), equalTo( "https://github.com/jquery1/jquery1/issues" ) );
        assertThat( merged.getLicense(), equalTo( "MIT1" ) );

        // dist-tags object merging verification
        assertThat( merged.getDistTags().getBeta(), equalTo( "2.2.1" ) );
        assertThat( merged.getDistTags().getLatest(), equalTo( "2.2.1" ) );

        // error, versions map merging verification
        Map<String, VersionMetadata> versions = merged.getVersions();
        assertThat( versions, notNullValue() );
        assertThat( versions.size(), equalTo( 1 ) );
        assertThat( versions.get( "1.5.1" ).getVersion(), equalTo( "1.5.1" ) );
    }

    private void initTestData( Transfer transfer, String resourcePath ) throws IOException
    {
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream( resourcePath );
                        OutputStream out = transfer.openOutputStream( TransferOperation.DOWNLOAD, false ))
        {

            if ( in == null )
            {
                fail( "Cannot find test resource: " + resourcePath + " on classpath!" );
            }

            IOUtils.copy( in, out );
        }
    }

    private static final class TestPackageMetadataProvider
                    implements PackageMetadataProvider
    {
        private PackageMetadata provided;

        private IndyWorkflowException error;

        public TestPackageMetadataProvider( PackageMetadata provided )
        {
            this.provided = provided;
        }

        public TestPackageMetadataProvider( IndyWorkflowException error )
        {
            this.error = error;
        }

        @Override
        public PackageMetadata getMetadata( StoreKey targetStore, String path ) throws IndyWorkflowException
        {
            if ( error != null )
            {
                throw error;
            }
            return provided;
        }
    }
}

package org.commonjava.indy.pkg.npm.model.io;

import com.fasterxml.jackson.databind.module.SimpleModule;
import org.commonjava.indy.pkg.npm.model.VersionMetadata;

import javax.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PackageSerializerModule extends SimpleModule
{

    public PackageSerializerModule()
    {
        super( "Indy Package Metadata API" );
        addDeserializer( VersionMetadata.class, new VersionMetadataDeserializer() );
    }

}

package org.commonjava.indy.pkg.maven.model;

import org.commonjava.atservice.annotation.Service;
import org.commonjava.indy.model.core.PackageTypeDescriptor;

import static org.commonjava.maven.galley.io.SpecialPathConstants.PKG_TYPE_MAVEN;

/**
 * {@link PackageTypeDescriptor} implementation for Maven content.
 *
 * Created by jdcasey on 5/10/17.
 */
// FIXME: Move to indy-pkg-maven-model-java module as soon as we can stop defaulting package type in StoreKey to 'maven'
@Service( PackageTypeDescriptor.class )
public class MavenPackageTypeDescriptor
        implements PackageTypeDescriptor
{
    public static final String MAVEN_PKG_KEY = PKG_TYPE_MAVEN;

    @Override
    public String getKey()
    {
        return MAVEN_PKG_KEY;
    }
}

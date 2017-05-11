package org.commonjava.indy.model.core;

import org.commonjava.atservice.annotation.Service;

import static org.commonjava.maven.galley.io.SpecialPathConstants.PKG_TYPE_GENERIC_HTTP;

/**
 * Package type to handle generic http content, as is used by httprox.
 *
 * Created by jdcasey on 5/10/17.
 */
@Service( PackageTypeDescriptor.class )
public class GenericPackageTypeDescriptor
    implements PackageTypeDescriptor
{
    public static final String GENERIC_PKG_KEY = PKG_TYPE_GENERIC_HTTP;

    @Override
    public String getKey()
    {
        return GENERIC_PKG_KEY;
    }
}

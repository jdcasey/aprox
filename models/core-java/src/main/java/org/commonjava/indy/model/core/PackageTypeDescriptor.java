package org.commonjava.indy.model.core;

import java.util.Map;

/**
 * Represents different types of package content (eg. maven, NPM, etc.). This is part of the data represented by
 * {@link org.commonjava.indy.model.core.StoreKey}, but rather than using an enum we need this to be extensible. So, this
 * interface offers a way to define new package types and also load the available package types, using
 * {@link java.util.ServiceLoader}.
 *
 * Created by jdcasey on 5/10/17.
 *
 * @see PackageTypes
 */
public interface PackageTypeDescriptor
{
    String getKey();
}

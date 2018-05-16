package org.commonjava.indy.promote.validate;

import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.promote.validate.model.ValidationRequest;

public interface SourcePathStoreKeyIterationAction<T>
{
    String apply( ValidationRequest request, StoreKey key, String path, T item );
}

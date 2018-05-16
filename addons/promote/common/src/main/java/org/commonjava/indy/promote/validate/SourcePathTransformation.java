package org.commonjava.indy.promote.validate;

import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.promote.validate.model.ValidationRequest;

import javax.xml.transform.Source;

public interface SourcePathTransformation<T>
{
    T apply( ValidationRequest request, String path );
}

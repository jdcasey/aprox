package org.commonjava.indy.promote.validate;

import org.commonjava.indy.promote.validate.model.ValidationRequest;

import java.util.function.BiFunction;

public interface ValidatorIterationAction<T>
        extends BiFunction<ValidationRequest, T, String>
{
}

package org.commonjava.indy.promote.rules

import org.commonjava.indy.model.core.StoreKey
import org.commonjava.indy.promote.model.PromoteRequest
import org.commonjava.indy.promote.validate.PromotionValidationException
import org.commonjava.indy.promote.validate.PromotionValidationTools
import org.commonjava.indy.promote.validate.SourcePathStoreKeyIterationAction
import org.commonjava.indy.promote.validate.SourcePathTransformation
import org.commonjava.indy.promote.validate.model.ValidationRequest
import org.commonjava.indy.promote.validate.model.ValidationRule
import org.commonjava.maven.atlas.ident.ref.ArtifactRef

import java.util.function.BiFunction

class NoPreExistingPaths implements ValidationRule {

    class Action implements SourcePathStoreKeyIterationAction<String>
    {
        @Override
        String apply(ValidationRequest req, StoreKey key, String path) {
            return req.getTools().exists(key, path) ? "${it} is already available in: ${key}" : null;
        }
    }

    class SourcePathTransform implements SourcePathTransformation<String>
    {
        String apply(ValidationRequest req, String it)
        {
            req.getTools().getArtifact(it) == null ? null : it;
        }
    }

    String validate(ValidationRequest request) throws PromotionValidationException
    {
        return tools.verifySourcePathsInStoreKeys( request, true, true, new Action() );
    }
}

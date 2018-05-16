package GroupPromoteMatchesSucceedingValidationTest

import org.commonjava.indy.model.core.StoreKey
import org.commonjava.indy.promote.validate.PromotionValidationTools
import org.commonjava.indy.promote.validate.SourcePathStoreKeyIterationAction
import org.commonjava.indy.promote.validate.model.ValidationRequest
import org.commonjava.indy.promote.validate.model.ValidationRule

import java.util.function.Function

class TestParallelIteration implements ValidationRule
{
    class Action implements SourcePathStoreKeyIterationAction<String>
    {
        @Override
        String apply(final ValidationRequest request, final StoreKey key, final String path) {
            return "${path} slept."
        }
    }

    String validate( ValidationRequest request ) throws Exception
    {
        def tools = request.getTools()

        return tools.verifySourcePathsInStoreKeys( request, true, true, PromotionValidationTools.SPX_NO_OP, new Action() );
    }
}
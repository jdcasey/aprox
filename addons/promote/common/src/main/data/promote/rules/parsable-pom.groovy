package org.commonjava.indy.promote.rules

import org.commonjava.indy.promote.validate.ValidatorIterationAction
import org.commonjava.indy.promote.validate.model.ValidationRequest
import org.commonjava.indy.promote.validate.model.ValidationRule
import org.slf4j.LoggerFactory

class ParsablePom implements ValidationRule {

    class IterationAction implements ValidatorIterationAction<String>
    {
        String apply(ValidationRequest request, String path)
        {
            if (path.endsWith(".pom")) {
                logger.info("Parsing POM from path: {}.", path)
                try {
                    request.getTools().readLocalPom(path, request)
                }
                catch (Exception e) {
                    return "${path}: Failed to parse POM. Error was: ${e}";
                }
            }

            return null;
        }
    }

    String validate(ValidationRequest request) {
        def logger = LoggerFactory.getLogger(ValidationRule.class)
        logger.info("Parsing POMs in:\n  {}.", request.getSourcePaths().join("\n  "))

        return request.getTools().iterate(request.getSourcePaths(), request, new IterationAction() )
    }
}
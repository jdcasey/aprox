package org.commonjava.indy.promote.rules;

import org.commonjava.indy.promote.validate.model.ValidationRequest
import org.commonjava.indy.promote.validate.model.ValidationRule
import org.slf4j.LoggerFactory

class ProjectVersionPattern implements ValidationRule {

    class VersionPatternAction implements ValidatorIterationAction<String>
    {
        String versionPattern;

        String apply( ValidationRequest req, String path )
        {
            def ref = req.getTools().getArtifact(it)
            if (ref != null) {
                def vs = ref.getVersionString()
                if (!vs.matches(versionPattern)) {
                    if (builder.length() > 0) {
                        builder.append("\n")
                    }
                    return "${it} does not match version pattern: '${versionPattern}' (version was: '${vs}')";
                }
            }

            return null;
        }
    }

    String validate(ValidationRequest request) throws Exception {
        def versionPattern = request.getValidationParameter("versionPattern")
        if (versionPattern != null)
        {
            return request.getTools().iterate(request.getSourcePaths(), request, new VersionPatternAction({versionPattern: versionPattern}));
        }

        def logger = LoggerFactory.getLogger(getClass())
        logger.warn("No 'versionPattern' parameter specified in rule-set: {}. Cannot execute ProjectVersionPattern rule!", request.getRuleSet().getName())

        return null;
    }
}
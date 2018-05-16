package org.commonjava.indy.promote.rules

import org.commonjava.indy.promote.validate.model.ValidationRequest
import org.commonjava.indy.promote.validate.model.ValidationRule
import org.commonjava.maven.galley.maven.rel.ModelProcessorConfig

class NoVersionRanges implements ValidationRule {

    String validate(ValidationRequest request) {
        def verifyStoreKeys = request.getTools().getValidationStoreKeys(request, true)

        def builder = new StringBuilder()
        def tools = request.getTools()
        def dc = new ModelProcessorConfig().setIncludeBuildSection(true).setIncludeManagedPlugins(true).setIncludeManagedDependencies(true)

        request.getSourcePaths().each { it ->
            if (it.endsWith(".pom")) {
                def relationships = tools.getRelationshipsForPom(it, dc, request, verifyStoreKeys)
                if (relationships != null) {
                    relationships.each { rel ->
                        def target = rel.getTarget()
                        if (!target.getVersionSpec().isSingle()) {
                            if (builder.length() > 0) {
                                builder.append("\n")
                            }
                            builder.append(target).append(" uses a compound version in: ").append(it)
                        }
                    }
                }
            }
        }

        builder.length() > 0 ? builder.toString() : null
    }
}
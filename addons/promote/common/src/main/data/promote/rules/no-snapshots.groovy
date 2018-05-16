package org.commonjava.indy.promote.rules

import org.commonjava.indy.model.core.StoreKey
import org.commonjava.indy.promote.validate.PromotionValidationException
import org.commonjava.indy.promote.validate.SourcePathStoreKeyIterationAction
import org.commonjava.indy.promote.validate.SourcePathTransformation
import org.commonjava.indy.promote.validate.model.ValidationRequest
import org.commonjava.indy.promote.validate.model.ValidationRule
import org.commonjava.maven.atlas.ident.ref.ArtifactRef
import org.commonjava.maven.galley.maven.rel.ModelProcessorConfig

class NoSnapshots implements ValidationRule {

    class Action implements SourcePathStoreKeyIterationAction<ArtifactRef> {
        @Override
        String apply(ValidationRequest req, StoreKey key, String it, ArtifactRef ref) {
            StringBuilder builder = new StringBuilder();

            if (!ref.getVersionSpec().isRelease()) {
                if (builder.length() > 0) {
                    builder.append("\n")
                }
                builder.append(it).append(" is a variable/snapshot version.")
            }

            def relationships = req.getTools().getRelationshipsForPom(it, dc, request, verifyStoreKeys)
            if (relationships != null) {
                relationships.each { rel ->
                    def target = rel.getTarget()
                    if (!target.getVersionSpec().isRelease()) {
                        if (builder.length() > 0) {
                            builder.append("\n")
                        }
                        builder.append(target).append(" uses a variable/snapshot version in: ").append(it)
                    }
                }
            }

            return builder.length() < 1 ? null : builder;
        }
    }

    class SourcePathTransform implements SourcePathTransformation<ArtifactRef> {
        ArtifactRef apply(ValidationRequest req, String it)
        {
            def aref = req.getTools().getArtifact(it)
            return aref == null ? null : aref;
        }
    }

    String validate(ValidationRequest request) throws PromotionValidationException
    {
        return tools.verifySourcePathsInStoreKeys( request, true, true, new Action() );
    }

    String validate(ValidationRequest request) {
        def verifyStoreKeys = request.getTools().getValidationStoreKeys(request, true)

        def builder = new StringBuilder()
        def tools = request.getTools()
        def dc = new ModelProcessorConfig().setIncludeBuildSection(true).setIncludeManagedPlugins(true).setIncludeManagedDependencies(true)

        request.getSourcePaths().each { it ->
            if (it.endsWith(".pom")) {
                def ref = tools.getArtifact(it)
                if (ref != null) {
                    if (!ref.getVersionSpec().isRelease()) {
                        if (builder.length() > 0) {
                            builder.append("\n")
                        }
                        builder.append(it).append(" is a variable/snapshot version.")
                    }
                }

                def relationships = tools.getRelationshipsForPom(it, dc, request, verifyStoreKeys)
                if (relationships != null) {
                    relationships.each { rel ->
                        def target = rel.getTarget()
                        if (!target.getVersionSpec().isRelease()) {
                            if (builder.length() > 0) {
                                builder.append("\n")
                            }
                            builder.append(target).append(" uses a variable/snapshot version in: ").append(it)
                        }
                    }
                }
            }
        }

        builder.length() > 0 ? builder.toString() : null
    }
}
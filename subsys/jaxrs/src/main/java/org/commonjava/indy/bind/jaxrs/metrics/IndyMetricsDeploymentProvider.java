package org.commonjava.indy.bind.jaxrs.metrics;

import io.undertow.servlet.api.DeploymentInfo;
import org.commonjava.indy.bind.jaxrs.IndyDeploymentProvider;
import org.commonjava.propulsor.metrics.servlet.MetricsDeploymentInfoProvider;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.core.Application;

@ApplicationScoped
@Named
public class IndyMetricsDeploymentProvider
        extends IndyDeploymentProvider
{
    @Inject
    private MetricsDeploymentInfoProvider metricsProvider;

    @Override
    public DeploymentInfo getDeploymentInfo( final String contextRoot, final Application application )
    {
        return metricsProvider.getDeploymentInfo();
    }
}

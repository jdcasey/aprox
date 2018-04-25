package org.commonjava.indy.boot.jaxrs;

import org.commonjava.indy.boot.IndyBootOptions;
import org.commonjava.propulsor.deploy.undertow.UndertowBootOptions;

import javax.enterprise.context.ApplicationScoped;

/**
 * Created by jdcasey on 4/25/18.
 */
@ApplicationScoped
public class JaxRsBootOptions
        extends IndyBootOptions
        implements UndertowBootOptions
{
}

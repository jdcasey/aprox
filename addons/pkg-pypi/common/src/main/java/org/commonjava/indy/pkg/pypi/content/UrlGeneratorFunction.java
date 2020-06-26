package org.commonjava.indy.pkg.pypi.content;

@FunctionalInterface
public interface UrlGeneratorFunction
{
    String KEY = "Url-Generator";

    String generate( String path );
}

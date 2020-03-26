/**
 * Copyright (C) 2013 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.commonjava.indy.repo.proxy;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * This output stream will cache the original stream and do replacing based on the passed
 * in candidates in content of original stream during flushing
 */
class ContentReplacingOutputStream
        extends ServletOutputStream
{
    private static final Logger logger = LoggerFactory.getLogger( ContentReplacingOutputStream.class );

    private StringBuffer buffer = new StringBuffer();

    private final ServletOutputStream originalStream;

    private final Map<String, String> reposReplacing;

    ContentReplacingOutputStream( final ServletOutputStream originalStream, final Map<String, String> reposReplacing )
    {
        this.originalStream = originalStream;
        this.reposReplacing = reposReplacing;
    }

    @Override
    public void write( int b )
    {
        buffer.append( (char) b );
    }

    @Override
    public void flush()
            throws IOException
    {
        try
        {
            String content = buffer.toString();
            for ( Map.Entry<String, String> repoReplacing : reposReplacing.entrySet() )
            {
                final String replaceTo = repoReplacing.getKey();
                final String origin = repoReplacing.getValue();
                logger.trace( "Repository Proxy: Content rewriting: Replacing {} to {}", origin, replaceTo );
                content = content.replaceAll( origin, replaceTo );
            }
            originalStream.write( content.getBytes() );
            originalStream.flush();
        }
        finally
        {
            buffer = new StringBuffer();
        }
    }

    @Override
    public void close()
            throws IOException
    {
        flush();
        IOUtils.closeQuietly( originalStream );
    }
}

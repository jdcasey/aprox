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

import javax.servlet.ServletOutputStream;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Map;

public class ContentReplacingResponseWrapper extends HttpServletResponseWrapper
{
    private final HttpServletRequest request;

    private final ContentReplacingOutputStream out;

    /**
     * Constructs a response adaptor wrapping the given response.
     * @throws IllegalArgumentException if the response is null
     * @param response -
     * @throws IOException -
     */
    public ContentReplacingResponseWrapper( final HttpServletRequest request, final HttpServletResponse response,
                                              final Map<String, String> reposReplacing )
            throws IOException
    {
        super( response );
        this.request = request;
        this.out = new ContentReplacingOutputStream( response.getOutputStream(), reposReplacing );
    }

    @Override
    public PrintWriter getWriter()
            throws IOException
    {
        return new PrintWriter( this.getResponse().getWriter() );
    }

    @Override
    public ServletOutputStream getOutputStream()
            throws IOException
    {
        final ServletOutputStream originalStream = this.getResponse().getOutputStream();
        return this.out;
    }
}

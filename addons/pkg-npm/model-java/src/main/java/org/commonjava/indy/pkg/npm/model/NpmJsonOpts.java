/**
 * Copyright (C) 2011-2020 Red Hat, Inc. (https://github.com/Commonjava/indy)
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
package org.commonjava.indy.pkg.npm.model;

import java.util.List;

public class NpmJsonOpts
{

    private final String file;

    private final Boolean wscript;

    private final List<String> contributors;

    private final Boolean serverjs;

    protected NpmJsonOpts()
    {
        this.file = null;
        this.wscript = null;
        this.contributors = null;
        this.serverjs = null;
    }

    public NpmJsonOpts( final String file, final Boolean wscript, final List<String> contributors, final Boolean serverjs )
    {
        this.file = file;
        this.wscript = wscript;
        this.contributors = contributors;
        this.serverjs = serverjs;
    }

    public String getFile()
    {
        return file;
    }

    public Boolean getWscript()
    {
        return wscript;
    }

    public List<String> getContributors()
    {
        return contributors;
    }

    public Boolean getServerjs()
    {
        return serverjs;
    }
}

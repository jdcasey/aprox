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
package org.commonjava.indy.pkg.npm.content;

import static org.commonjava.maven.galley.util.PathUtils.normalize;

public class PackagePath
{

    private String tarPath;

    private Boolean isScoped;

    private String packageName;

    private String version;

    private String scopedName;

    public PackagePath( String tarPath )
    {
        this.tarPath = tarPath;
        init();
    }

    private void init()
    {
        String[] pathParts = tarPath.split( "/" );
        if ( tarPath.startsWith( "@" ) )
        {
            isScoped = Boolean.TRUE;
            scopedName = pathParts[0];
            packageName = pathParts[1];
            String tarName = pathParts[3];
            version = tarName.substring( packageName.length() + 1, tarName.length() - 4 );
        }
        else
        {
            isScoped = Boolean.FALSE;
            packageName = pathParts[0];
            String tarName = pathParts[2];
            version = tarName.substring( packageName.length() + 1, tarName.length() - 4 );
        }
    }

    public String getTarPath()
    {
        return tarPath;
    }

    public void setTarPath( String tarPath )
    {
        this.tarPath = tarPath;
    }

    public Boolean isScoped()
    {
        return isScoped;
    }

    public void setScoped( Boolean scoped )
    {
        isScoped = scoped;
    }

    public String getPackageName()
    {
        return packageName;
    }

    public void setPackageName( String packageName )
    {
        this.packageName = packageName;
    }

    public String getVersion()
    {
        return version;
    }

    public void setVersion( String version )
    {
        this.version = version;
    }

    public String getScopedName()
    {
        return scopedName;
    }

    public void setScopedName( String scopedName )
    {
        this.scopedName = scopedName;
    }

    public String getVersionPath()
    {
        return isScoped() ? normalize( scopedName, packageName, version ) : normalize( packageName, version );
    }

    @Override
    public String toString()
    {
        return "PackagePath{" + "tarPath='" + tarPath + '\'' + ", isScoped=" + isScoped + ", packageName='"
                        + packageName + '\'' + ", version='" + version + '\'' + ", scopedName='" + scopedName + '\''
                        + '}';
    }
}

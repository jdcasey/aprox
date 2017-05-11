/**
 * Copyright (C) 2011 Red Hat, Inc. (jdcasey@commonjava.org)
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
package org.commonjava.indy.koji.content;

import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.subsys.template.ScriptEngine;

/**
 * Responsible for creating new {@link RemoteRepository} instances used to proxy artifacts for a given Koji build.
 *
 * This interface will be implemented by a Groovy script, and accessed by way of the
 * {@link org.commonjava.indy.subsys.template.ScriptEngine#parseStandardScriptInstance(ScriptEngine.StandardScriptType, String, Class)} method.
 *
 * Created by jdcasey on 8/17/16.
 */
public interface KojiRepositoryCreator
{
    RemoteRepository createRemoteRepository( String packageType, String name, String url, Integer downloadTimeoutSeconds );
}

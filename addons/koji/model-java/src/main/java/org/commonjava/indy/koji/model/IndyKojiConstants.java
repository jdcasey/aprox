/**
 * Copyright (C) 2011-2018 Red Hat, Inc. (https://github.com/Commonjava/indy)
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
package org.commonjava.indy.koji.model;

/**
 * Created by ruhan on 3/28/18.
 */
public class IndyKojiConstants
{
    public static final String KOJI = "koji";

    public static final String KOJI_ORIGIN = KOJI;

    public static final String KOJI_ORIGIN_BINARY = "koji-binary";

    public static final String REPAIR_KOJI = "repair/" + KOJI;

    public static final String VOL = "vol";

    public static final String REPAIR_KOJI_VOL = REPAIR_KOJI + "/" + VOL;

    public static final String CONSOLIDATE = "consolidate";

    public static final String MASK = "mask";

    public static final String ALL_MASKS = "mask/all";

    public static final String META_TIMEOUT = "metadata/timeout";

    public static final String META_TIMEOUT_ALL = META_TIMEOUT + "/all";
}

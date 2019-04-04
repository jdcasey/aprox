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

import io.swagger.annotations.ApiModelProperty;

import java.util.List;

public class KojiMultiRepairResult
{
    @ApiModelProperty( "Results for all stores where repair was attempted, including failures" )
    private List<KojiRepairResult> results;

    public KojiMultiRepairResult(){}

    public KojiMultiRepairResult( final List<KojiRepairResult> results )
    {
        this.results = results;
    }

    public List<KojiRepairResult> getResults()
    {
        return results;
    }

    public void setResults( final List<KojiRepairResult> results )
    {
        this.results = results;
    }
}

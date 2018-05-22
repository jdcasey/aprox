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
package org.commonjava.indy.subsys.prefetch;

import org.commonjava.indy.content.StoreResource;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.util.LocationUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Alternative;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.commonjava.indy.model.core.RemoteRepository.PREFETCH_LISTING_TYPE_HTML;
import static org.commonjava.indy.model.core.RemoteRepository.PREFETCH_LISTING_TYPE_KOJI;

/**
 * Used for koji proxy remote repo, which will contain a path-mask that can directly provide the downloading list.
 */
@Alternative
@ApplicationScoped
public class KojiContentListBuilder
        implements ContentListBuilder
{
    private static final Logger logger = LoggerFactory.getLogger( HtmlContentListBuilder.class );

    @Override
    public List<StoreResource> buildContent( RemoteRepository repository )
    {
        if ( PREFETCH_LISTING_TYPE_HTML.equals( repository.getPrefetchListingType() )
                || repository.getPrefetchPriority() <= 0 )
        {
            logger.error(
                    "The repository {} is a normal remote or prefetch disabled, can not use koji content listing",
                    repository.getName() );
            return Collections.emptyList();
        }
        Set<String> paths = repository.getPathMaskPatterns();
        return paths.stream()
                    .map( p -> new StoreResource( LocationUtils.toLocation( repository ), p ) )
                    .collect( Collectors.toList() );
    }

    @Override
    public String type()
    {
        return PREFETCH_LISTING_TYPE_KOJI;
    }
}

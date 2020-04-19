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
package org.commonjava.indy.conf;

import java.io.File;

/**
 * Base configuration for Indy's core. This basically sets timeouts for a couple different (relatively obscure) features.
 */
public interface IndyConfiguration
{

    String PROP_NODE_ID = "indy.node.id";

    /**
     * Retrieve the node identifier to be used when deploying in a clustered context.
     */
    String getNodeId();

    /**
     * Number of seconds before the content in a repository marked as "pass-through" will timeout. Pass-through remote repos are designed
     * to hold content for a minimal amount of time. They would be a simple pass-through, non-caching proxy, but in reality artifacts are usually 
     * referenced more than once from any given build. So, setting a very low pass-through timeout for content in these repositories helps to
     * cut down on traffic to the upstream repository.
     */
    int getPassthroughTimeoutSeconds();

    /**
     * Number of seconds before a negative cache (or, not-found cache) URL record will timeout. These are used to prevent constant re-request for URLs
     * that have been known to fail for one reason or another (ranging from 404's to 500's, to just about anything else that's not HTTP 200, 301, or 
     * 302). This value can be tuned based on use case, but for most people it should be relatively high.
     * <br/>
     * Also, the NFC (Not-Found Cache) can be maintained manually via the UI.
     */
    int getNotFoundCacheTimeoutSeconds();

    int getRequestTimeoutSeconds();

    int getStoreDisableTimeoutSeconds();

    /**
     * Number of minutes between sweeps of the NFC expiration reaper thread.
     * @since 1.1.15
     */
    int getNfcExpirationSweepMinutes();

    /**
     * List of http headers to look for and include in the MDC.
     * @since 1.3
     */
    String getMdcHeaders();

    /**
     * Page size of NFC getMissing result set
     * @since 1.3.0
     */
    int getNfcMaxResultSetSize();

    File getIndyHomeDir();

    File getIndyConfDir();

    Boolean isAllowRemoteListDownload();

    /**
     * Disposable stores can be deleted alone with its the content. It serves as a safe guard because mis-deleted
     * repo definition can be restored but mis-deleted content can not.
     */
    String getDisposableStorePattern();

    int getRemoteMetadataTimeoutSeconds();

    int getForkJoinPoolCommonParallelism();

    boolean isClusterEnabled();

    String getCacheKeyspace();

    String getAffectedGroupsExcludeFilter();

    String getNfcProvider(); // ispn or cassandra (default)

    /**
     * Let indy run standalone local mode, which will not enable cassandra path mapped storage
     * @return
     * @since 2.0.0
     */
    Boolean isStandalone();

    boolean isRepositoryFilterEnabled();
}

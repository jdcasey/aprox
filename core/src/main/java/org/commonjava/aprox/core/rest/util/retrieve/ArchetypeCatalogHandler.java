/*******************************************************************************
 * Copyright 2011 John Casey
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package org.commonjava.aprox.core.rest.util.retrieve;

import static org.apache.commons.io.IOUtils.closeQuietly;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.core.Response;

import org.commonjava.aprox.core.rest.util.ArchetypeCatalogMerger;
import org.commonjava.aprox.core.rest.util.GroupMergeHelper;
import org.commonjava.aprox.filer.FileManager;
import org.commonjava.aprox.model.ArtifactStore;
import org.commonjava.aprox.model.Group;
import org.commonjava.aprox.rest.AproxWorkflowException;
import org.commonjava.aprox.rest.util.retrieve.GroupPathHandler;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.maven.galley.model.TransferOperation;

@ApplicationScoped
public class ArchetypeCatalogHandler
    implements GroupPathHandler
{

    @Inject
    private GroupMergeHelper helper;

    @Inject
    private FileManager fileManager;

    @Inject
    private ArchetypeCatalogMerger merger;

    @Override
    public boolean canHandle( final String path )
    {
        return path.endsWith( ArchetypeCatalogMerger.CATALOG_NAME );
    }

    @Override
    public Transfer retrieve( final Group group, final List<? extends ArtifactStore> stores, final String path )
        throws AproxWorkflowException
    {
        final Transfer target = fileManager.getStorageReference( group, path );

        if ( !target.exists() )
        {
            final Set<Transfer> sources = fileManager.retrieveAll( stores, path );
            final byte[] merged = merger.merge( sources, group, path );
            if ( merged != null )
            {
                OutputStream fos = null;
                try
                {
                    fos = target.openOutputStream( TransferOperation.GENERATE, true );
                    fos.write( merged );
                }
                catch ( final IOException e )
                {
                    throw new AproxWorkflowException( Response.serverError()
                                                              .build(),
                                                      "Failed to write merged archetype catalog to: %s.\nError: %s", e,
                                                      target, e.getMessage() );
                }
                finally
                {
                    closeQuietly( fos );
                }

                helper.writeChecksumsAndMergeInfo( merged, sources, group, path );
            }
        }

        if ( target.exists() )
        {
            return target;
        }

        return null;
    }

    @Override
    public Transfer store( final Group group, final List<? extends ArtifactStore> stores, final String path,
                           final InputStream stream )
        throws AproxWorkflowException
    {
        if ( path.endsWith( ArchetypeCatalogMerger.CATALOG_NAME ) )
        {
            // delete so it'll be recomputed.
            final Transfer target = fileManager.getStorageReference( group, path );
            try
            {
                target.delete();
            }
            catch ( final IOException e )
            {
                throw new AproxWorkflowException(
                                                  Response.serverError()
                                                          .build(),
                                                  "Failed to delete generated file (to allow re-generation on demand: %s. Error: %s",
                                                  e, target.getFullPath(), e.getMessage() );
            }
        }

        return fileManager.store( stores, path, stream );
    }

    @Override
    public boolean delete( final Group group, final List<? extends ArtifactStore> stores, final String path )
        throws AproxWorkflowException, IOException
    {
        final Transfer target = fileManager.getStorageReference( group, path );

        if ( target == null )
        {
            return false;
        }

        target.delete();

        helper.deleteChecksumsAndMergeInfo( group, path );

        return true;
    }

}

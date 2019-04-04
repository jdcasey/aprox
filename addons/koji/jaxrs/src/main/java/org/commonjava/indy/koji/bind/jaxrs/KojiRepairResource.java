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
package org.commonjava.indy.koji.bind.jaxrs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.red.build.koji.KojiClientException;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiImplicitParam;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiResponse;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.bind.jaxrs.IndyResources;
import org.commonjava.indy.bind.jaxrs.SecurityManager;
import org.commonjava.indy.koji.data.KojiRepairException;
import org.commonjava.indy.koji.data.KojiRepairManager;
import org.commonjava.indy.koji.model.KojiMultiRepairResult;
import org.commonjava.indy.koji.model.KojiRepairRequest;
import org.commonjava.indy.koji.model.KojiRepairResult;
import org.commonjava.indy.model.core.PackageTypeDescriptor;
import org.commonjava.indy.model.core.PackageTypes;
import org.commonjava.indy.model.core.StoreKey;
import org.commonjava.indy.model.core.StoreType;
import org.commonjava.indy.util.ApplicationContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import static org.commonjava.indy.bind.jaxrs.util.JaxRsUriFormatter.getBaseUrlByStoreKey;
import static org.commonjava.indy.bind.jaxrs.util.ResponseUtils.throwError;
import static org.commonjava.indy.koji.model.IndyKojiConstants.ALL_MASKS;
import static org.commonjava.indy.koji.model.IndyKojiConstants.CONSOLIDATE;
import static org.commonjava.indy.koji.model.IndyKojiConstants.MASK;
import static org.commonjava.indy.koji.model.IndyKojiConstants.META_TIMEOUT;
import static org.commonjava.indy.koji.model.IndyKojiConstants.META_TIMEOUT_ALL;
import static org.commonjava.indy.koji.model.IndyKojiConstants.REPAIR_KOJI;
import static org.commonjava.indy.koji.model.IndyKojiConstants.VOL;
import static org.commonjava.indy.util.ApplicationContent.application_json;

@Api( value = "Koji repairVolume operation", description = "Repair Koji remote repositories." )
@Path( "/api/" + REPAIR_KOJI )
@Produces( { application_json } )
public class KojiRepairResource
                implements IndyResources
{
    private final Logger logger = LoggerFactory.getLogger( getClass() );

    @Inject
    private ObjectMapper mapper;

    @Inject
    private SecurityManager securityManager;

    @Inject
    private KojiRepairManager repairManager;

    @ApiOperation( "Repair koji repository remote url /vol." )
    @ApiResponse( code = 200, message = "Operation finished (consult response content for success/failure).",
                    response = KojiRepairResult.class )
    @ApiImplicitParam( name = "body", paramType = "body",
                    value = "JSON request specifying source and other configuration options",
                    required = true, dataType = "org.commonjava.indy.koji.model.KojiRepairRequest" )
    @POST
    @Path( "/" + VOL )
    @Consumes( ApplicationContent.application_json )
    public KojiRepairResult repairVolumes( final KojiRepairRequest request, final @Context HttpServletRequest servletRequest,
                                    final @Context SecurityContext securityContext, final @Context UriInfo uriInfo )
    {
        try
        {
            String user = securityManager.getUser( securityContext, servletRequest );
            final String baseUrl = getBaseUrlByStoreKey( uriInfo, request.getSource() );
            return repairManager.repairVol( request, user, baseUrl );
        }
        catch ( KojiRepairException | KojiClientException e )
        {
            logger.error( e.getMessage(), e );
            throwError( e );
        }

        return null;
    }

    @ApiOperation( "Repair koji repository path masks." )
    @ApiResponse( code = 200, message = "Operation finished (consult response content for success/failure).",
                  response = KojiRepairResult.class )
    @ApiImplicitParam( name = "body", paramType = "body",
                       value = "JSON request specifying source and other configuration options",
                       required = true, dataType = "org.commonjava.indy.koji.model.KojiRepairRequest" )
    @POST
    @Path( "/" + MASK )
    @Consumes( ApplicationContent.application_json )
    public KojiRepairResult repairPathMasks( final KojiRepairRequest request, final @Context HttpServletRequest servletRequest,
                                    final @Context SecurityContext securityContext, final @Context UriInfo uriInfo )
    {
        try
        {
            String user = securityManager.getUser( securityContext, servletRequest );
            return repairManager.repairPathMask( request, user );
        }
        catch ( KojiRepairException e )
        {
            logger.error( e.getMessage(), e );
            throwError( e );
        }

        return null;
    }

    @ApiOperation( "Repair koji repository path masks for ALL koji remote repositories." )
    @ApiResponse( code = 200, message = "Operation finished (consult response content for success/failure).",
                  response = KojiMultiRepairResult.class )
    @POST
    @Path( "/" + ALL_MASKS )
    @Consumes( ApplicationContent.application_json )
    public KojiMultiRepairResult repairAllPathMasks( final @Context HttpServletRequest servletRequest,
                                             final @Context SecurityContext securityContext, final @Context UriInfo uriInfo )
    {
        try
        {
            String user = securityManager.getUser( securityContext, servletRequest );
            return repairManager.repairAllPathMasks( user );
        }
        catch ( KojiRepairException | IndyWorkflowException e )
        {
            logger.error( e.getMessage(), e );
            throwError( e );
        }

        return null;
    }

    @ApiOperation( "Consolidate all content available from existing Koji remote repositories in the target group "
                           + "(by membership order, with no overwrites) into a hosted repository." )
    @ApiResponse( code = 200, message = "Operation finished (consult response content for success/failure).",
                  response = KojiMultiRepairResult.class )
    @POST
    @Path( "/" + CONSOLIDATE + "/{packageType}/{name}" )
    @Consumes( ApplicationContent.application_json )
    public KojiMultiRepairResult consolidateRemoteRepos( final @PathParam( "packageType" ) String packageType,
                                                         final @PathParam( "name" ) String name,
                                                         final @Context HttpServletRequest servletRequest,
                                                         final @Context SecurityContext securityContext,
                                                         final @Context UriInfo uriInfo )
    {
        try
        {
            StoreKey targetKey = new StoreKey( packageType, StoreType.group, name );
            String user = securityManager.getUser( securityContext, servletRequest );
            return repairManager.consolidateAllRemoteRepos( targetKey, user );
        }
        catch ( KojiRepairException | IndyWorkflowException e )
        {
            logger.error( e.getMessage(), e );
            throwError( e );
        }

        return null;
    }

    @ApiOperation( "Repair koji repository metadata timeout to \"never timeout(-1)\"." )
    @ApiResponse( code = 200, message = "Operation finished (consult response content for success/failure).",
                  response = KojiRepairResult.class )
    @ApiImplicitParam( name = "body", paramType = "body",
                       value = "JSON request specifying source and other configuration options",
                       required = true, dataType = "org.commonjava.indy.koji.model.KojiRepairRequest" )
    @POST
    @Path( "/" + META_TIMEOUT )
    @Consumes( ApplicationContent.application_json )
    public KojiRepairResult repairMetadataTimeout( final KojiRepairRequest request, final @Context HttpServletRequest servletRequest,
                                             final @Context SecurityContext securityContext )
    {
        try
        {
            String user = securityManager.getUser( securityContext, servletRequest );
            return repairManager.repairMetadataTimeout( request, user );
        }
        catch ( KojiRepairException e )
        {
            logger.error( e.getMessage(), e );
            throwError( e );
        }

        return null;
    }

    @ApiOperation(
            "Repair koji repository metadata timeout to \"never timeout(-1)\" for all koji remote repositories." )
    @ApiImplicitParam( name = "isDryRun", paramType = "query",
                       value = "boolean value to specify if this request is a dry run request", defaultValue = "false",
                       dataType = "java.lang.Boolean" )
    @ApiResponse( code = 200, message = "Operation finished (consult response content for success/failure).",
                  response = KojiMultiRepairResult.class )
    @POST
    @Path( "/" + META_TIMEOUT_ALL )
    @Consumes( ApplicationContent.application_json )
    public KojiMultiRepairResult repairAllMetadataTimeout( final @Context HttpServletRequest servletRequest,
                                                           final @QueryParam( "isDryRun" ) Boolean isDryRun,
                                                           final @Context SecurityContext securityContext )
    {

        String user = securityManager.getUser( securityContext, servletRequest );
        final boolean dryRun = isDryRun == null ? false : isDryRun;
        try
        {
            return repairManager.repairAllMetadataTimeout( user, dryRun );
        }
        catch ( KojiRepairException | IndyWorkflowException e )
        {
            logger.error( e.getMessage(), e );
            throwError( e );
        }

        return null;
    }

}

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
package org.commonjava.indy.promote.validate;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.ClassUtils;
import org.commonjava.cdi.util.weft.DrainingExecutorCompletionService;
import org.commonjava.cdi.util.weft.ExecutorConfig;
import org.commonjava.cdi.util.weft.WeftExecutorService;
import org.commonjava.cdi.util.weft.WeftManaged;
import org.commonjava.indy.IndyWorkflowException;
import org.commonjava.indy.audit.ChangeSummary;
import org.commonjava.indy.content.ContentManager;
import org.commonjava.indy.content.DownloadManager;
import org.commonjava.indy.data.IndyDataException;
import org.commonjava.indy.data.StoreDataManager;
import org.commonjava.indy.model.core.ArtifactStore;
import org.commonjava.indy.model.core.RemoteRepository;
import org.commonjava.indy.promote.conf.PromoteConfig;
import org.commonjava.indy.promote.model.GroupPromoteRequest;
import org.commonjava.indy.promote.model.PathsPromoteRequest;
import org.commonjava.indy.promote.model.PromoteRequest;
import org.commonjava.indy.promote.model.ValidationResult;
import org.commonjava.indy.promote.model.ValidationRuleSet;
import org.commonjava.indy.promote.validate.model.ValidationRequest;
import org.commonjava.indy.promote.validate.model.ValidationRuleMapping;
import org.commonjava.maven.galley.event.EventMetadata;
import org.commonjava.maven.galley.model.Transfer;
import org.commonjava.propulsor.metrics.MetricsManager;
import org.commonjava.propulsor.metrics.annotation.Measure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static com.codahale.metrics.MetricRegistry.name;
import static java.lang.String.format;
import static org.apache.commons.lang.StringUtils.join;
import static org.commonjava.indy.core.ctl.PoolUtils.detectOverloadVoid;

/**
 * Created by jdcasey on 9/11/15.
 */
public class PromotionValidator
{
    private static final String PROMOTE_REPO_PREFIX = "Promote_";

    private static final String PROMOTION_VALIDATION_RULE = "promotion-validation-rule";

    private static final String PROMOTION_VALIDATION_RULE_SET = "promotion-validation-rule-set";

    @Inject
    private PromoteValidationsManager validationsManager;

    @Inject
    private PromotionValidationTools validationTools;

    @Inject
    private StoreDataManager storeDataMgr;

    @Inject
    private DownloadManager downloadManager;

    @Inject
    private PromoteConfig config;

    @Inject
    private MetricsManager metricsManager;

    @Inject
    @WeftManaged
    @ExecutorConfig( named = "promote-validation-rules-runner", threads = 20, priority = 5, loadSensitive = ExecutorConfig.BooleanLiteral.TRUE, maxLoadFactor = 400 )
    private WeftExecutorService validateService;

    private final Logger logger = LoggerFactory.getLogger( getClass() );

    protected PromotionValidator()
    {
    }

    public PromotionValidator( PromoteValidationsManager validationsManager, PromotionValidationTools validationTools,
                               StoreDataManager storeDataMgr, DownloadManager downloadManager,
                               WeftExecutorService validateService, MetricsManager metricsManager )
    {
        this.validationsManager = validationsManager;
        this.validationTools = validationTools;
        this.storeDataMgr = storeDataMgr;
        this.downloadManager = downloadManager;
        this.validateService = validateService;
        this.metricsManager = metricsManager;
    }

    /**
     * NOTE: As of Indy 1.2.6, ValidationRequest passed back to enable further post-processing, especially of promotion
     * paths, after promotion takes place. This enables us to avoid re-executing recursive path discovery, for instance.
     *
     * @param request
     * @param result
     * @param baseUrl
     * @return
     * @throws PromotionValidationException
     * @throws IndyWorkflowException
     */
    @Measure
    public ValidationRequest validate( PromoteRequest request, ValidationResult result, String baseUrl )
            throws PromotionValidationException, IndyWorkflowException
    {
        ValidationRuleSet set = validationsManager.getRuleSetMatching( request.getTargetKey() );

        ArtifactStore source;
        try
        {
            source = storeDataMgr.getArtifactStore( request.getSource() );
        }
        catch ( IndyDataException e )
        {
            throw new PromotionValidationException(
                    String.format( "Failed to retrieve source ArtifactStore: %s for validation", request.getSource() ),
                    e );
        }

        if ( set != null )
        {
            result.setRuleSet( set.getName() );
            MDC.put( PROMOTION_VALIDATION_RULE_SET, set.getName() );

            logger.debug( "Running validation rule-set for promotion: {}", set.getName() );

            List<String> ruleNames = set.getRuleNames();
            if ( ruleNames != null && !ruleNames.isEmpty() )
            {
                final ArtifactStore store = getRequestStore( request, baseUrl );
                final ValidationRequest req = new ValidationRequest( request, set, validationTools, store );
                try
                {
                    DrainingExecutorCompletionService<Exception> svc =
                            new DrainingExecutorCompletionService<>( validateService );

                    detectOverloadVoid(()->{
                        for ( String ruleRef : ruleNames )
                        {
                            svc.submit( () -> {
                                MDC.put( PROMOTION_VALIDATION_RULE, ruleRef );
                                Exception err = null;
                                try
                                {
                                    executeValidationRule( ruleRef, req, result, request );
                                }
                                catch ( Exception e )
                                {
                                    err = e;
                                }
                                finally
                                {
                                    MDC.remove( PROMOTION_VALIDATION_RULE );
                                }

                                return err;
                            } );
                        }
                    });

                    List<String> errors = new ArrayList<>();
                    svc.drain( err->{
                        if ( err != null )
                        {
                            logger.error( "Promotion validation failure", err );
                            errors.add( err.getMessage() );
                        }
                    } );

                    if ( !errors.isEmpty() )
                    {
                        throw new PromotionValidationException( format( "Failed to do promotion validation: \n\n%s", join( errors, "\n" ) ) );
                    }
                }
                catch ( InterruptedException e )
                {
                    throw new PromotionValidationException( "Failed to do promotion validation: validation execution has been interrupted ", e );
                }
                catch ( ExecutionException e )
                {
                    throw new PromotionValidationException( "Failed to execute promotion validations", e );
                }
                finally
                {
                    if ( needTempRepo( request ) )
                    {
                        try
                        {
                            final String changeSum = format( "Removes the temp remote repo [%s] after promote operation.", store );
                            storeDataMgr.deleteArtifactStore( store.getKey(),
                                                              new ChangeSummary( ChangeSummary.SYSTEM_USER,
                                                                                 changeSum ),
                                                              new EventMetadata().set( ContentManager.SUPPRESS_EVENTS,
                                                                                       true ) );

                            Transfer root = downloadManager.getStoreRootDirectory( store );
                            if ( root.exists() )
                            {
                                root.delete( false );
                            }

                            logger.debug( "Promotion temporary repo {} has been deleted for {}", store.getKey(),
                                         request.getSource() );
                        }
                        catch ( IndyDataException | IOException e )
                        {
                            logger.warn( "Temporary promotion validation repository was NOT removed correctly.", e );
                        }
                    }
                }
                return req;
            }
            else
            {
                logger.info( "No validation rules are defined for: {}", request.getTargetKey() );
                return new ValidationRequest( request, set, validationTools, source );
            }
        }
        else
        {
            logger.info( "No validation rule-sets are defined for: {}", request.getTargetKey() );
            return new ValidationRequest( request, set, validationTools, source );
        }
    }

    //@Measure <-- this does not work. Internal calls can never be intercepted.
    private void executeValidationRule( final String ruleRef, final ValidationRequest req,
                                        final ValidationResult result, final PromoteRequest request )
            throws PromotionValidationException
    {
        String ruleName =
                new File( ruleRef ).getName(); // flatten in case some path fragment leaks in...

        ValidationRuleMapping rule = validationsManager.getRuleMappingNamed( ruleName );
        if ( rule != null )
        {
            logger.debug( "Running promotion validation rule: {}", rule.getName() );
            String error = null;
            if ( metricsManager != null )
            {
                AtomicReference<Exception> ex = new AtomicReference<>();
                error = metricsManager.wrapWithStandardMetrics( () -> {
                    try
                    {
                        return rule.getRule().validate( req );
                    }
                    catch ( Exception e )
                    {
                        ex.set( e );
                        return null;
                    }
                }, () -> getMetricName( rule.getName() ) );

                if ( ex.get() != null )
                {
                    throwException( ex.get(), rule, request );
                }
            }
            else
            {
                try
                {
                    error = rule.getRule().validate( req );
                }
                catch ( Exception e )
                {
                    throwException( e, rule, request );
                }
            }

            if ( StringUtils.isNotEmpty( error ) )
            {
                logger.debug( "{} failed with error: {}", rule.getName(), error );
                result.addValidatorError( rule.getName(), error );
            }
            else
            {
                logger.debug( "{} succeeded", rule.getName() );
            }
        }
    }

    private void throwException( Exception e, ValidationRuleMapping rule, PromoteRequest request )
                    throws PromotionValidationException
    {
        if ( e instanceof PromotionValidationException )
        {
            throw (PromotionValidationException) e;
        }

        throw new PromotionValidationException( "Failed to run validation rule: {} for request: {}. Reason: {}", e,
                                                rule.getName(), request, e );
    }

    private String getMetricName( String ruleName )
    {
        String cls = ClassUtils.getAbbreviatedName( getClass().getName(), 1 );
        return name( cls, "rule", ruleName );
    }

    private boolean needTempRepo( PromoteRequest promoteRequest )
            throws PromotionValidationException
    {
        if ( promoteRequest instanceof GroupPromoteRequest )
        {
            return false;
        }
        else if ( promoteRequest instanceof PathsPromoteRequest )
        {
            final Set<String> reqPaths = ( (PathsPromoteRequest) promoteRequest ).getPaths();
            return reqPaths != null && !reqPaths.isEmpty();
        }
        else
        {
            throw new PromotionValidationException( "The promote request is not a valid request, should not happen" );
        }
    }

    private ArtifactStore getRequestStore( PromoteRequest promoteRequest, String baseUrl )
            throws PromotionValidationException
    {
        final ArtifactStore store;
        final Logger logger = LoggerFactory.getLogger( getClass() );
        logger.info( "Promotion baseUrl: {} ", baseUrl );

        if ( needTempRepo( promoteRequest ) )
        {
            logger.info( "Promotion temporary repo is needed for {}, points to {} ", promoteRequest.getSource(),
                         baseUrl );
            final PathsPromoteRequest pathsReq = (PathsPromoteRequest) promoteRequest;

            String tempName =
                    PROMOTE_REPO_PREFIX + "tmp_" + pathsReq.getSource().getName() + "_" + new SimpleDateFormat(
                            "yyyyMMdd.hhmmss.SSSZ" ).format( new Date() );

            String packageType = pathsReq.getSource().getPackageType();

            final RemoteRepository tempRemote = new RemoteRepository( packageType, tempName, baseUrl );

            tempRemote.setPathMaskPatterns( pathsReq.getPaths() );

            store = tempRemote;
            try
            {
                final ChangeSummary changeSummary =
                        new ChangeSummary( ChangeSummary.SYSTEM_USER, "create temp remote repository" );

                storeDataMgr.storeArtifactStore( tempRemote, changeSummary, false, true, new EventMetadata() );
            }
            catch ( IndyDataException e )
            {
                throw new PromotionValidationException( "Can not store the temp remote repository correctly", e );
            }
        }
        else
        {
            logger.info( "Promotion temporary repo is not needed for {} ", promoteRequest.getSource() );
            try
            {
                store = storeDataMgr.getArtifactStore( promoteRequest.getSource() );
            }
            catch ( IndyDataException e )
            {
                throw new PromotionValidationException( "Failed to retrieve source ArtifactStore: {}. Reason: {}", e,
                                                        promoteRequest.getSource(), e.getMessage() );
            }
        }
        return store;
    }
}

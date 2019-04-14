/*
 * Copyright 2015-2018 floragunn GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.floragunn.searchguard.privileges;

import java.io.Serializable;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.ElasticsearchSecurityException;
import org.elasticsearch.action.ActionRequest;
import org.elasticsearch.action.admin.cluster.shards.ClusterSearchShardsRequest;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ThreadContext;
import org.elasticsearch.threadpool.ThreadPool;

import com.floragunn.searchguard.resolver.IndexResolverReplacer.Resolved;
import com.floragunn.searchguard.sgconf.ConfigModel.SgRoles;
import com.floragunn.searchguard.support.Base64Helper;
import com.floragunn.searchguard.support.ConfigConstants;
import com.floragunn.searchguard.support.HeaderHelper;
import com.floragunn.searchguard.support.WildcardMatcher;
import com.floragunn.searchguard.user.User;

public class DlsFlsEvaluator {

    protected final Logger log = LogManager.getLogger(this.getClass());

    private final ThreadPool threadPool;
    private final boolean localHashingEnabled;
    private ThreadLocal<SecureRandom> secureRandomThreadLocal = null;

    public DlsFlsEvaluator(Settings settings, ThreadPool threadPool) {
        this.threadPool = threadPool;
        this.localHashingEnabled = settings.getAsBoolean(ConfigConstants.SEARCHGUARD_COMPLIANCE_LOCAL_HASHING_ENABLED, false);
        
        if(this.localHashingEnabled) {
            this.secureRandomThreadLocal = ThreadLocal.withInitial(() -> new SecureRandom());
        }
    }

    public PrivilegesEvaluatorResponse evaluate(final ActionRequest request, final ClusterService clusterService, final IndexNameExpressionResolver resolver, final Resolved requestedResolved, final User user,
            final SgRoles sgRoles, final PrivilegesEvaluatorResponse presponse) {

        ThreadContext threadContext = threadPool.getThreadContext();

        // maskedFields
        final Map<String, Set<String>> maskedFieldsMap = sgRoles.getMaskedFields(user, resolver, clusterService);

       
        if (maskedFieldsMap != null && !maskedFieldsMap.isEmpty()) {
            
            if(request instanceof ClusterSearchShardsRequest && HeaderHelper.isTrustedClusterRequest(threadContext)) {
                threadContext.addResponseHeader(ConfigConstants.SG_MASKED_FIELD_HEADER, Base64Helper.serializeObject((Serializable) maskedFieldsMap));
                if (log.isDebugEnabled()) {
                    log.debug("added response header for masked fields info: {}", maskedFieldsMap);
                }
            } else {
                
                if (localHashingEnabled &&  secureRandomThreadLocal != null && HeaderHelper.getSafeFromHeader(threadContext, ConfigConstants.SG_LOCAL_HASH_SALT_HEADER) == null) {
                    final byte[] saltBytes = new byte[16];
                    secureRandomThreadLocal.get().nextBytes(saltBytes);
                    final String salt = Base64.getEncoder().encodeToString(saltBytes);
                    threadContext.putHeader(ConfigConstants.SG_LOCAL_HASH_SALT_HEADER, salt);
                }
                
                if (threadContext.getHeader(ConfigConstants.SG_MASKED_FIELD_HEADER) != null) {
                    if (!maskedFieldsMap.equals(Base64Helper.deserializeObject(threadContext.getHeader(ConfigConstants.SG_MASKED_FIELD_HEADER)))) {
                        throw new ElasticsearchSecurityException(ConfigConstants.SG_MASKED_FIELD_HEADER + " does not match (SG 901D)");
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug(ConfigConstants.SG_MASKED_FIELD_HEADER + " already set");
                        }
                    }
                } else {
                    threadContext.putHeader(ConfigConstants.SG_MASKED_FIELD_HEADER, Base64Helper.serializeObject((Serializable) maskedFieldsMap));
                    if (log.isDebugEnabled()) {
                        log.debug("attach masked fields info: {}", maskedFieldsMap);
                    }
                }
            }
        
            presponse.maskedFields = new HashMap<>(maskedFieldsMap);
            
            if (!requestedResolved.getAllIndices().isEmpty()) {
                for (Iterator<Entry<String, Set<String>>> it = presponse.maskedFields.entrySet().iterator(); it.hasNext();) {
                    Entry<String, Set<String>> entry = it.next();
                    if (!WildcardMatcher.matchAny(entry.getKey(), requestedResolved.getAllIndices(), false)) {
                        it.remove();
                    }
                }
            }     
        }

        

        // attach dls/fls map if not already done
        final Tuple<Map<String, Set<String>>, Map<String, Set<String>>> dlsFls = sgRoles.getDlsFls(user, resolver, clusterService);
        final Map<String, Set<String>> dlsQueries = dlsFls.v1();
        final Map<String, Set<String>> flsFields = dlsFls.v2();

        if (!dlsQueries.isEmpty()) {

            if(request instanceof ClusterSearchShardsRequest && HeaderHelper.isTrustedClusterRequest(threadContext)) {
                threadContext.addResponseHeader(ConfigConstants.SG_DLS_QUERY_HEADER, Base64Helper.serializeObject((Serializable) dlsQueries));
                if (log.isDebugEnabled()) {
                    log.debug("added response header for DLS info: {}", dlsQueries);
                }
            } else {
                if (threadContext.getHeader(ConfigConstants.SG_DLS_QUERY_HEADER) != null) {
                    if (!dlsQueries.equals(Base64Helper.deserializeObject(threadContext.getHeader(ConfigConstants.SG_DLS_QUERY_HEADER)))) {
                        throw new ElasticsearchSecurityException(ConfigConstants.SG_DLS_QUERY_HEADER + " does not match (SG 900D)");
                    }
                } else {
                    threadContext.putHeader(ConfigConstants.SG_DLS_QUERY_HEADER, Base64Helper.serializeObject((Serializable) dlsQueries));
                    if (log.isDebugEnabled()) {
                        log.debug("attach DLS info: {}", dlsQueries);
                    }
                }
            }

            presponse.queries = new HashMap<>(dlsQueries);

            if (!requestedResolved.getAllIndices().isEmpty()) {
                for (Iterator<Entry<String, Set<String>>> it = presponse.queries.entrySet().iterator(); it.hasNext();) {
                    Entry<String, Set<String>> entry = it.next();
                    if (!WildcardMatcher.matchAny(entry.getKey(), requestedResolved.getAllIndices(), false)) {
                        it.remove();
                    }
                }
            }

        }

        if (!flsFields.isEmpty()) {

            if(request instanceof ClusterSearchShardsRequest && HeaderHelper.isTrustedClusterRequest(threadContext)) {
                threadContext.addResponseHeader(ConfigConstants.SG_FLS_FIELDS_HEADER, Base64Helper.serializeObject((Serializable) flsFields));
                if (log.isDebugEnabled()) {
                    log.debug("added response header for FLS info: {}", flsFields);
                }
            } else {
                if (threadContext.getHeader(ConfigConstants.SG_FLS_FIELDS_HEADER) != null) {
                    if (!flsFields.equals(Base64Helper.deserializeObject(threadContext.getHeader(ConfigConstants.SG_FLS_FIELDS_HEADER)))) {
                        throw new ElasticsearchSecurityException(ConfigConstants.SG_FLS_FIELDS_HEADER + " does not match (SG 901D)");
                    } else {
                        if (log.isDebugEnabled()) {
                            log.debug(ConfigConstants.SG_FLS_FIELDS_HEADER + " already set");
                        }
                    }
                } else {
                    threadContext.putHeader(ConfigConstants.SG_FLS_FIELDS_HEADER, Base64Helper.serializeObject((Serializable) flsFields));
                    if (log.isDebugEnabled()) {
                        log.debug("attach FLS info: {}", flsFields);
                    }
                }
            }
            
            presponse.allowedFlsFields = new HashMap<>(flsFields);

            if (!requestedResolved.getAllIndices().isEmpty()) {
                for (Iterator<Entry<String, Set<String>>> it = presponse.allowedFlsFields.entrySet().iterator(); it.hasNext();) {
                    Entry<String, Set<String>> entry = it.next();
                    if (!WildcardMatcher.matchAny(entry.getKey(), requestedResolved.getAllIndices(), false)) {
                        it.remove();
                    }
                }
            }
        }

        /*if (requestedResolved == Resolved._EMPTY) {
            presponse.allowed = true;
            return presponse.markComplete();
        }*/
        
        return presponse;
    }
}

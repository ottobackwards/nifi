/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.cluster.coordination.http.replication;

import org.apache.nifi.authorization.user.NiFiUser;
import org.apache.nifi.cluster.manager.exception.ConnectingNodeMutableRequestException;
import org.apache.nifi.cluster.protocol.NodeIdentifier;

import java.net.URI;
import java.util.Map;
import java.util.Set;

public interface RequestReplicator {
    /**
     * Stops the instance from replicating requests. Calling this method on a stopped instance has no effect.
     */
    void shutdown();

    /**
     * Replicates a request to each node in the cluster. If the request attempts to modify the flow and there is a node
     * that is not currently connected, an Exception will be thrown. Otherwise, the returned AsyncClusterResponse object
     * will contain the results that are immediately available, as well as an identifier for obtaining an updated result
     * later. NOTE: This method will ALWAYS indicate that the request has been replicated.
     *
     * @param method  the HTTP method (e.g., POST, PUT)
     * @param uri     the base request URI (up to, but not including, the query string)
     * @param entity  an entity
     * @param headers any HTTP headers
     * @return an AsyncClusterResponse that indicates the current status of the request and provides an identifier for obtaining an updated response later
     * @throws ConnectingNodeMutableRequestException   if the request attempts to modify the flow and there is a node that is in the CONNECTING state
     */
    AsyncClusterResponse replicate(String method, URI uri, Object entity, Map<String, String> headers);

    /**
     * Replicates a request to each node in the cluster. If the request attempts to modify the flow and there is a node
     * that is not currently connected, an Exception will be thrown. Otherwise, the returned AsyncClusterResponse object
     * will contain the results that are immediately available, as well as an identifier for obtaining an updated result
     * later. NOTE: This method will ALWAYS indicate that the request has been replicated.
     *
     * @param user the user making the request
     * @param method the HTTP method (e.g., POST, PUT)
     * @param uri the base request URI (up to, but not including, the query string)
     * @param entity an entity
     * @param headers any HTTP headers
     * @return an AsyncClusterResponse that indicates the current status of the request and provides an identifier for obtaining an updated response later
     * @throws ConnectingNodeMutableRequestException if the request attempts to modify the flow and there is a node that is in the CONNECTING state
     */
    AsyncClusterResponse replicate(NiFiUser user, String method, URI uri, Object entity, Map<String, String> headers);

    /**
     * Requests are sent to each node in the given set of Node Identifiers. The returned AsyncClusterResponse object will contain
     * the results that are immediately available, as well as an identifier for obtaining an updated result later.
     * <p>
     * HTTP DELETE, GET, HEAD, and OPTIONS methods will throw an IllegalArgumentException if used.
     *
     * @param nodeIds the node identifiers
     * @param user the user making the request
     * @param method the HTTP method (e.g., POST, PUT)
     * @param uri the base request URI (up to, but not including, the query string)
     * @param entity an entity
     * @param headers any HTTP headers
     * @param indicateReplicated if <code>true</code>, will add a header indicating to the receiving nodes that the request
     *            has already been replicated, so the receiving node will not replicate the request itself.
     * @param performVerification if <code>true</code>, and the request is mutable, will verify that all nodes are connected before
     *            making the request and that all nodes are able to perform the request before acutally attempting to perform the task.
     *            If false, will perform no such verification
     * @return an AsyncClusterResponse that indicates the current status of the request and provides an identifier for obtaining an updated response later
     */
    AsyncClusterResponse replicate(Set<NodeIdentifier> nodeIds, NiFiUser user, String method, URI uri, Object entity, Map<String, String> headers, boolean indicateReplicated,
        boolean performVerification);

    /**
     * Requests are sent to each node in the given set of Node Identifiers. The returned AsyncClusterResponse object will contain
     * the results that are immediately available, as well as an identifier for obtaining an updated result later.
     * <p>
     * HTTP DELETE, GET, HEAD, and OPTIONS methods will throw an IllegalArgumentException if used.
     *
     * @param nodeIds the node identifiers
     * @param method the HTTP method (e.g., POST, PUT)
     * @param uri the base request URI (up to, but not including, the query string)
     * @param entity an entity
     * @param headers any HTTP headers
     * @param indicateReplicated if <code>true</code>, will add a header indicating to the receiving nodes that the request
     *            has already been replicated, so the receiving node will not replicate the request itself.
     * @param performVerification if <code>true</code>, and the request is mutable, will verify that all nodes are connected before
     *            making the request and that all nodes are able to perform the request before acutally attempting to perform the task.
     *            If false, will perform no such verification
     * @return an AsyncClusterResponse that indicates the current status of the request and provides an identifier for obtaining an updated response later
     */
    AsyncClusterResponse replicate(Set<NodeIdentifier> nodeIds, String method, URI uri, Object entity, Map<String, String> headers, boolean indicateReplicated,
        boolean performVerification);


    /**
     * Forwards a request to the Cluster Coordinator so that it is able to replicate the request to all nodes in the cluster.
     *
     * @param coordinatorNodeId the node identifier of the Cluster Coordinator
     * @param method            the HTTP method (e.g., POST, PUT)
     * @param uri               the base request URI (up to, but not including, the query string)
     * @param entity            an entity
     * @param headers           any HTTP headers
     * @return an AsyncClusterResponse that indicates the current status of the request and provides an identifier for obtaining an updated response later
     */
    AsyncClusterResponse forwardToCoordinator(NodeIdentifier coordinatorNodeId, String method, URI uri, Object entity, Map<String, String> headers);

    /**
     * Forwards a request to the Cluster Coordinator so that it is able to replicate the request to all nodes in the cluster.
     *
     * @param coordinatorNodeId the node identifier of the Cluster Coordinator
     * @param user the user making the request
     * @param method the HTTP method (e.g., POST, PUT)
     * @param uri the base request URI (up to, but not including, the query string)
     * @param entity an entity
     * @param headers any HTTP headers
     * @return an AsyncClusterResponse that indicates the current status of the request and provides an identifier for obtaining an updated response later
     */
    AsyncClusterResponse forwardToCoordinator(NodeIdentifier coordinatorNodeId, NiFiUser user, String method, URI uri, Object entity, Map<String, String> headers);

    /**
     * <p>
     * Returns an AsyncClusterResponse that provides the most up-to-date status of the request with the given identifier.
     * If the request is finished, meaning that all nodes in the cluster have reported back their status or have timed out,
     * then the response will be removed and any subsequent calls to obtain the response with the same identifier will return
     * <code>null</code>. If the response is not complete, the method may be called again at some point in the future in order
     * to check again if the request has completed.
     * </p>
     *
     * @param requestIdentifier the identifier of the request to obtain a response for
     * @return an AsyncClusterResponse that provides the most up-to-date status of the request with the given identifier, or <code>null</code> if
     *         no request exists with the given identifier
     */
    AsyncClusterResponse getClusterResponse(String requestIdentifier);
}

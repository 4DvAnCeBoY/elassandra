/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.cluster.service;

import static org.elasticsearch.common.util.concurrent.EsExecutors.daemonThreadFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.JsonParseException;
import org.codehaus.jackson.map.JsonMappingException;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchIllegalStateException;
import org.elasticsearch.Version;
import org.elasticsearch.cassandra.ConcurrentMetaDataUpdateException;
import org.elasticsearch.cassandra.ElasticSchemaService;
import org.elasticsearch.cassandra.SchemaService;
import org.elasticsearch.cassandra.SecondaryIndicesService;
import org.elasticsearch.cluster.AckedClusterStateUpdateTask;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.cluster.ClusterStateUpdateTask;
import org.elasticsearch.cluster.LocalNodeMasterListener;
import org.elasticsearch.cluster.ProcessedClusterStateUpdateTask;
import org.elasticsearch.cluster.TimeoutClusterStateListener;
import org.elasticsearch.cluster.TimeoutClusterStateUpdateTask;
import org.elasticsearch.cluster.block.ClusterBlock;
import org.elasticsearch.cluster.block.ClusterBlocks;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.metadata.ProcessClusterEventTimeoutException;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNode.DiscoveryNodeStatus;
import org.elasticsearch.cluster.node.DiscoveryNodeService;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.IndexRoutingTable;
import org.elasticsearch.cluster.routing.IndexShardRoutingTable;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.operation.OperationRouting;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.logging.Loggers;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.text.StringText;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.common.util.concurrent.CountDown;
import org.elasticsearch.common.util.concurrent.EsExecutors;
import org.elasticsearch.common.util.concurrent.EsRejectedExecutionException;
import org.elasticsearch.common.util.concurrent.PrioritizedEsThreadPoolExecutor;
import org.elasticsearch.common.util.concurrent.PrioritizedRunnable;
import org.elasticsearch.discovery.Discovery;
import org.elasticsearch.discovery.DiscoveryService;
import org.elasticsearch.gateway.GatewayService;
import org.elasticsearch.node.settings.NodeSettingsService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

/**
 *
 */
public class InternalClusterService extends AbstractLifecycleComponent<ClusterService> implements ClusterService {

    public static final String UPDATE_THREAD_NAME = "clusterService#updateTask";
    private final ThreadPool threadPool;

    private final DiscoveryService discoveryService;

    private final OperationRouting operationRouting;

    private final TransportService transportService;

    private final NodeSettingsService nodeSettingsService;
    private final DiscoveryNodeService discoveryNodeService;
    private final SchemaService elasticSchemaService;
    private final SecondaryIndicesService secondaryIndicesService;
    private final Version version;

    private final TimeValue reconnectInterval;

    private volatile PrioritizedEsThreadPoolExecutor updateTasksExecutor;

    private final List<ClusterStateListener> priorityClusterStateListeners = new CopyOnWriteArrayList<>();
    private final List<ClusterStateListener> clusterStateListeners = new CopyOnWriteArrayList<>();
    private final List<ClusterStateListener> lastClusterStateListeners = new CopyOnWriteArrayList<>();
    private final LocalNodeMasterListeners localNodeMasterListeners;

    private final Queue<NotifyTimeout> onGoingTimeouts = ConcurrentCollections.newQueue();

    private volatile ClusterState clusterState;

    private final ClusterBlocks.Builder initialBlocks;

    private volatile ScheduledFuture reconnectToNodes;

    @Inject
    public InternalClusterService(Settings settings, DiscoveryService discoveryService, OperationRouting operationRouting, TransportService transportService, NodeSettingsService nodeSettingsService,
            ThreadPool threadPool, ClusterName clusterName, DiscoveryNodeService discoveryNodeService, SchemaService elasticSchemaService, SecondaryIndicesService secondaryIndicesService, Version version) {
        super(settings);
        this.operationRouting = operationRouting;
        this.transportService = transportService;
        this.discoveryService = discoveryService;
        this.threadPool = threadPool;
        this.nodeSettingsService = nodeSettingsService;
        this.discoveryNodeService = discoveryNodeService;
        this.elasticSchemaService = elasticSchemaService;
        this.secondaryIndicesService = secondaryIndicesService;
        this.version = version;

        // will be replaced on doStart.
        this.clusterState = ClusterState.builder(clusterName).build();

        this.nodeSettingsService.setClusterService(this);

        this.reconnectInterval = componentSettings.getAsTime("reconnect_interval", TimeValue.timeValueSeconds(10));

        localNodeMasterListeners = new LocalNodeMasterListeners(threadPool);

        // No more master election
        //initialBlocks = ClusterBlocks.builder().addGlobalBlock(discoveryService.getNoMasterBlock());
        // Add NO_CASSANDRA_RING_BLOCK to avoid to save metadata while cassandra ring not ready.
        initialBlocks = ClusterBlocks.builder().addGlobalBlock(GatewayService.NO_CASSANDRA_RING_BLOCK);
    }

    public NodeSettingsService settingsService() {
        return this.nodeSettingsService;
    }

    public void addInitialStateBlock(ClusterBlock block) throws ElasticsearchIllegalStateException {
        if (lifecycle.started()) {
            throw new ElasticsearchIllegalStateException("can't set initial block when started");
        }
        initialBlocks.addGlobalBlock(block);
    }

    @Override
    public void removeInitialStateBlock(ClusterBlock block) throws ElasticsearchIllegalStateException {
        if (lifecycle.started()) {
            throw new ElasticsearchIllegalStateException("can't set initial block when started");
        }
        initialBlocks.removeGlobalBlock(block);
    }

    @Override
    protected void doStart() throws ElasticsearchException {

        // try to create if not exists elastic_admin keyspace and initialize persisted metadata
        try {
            elasticSchemaService.createElasticAdminKeyspace();
        } catch (Throwable e) {
            logger.warn("Cannot create "+ElasticSchemaService.ELASTIC_ADMIN_KEYSPACE, e);
        }

        add(localNodeMasterListeners);
        this.clusterState = ClusterState.builder(clusterState).blocks(initialBlocks).build();
        this.updateTasksExecutor = EsExecutors.newSinglePrioritizing(daemonThreadFactory(settings, UPDATE_THREAD_NAME));
        this.reconnectToNodes = threadPool.schedule(reconnectInterval, ThreadPool.Names.GENERIC, new ReconnectToNodes());

        Map<String, String> nodeAttributes = discoveryNodeService.buildAttributes();
        // note, we rely on the fact that its a new id each time we start, see FD and "kill -9" handling
        final String nodeId = DiscoveryService.generateNodeId(settings);
        DiscoveryNode localNode = new DiscoveryNode(settings.get("name"), nodeId, transportService.boundAddress().publishAddress(), nodeAttributes, version);
        DiscoveryNodes.Builder nodeBuilder = DiscoveryNodes.builder().put(localNode).localNodeId(localNode.id()).masterNodeId(localNode.id());
        this.clusterState = ClusterState.builder(clusterState).nodes(nodeBuilder).blocks(initialBlocks).build();
        
        addLast(this.secondaryIndicesService);
        
        // no more election => no more NoMasterBlock
        // this.clusterState = ClusterState.builder(clusterState).nodes(nodeBuilder).blocks(ClusterBlocks.builder()).build();
    }

    @Override
    protected void doStop() throws ElasticsearchException {

        this.reconnectToNodes.cancel(true);
        for (NotifyTimeout onGoingTimeout : onGoingTimeouts) {
            onGoingTimeout.cancel();
            onGoingTimeout.listener.onClose();
        }
        updateTasksExecutor.shutdown();
        try {
            updateTasksExecutor.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // ignore
        }
        remove(localNodeMasterListeners);

    }

    @Override
    protected void doClose() throws ElasticsearchException {
    }

    /**
     * Get indices shard state from gossip endpoints state map.
     * @param address
     * @param index
     * @return
     * @throws JsonParseException
     * @throws JsonMappingException
     * @throws IOException
     */
    public ShardRoutingState readIndexShardState(InetAddress address, String index, ShardRoutingState defaultState) {
        return this.discoveryService.readIndexShardState(address, index, defaultState);
    }

    /**
     * Set index shard state in the gossip endpoint map (must be synchronized).
     * @param index
     * @param shardRoutingState
     * @throws JsonGenerationException
     * @throws JsonMappingException
     * @throws IOException
     */
    public void writeIndexShardSate(String index, ShardRoutingState shardRoutingState) throws JsonGenerationException, JsonMappingException, IOException {
        this.discoveryService.writeIndexShardSate(index, shardRoutingState);
    }
    
    /**
     * Publish on Gossip.X1 all local shard state.
     */
    public void publishAllShardsState() {
        for (IndexRoutingTable indexRoutingTable : state().routingTable()) {
            IndexShardRoutingTable indexShardRoutingTable = indexRoutingTable.shards().get(0);
            try {
                writeIndexShardSate(indexRoutingTable.getIndex(), indexShardRoutingTable.getPrimaryShardRouting().state() );
            } catch (IOException e) {
                logger.error("Failed to publish primary shard state index=[{}]", indexRoutingTable.getIndex(), e);
            }
        }
    }

    @Override
    public DiscoveryNode localNode() {
        return clusterState.getNodes().localNode();
    }

    @Override
    public OperationRouting operationRouting() {
        return operationRouting;
    }

    public ClusterState state() {
        return this.clusterState;
    }

    public ClusterState state(ClusterState newState) {
        this.clusterState = newState;
        return newState;
    }

    public void addFirst(ClusterStateListener listener) {
        priorityClusterStateListeners.add(listener);
    }

    public void addLast(ClusterStateListener listener) {
        lastClusterStateListeners.add(listener);
    }

    public void add(ClusterStateListener listener) {
        clusterStateListeners.add(listener);
    }

    public void remove(ClusterStateListener listener) {
        clusterStateListeners.remove(listener);
        priorityClusterStateListeners.remove(listener);
        lastClusterStateListeners.remove(listener);
        for (Iterator<NotifyTimeout> it = onGoingTimeouts.iterator(); it.hasNext();) {
            NotifyTimeout timeout = it.next();
            if (timeout.listener.equals(listener)) {
                timeout.cancel();
                it.remove();
            }
        }
    }

    @Override
    public void add(LocalNodeMasterListener listener) {
        localNodeMasterListeners.add(listener);
    }

    @Override
    public void remove(LocalNodeMasterListener listener) {
        localNodeMasterListeners.remove(listener);
    }

    public void add(final TimeValue timeout, final TimeoutClusterStateListener listener) {
        if (lifecycle.stoppedOrClosed()) {
            listener.onClose();
            return;
        }
        // call the post added notification on the same event thread
        try {
            updateTasksExecutor.execute(new TimedPrioritizedRunnable(Priority.HIGH, "_add_listener_") {
                @Override
                public void run() {
                    NotifyTimeout notifyTimeout = new NotifyTimeout(listener, timeout);
                    notifyTimeout.future = threadPool.schedule(timeout, ThreadPool.Names.GENERIC, notifyTimeout);
                    onGoingTimeouts.add(notifyTimeout);
                    clusterStateListeners.add(listener);
                    listener.postAdded();
                }
            });
        } catch (EsRejectedExecutionException e) {
            if (lifecycle.stoppedOrClosed()) {
                listener.onClose();
            } else {
                throw e;
            }
        }
    }

    public void submitStateUpdateTask(final String source, final ClusterStateUpdateTask updateTask) {
        submitStateUpdateTask(source, Priority.NORMAL, updateTask);
    }

    public void submitStateUpdateTask(final String source, Priority priority, final ClusterStateUpdateTask updateTask) {
        if (!lifecycle.started()) {
            return;
        }
        logger.debug("submit new task source={} task class={} doPresistMetaData={} priority={}", 
                source, updateTask.getClass().getName(), updateTask.doPresistMetaData(), priority);
        try {
            final UpdateTask task = new UpdateTask(source, priority, updateTask);
            if (updateTask instanceof TimeoutClusterStateUpdateTask) {
                final TimeoutClusterStateUpdateTask timeoutUpdateTask = (TimeoutClusterStateUpdateTask) updateTask;
                updateTasksExecutor.execute(task, threadPool.scheduler(), timeoutUpdateTask.timeout(), new Runnable() {
                    @Override
                    public void run() {
                        threadPool.generic().execute(new Runnable() {
                            @Override
                            public void run() {
                                timeoutUpdateTask.onFailure(task.source(), new ProcessClusterEventTimeoutException(timeoutUpdateTask.timeout(), task.source()));
                            }
                        });
                    }
                });
            } else {
                updateTasksExecutor.execute(task);
            }
        } catch (EsRejectedExecutionException e) {
            // ignore cases where we are shutting down..., there is really nothing interesting
            // to be done here...
            if (!lifecycle.stoppedOrClosed()) {
                throw e;
            }
        }
    }

    @Override
    public List<PendingClusterTask> pendingTasks() {
        PrioritizedEsThreadPoolExecutor.Pending[] pendings = updateTasksExecutor.getPending();
        List<PendingClusterTask> pendingClusterTasks = new ArrayList<>(pendings.length);
        for (PrioritizedEsThreadPoolExecutor.Pending pending : pendings) {
            final String source;
            final long timeInQueue;
            // we have to capture the task as it will be nulled after execution and we don't want to change while we check things here.
            final Object task = pending.task;
            if (task == null) {
                continue;
            } else if (task instanceof TimedPrioritizedRunnable) {
                TimedPrioritizedRunnable runnable = (TimedPrioritizedRunnable) task;
                source = runnable.source();
                timeInQueue = runnable.timeSinceCreatedInMillis();
            } else {
                assert false : "expected TimedPrioritizedRunnable got " + task.getClass();
                source = "unknown";
                timeInQueue = 0;
            }

            pendingClusterTasks.add(new PendingClusterTask(pending.insertionOrder, pending.priority, new StringText(source), timeInQueue, pending.executing));
        }
        return pendingClusterTasks;
    }

    @Override
    public int numberOfPendingTasks() {
        return updateTasksExecutor.getNumberOfPendingTasks();
    }

    /**
     * Block until recovery is done and all local shards are STARTED
     */
    public void waitShardsStarted() {
        logger.debug("waiting until all local primary shards are STARTED");
        while ((state().blocks().hasGlobalBlock(GatewayService.STATE_NOT_RECOVERED_BLOCK)) || (!state().routingTable().isLocalShardsStarted())) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                // ignore
            }
        }
        logger.debug("Ok, all local primary shards are STARTED, state={}", state().prettyPrint());
    }

    static abstract class TimedPrioritizedRunnable extends PrioritizedRunnable {
        private final long creationTime;
        protected final String source;

        protected TimedPrioritizedRunnable(Priority priority, String source) {
            super(priority);
            this.source = source;
            this.creationTime = System.currentTimeMillis();
        }

        public long timeSinceCreatedInMillis() {
            // max with 0 to make sure we always return a non negative number
            // even if time shifts.
            return Math.max(0, System.currentTimeMillis() - creationTime);
        }

        public String source() {
            return source;
        }
    }

    /**
     * Seems to manage all clusterState updates
     * - publish through the discoveryService.publish() to all remote nodes and then
     * - fire a clusterChanged() event for registered listeners.
     * 
     * => Modified to only broadcast MetaData change to other nodes.
     * @author vroyer
     *
     */
    class UpdateTask extends TimedPrioritizedRunnable {

        public final ClusterStateUpdateTask updateTask;

        UpdateTask(String source, Priority priority, ClusterStateUpdateTask updateTask) {
            super(priority, source);
            this.updateTask = updateTask;
        }

        @Override
        public void run() {
            if (!lifecycle.started()) {
                logger.debug("processing [{}]: ignoring, cluster_service not started", source);
                return;
            }
            logger.debug("current metadata={}/{} processing [{}]: execute", clusterState.metaData().uuid(), clusterState.metaData().version(), source);
            ClusterState previousClusterState = clusterState;
            ClusterState newClusterState;
            try {
                newClusterState = updateTask.execute(previousClusterState);

                if (!newClusterState.metaData().equals(previousClusterState.metaData()) && !newClusterState.blocks().disableStatePersistence() && updateTask.doPresistMetaData()) {
                    // try to persist new metadata in cassandra.
                    try {
                        elasticSchemaService.persistMetaData(previousClusterState.metaData(), newClusterState.metaData(), source);
                    } catch (ConcurrentMetaDataUpdateException e) {
                        // should replay the task later when current cluster state will match the expected metadata uuid and version
                        logger.debug("Cannot overwrite persistent metadata, will resubmit after next metadata update");
                        InternalClusterService.this.addFirst(new ClusterStateListener() {
                            @Override
                            public void clusterChanged(ClusterChangedEvent event) {
                                if (event.metaDataChanged()) {
                                    logger.debug("resubmit task source={} after metadata update", source);
                                    InternalClusterService.this.submitStateUpdateTask(source, Priority.URGENT, updateTask);
                                    InternalClusterService.this.remove(this); // replay only once.
                                }
                            }
                        });
                        return;
                    }
                }

            } catch (Throwable e) {
                if (logger.isTraceEnabled()) {
                    StringBuilder sb = new StringBuilder("failed to execute cluster state update, state:\nversion [").append(previousClusterState.version()).append("], source [").append(source)
                            .append("]\n");
                    sb.append(previousClusterState.nodes().prettyPrint());
                    sb.append(previousClusterState.routingTable().prettyPrint());
                    sb.append(previousClusterState.readOnlyRoutingNodes().prettyPrint());
                    logger.trace(sb.toString(), e);
                }
                updateTask.onFailure(source, e);
                return;
            }

            if (previousClusterState == newClusterState) {
                logger.debug("processing [{}]: no change in cluster_state", source);
                if (updateTask instanceof AckedClusterStateUpdateTask) {
                    //no need to wait for ack if nothing changed, the update can be counted as acknowledged
                    ((AckedClusterStateUpdateTask) updateTask).onAllNodesAcked(null);
                }
                if (updateTask instanceof ProcessedClusterStateUpdateTask) {
                    ((ProcessedClusterStateUpdateTask) updateTask).clusterStateProcessed(source, previousClusterState, newClusterState);
                }
                return;
            }

            try {
                newClusterState.status(ClusterState.ClusterStateStatus.BEING_APPLIED);

                if (logger.isTraceEnabled()) {
                    StringBuilder sb = new StringBuilder("cluster state updated, source [").append(source).append("]\n");
                    sb.append(newClusterState.prettyPrint());
                    logger.trace(sb.toString());
                } else if (logger.isDebugEnabled()) {
                    logger.debug("cluster state updated, version [{}], source [{}]", newClusterState.version(), source);
                }

                ClusterChangedEvent clusterChangedEvent = new ClusterChangedEvent(source, newClusterState, previousClusterState, updateTask.doPresistMetaData());
                // new cluster state, notify all listeners
                final DiscoveryNodes.Delta nodesDelta = clusterChangedEvent.nodesDelta();
                if (nodesDelta.hasChanges() && logger.isInfoEnabled()) {
                    String summary = nodesDelta.shortSummary();
                    if (summary.length() > 0) {
                        logger.info("{}, reason: {}", summary, source);
                    }
                }

                for (DiscoveryNode node : nodesDelta.addedNodes()) {
                    if (!nodeRequiresConnection(node)) {
                        continue;
                    }
                    try {
                        transportService.connectToNode(node);
                    } catch (Throwable e) {
                        // the fault detection will detect it as failed as well
                        logger.warn("failed to connect to node [" + node + "]", e);
                    }
                }
               
                
                newClusterState.status(ClusterState.ClusterStateStatus.APPLIED);
                
                // update the current local cluster state
                clusterState = newClusterState;
                logger.debug("set local clusterState version={} metadata.version={}", newClusterState.version(), newClusterState.metaData().version());

                // publish in gossip state the applied metadata.uuid and version
                discoveryService.publish(newClusterState);

                // wait for acknowledgment
                if (updateTask instanceof AckedClusterStateUpdateTask) {
                    final AckedClusterStateUpdateTask ackedUpdateTask = (AckedClusterStateUpdateTask) updateTask;
                    if (ackedUpdateTask.mustApplyMetaData() && newClusterState.nodes().size() > 1) {
                        try {
                            logger.info("Waiting MetaData.version = {} for all other alive nodes", newClusterState.metaData().version() );
                            if (!discoveryService.awaitMetaDataVersion(newClusterState.metaData().version(), ackedUpdateTask.ackTimeout())) {
                                logger.warn("Timeout waiting metadata version = {}", newClusterState.metaData().version());
                            }
                            ackedUpdateTask.onAllNodesAcked(null);
                        } catch (InterruptedException e) {
                            logger.warn("Interruped while waiting MetaData.version = {}",e, newClusterState.metaData().version() );
                            ackedUpdateTask.onAllNodesAcked(e);
                        }
                    } else {
                        ackedUpdateTask.onAllNodesAcked(null);
                    }
                }
               
                // notify listeners.
                for (ClusterStateListener listener : priorityClusterStateListeners) {
                    listener.clusterChanged(clusterChangedEvent);
                }
                for (ClusterStateListener listener : clusterStateListeners) {
                    listener.clusterChanged(clusterChangedEvent);
                }
                for (ClusterStateListener listener : lastClusterStateListeners) {
                    listener.clusterChanged(clusterChangedEvent);
                }

                
                for (DiscoveryNode node : nodesDelta.removedNodes()) {
                    try {
                        transportService.disconnectFromNode(node);
                    } catch (Throwable e) {
                        logger.warn("failed to disconnect to node [" + node + "]", e);
                    }
                }
                

                
                // notify 
                if (updateTask instanceof ProcessedClusterStateUpdateTask) {
                    ((ProcessedClusterStateUpdateTask) updateTask).clusterStateProcessed(source, previousClusterState, newClusterState);
                }
                
                logger.debug("processing [{}]: done applying updated cluster_state version={}", source, newClusterState.version());
            } catch (Throwable t) {
                StringBuilder sb = new StringBuilder("failed to apply updated cluster state:\nversion [").append(newClusterState.version()).append("], source [").append(source).append("]\n");
                sb.append(newClusterState.nodes().prettyPrint());
                sb.append(newClusterState.routingTable().prettyPrint());
                sb.append(newClusterState.readOnlyRoutingNodes().prettyPrint());
                logger.warn(sb.toString(), t);
                // TODO: do we want to call updateTask.onFailure here?
            }
        }
    }

    class NotifyTimeout implements Runnable {
        final TimeoutClusterStateListener listener;
        final TimeValue timeout;
        ScheduledFuture future;

        NotifyTimeout(TimeoutClusterStateListener listener, TimeValue timeout) {
            this.listener = listener;
            this.timeout = timeout;
        }

        public void cancel() {
            future.cancel(false);
        }

        @Override
        public void run() {
            if (future.isCancelled()) {
                return;
            }
            if (lifecycle.stoppedOrClosed()) {
                listener.onClose();
            } else {
                listener.onTimeout(this.timeout);
            }
            // note, we rely on the listener to remove itself in case of timeout if needed
        }
    }

    private class ReconnectToNodes implements Runnable {

        private ConcurrentMap<DiscoveryNode, Integer> failureCount = ConcurrentCollections.newConcurrentMap();

        @Override
        public void run() {
            // master node will check against all nodes if its alive with certain discoveries implementations,
            // but we can't rely on that, so we check on it as well
            for (DiscoveryNode node : clusterState.nodes()) {
                if (lifecycle.stoppedOrClosed()) {
                    return;
                }
                if (!nodeRequiresConnection(node)) {
                    continue;
                }
                if (!node.getStatus().equals(DiscoveryNodeStatus.ALIVE)) {
                    // don't try to connect until gossip update node status to ALIVE...
                    continue;
                }
                if (clusterState.nodes().nodeExists(node.id())) { // we double check existence of node since connectToNode might take time...
                    if (!transportService.nodeConnected(node)) {
                        try {
                            transportService.connectToNode(node);
                        } catch (Exception e) {
                            if (lifecycle.stoppedOrClosed()) {
                                return;
                            }
                            if (clusterState.nodes().nodeExists(node.id())) { // double check here as well, maybe its gone?
                                Integer nodeFailureCount = failureCount.get(node);
                                if (nodeFailureCount == null) {
                                    nodeFailureCount = 1;
                                } else {
                                    nodeFailureCount = nodeFailureCount + 1;
                                }
                                // log every 6th failure
                                if ((nodeFailureCount % 6) == 0) {
                                    // reset the failure count...
                                    nodeFailureCount = 0;
                                    logger.warn("failed to reconnect to node {}", e, node);
                                }
                                failureCount.put(node, nodeFailureCount);
                            }
                        }
                    }
                }
            }
            // go over and remove failed nodes that have been removed
            DiscoveryNodes nodes = clusterState.nodes();
            for (Iterator<DiscoveryNode> failedNodesIt = failureCount.keySet().iterator(); failedNodesIt.hasNext();) {
                DiscoveryNode failedNode = failedNodesIt.next();
                if (!nodes.nodeExists(failedNode.id())) {
                    failedNodesIt.remove();
                }
            }
            if (lifecycle.started()) {
                reconnectToNodes = threadPool.schedule(reconnectInterval, ThreadPool.Names.GENERIC, this);
            }
        }
    }

    private boolean nodeRequiresConnection(DiscoveryNode node) {
        return localNode().shouldConnectTo(node);
    }

    private static class LocalNodeMasterListeners implements ClusterStateListener {

        private final List<LocalNodeMasterListener> listeners = new CopyOnWriteArrayList<>();
        private final ThreadPool threadPool;
        private volatile boolean master = false;

        private LocalNodeMasterListeners(ThreadPool threadPool) {
            this.threadPool = threadPool;
        }

        @Override
        public void clusterChanged(ClusterChangedEvent event) {
            if (!master && event.localNodeMaster()) {
                master = true;
                for (LocalNodeMasterListener listener : listeners) {
                    Executor executor = threadPool.executor(listener.executorName());
                    executor.execute(new OnMasterRunnable(listener));
                }
                return;
            }

            if (master && !event.localNodeMaster()) {
                master = false;
                for (LocalNodeMasterListener listener : listeners) {
                    Executor executor = threadPool.executor(listener.executorName());
                    executor.execute(new OffMasterRunnable(listener));
                }
            }
        }

        private void add(LocalNodeMasterListener listener) {
            listeners.add(listener);
        }

        private void remove(LocalNodeMasterListener listener) {
            listeners.remove(listener);
        }

        private void clear() {
            listeners.clear();
        }
    }

    private static class OnMasterRunnable implements Runnable {

        private final LocalNodeMasterListener listener;

        private OnMasterRunnable(LocalNodeMasterListener listener) {
            this.listener = listener;
        }

        @Override
        public void run() {
            listener.onMaster();
        }
    }

    private static class OffMasterRunnable implements Runnable {

        private final LocalNodeMasterListener listener;

        private OffMasterRunnable(LocalNodeMasterListener listener) {
            this.listener = listener;
        }

        @Override
        public void run() {
            listener.offMaster();
        }
    }

    private static class NoOpAckListener implements Discovery.AckListener {
        @Override
        public void onNodeAck(DiscoveryNode node, @Nullable Throwable t) {
        }

        @Override
        public void onTimeout() {
        }
    }
    
    

    private static class AckCountDownListener implements Discovery.AckListener {

        private static final ESLogger logger = Loggers.getLogger(AckCountDownListener.class);

        private final AckedClusterStateUpdateTask ackedUpdateTask;
        private final CountDown countDown;
        private final DiscoveryNodes nodes;
        private final long clusterStateVersion;
        private final Future<?> ackTimeoutCallback;
        private Throwable lastFailure;

        AckCountDownListener(AckedClusterStateUpdateTask ackedUpdateTask, long clusterStateVersion, DiscoveryNodes nodes, ThreadPool threadPool) {
            this.ackedUpdateTask = ackedUpdateTask;
            this.clusterStateVersion = clusterStateVersion;
            this.nodes = nodes;
            int countDown = 0;
            for (DiscoveryNode node : nodes) {
                if (ackedUpdateTask.mustAck(node)) {
                    countDown++;
                }
            }
            //we always wait for at least 1 node (the master)
            countDown = Math.max(1, countDown);
            logger.trace("expecting {} acknowledgements for cluster_state update (version: {})", countDown, clusterStateVersion);
            this.countDown = new CountDown(countDown);
            this.ackTimeoutCallback = threadPool.schedule(ackedUpdateTask.ackTimeout(), ThreadPool.Names.GENERIC, new Runnable() {
                @Override
                public void run() {
                    onTimeout();
                }
            });
        }

        @Override
        public void onNodeAck(DiscoveryNode node, @Nullable Throwable t) {
            if (!ackedUpdateTask.mustAck(node)) {
                //we always wait for the master ack anyway
                if (!node.equals(nodes.masterNode())) {
                    return;
                }
            }
            if (t == null) {
                logger.trace("ack received from node [{}], cluster_state update (version: {})", node, clusterStateVersion);
            } else {
                this.lastFailure = t;
                logger.debug("ack received from node [{}], cluster_state update (version: {})", t, node, clusterStateVersion);
            }

            if (countDown.countDown()) {
                logger.trace("all expected nodes acknowledged cluster_state update (version: {})", clusterStateVersion);
                ackTimeoutCallback.cancel(true);
                ackedUpdateTask.onAllNodesAcked(lastFailure);
            }
        }

        @Override
        public void onTimeout() {
            if (countDown.fastForward()) {
                logger.trace("timeout waiting for acknowledgement for cluster_state update (version: {})", clusterStateVersion);
                ackedUpdateTask.onAckTimeout();
            }
        }
    }
}

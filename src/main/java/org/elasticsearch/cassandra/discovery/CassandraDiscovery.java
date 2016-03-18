/*
 * Copyright (c) 2015 Vincent Royer (vroyer@vroyer.org).
 * Contains some code from Elasticsearch (http://www.elastic.co)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.elasticsearch.cassandra.discovery;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.SystemKeyspace;
import org.apache.cassandra.gms.ApplicationState;
import org.apache.cassandra.gms.EndpointState;
import org.apache.cassandra.gms.Gossiper;
import org.apache.cassandra.gms.IEndpointStateChangeSubscriber;
import org.apache.cassandra.gms.VersionedValue;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.FBUtilities;
import org.codehaus.jackson.JsonGenerationException;
import org.codehaus.jackson.map.JsonMappingException;
import org.codehaus.jackson.map.ObjectMapper;
import org.codehaus.jackson.type.TypeReference;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.Version;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.ProcessedClusterStateNonMasterUpdateTask;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.node.DiscoveryNode.DiscoveryNodeStatus;
import org.elasticsearch.cluster.node.DiscoveryNodeService;
import org.elasticsearch.cluster.node.DiscoveryNodes;
import org.elasticsearch.cluster.routing.RoutingTable;
import org.elasticsearch.cluster.routing.ShardRoutingState;
import org.elasticsearch.cluster.routing.allocation.AllocationService;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.internal.Nullable;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.transport.InetSocketTransportAddress;
import org.elasticsearch.common.unit.TimeValue;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;
import org.elasticsearch.discovery.Discovery;
import org.elasticsearch.discovery.DiscoverySettings;
import org.elasticsearch.discovery.InitialStateDiscoveryListener;
import org.elasticsearch.node.service.NodeService;
import org.elasticsearch.transport.TransportService;

import com.google.common.collect.Maps;

/**
 * Discover the cluster topology from cassandra snitch and settings, mappings, blocks from the elastic_admin keyspace.
 * Publishing is just a nofification to refresh in memory configuration from the cassandra table.
 * @author vroyer
 *
 */
public class CassandraDiscovery extends AbstractLifecycleComponent<Discovery> implements Discovery, IEndpointStateChangeSubscriber {

    private static final DiscoveryNode[] NO_MEMBERS = new DiscoveryNode[0];

    private final TransportService transportService;
    private final ClusterService clusterService;
    private final DiscoveryNodeService discoveryNodeService;
    private final ClusterName clusterName;
    private final Version version;
    private final DiscoverySettings discoverySettings;

    //private final PublishClusterStateAction publishClusterStateAction;

    private final AtomicBoolean initialStateSent = new AtomicBoolean();
    private final CopyOnWriteArrayList<InitialStateDiscoveryListener> initialStateListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<MetaDataVersionListener> metaDataVersionListeners = new CopyOnWriteArrayList<>();
    
    
    
    private AllocationService allocationService;

    private DiscoveryNode localNode;

    @Nullable
    private NodeService nodeService;

    private volatile boolean master = true;

    private static final ConcurrentMap<ClusterName, ClusterGroup> clusterGroups = ConcurrentCollections.newConcurrentMap();

    private final ClusterGroup clusterGroup;

    private InetAddress localAddress = null;
    private String localDc = null;
    
    @Inject
    public CassandraDiscovery(Settings settings, ClusterName clusterName, TransportService transportService, ClusterService clusterService, DiscoveryNodeService discoveryNodeService, 
            Version version, DiscoverySettings discoverySettings) {
        super(settings);
        this.clusterName = clusterName;
        this.clusterService = clusterService;
        this.transportService = transportService;
        this.discoveryNodeService = discoveryNodeService;
        this.version = version;
        this.discoverySettings = discoverySettings;
        
        this.clusterGroup = new ClusterGroup();
        clusterGroups.put(clusterName, clusterGroup);
    }

    @Override
    public void setNodeService(@Nullable NodeService nodeService) {
        this.nodeService = nodeService;
    }


    

    /**
     * TODO: provide customizable node name factory.
     * @param addr
     * @return
     */
    public static String buildNodeName(InetAddress addr) {
        String hostname = addr.getHostName();
        if (hostname != null)
            return hostname;
        return String.format(Locale.getDefault(), "node%03d%03d%03d%03d", 
                (int) (addr.getAddress()[0] & 0xFF), (int) (addr.getAddress()[1] & 0xFF), 
                (int) (addr.getAddress()[2] & 0xFF), (int) (addr.getAddress()[3] & 0xFF));
    }

    @Override
    protected void doStart()  {

        synchronized (clusterGroup) {
            logger.debug("Connected to cluster [{}]", clusterName.value());

            localAddress = FBUtilities.getLocalAddress();
            localDc = DatabaseDescriptor.getEndpointSnitch().getDatacenter(localAddress);

            InetSocketTransportAddress elasticAddress = (InetSocketTransportAddress) transportService.boundAddress().publishAddress();
            logger.info("Listening address Cassandra=" + localAddress + " Elastic=" + elasticAddress.toString());

            // get local node from cassandra cluster
            {
                Map<String, String> attrs = Maps.newHashMap();
                attrs.put("data", "true");
                attrs.put("master", "true");
                attrs.put("data_center", localDc);
                attrs.put("rack", DatabaseDescriptor.getEndpointSnitch().getRack(localAddress));

                String hostId = SystemKeyspace.getLocalHostId().toString();
                localNode = new DiscoveryNode(buildNodeName(localAddress), hostId, transportService.boundAddress().publishAddress(), attrs, version);
                localNode.status(DiscoveryNodeStatus.ALIVE);
                this.transportService.setLocalNode(localNode); // clusterService start before DiscoveryService.
                master = true;
                clusterGroup.put(this.localNode.getId(), this.localNode);
                logger.info("localNode name={} id={}", this.localNode.getName(), this.localNode.getId());
            }

            // initialize cluster from cassandra system.peers 
            Map<InetAddress, UUID> peers = SystemKeyspace.loadHostIds();
            Map<InetAddress, Map<String, String>> endpointInfo = SystemKeyspace.loadDcRackInfo();
            for (Entry<InetAddress, UUID> entry : peers.entrySet()) {
                if ((!entry.getKey().equals(localAddress)) && (localDc.equals(endpointInfo.get(entry.getKey()).get("data_center")))) {
                    Map<String, String> attrs = Maps.newHashMap();
                    attrs.put("data", "true");
                    attrs.put("master", "true");
                    attrs.putAll(endpointInfo.get(entry.getKey()));
                    DiscoveryNode dn = new DiscoveryNode(buildNodeName(entry.getKey()), entry.getValue().toString(), new InetSocketTransportAddress(entry.getKey(), settings.getAsInt(
                            "transport.tcp.port", 9300)), attrs, version);
                    EndpointState endpointState = Gossiper.instance.getEndpointStateForEndpoint(entry.getKey());
                    if (endpointState == null) {
                        dn.status( DiscoveryNodeStatus.UNKNOWN );
                    } else {
                        dn.status((endpointState.isAlive()) ? DiscoveryNodeStatus.ALIVE : DiscoveryNodeStatus.DEAD);
                    }
                    clusterGroup.put(dn.getId(), dn);
                    logger.debug("remanent node addr_ip={} node_name={} host_id={} ", entry.getKey().toString(), dn.getId(), dn.getName());
                }
            }

            Gossiper.instance.register(this);
            updateClusterGroupsFromGossiper();
            updateClusterState("starting-cassandra-discovery", null);
        }
    }
    
    
    public DiscoveryNodes nodes() {
        return this.clusterGroup.nodes();
    }

    private void updateClusterState(String source, MetaData newMetadata) {
        final MetaData schemaMetaData = newMetadata;
        clusterService.submitStateUpdateTask(source, (schemaMetaData==null) ? Priority.NORMAL : Priority.URGENT, new ProcessedClusterStateNonMasterUpdateTask() {

            @Override
            public ClusterState execute(ClusterState currentState) {
                ClusterState.Builder newStateBuilder = ClusterState.builder(currentState).nodes(nodes());

                if (schemaMetaData != null) {
                    newStateBuilder.metaData(schemaMetaData);
                }

                ClusterState newClusterState = clusterService.updateNumberOfShards( newStateBuilder.build() );
                RoutingTable newRoutingTable = RoutingTable.builder(clusterService, newClusterState).build();
                
                return ClusterState.builder(newClusterState).routingTable(newRoutingTable).build();
            }

            @Override
            public boolean doPresistMetaData() {
                return false;
            }

            @Override
            public void onFailure(String source, Throwable t) {
                logger.error("unexpected failure during [{}]", t, source);
            }

            @Override
            public void clusterStateProcessed(String source, ClusterState oldState, ClusterState newState) {
                sendInitialStateEventIfNeeded();
            }
        });
    }

    /**
     * Update cluster group members from cassandra topology (should only be triggered by IEndpointStateChangeSubscriber events).
     * This should trigger re-sharding of index for new nodes (when token distribution change).
     */
    public void updateClusterGroupsFromGossiper() {
        for (Entry<InetAddress, EndpointState> entry : Gossiper.instance.getEndpointStates()) {
            DiscoveryNodeStatus status = (entry.getValue().isAlive()) ? DiscoveryNode.DiscoveryNodeStatus.ALIVE : DiscoveryNode.DiscoveryNodeStatus.DEAD;

            if (DatabaseDescriptor.getEndpointSnitch().getDatacenter(entry.getKey()).equals(localDc)) {
                VersionedValue vv = entry.getValue().getApplicationState(ApplicationState.HOST_ID);
                if (vv != null) {
                    String hostId = vv.value;
                    DiscoveryNode dn = clusterGroup.get(hostId);
                    if (dn == null) {
                        Map<String, String> attrs = Maps.newHashMap();
                        attrs.put("data", "true");
                        attrs.put("master", "true");
                        attrs.put("data_center", localDc);
                        attrs.put("rack", DatabaseDescriptor.getEndpointSnitch().getRack(entry.getKey()));

                        dn = new DiscoveryNode(buildNodeName(entry.getKey()), hostId.toString(), new InetSocketTransportAddress(entry.getKey(), settings.getAsInt("transport.tcp.port", 9300)), attrs, version);
                        dn.status(status);

                        if (localAddress.equals(entry.getKey())) {
                            logger.debug("Update local node host_id={} status={} timestamp={}", 
                                    entry.getKey().toString(), dn.getId(), dn.getName(), entry.getValue().isAlive(), entry.getValue().getUpdateTimestamp());
                            clusterGroup.remove(this.localNode.id());
                            this.localNode = dn;
                        } else {
                            logger.debug("New node addr_ip={} node_name={} host_id={} status={} timestamp={}", 
                                    entry.getKey().toString(), dn.getId(), dn.getName(), entry.getValue().isAlive(), entry.getValue().getUpdateTimestamp());
                        }
                        clusterGroup.put(dn.getId(), dn);
                    } else {
                        // may update DiscoveryNode status.
                        if (!dn.getStatus().equals(status)) {
                            dn.status(status);
                        }
                    }
                }
            }
        }

    }

    public MetaData hasNewMetaData() {
        MetaData currentMetaData = clusterService.state().metaData();
        MetaData newMetaData = clusterService.readMetaDataAsRow();
        // TODO: merge metadata ?
        if (newMetaData.version() > currentMetaData.version()) {
            logger.debug("updating metadata from uid/version={}/{} to {}/{}", currentMetaData.uuid(), currentMetaData.version(), newMetaData.uuid(), newMetaData.version());
            return newMetaData;
        }
        if (logger.isTraceEnabled())
            logger.trace("ignoring unchanged metadata uuid/version={}/{}", newMetaData.uuid(), newMetaData.version());
        return null;
    }
    
    public void updateNode(InetAddress addr, EndpointState state) {
        
        DiscoveryNodeStatus status = (state.isAlive()) ? DiscoveryNode.DiscoveryNodeStatus.ALIVE : DiscoveryNode.DiscoveryNodeStatus.DEAD;
        boolean updatedNode = false;
        if (DatabaseDescriptor.getEndpointSnitch().getDatacenter(addr).equals(localDc)) {
            String hostId = state.getApplicationState(ApplicationState.HOST_ID).value;
            DiscoveryNode dn = clusterGroup.get(hostId);
            if (dn == null) {
                Map<String, String> attrs = Maps.newHashMap();
                attrs.put("data", "true");
                attrs.put("master", "true");
                attrs.put("data_center", localDc);
                attrs.put("rack", DatabaseDescriptor.getEndpointSnitch().getRack(addr));

                dn = new DiscoveryNode(buildNodeName(addr), hostId.toString(), new InetSocketTransportAddress(addr, settings.getAsInt("transport.tcp.port", 9300)), attrs, version);
                dn.status(status);
                logger.debug("New node soure=updateNode addr_ip={} node_name={} host_id={} status={} timestamp={}", addr.getHostAddress(), dn.getId(), dn.getName(), status, state.getUpdateTimestamp());
                clusterGroup.members.put(dn.getId(), dn);
                updatedNode = true;
            } else {
                // may update DiscoveryNode status.
                if (!dn.getStatus().equals(status)) {
                    dn.status(status);
                    updatedNode = true;
                }
            }
        }
        if (updatedNode)
            updateClusterState("update-node-" + addr.getHostAddress(), null);
    }

   
    
    
    public boolean awaitMetaDataVersion(long version, TimeValue timeout) throws InterruptedException {
        MetaDataVersionListener listener = new MetaDataVersionListener(version);
        return listener.await(timeout.millis(), TimeUnit.MILLISECONDS);
    }
    
    
    public void checkMetaDataVersion() {
        for(Iterator<MetaDataVersionListener> it = this.metaDataVersionListeners.iterator(); it.hasNext(); ) {
            MetaDataVersionListener listener = it.next();
            boolean versionReached = true;
            for(InetAddress addr : Gossiper.instance.getLiveTokenOwners()) {
                if (DatabaseDescriptor.getEndpointSnitch().getDatacenter(addr).equals(localDc)) {
                    EndpointState endPointState = Gossiper.instance.getEndpointStateForEndpoint(addr);
                    VersionedValue vv = endPointState.getApplicationState(ELASTIC_META_DATA);
                    if (vv != null && vv.value.lastIndexOf('/') > 0) { 
                        Long version = Long.valueOf(vv.value.substring(vv.value.lastIndexOf('/')+1));
                        if (version < listener.version()) {
                            versionReached = false;
                            break;
                        }
                    }
                }
            }
            if (versionReached) {
                logger.debug("MetaData.version = {} reached", listener.version());
                listener.release();
                metaDataVersionListeners.remove(listener);
            }
        }
    }
    
    
    
    public class MetaDataVersionListener  {
        final long expectedVersion;
        final CountDownLatch countDownLatch = new CountDownLatch(1);
        
        public MetaDataVersionListener(long version) {
            expectedVersion = version;
        }

        public long version() {
            return this.expectedVersion;
        }
        

        public boolean await(long timeout, TimeUnit unit) throws InterruptedException {
            CassandraDiscovery.this.metaDataVersionListeners.add(this);
            Set<Entry<InetAddress,EndpointState>> currentStates = Gossiper.instance.getEndpointStates();
            boolean versionReached = true;
            for(Entry<InetAddress,EndpointState> entry : currentStates) {
                if (DatabaseDescriptor.getEndpointSnitch().getDatacenter(entry.getKey()).equals(localDc) && entry.getValue().isAlive()) {
                    VersionedValue vv = entry.getValue().getApplicationState(ELASTIC_META_DATA);
                    if (vv != null && vv.value.lastIndexOf('/') > 0) { 
                        Long version = Long.valueOf(vv.value.substring(vv.value.lastIndexOf('/')+1));
                        if (version < expectedVersion) {
                            versionReached = false;
                            break;
                        }
                    }
                }
            }
            if (!versionReached) {
                return countDownLatch.await(timeout, unit);
            }
           return true;
        }

        public void release() {
            countDownLatch.countDown();
        }
    }
    
    @Override
    public void beforeChange(InetAddress arg0, EndpointState arg1, ApplicationState arg2, VersionedValue arg3) {
        //logger.warn("beforeChange({},{},{},{})",arg0,arg1,arg2,arg3);
    }

    @Override
    public void onChange(InetAddress endpoint, ApplicationState state, VersionedValue value) {
        if (!this.localAddress.equals(endpoint)) {
            switch (state) {
            case SCHEMA: // remote metadata change
                MetaData metadata = hasNewMetaData();
                if (metadata != null) {
                    logger.debug("Endpoint={} ApplicationState={} value={} => update metaData {}/{}", 
                            endpoint, state, value.value, metadata.uuid(), metadata.version());
                    updateClusterState("onChange-" + endpoint + "-" + state.toString()+" metadata="+metadata.uuid()+"/"+metadata.version(), metadata);
                }
                break;
            case X1: // remote shards state change
                logger.debug("Endpoint={} ApplicationState={} value={} => update routingTable", endpoint, state, value.value);
                updateClusterState("onChange-" + endpoint + "-" + state.toString()+" X1="+value.value, null);
                break;
            case X2:
                checkMetaDataVersion();
                break;
            }
        }
    }

    @Override
    public void onAlive(InetAddress arg0, EndpointState arg1) {
        logger.debug("onAlive Endpoint={} ApplicationState={} isAlive={} => update node", arg0, arg1, arg1.isAlive());
        updateNode(arg0, arg1);
    }
    
    @Override
    public void onDead(InetAddress arg0, EndpointState arg1) {
        logger.debug("onDead Endpoint={}  ApplicationState={} isAlive={} => update node", arg0, arg1, arg1.isAlive());
        updateNode(arg0, arg1);
    }
    
    @Override
    public void onRestart(InetAddress arg0, EndpointState arg1) {
        //logger.debug("onRestart Endpoint={}  ApplicationState={} isAlive={}", arg0, arg1, arg1.isAlive());
    }

    @Override
    public void onJoin(InetAddress arg0, EndpointState arg1) {
        //logger.debug("onAlive Endpoint={} ApplicationState={} isAlive={}", arg0, arg1, arg1.isAlive() );
    }
   
    @Override
    public void onRemove(InetAddress arg0) {
        // TODO: support onRemove (hostId unavailable)
        //logger.warn("onRemove Endpoint={}  => removing a node not supported", arg0);
    }

    @Override
    protected void doStop() throws ElasticsearchException {
        Gossiper.instance.unregister(this);
        
        synchronized (clusterGroup) {
            if (clusterGroup == null) {
                logger.warn("Illegal state, should not have an empty cluster group when stopping, I should be there at teh very least...");
                return;
            }
            if (clusterGroup.members().isEmpty()) {
                // no more members, remove and return
                clusterGroups.remove(clusterName);
                return;
            }
        }
    }

    /**
     * ELASTIC_INDEX_STATES = Map<IndexUid,ShardRoutingState>
     */
    private static final ApplicationState ELASTIC_SHARDS_STATES = ApplicationState.X1;
    private static final ApplicationState ELASTIC_META_DATA = ApplicationState.X2;
    private static final ObjectMapper jsonMapper = new ObjectMapper();
    private static final TypeReference<Map<String, ShardRoutingState>> indexShardStateTypeReference = new TypeReference<Map<String, ShardRoutingState>>() {
    };

    /**
     * read the remote shard state from gossiper the X1 field.
     * TODO: cache all enpoint.X1 state to avoid many JSON parsing.
     */
    @Override
    public ShardRoutingState readIndexShardState(InetAddress address, String index, ShardRoutingState defaultState) {
        EndpointState state = Gossiper.instance.getEndpointStateForEndpoint(address);
        if (state != null) {
            VersionedValue value = state.getApplicationState(ELASTIC_SHARDS_STATES);
            if (value != null) {
                try {
                    Map<String, ShardRoutingState> shardsStateMap = jsonMapper.readValue(value.value, indexShardStateTypeReference);
                    ShardRoutingState shardState = shardsStateMap.get(index);
                    if (shardState != null) {
                        logger.debug("index shard state  addr={} index={} state={}", address, index, shardState);
                        return shardState;
                    }
                } catch (Exception e) {
                    logger.warn("Failed to parse gossip index shard state", e);
                }
            }
        }
        return defaultState;
    }

    /**
     * add local index shard state to local application state.
     * @param index
     * @param shardRoutingState
     * @throws JsonGenerationException
     * @throws JsonMappingException
     * @throws IOException
     */
    @Override
    public synchronized void writeIndexShardState(String index, ShardRoutingState shardRoutingState) throws JsonGenerationException, JsonMappingException, IOException {
        if (Gossiper.instance.isEnabled()) {
            Map<String, ShardRoutingState> shardsStateMap = null;
            EndpointState state = Gossiper.instance.getEndpointStateForEndpoint(FBUtilities.getBroadcastAddress());
            if (state != null) {
                VersionedValue value = state.getApplicationState(ELASTIC_SHARDS_STATES);
                if (value != null) {
                    shardsStateMap = (Map<String, ShardRoutingState>) jsonMapper.readValue(value.value, new TypeReference<Map<String, ShardRoutingState>>() {
                    });
                }
            }
            if (shardsStateMap == null) {
                shardsStateMap = new HashMap<String, ShardRoutingState>();
            }
            if (shardRoutingState != null) {
                shardsStateMap.put(index, shardRoutingState);
            } else {
                if (shardsStateMap.containsKey(index)) {
                    shardsStateMap.remove(index);
                }
            }
            Gossiper.instance.addLocalApplicationState(ELASTIC_SHARDS_STATES, StorageService.instance.valueFactory.datacenter(jsonMapper.writeValueAsString(shardsStateMap)));
        } else {
            logger.warn("Gossiper not enabled to publish shard state");
        }
    }

    @Override
    public void publish(ClusterState clusterState) {
        if (Gossiper.instance.isEnabled()) {
            String clusterStateSting = clusterState.metaData().uuid() + '/' + clusterState.metaData().version();
            Gossiper.instance.addLocalApplicationState(ELASTIC_META_DATA, StorageService.instance.valueFactory.datacenter(clusterStateSting));
        }
    }

    @Override
    protected void doClose()  {
    }

    @Override
    public DiscoveryNode localNode() {
        return localNode;
    }

    @Override
    public void addListener(InitialStateDiscoveryListener listener) {
        this.initialStateListeners.add(listener);
    }

    @Override
    public void removeListener(InitialStateDiscoveryListener listener) {
        this.initialStateListeners.remove(listener);
    }




    
    @Override
    public String nodeDescription() {
        return clusterName.value() + "/" + localNode.id();
    }

    private void sendInitialStateEventIfNeeded() {
        if (initialStateSent.compareAndSet(false, true)) {
            for (InitialStateDiscoveryListener listener : initialStateListeners) {
                listener.initialStateProcessed();
            }
        }
    }

    private class ClusterGroup {

        private Map<String, DiscoveryNode> members = ConcurrentCollections.newConcurrentMap();

        Map<String, DiscoveryNode> members() {
            return members;
        }

        public void put(String id, DiscoveryNode node) {
            members.put(id, node);
        }
        
        public void remove(String id) {
            members.remove(id);
        }


        public DiscoveryNode get(String id) {
            return members.get(id);
        }
        
        public DiscoveryNodes nodes() {
            DiscoveryNodes.Builder nodesBuilder = new DiscoveryNodes.Builder();
            nodesBuilder.localNodeId(CassandraDiscovery.this.localNode.id()).masterNodeId(CassandraDiscovery.this.localNode.id());
            for (DiscoveryNode node : members.values()) {
                nodesBuilder.put(node);
            }
            return nodesBuilder.build();
        }
    }


    @Override
    public void publish(ClusterChangedEvent clusterChangedEvent, AckListener ackListener) {
        // TODO Auto-generated method stub
        
    }

}

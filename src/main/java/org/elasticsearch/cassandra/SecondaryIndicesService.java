package org.elasticsearch.cassandra;

import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.common.component.LifecycleComponent;


public interface SecondaryIndicesService extends LifecycleComponent<SecondaryIndicesService>, ClusterStateListener {

    
    public void dropSecondaryIndices(String index);
    
    
    public void addDeleteListener(DeleteListener listener);
    public void removeDeleteListener(DeleteListener listener);
    
    public interface DeleteListener {
        public String index();
        public void onIndexDeleted();
    }
    
}

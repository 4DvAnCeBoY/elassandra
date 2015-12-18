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
package org.elasticsearch.cassandra;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql3.UntypedResultSet;
import org.apache.cassandra.cql3.UntypedResultSet.Row;
import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.exceptions.RequestExecutionException;
import org.apache.cassandra.exceptions.RequestValidationException;
import org.apache.cassandra.exceptions.SyntaxException;
import org.elasticsearch.action.index.IndexRequest;
import org.elasticsearch.cluster.ClusterState;
import org.elasticsearch.cluster.metadata.MappingMetaData;
import org.elasticsearch.cluster.metadata.MetaData;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.fieldvisitor.FieldsVisitor;
import org.elasticsearch.index.get.GetField;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.MapperService;
import org.elasticsearch.index.mapper.object.ObjectMapper;
import org.elasticsearch.indices.IndicesService;

import com.google.common.collect.ImmutableList;

public interface SchemaService {

    public Map<String, GetField> flattenGetField(final String[] fieldFilter, final String path, final Object node, Map<String, GetField> flatFields);
    public Map<String, List<Object>> flattenTree(final Set<String> neededFiedls, final String path, final Object node, Map<String, List<Object>> fields);

    public void createElasticAdminKeyspace() throws Exception;
    public void createIndexKeyspace(String index, int replicationFactor) throws IOException;
    
    public void createSecondaryIndices(String index) throws IOException;
    public void createSecondaryIndex(String ksName, MappingMetaData mapping) throws IOException;
    public void dropSecondaryIndices(String ksName) throws RequestExecutionException;
    public void dropSecondaryIndex(String ksName, String cfName) throws RequestExecutionException;
    
    public void removeIndexKeyspace(String index) throws IOException;
    
    public String buildUDT(String ksName, String cfName, String name, ObjectMapper objectMapper) throws RequestExecutionException;

    public void updateTableSchema(String index, String type, Set<String> columns, DocumentMapper docMapper) throws IOException;
    
    public List<ColumnDefinition> getPrimaryKeyColumns(String ksName, String cfName) throws ConfigurationException;

    public Collection<String> mappedColumns(final String index, final String type);
    public String[] mappedColumns(final MapperService mapperService, final String type);
    
    public UntypedResultSet fetchRow(String index, String type, Collection<String> requiredColumns,String id) throws InvalidRequestException, RequestExecutionException, RequestValidationException,
            IOException;

    public UntypedResultSet fetchRow(String index, String type, Collection<String> requiredColumns, String id, ConsistencyLevel cl) throws InvalidRequestException, RequestExecutionException,
            RequestValidationException, IOException;

    public UntypedResultSet fetchRow(final String index, final String type, final String id) 
            throws InvalidRequestException, RequestExecutionException, RequestValidationException, IOException;
    
    public UntypedResultSet fetchRowInternal(String index, String type, Collection<String> requiredColumns, String id) throws ConfigurationException, IOException;
    public UntypedResultSet fetchRowInternal(String ksName, String cfName, Collection<String> requiredColumns, Object[] pkColumns) throws ConfigurationException, IOException;
    
    public Map<String, Object> rowAsMap(final String index, final String type, UntypedResultSet.Row row) throws IOException;
    public int rowAsMap(final String index, final String type, UntypedResultSet.Row row, Map<String, Object> map) throws IOException;

    public void deleteRow(String index, String type, String id, ConsistencyLevel cl) throws InvalidRequestException, RequestExecutionException, RequestValidationException, IOException;

    public String insertDocument(IndicesService indicesService, IndexRequest request, ClusterState clusterState, Long writetime, Boolean applied) throws Exception;

    public String insertRow(String index, String type, Map<String, Object> map, String id, boolean ifNotExists, long ttl, ConsistencyLevel cl, Long writetime, Boolean applied)
            throws Exception;

    public void index(String[] indices, Collection<Range<Token>> tokenRanges);

    public void index(String index, String type, String id, Object[] sourceData);

    public void blockingMappingUpdate(IndexService indexService, DocumentMapper mapper ) throws Exception;
    
    public Token getToken(ByteBuffer rowKey, ColumnFamily cf);

    

    public void writeMetaDataAsComment(MetaData metadata) throws ConfigurationException, IOException;

    public void initializeMetaDataAsComment();

    public MetaData readMetaDataAsComment() throws NoPersistedMetaDataException;

    public MetaData readMetaDataAsRow() throws NoPersistedMetaDataException;

    public void persistMetaData(MetaData currentMetadData, MetaData newMetaData, String source) throws ConfigurationException, IOException, InvalidRequestException, RequestExecutionException,
            RequestValidationException;
    
    public Map<String, Object> expandTableMapping(final String ksName, Map<String, Object> mapping) throws IOException, SyntaxException, ConfigurationException;
    public Map<String, Object> expandTableMapping(final String ksName, final String cfName, Map<String, Object> mapping) throws IOException, SyntaxException, ConfigurationException;

}

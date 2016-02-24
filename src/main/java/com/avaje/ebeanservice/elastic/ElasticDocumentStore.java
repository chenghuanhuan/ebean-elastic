package com.avaje.ebeanservice.elastic;

import com.avaje.ebean.DocStoreQueueEntry;
import com.avaje.ebean.DocumentStore;
import com.avaje.ebean.PersistenceIOException;
import com.avaje.ebean.Query;
import com.avaje.ebean.QueryEachConsumer;
import com.avaje.ebean.plugin.SpiBeanType;
import com.avaje.ebean.plugin.SpiServer;
import com.avaje.ebeaninternal.api.SpiQuery;
import com.avaje.ebeanservice.docstore.api.DocStoreQueryUpdate;
import com.avaje.ebeanservice.elastic.index.IndexService;
import com.avaje.ebeanservice.elastic.support.ElasticBatchUpdate;
import com.avaje.ebeanservice.elastic.support.IndexMessageSender;
import com.avaje.ebeanservice.elastic.updategroup.ConvertToGroups;
import com.avaje.ebeanservice.elastic.updategroup.ProcessGroup;
import com.avaje.ebeanservice.elastic.updategroup.UpdateGroup;
import com.fasterxml.jackson.core.JsonFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

/**
 * ElasticSearch based document store.
 */
public class ElasticDocumentStore implements DocumentStore {


  private final SpiServer server;

  private final ElasticUpdateProcessor updateProcessor;

  private final ElasticQueryService queryService;

  private final IndexService indexService;

  public ElasticDocumentStore(SpiServer server, ElasticUpdateProcessor updateProcessor, IndexMessageSender sender, JsonFactory jsonFactory) {
    this.server = server;
    this.updateProcessor = updateProcessor;
    this.queryService = new ElasticQueryService(server, jsonFactory, sender);
    this.indexService = new IndexService(server, jsonFactory, sender);
  }

  @Override
  public void process(List<DocStoreQueueEntry> entries) throws IOException {

    Collection<UpdateGroup> groups = ConvertToGroups.groupByQueueId(entries);
    ElasticBatchUpdate txn = updateProcessor.createBatchUpdate(0);

    try {
      for (UpdateGroup group : groups) {
        SpiBeanType<?> desc = server.getBeanTypeForQueueId(group.getQueueId());
        ProcessGroup.process(server, desc, group, txn);
      }

      txn.flush();

    } catch (IOException e) {
      throw new PersistenceIOException(e);
    }
  }

  @Override
  public void dropIndex(String newIndex) {
    try {
      indexService.dropIndex(newIndex);
    } catch (IOException e) {
      throw new PersistenceIOException(e);
    }
  }

  @Override
  public void createIndex(String indexName, String alias, String mappingResource) {
    try {
      indexService.createIndex(indexName, alias, mappingResource);
    } catch (IOException e) {
      throw new PersistenceIOException(e);
    }
  }

  @Override
  public long copyIndex(Class<?> beanType, String newIndex, long epochMillis) {
    SpiBeanType<?> type = server.getBeanType(beanType);
    checkMapped(type);
    try {
      ElasticBatchUpdate txn = updateProcessor.createBatchUpdate(0);
      return queryService.copyIndexSince(type, newIndex, txn, epochMillis);

    } catch (IOException e) {
      throw new PersistenceIOException(e);
    }
  }

  @Override
  public long copyIndex(Class<?> beanType, String newIndex) {
    return copyIndex(beanType, newIndex, 0);
  }

  @Override
  public void indexAll(Class<?> beanType) {
    SpiBeanType<?> type = server.getBeanType(beanType);
    checkMapped(type);
    Query<?> query = server.createQuery(beanType);
    indexByQuery(query);
  }

  @Override
  public <T> void indexByQuery(Query<T> query) {
    indexByQuery(query, 0);
  }

  @Override
  public <T> void indexByQuery(Query<T> query, int bulkBatchSize) {

    SpiQuery<T> spiQuery = (SpiQuery<T>) query;
    Class<T> beanType = spiQuery.getBeanType();

    SpiBeanType<T> beanDescriptor = server.getBeanType(beanType);
    checkMapped(beanDescriptor);

    try {
      DocStoreQueryUpdate<T> update = updateProcessor.createQueryUpdate(beanDescriptor, bulkBatchSize);
      indexByQuery(beanDescriptor, query, update);
      update.flush();

    } catch (IOException e) {
      throw new PersistenceIOException(e);
    }
  }


  private <T> void indexByQuery(final SpiBeanType<T> desc, Query<T> query, final DocStoreQueryUpdate<T> queryUpdate) throws IOException {

    desc.docStoreApplyPath(query);
    query.setLazyLoadBatchSize(100);
    query.findEach(new QueryEachConsumer<T>() {
      @Override
      public void accept(T bean) {
        Object idValue = desc.getBeanId(bean);
        try {
          queryUpdate.store(idValue, bean);
        } catch (Exception e) {
          throw new PersistenceIOException("Error performing query update to doc store", e);
        }
      }
    });
  }

  @Override
  public <T> void findEach(Query<T> query, QueryEachConsumer<T> consumer) {
    queryService.findEach(query, consumer);
  }

  @Override
  public <T> List<T> findList(Query<T> query) {
    return queryService.findList(query);
  }

  @Override
  public <T> T getById(Class<T> beanType, Object id) {
    return queryService.getById(beanType, id);
  }

  public void onStartup() {

    try {
      indexService.createIndexes();

    } catch (IOException e) {
      throw new PersistenceIOException(e);
    }
  }

  private void checkMapped(SpiBeanType<?> type) {
    if (type == null) {
      throw new IllegalStateException("No bean type mapping found?");
    }
    if (!type.isDocStoreMapped()) {
      throw new IllegalStateException("No doc store mapping for bean type "+type.getFullName());
    }
  }
}

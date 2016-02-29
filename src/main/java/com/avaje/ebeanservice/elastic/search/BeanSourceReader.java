package com.avaje.ebeanservice.elastic.search;

import com.avaje.ebean.bean.EntityBean;
import com.avaje.ebean.bean.PersistenceContext;
import com.avaje.ebean.plugin.BeanType;
import com.avaje.ebean.plugin.ExpressionPath;
import com.avaje.ebean.text.json.JsonBeanReader;
import com.avaje.ebeaninternal.server.deploy.BeanPropertyAssocMany;
import com.fasterxml.jackson.core.JsonParser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the source and fields from an ElasticSearch search result and populates beans.
 */
public class BeanSourceReader<T> implements SearchSourceListener {

  private final BeanType<T> desc;

  private final JsonBeanReader<T> reader;

  private final List<T> beans = new ArrayList<T>();

  private final boolean hasContext;

  private final PersistenceContext persistenceContext;

  private T currentBean;

  private final BeanPropertyAssocMany<?> lazyLoadMany;

  public BeanSourceReader(BeanType<T> desc, JsonBeanReader<T> reader, BeanPropertyAssocMany<?> lazyLoadMany) {
    this.desc = desc;
    this.reader = reader;
    this.persistenceContext = reader.getPersistenceContext();
    this.hasContext = persistenceContext != null;
    this.lazyLoadMany = lazyLoadMany;
  }

  @Override
  public void readSource(JsonParser parser, String id) throws IOException {

    currentBean = reader.read();
    desc.setBeanId(currentBean, id);
    beans.add(currentBean);
    loadPersistenceContext();
  }

  private void loadPersistenceContext() {
    if (hasContext) {
      EntityBean current = (EntityBean)currentBean;
      Object beanId = desc.getBeanId(currentBean);
      reader.persistenceContextPut(beanId, currentBean);
      if (lazyLoadMany != null) {
        lazyLoadMany.lazyLoadMany(current);
      }
    }
  }

  @Override
  public void readFields(Map<String, Object> fields, String id, double score) {

    if (currentBean != null) {
      applyFields(currentBean, fields);

    } else {
      T bean = desc.createBean();
      desc.setBeanId(bean, id);
      applyFields(bean, fields);
      beans.add(bean);
      loadPersistenceContext();
    }

  }

  @SuppressWarnings("unchecked")
  private void applyFields(T bean, Map<String, Object> fields) {

    Set<Map.Entry<String, Object>> entries = fields.entrySet();
    for (Map.Entry<String, Object> entry : entries) {
      ExpressionPath path = desc.getExpressionPath(entry.getKey());
      List<Object> value = (List<Object>)entry.getValue();

      if (!path.containsMany()) {
        if (value.size() == 1) {
          path.set(bean, value.get(0));
        }
      }
    }
  }

  public List<T> getList() {
    return beans;
  }

  public int size() {
    return beans.size();
  }

  public void readIdOnly(String id, double score) {
    T bean = desc.createBean();
    desc.setBeanId(bean, id);
    beans.add(bean);
    loadPersistenceContext();
  }
}

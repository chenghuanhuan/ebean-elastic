package com.avaje.ebeanservice.elastic.search;

import com.avaje.ebean.plugin.BeanType;
import com.avaje.ebean.plugin.ExpressionPath;
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

  private final List<T> beans = new ArrayList<T>();

  private T currentBean;

  public BeanSourceReader(BeanType<T> desc) {
    this.desc = desc;
  }

  @Override
  public void readSource(JsonParser parser, String id) throws IOException {
    currentBean = desc.jsonRead(parser, null, null);
    desc.setBeanId(currentBean, id);
    beans.add(currentBean);
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
  }
}

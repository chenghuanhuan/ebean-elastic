package com.avaje.ebeanservice.elastic.index;

import com.avaje.ebean.PersistenceIOException;
import com.avaje.ebean.config.DocStoreConfig;
import com.avaje.ebean.plugin.BeanType;
import com.avaje.ebean.plugin.SpiServer;
import com.avaje.ebeanservice.elastic.support.IndexMessageSender;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.StringWriter;

/**
 * Index exists, drop, create functions.
 */
public class EIndexService {

  private static final Logger logger = LoggerFactory.getLogger(EIndexService.class);

  private final SpiServer server;

  private final JsonFactory jsonFactory;

  private final EIndexMappingsBuilder mappingsBuilder;

  private final IndexMessageSender sender;

  public EIndexService(SpiServer server, JsonFactory jsonFactory, IndexMessageSender sender) {
    this.server = server;
    this.jsonFactory = jsonFactory;
    this.sender = sender;
    this.mappingsBuilder = new EIndexMappingsBuilder(jsonFactory);
  }

  public boolean indexExists(String indexName) throws IOException {
    return sender.indexExists(indexName);
  }

  public void dropIndex(String indexName) throws IOException {
    sender.indexDelete(indexName);
  }

  public void createIndex(String indexName, String alias) throws IOException {

    String resourcePath = "/index-mapping/" + indexName +".mapping.json";

    String rawJsonMapping = readResource(resourcePath);
    if (rawJsonMapping == null) {
      throw new IllegalArgumentException("No resource "+resourcePath+" found in classPath");
    }

    if (!createIndexWithMapping(false, indexName, alias, rawJsonMapping)) {
      throw new IllegalArgumentException("Index " + indexName + " not created as it already exists?");
    }
  }

  private String readResource(String mappingResource) {

    InputStream is = this.getClass().getResourceAsStream(mappingResource);
    if (is == null) {
      return null;
    }

    try {
      InputStreamReader reader = new InputStreamReader(is);
      LineNumberReader lineReader = new LineNumberReader(reader);

      StringBuilder buffer = new StringBuilder(300);
      String line;
      while ((line = lineReader.readLine()) != null) {
        buffer.append(line);
      }

      return buffer.toString();

    } catch (IOException e) {
      throw new PersistenceIOException(e);
    }
  }

  public boolean createIndexWithMapping(boolean dropCreate, String indexName, String alias, String jsonMapping) throws IOException {

    if (indexExists(indexName)) {
      if (!dropCreate) {
        logger.debug("index {} already exists", indexName);
        return false;
      }
      logger.debug("drop index {}", indexName);
      dropIndex(indexName);
    }
    logger.debug("create index {}", indexName);
    sender.indexCreate(indexName, jsonMapping);

    if (alias != null) {
      String aliasJson = asJson(new AliasChanges().add(indexName, alias));
      logger.debug("add alias {} for index {}", alias, indexName);
      sender.indexAlias(aliasJson);
    }
    return true;
  }

  private String asJson(AliasChanges aliasChanges) throws IOException {

    StringWriter writer = new StringWriter();
    JsonGenerator gen  = jsonFactory.createGenerator(writer);

    aliasChanges.writeJson(gen);
    gen.flush();
    return writer.toString();
  }

  public void createIndexes() throws IOException {

    DocStoreConfig docStoreConfig = server.getServerConfig().getDocStoreConfig();

    boolean dropCreate = docStoreConfig.isDropCreate();

    for (BeanType<?> beanType : server.getBeanTypes()) {
      if (beanType.isDocStoreMapped()) {
        createIndex(dropCreate, beanType);
      }
    }
  }

  private void createIndex(boolean dropCreate, BeanType<?> beanType) throws IOException {

    String mappingJson = mappingsBuilder.createMappingJson(beanType);
    String alias = beanType.docStore().getIndexName();
    String indexName = alias+"_v1";

    writeMappingFile(indexName, mappingJson);

    createIndexWithMapping(dropCreate, indexName, alias, mappingJson);
  }

  /**
   * Write the mapping to a file.
   */
  private void writeMappingFile(String indexName, String mappingJson) {

    try {
      File dir = new File("elastic-index");
      if (!dir.mkdirs()) {
        logger.warn("Unable to make directories for {}", dir.getAbsolutePath());
      }
      File file = new File(dir, indexName + ".mapping.json");
      FileWriter writer = new FileWriter(file);
      writer.write(mappingJson);
      writer.flush();
      writer.close();
    } catch (IOException e) {
      logger.error("Error trying to write index mapping", e);
    }
  }

}

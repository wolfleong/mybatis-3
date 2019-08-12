/**
 * Copyright 2009-2019 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.ResultMapResolver;
import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLMapperBuilder extends BaseBuilder {
  /**
   * 基于Java XPath解析器
   */
  private final XPathParser parser;

  /**
   * Mapper构造器助手
   */
  private final MapperBuilderAssistant builderAssistant;
  /**
   * sql块集合, 如: <sql id="abc">... </sql>
   */
  private final Map<String, XNode> sqlFragments;
  /**
   * 资源引用地址, 可能是resource , 可能是url
   */
  private final String resource;

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(reader, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  @Deprecated
  public XMLMapperBuilder(Reader reader, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(reader, true, configuration.getVariables(), new XMLMapperEntityResolver()),
      configuration, resource, sqlFragments);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments, String namespace) {
    this(inputStream, configuration, resource, sqlFragments);
    this.builderAssistant.setCurrentNamespace(namespace);
  }

  public XMLMapperBuilder(InputStream inputStream, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    this(new XPathParser(inputStream, true, configuration.getVariables(), new XMLMapperEntityResolver()),
      configuration, resource, sqlFragments);
  }

  private XMLMapperBuilder(XPathParser parser, Configuration configuration, String resource, Map<String, XNode> sqlFragments) {
    super(configuration);
    //创建Mapper构造器助手
    this.builderAssistant = new MapperBuilderAssistant(configuration, resource);
    this.parser = parser;
    this.sqlFragments = sqlFragments;
    this.resource = resource;
  }

  /**
   * 解析方法
   */
  public void parse() {
    //判断是否已经加载过, 如果没有加载, 开始解析
    if (!configuration.isResourceLoaded(resource)) {
      //解析xml的 mapper 节点
      configurationElement(parser.evalNode("/mapper"));
      //记录xml资源文件, 表示已经加载过当前resource
      configuration.addLoadedResource(resource);
      //绑定当前 Mapper接口
      bindMapperForNamespace();
    }
    //迭代重新解析未成功的ResultMap
    parsePendingResultMaps();
    //迭代重新解析未成功的cacheRef
    parsePendingCacheRefs();
    //迭代重新解析未成功的statements
    parsePendingStatements();
  }

  public XNode getSqlFragment(String refid) {
    return sqlFragments.get(refid);
  }

  /**
   * 解析mapper节点
   */
  private void configurationElement(XNode context) {
    try {
      //获取namespace, 注意namespace是不能为为null或者空串
      String namespace = context.getStringAttribute("namespace");
      if (namespace == null || namespace.equals("")) {
        throw new BuilderException("Mapper's namespace cannot be empty");
      }
      //设置namespace到解析助手中
      builderAssistant.setCurrentNamespace(namespace);
      //解析 <cache-ref> 节点
      cacheRefElement(context.evalNode("cache-ref"));
      //解析 <cache> 节点
      cacheElement(context.evalNode("cache"));
      //解析 <parameterMap>, 已经废弃! 不做解读
      parameterMapElement(context.evalNodes("/mapper/parameterMap"));
      //解析 <resultMap>
      resultMapElements(context.evalNodes("/mapper/resultMap"));
      //解析 <sql>
      sqlElement(context.evalNodes("/mapper/sql"));
      //解析 <select|insert|update|delete>
      buildStatementFromContext(context.evalNodes("select|insert|update|delete"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing Mapper XML. The XML location is '" + resource + "'. Cause: " + e, e);
    }
  }

  /**
   * 解析对数据库操作的sql, 如select,insert, update, delete
   */
  private void buildStatementFromContext(List<XNode> list) {
    //处理databaseId不为null的情况
    if (configuration.getDatabaseId() != null) {
      buildStatementFromContext(list, configuration.getDatabaseId());
    }
    buildStatementFromContext(list, null);
    // 上面两块代码，可以简写成 buildStatementFromContext(list, configuration.getDatabaseId());哈哈 可以可以
  }

  private void buildStatementFromContext(List<XNode> list, String requiredDatabaseId) {
    for (XNode context : list) {
      //每个节点创建 XMLStatementBuilder 对象
      final XMLStatementBuilder statementParser = new XMLStatementBuilder(configuration, builderAssistant, context, requiredDatabaseId);
      try {
        //解析
        statementParser.parseStatementNode();
      } catch (IncompleteElementException e) {
        //解析失败, 记录起来
        configuration.addIncompleteStatement(statementParser);
      }
    }
  }

  private void parsePendingResultMaps() {
    //获取未解析成功的 ResultMapResolver
    Collection<ResultMapResolver> incompleteResultMaps = configuration.getIncompleteResultMaps();
    synchronized (incompleteResultMaps) {
      Iterator<ResultMapResolver> iter = incompleteResultMaps.iterator();
      //如果一直都有值, while就一直不退出
      while (iter.hasNext()) {
        try {
          //迭代解析
          iter.next().resolve();
          //解析成功, 则移除
          iter.remove();
        } catch (IncompleteElementException e) {
          // ResultMap is still missing a resource...
          //如果报错, 那就是依然有资源缺少, 忽略
        }
      }
    }
  }

  private void parsePendingCacheRefs() {
    Collection<CacheRefResolver> incompleteCacheRefs = configuration.getIncompleteCacheRefs();
    synchronized (incompleteCacheRefs) {
      Iterator<CacheRefResolver> iter = incompleteCacheRefs.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolveCacheRef();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Cache ref is still missing a resource...
        }
      }
    }
  }

  private void parsePendingStatements() {
    Collection<XMLStatementBuilder> incompleteStatements = configuration.getIncompleteStatements();
    synchronized (incompleteStatements) {
      Iterator<XMLStatementBuilder> iter = incompleteStatements.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().parseStatementNode();
          iter.remove();
        } catch (IncompleteElementException e) {
          // Statement is still missing a resource...
        }
      }
    }
  }

  /**
   * 概思路是:
   * 获取缓存引用的namespace, 先记录下缓存引用的指向, 再创建引用解析器, 用引用解析器解析缓存引用.
   * 引用解析器是调用mapper构建助手来解析的, 在缓存对象映射中找, 找到就返回
   */
  private void cacheRefElement(XNode context) {
    if (context != null) {
      //记录缓存引用指向, 将当前 namespace 的缓存指向指定的 namespace
      configuration.addCacheRef(builderAssistant.getCurrentNamespace(), context.getStringAttribute("namespace"));
      //创建缓存引用解析器
      CacheRefResolver cacheRefResolver = new CacheRefResolver(builderAssistant, context.getStringAttribute("namespace"));
      try {
        //解析引用
        cacheRefResolver.resolveCacheRef();
      } catch (IncompleteElementException e) {
        //如果抛异常, 记录未解析完成的缓存引用
        configuration.addIncompleteCacheRef(cacheRefResolver);
      }
    }
  }

  private void cacheElement(XNode context) {
    if (context != null) {
      //获取缓存实现类, 如果没有, 则默认是 PERPETUAL, 也就是不过期缓存
      String type = context.getStringAttribute("type", "PERPETUAL");
      //解析缓存类
      Class<? extends Cache> typeClass = typeAliasRegistry.resolveAlias(type);
      //获取缓存的清除方式, 默认是 LRU, 也就是最近使用不删除原则
      String eviction = context.getStringAttribute("eviction", "LRU");
      //解析清除方式的类
      Class<? extends Cache> evictionClass = typeAliasRegistry.resolveAlias(eviction);
      //获取缓存刷新时间, 也就是定时清除缓存的时间
      Long flushInterval = context.getLongAttribute("flushInterval");
      //设置缓存的大小, 基本所有的装饰类都有setSize这个方法
      Integer size = context.getIntAttribute("size");
      //获取缓存的对象是否可读写, 底层通过序列化反序列化来实现
      boolean readWrite = !context.getBooleanAttribute("readOnly", false);
      //获取缓存, 获取不到是否阻塞
      boolean blocking = context.getBooleanAttribute("blocking", false);
      //获取<cache>的配置
      Properties props = context.getChildrenAsProperties();
      //根据参数创建缓存对象
      builderAssistant.useNewCache(typeClass, evictionClass, flushInterval, size, readWrite, blocking, props);
    }
  }

  private void parameterMapElement(List<XNode> list) {
    for (XNode parameterMapNode : list) {
      String id = parameterMapNode.getStringAttribute("id");
      String type = parameterMapNode.getStringAttribute("type");
      Class<?> parameterClass = resolveClass(type);
      List<XNode> parameterNodes = parameterMapNode.evalNodes("parameter");
      List<ParameterMapping> parameterMappings = new ArrayList<>();
      for (XNode parameterNode : parameterNodes) {
        String property = parameterNode.getStringAttribute("property");
        String javaType = parameterNode.getStringAttribute("javaType");
        String jdbcType = parameterNode.getStringAttribute("jdbcType");
        String resultMap = parameterNode.getStringAttribute("resultMap");
        String mode = parameterNode.getStringAttribute("mode");
        String typeHandler = parameterNode.getStringAttribute("typeHandler");
        Integer numericScale = parameterNode.getIntAttribute("numericScale");
        ParameterMode modeEnum = resolveParameterMode(mode);
        Class<?> javaTypeClass = resolveClass(javaType);
        JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
        Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
        ParameterMapping parameterMapping = builderAssistant.buildParameterMapping(parameterClass, property, javaTypeClass, jdbcTypeEnum, resultMap, modeEnum, typeHandlerClass, numericScale);
        parameterMappings.add(parameterMapping);
      }
      builderAssistant.addParameterMap(id, parameterClass, parameterMappings);
    }
  }

  private void resultMapElements(List<XNode> list) throws Exception {
    for (XNode resultMapNode : list) {
      try {
        //处理单个<resultMap>
        resultMapElement(resultMapNode);
      } catch (IncompleteElementException e) {
        // ignore, it will be retried
      }
    }
  }

  /**
   * 解析 resultMap 节点
   */
  private ResultMap resultMapElement(XNode resultMapNode) throws Exception {
    return resultMapElement(resultMapNode, Collections.emptyList(), null);
  }

  /**
   * 解析 resultMap 节点, 以下四种节点都可能进来
   * <resultMap type="Blog"></resultMap>
   * <constructor></constructor>
   * <association javaType="Author"></association>
   * <collection ofType="Post" javaType="ArrayList"></collection>
   * <case resultType="DraftPost"></case>
   */
  private ResultMap resultMapElement(XNode resultMapNode, List<ResultMapping> additionalResultMappings, Class<?> enclosingType) throws Exception {
    ErrorContext.instance().activity("processing " + resultMapNode.getValueBasedIdentifier());
    //获取 type 属性, 配置type有4个参数: type, ofType, resultType, javaType
    //<collection>中有两个javaType和ofType, java是指定列表字段的类型, ofType是指列表中元素的类型
    String type = resultMapNode.getStringAttribute("type",
      resultMapNode.getStringAttribute("ofType",
        resultMapNode.getStringAttribute("resultType",
          resultMapNode.getStringAttribute("javaType"))));
    //解析class
    Class<?> typeClass = resolveClass(type);
    if (typeClass == null) {
      typeClass = inheritEnclosingType(resultMapNode, enclosingType);
    }
    Discriminator discriminator = null;
    List<ResultMapping> resultMappings = new ArrayList<>();
    resultMappings.addAll(additionalResultMappings);
    List<XNode> resultChildren = resultMapNode.getChildren();
    //遍历子节点
    for (XNode resultChild : resultChildren) {
      //处理<constructor>节点
      if ("constructor".equals(resultChild.getName())) {
        //解析构造器
        processConstructorElement(resultChild, typeClass, resultMappings);
        //处理 <discriminator> 节点
      } else if ("discriminator".equals(resultChild.getName())) {
        //鉴别器, 相当于java的switch
        discriminator = processDiscriminatorElement(resultChild, typeClass, resultMappings);
      } else {
        List<ResultFlag> flags = new ArrayList<>();
        //如果有id节点, 则添加到 flags 中
        if ("id".equals(resultChild.getName())) {
          flags.add(ResultFlag.ID);
        }
        //resultMapping节点
        //<result>, <association>, <collection> 这三种节点, 用buildResultMappingFromContext
        resultMappings.add(buildResultMappingFromContext(resultChild, typeClass, flags));
      }
    }
    //获取id属性, 如果没有id属性, 则用当前节点的信息创建一个唯一id
    String id = resultMapNode.getStringAttribute("id",
      resultMapNode.getValueBasedIdentifier());
    //获取 extend 属性
    String extend = resultMapNode.getStringAttribute("extends");

    //获取autoMapping 属性
    Boolean autoMapping = resultMapNode.getBooleanAttribute("autoMapping");
    //创建 ResultMap 解析器
    ResultMapResolver resultMapResolver = new ResultMapResolver(builderAssistant, id, typeClass, extend, discriminator, resultMappings, autoMapping);
    try {
      //解析
      return resultMapResolver.resolve();
    } catch (IncompleteElementException e) {
      //如果解析失败, 表示信息不全 , 记录到configuration, 后续再解析
      configuration.addIncompleteResultMap(resultMapResolver);
      throw e;
    }
  }

  protected Class<?> inheritEnclosingType(XNode resultMapNode, Class<?> enclosingType) {
    if ("association".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
      String property = resultMapNode.getStringAttribute("property");
      if (property != null && enclosingType != null) {
        MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
        return metaResultType.getSetterType(property);
      }
    } else if ("case".equals(resultMapNode.getName()) && resultMapNode.getStringAttribute("resultMap") == null) {
      return enclosingType;
    }
    return null;
  }

  private void processConstructorElement(XNode resultChild, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
    List<XNode> argChildren = resultChild.getChildren();
    //遍历子节点, 一个<arg>相当于一个resultMapping
    for (XNode argChild : argChildren) {
      //获得 ResultFlag 集合
      List<ResultFlag> flags = new ArrayList<>();
      flags.add(ResultFlag.CONSTRUCTOR);
      //如果有id标识, 则加一个id标识
      if ("idArg".equals(argChild.getName())) {
        flags.add(ResultFlag.ID);
      }
      //将 <constructor> 的子节点映射为 resultMapping
      resultMappings.add(buildResultMappingFromContext(argChild, resultType, flags));
    }
  }

  private Discriminator processDiscriminatorElement(XNode context, Class<?> resultType, List<ResultMapping> resultMappings) throws Exception {
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    String typeHandler = context.getStringAttribute("typeHandler");
    //解析javaType
    Class<?> javaTypeClass = resolveClass(javaType);
    //解析 TypeHandler
    Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
    //解析jdbcType
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    Map<String, String> discriminatorMap = new HashMap<>();
    //遍历<case>节点, 一个<case>相当于一个resultMap
    for (XNode caseChild : context.getChildren()) {
      //获取value的值
      String value = caseChild.getStringAttribute("value");
      //获取case指定的resultMap引用或者创建并获取一个嵌套resultMap的id
      String resultMap = caseChild.getStringAttribute("resultMap", processNestedResultMappings(caseChild, resultMappings, resultType));
      //记录到鉴别器的映射
      discriminatorMap.put(value, resultMap);
    }
    //用助手构建 Discriminator
    return builderAssistant.buildDiscriminator(resultType, column, javaTypeClass, jdbcTypeEnum, typeHandlerClass, discriminatorMap);
  }

  /**
   * 解析sql节点
   */
  private void sqlElement(List<XNode> list) {
    //如果有配置dataBeseId, 则获取dataBaseId来处理
    if (configuration.getDatabaseId() != null) {
      sqlElement(list, configuration.getDatabaseId());
    }
    sqlElement(list, null);
  }

  private void sqlElement(List<XNode> list, String requiredDatabaseId) {
    for (XNode context : list) {
      //数据库标识
      String databaseId = context.getStringAttribute("databaseId");
      //sql片段的id
      String id = context.getStringAttribute("id");
      // 拼接完整的 id 属性, 格式为 `${namespace}.${id}`
      id = builderAssistant.applyCurrentNamespace(id, false);
      //检测当前的sql片段添加, 主要从两个方面处理, (dataBaseId是否匹配, sql片段是否已经存在)
      if (databaseIdMatchesCurrent(id, databaseId, requiredDatabaseId)) {
        //添加或替换sql片段
        sqlFragments.put(id, context);
      }
    }
  }

  /**
   * 判断dataBaseId是否匹配
   */
  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    //如果 requiredDatabaseId 不为空, 则必须匹配上
    if (requiredDatabaseId != null) {
      return requiredDatabaseId.equals(databaseId);
    }
    //如果requiredDatabaseId为null, 且databaseId不为null, 匹配不上
    if (databaseId != null) {
      return false;
    }
    //sql片段没有重复, 可以
    if (!this.sqlFragments.containsKey(id)) {
      return true;
    }
    //如果已经存在这个sql片段, 只有当原来的sql片段的databaseId为null时, 才可以加入或替换
    // skip this fragment if there is a previous one with a not null databaseId
    XNode context = this.sqlFragments.get(id);
    return context.getStringAttribute("databaseId") == null;
  }

  /**
   * 构建ResultMapping, 主要处理以下几种节点
   * resultMap/result
   * resultMap/association
   * resultMap/collection
   * resultMap/constructor/arg
   *
   */
  private ResultMapping buildResultMappingFromContext(XNode context, Class<?> resultType, List<ResultFlag> flags) throws Exception {
    String property;
    //如果是<constructor>标签下的
    if (flags.contains(ResultFlag.CONSTRUCTOR)) {
      //那 constructor/arg 中, 对应java字段的叫 name
      //如果<arg> 有name字段, 则按字段名匹配, 如果没有name则按顺序匹配
      property = context.getStringAttribute("name");
    } else {
      //如果是非 constructor 下, 对应java字段的是 property
      property = context.getStringAttribute("property");
    }
    //指定表的列
    String column = context.getStringAttribute("column");
    String javaType = context.getStringAttribute("javaType");
    String jdbcType = context.getStringAttribute("jdbcType");
    //关联查询会用到
    String nestedSelect = context.getStringAttribute("select");
    //指定映射结果集或嵌套结果集
    String nestedResultMap = context.getStringAttribute("resultMap",
      processNestedResultMappings(context, Collections.emptyList(), resultType));
    //指定非空列
    String notNullColumn = context.getStringAttribute("notNullColumn");
    //列前缀, 如果有多个表, 列名都一样, 就用前缀区分
    String columnPrefix = context.getStringAttribute("columnPrefix");
    //类型处理器
    String typeHandler = context.getStringAttribute("typeHandler");
    //结果集名称, 多结果集才会用上
    String resultSet = context.getStringAttribute("resultSet");
    //多结果集关联的外键
    String foreignColumn = context.getStringAttribute("foreignColumn");
    //是否懒加载
    boolean lazy = "lazy".equals(context.getStringAttribute("fetchType", configuration.isLazyLoadingEnabled() ? "lazy" : "eager"));
    //解析javaType类
    Class<?> javaTypeClass = resolveClass(javaType);
    //解析TypeHandler类
    Class<? extends TypeHandler<?>> typeHandlerClass = resolveClass(typeHandler);
    //解析指定JdbcType
    JdbcType jdbcTypeEnum = resolveJdbcType(jdbcType);
    return builderAssistant.buildResultMapping(resultType, property, column, javaTypeClass, jdbcTypeEnum, nestedSelect, nestedResultMap, notNullColumn, columnPrefix, typeHandlerClass, flags, resultSet, foreignColumn, lazy);
  }

  /**
   * 处理嵌套结果集映射
   */
  private String processNestedResultMappings(XNode context, List<ResultMapping> resultMappings, Class<?> enclosingType) throws Exception {
    //是 association 或  collection 或 case节点下, 且select 属性为null
    if ("association".equals(context.getName())
      || "collection".equals(context.getName())
      || "case".equals(context.getName())) {
      //select查询是不能嵌套的
      if (context.getStringAttribute("select") == null) {
        validateCollection(context, enclosingType);
        //创建一个resultMap
        ResultMap resultMap = resultMapElement(context, resultMappings, enclosingType);
        //返回resultMap的id
        return resultMap.getId();
      }
    }
    return null;
  }

  /**
   * 校验集合, 当节点为collection时, 属性在返回值中不存在, 然后又没指定JavaType, 则没办法确定Collection的实现类
   * 当是collection类型, 如果返回的类型中没有property这个属性, 则必须设置 javaType和resultMap中的一个
   * 当时来这个方法时, resultMap的属性肯定是null
   * todo wolfleong 为不明白为什么要这么校验, association为什么不用校验,
   * 没有指定resultMap, 没有javaType, 返回值的类也没有property属性的话, 没办法确定当前这个property的类型
   */
  protected void validateCollection(XNode context, Class<?> enclosingType) {
    //如果是collection, 但resultMap为null, javaType为null
    if ("collection".equals(context.getName()) && context.getStringAttribute("resultMap") == null
      && context.getStringAttribute("javaType") == null) {
      MetaClass metaResultType = MetaClass.forClass(enclosingType, configuration.getReflectorFactory());
      String property = context.getStringAttribute("property");
      //如果找不到 property的设置器, 表示 当前的ResultMap的返回类型没有property这个字段
      if (!metaResultType.hasSetter(property)) {
        throw new BuilderException(
          "Ambiguous collection type for property '" + property + "'. You must specify 'javaType' or 'resultMap'.");
      }
    }
  }

  private void bindMapperForNamespace() {
    //获取xml的namespace
    String namespace = builderAssistant.getCurrentNamespace();
    if (namespace != null) {
      Class<?> boundType = null;
      try {
        //加载这个类是否存在
        boundType = Resources.classForName(namespace);
      } catch (ClassNotFoundException e) {
        //类不存在没关系, 不是必须的
        //ignore, bound type is not required
      }
      if (boundType != null) {
        //类存在, 判断有没有加载过
        if (!configuration.hasMapper(boundType)) {
          // Spring may not know the real resource name so we set a flag
          // to prevent loading again this resource from the mapper interface
          // look at MapperAnnotationBuilder#loadXmlResource
          //标记 namespace的xml文件 已经添加，避免 MapperAnnotationBuilder#loadXmlResource(...) 重复加载
          configuration.addLoadedResource("namespace:" + namespace);
          //注册Mapper接口
          configuration.addMapper(boundType);
        }
      }
    }
  }

}

/**
 *    Copyright 2009-2019 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import java.util.List;
import java.util.Locale;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class XMLStatementBuilder extends BaseBuilder {

  /**
   * Mapper构建助手
   */
  private final MapperBuilderAssistant builderAssistant;
  /**
   * 当前的XNode节点
   */
  private final XNode context;
  /**
   * 需要的databaseId
   */
  private final String requiredDatabaseId;

  public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context) {
    this(configuration, builderAssistant, context, null);
  }

  public XMLStatementBuilder(Configuration configuration, MapperBuilderAssistant builderAssistant, XNode context, String databaseId) {
    super(configuration);
    this.builderAssistant = builderAssistant;
    this.context = context;
    this.requiredDatabaseId = databaseId;
  }

  public void parseStatementNode() {
    //获取节点的id
    String id = context.getStringAttribute("id");
    //获取dataBaseId
    String databaseId = context.getStringAttribute("databaseId");

    //如果databaseId匹配不上
    if (!databaseIdMatchesCurrent(id, databaseId, this.requiredDatabaseId)) {
      return;
    }

    //获取node节点的名称
    String nodeName = context.getNode().getNodeName();
    //根据节点名称, 确定sql的类型
    SqlCommandType sqlCommandType = SqlCommandType.valueOf(nodeName.toUpperCase(Locale.ENGLISH));
    //判定是否是查询类型
    boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
    //将其设置为 true 后，只要语句被调用，都会导致本地缓存和二级缓存被清空，默认值：false
    boolean flushCache = context.getBooleanAttribute("flushCache", !isSelect);
    //将其设置为 true 后，将会导致本条语句的结果被二级缓存缓存起来，默认值：对 select 元素为 true
    boolean useCache = context.getBooleanAttribute("useCache", isSelect);
    //这个设置仅针对嵌套结果 select 语句适用：如果为 true，就是假设包含了嵌套结果集或是分组，
    // 这样的话当返回一个主结果行的时候，就不会发生有对前面结果集的引用的情况。
    // 这就使得在获取嵌套的结果集的时候不至于导致内存不够用。默认值：false
    //todo wolfleong 不懂这个参数的作用 resultOrdered
    boolean resultOrdered = context.getBooleanAttribute("resultOrdered", false);

    //在解析之前, 替换include标签为引用的sql标签内容
    // Include Fragments before parsing
    XMLIncludeTransformer includeParser = new XMLIncludeTransformer(configuration, builderAssistant);
    includeParser.applyIncludes(context.getNode());

    //获取parameterType
    String parameterType = context.getStringAttribute("parameterType");
    //解析parameterType的类
    Class<?> parameterTypeClass = resolveClass(parameterType);

    //获取 lang 配置
    String lang = context.getStringAttribute("lang");
    //获取 LanguageDriver 对象, 默认没有设置的话是 XMLLanguageDriver
    LanguageDriver langDriver = getLanguageDriver(lang);

    //解析<selectKey>标签
    //<selectKey keyProperty="id" resultType="int" order="BEFORE">
    //    select CAST(RANDOM()*1000000 as INTEGER) a from SYSIBM.SYSDUMMY1
    //  </selectKey>
    // Parse selectKey after includes and remove them.
    processSelectKeyNodes(id, parameterTypeClass, langDriver);

    // Parse the SQL (pre: <selectKey> and <include> were parsed and removed)
    KeyGenerator keyGenerator;
    //接后缀
    String keyStatementId = id + SelectKeyGenerator.SELECT_KEY_SUFFIX;
    //拼接namespace
    keyStatementId = builderAssistant.applyCurrentNamespace(keyStatementId, true);
    //如果有配置<selectKey>节点, 就肯定会生成keyGenerator
    if (configuration.hasKeyGenerator(keyStatementId)) {
      //获取生成的keyGenerator
      keyGenerator = configuration.getKeyGenerator(keyStatementId);
    } else {
      //如果配置了 useGeneratedKeys 为true, 则用 Jdbc3KeyGenerator, 否则用 NoKeyGenerator 作为 keyGenerator
      // useGeneratedKeys 的默认值是 配置了 useGeneratedKeys 且是 insert 类型的sql才为true
      //todo wolfleong 到时要看清楚, 是仅对insert和update有用呢, 还是只对insert有用
      keyGenerator = context.getBooleanAttribute("useGeneratedKeys",
          configuration.isUseGeneratedKeys() && SqlCommandType.INSERT.equals(sqlCommandType))
          ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
    }
    //创建sqlSource
    SqlSource sqlSource = langDriver.createSqlSource(configuration, context, parameterTypeClass);
    //获取 statementType , 默认是 StatementType.PREPARED
    StatementType statementType = StatementType.valueOf(context.getStringAttribute("statementType", StatementType.PREPARED.toString()));
    //这是一个给驱动的提示，尝试让驱动程序每次批量返回的结果行数和这个设置值相等。 默认值为未设置（unset）
    Integer fetchSize = context.getIntAttribute("fetchSize");
    //获取超时时间, 驱动程序等待数据库返回请求结果的秒数
    Integer timeout = context.getIntAttribute("timeout");
    //参数废弃
    String parameterMap = context.getStringAttribute("parameterMap");
    //结果类型
    String resultType = context.getStringAttribute("resultType");
    //解析结果类
    Class<?> resultTypeClass = resolveClass(resultType);
    //获取指定的 resultMap
    String resultMap = context.getStringAttribute("resultMap");
    //获取 resultSetType
    String resultSetType = context.getStringAttribute("resultSetType");
    // 转换成 resultSetType 枚举
    ResultSetType resultSetTypeEnum = resolveResultSetType(resultSetType);
    //（仅对 insert 和 update 有用）唯一标记一个属性，
    // MyBatis 会通过 getGeneratedKeys 的返回值或者通过 insert 语句的 selectKey 子元素设置它的键值，默认值：未设置（unset）。
    // 如果希望得到多个生成的列，也可以是逗号分隔的属性名称列表。
    String keyProperty = context.getStringAttribute("keyProperty");
    // 获取配置 keyColumn
    String keyColumn = context.getStringAttribute("keyColumn");
    // 属性为每个结果集指定一个名字，多个名字使用逗号隔开
    String resultSets = context.getStringAttribute("resultSets");
    //创建MappedStatement
    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
        fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
        resultSetTypeEnum, flushCache, useCache, resultOrdered,
        keyGenerator, keyProperty, keyColumn, databaseId, langDriver, resultSets);
  }

  /**
   * <selectKey keyProperty="id" resultType="int" order="BEFORE">
   *     select CAST(RANDOM()*1000000 as INTEGER) a from SYSIBM.SYSDUMMY1
   * </selectKey>
   */
  private void processSelectKeyNodes(String id, Class<?> parameterTypeClass, LanguageDriver langDriver) {
    //获取当前节点下的<selectKey>
    List<XNode> selectKeyNodes = context.evalNodes("selectKey");
    //如果databaseId不null, 指定databaseId调用
    if (configuration.getDatabaseId() != null) {
      parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, configuration.getDatabaseId());
    }
    //指定databaseId为null的调用
    parseSelectKeyNodes(id, selectKeyNodes, parameterTypeClass, langDriver, null);
    //解析完成后, 删除selectKey节点
    removeSelectKeyNodes(selectKeyNodes);
  }

  private void parseSelectKeyNodes(String parentId, List<XNode> list, Class<?> parameterTypeClass, LanguageDriver langDriver, String skRequiredDatabaseId) {
    for (XNode nodeToHandle : list) {
      //将当前sql执行节点的id拼接后缀, 如: findPersonById!selectKey
      //注意, id还没有拼接上namespace, 格式为 `${id}!selectKey`
      String id = parentId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
      //获取<selectKey>上的databaseId
      String databaseId = nodeToHandle.getStringAttribute("databaseId");
      //判断id是否匹配, 是否可以添加, 在这里可以看到, 即使有多个 <selectionKey /> 节点，但是最终只会有一个节点被解析，
      // 就是符合的 databaseId 对应的。因为不同的数据库实现不同，对于获取主键的方式也会不同
      if (databaseIdMatchesCurrent(id, databaseId, skRequiredDatabaseId)) {
        parseSelectKeyNode(id, nodeToHandle, parameterTypeClass, langDriver, databaseId);
      }
    }
  }

  /**
   * 解析<selectKey></selectKey>, 并生成对应的MappedStatement, 且生成对应KeyGenerator
   */
  private void parseSelectKeyNode(String id, XNode nodeToHandle, Class<?> parameterTypeClass, LanguageDriver langDriver, String databaseId) {
    //获取resultType字符串
    String resultType = nodeToHandle.getStringAttribute("resultType");
    //解析resultType
    Class<?> resultTypeClass = resolveClass(resultType);
    //获取statementType, 如果没有填, 默认为 StatementType.PREPARED
    StatementType statementType = StatementType.valueOf(nodeToHandle.getStringAttribute("statementType", StatementType.PREPARED.toString()));
    //获取 keyProperty
    String keyProperty = nodeToHandle.getStringAttribute("keyProperty");
    //获取keyColumn
    String keyColumn = nodeToHandle.getStringAttribute("keyColumn");
    //	这可以被设置为 BEFORE 或 AFTER。如果设置为 BEFORE，那么它会首先生成主键，设置 keyProperty 然后执行插入语句。
    //	如果设置为 AFTER，那么先执行插入语句，然后是 selectKey 中的语句 - 这和 Oracle 数据库的行为相似，在插入语句内部可能有嵌入索引调用。
    boolean executeBefore = "BEFORE".equals(nodeToHandle.getStringAttribute("order", "AFTER"));

    //defaults
    boolean useCache = false;
    boolean resultOrdered = false;
    //默认是NoKeyGenerator
    KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
    Integer fetchSize = null;
    Integer timeout = null;
    boolean flushCache = false;
    String parameterMap = null;
    String resultMap = null;
    ResultSetType resultSetTypeEnum = null;

    //将当前 <selectKey>的节点, 创建SqlSource
    SqlSource sqlSource = langDriver.createSqlSource(configuration, nodeToHandle, parameterTypeClass);
    //定义当前sqlSource为 select类型
    SqlCommandType sqlCommandType = SqlCommandType.SELECT;
    //创建 MappedStatement 并添加到全局的Configuration中
    builderAssistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType,
        fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass,
        resultSetTypeEnum, flushCache, useCache, resultOrdered,
        keyGenerator, keyProperty, keyColumn, databaseId, langDriver, null);
    //拼接 namespace
    id = builderAssistant.applyCurrentNamespace(id, false);
    //获取mappedStatement
    MappedStatement keyStatement = configuration.getMappedStatement(id, false);
    //创建并添加一个keyGenerator
    configuration.addKeyGenerator(id, new SelectKeyGenerator(keyStatement, executeBefore));
  }

  /**
   * 解析完成selectKey完成后, 删除selectKey节点
   */
  private void removeSelectKeyNodes(List<XNode> selectKeyNodes) {
    for (XNode nodeToHandle : selectKeyNodes) {
      nodeToHandle.getParent().getNode().removeChild(nodeToHandle.getNode());
    }
  }

  private boolean databaseIdMatchesCurrent(String id, String databaseId, String requiredDatabaseId) {
    //如果  requiredDatabaseId 不为null, 不相等就匹配不上
    if (requiredDatabaseId != null) {
      return requiredDatabaseId.equals(databaseId);
    }
    //如果 requiredDatabaseId 为 null, 但 databaseId 不为空, 匹配不上
    if (databaseId != null) {
      return false;
    }
    //拼接完整的namespace的id
    id = builderAssistant.applyCurrentNamespace(id, false);
    //如果全局配置上没有这个statement, 则可以
    if (!this.configuration.hasStatement(id, false)) {
      return true;
    }
    //如果前一个 MappedStatement 不为null, 但它的databaseId是null的话, 可以复盖前面的
    // skip this statement if there is a previous one with a not null databaseId
    MappedStatement previous = this.configuration.getMappedStatement(id, false); // issue #2
    return previous.getDatabaseId() == null;
  }

  private LanguageDriver getLanguageDriver(String lang) {
    Class<? extends LanguageDriver> langClass = null;
    //如果lang不为null
    if (lang != null) {
      //解析lang class
      langClass = resolveClass(lang);
    }
    //通过langClass 获取 LanguageDriver
    return configuration.getLanguageDriver(langClass);
  }

}

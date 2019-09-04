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
package org.apache.ibatis.executor.resultset;

import java.lang.reflect.Constructor;
import java.sql.CallableStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.annotations.AutomapConstructor;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.cursor.defaults.DefaultCursor;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.ResultLoader;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.executor.result.DefaultResultHandler;
import org.apache.ibatis.executor.result.ResultMapException;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * ResultSetHandler 的默认实现类
 * @author Clinton Begin
 * @author Eduardo Macarron
 * @author Iwao AVE!
 * @author Kazuki Shimizu
 */
public class DefaultResultSetHandler implements ResultSetHandler {

  private static final Object DEFERRED = new Object();

  /**
   * 执行器
   */
  private final Executor executor;
  /**
   * 全局配置
   */
  private final Configuration configuration;
  /**
   * 执行的 MappedStatement
   */
  private final MappedStatement mappedStatement;
  /**
   * 分页参数
   */
  private final RowBounds rowBounds;
  /**
   * 参数处理器
   */
  private final ParameterHandler parameterHandler;
  /**
   * 结果处理器, 用户指定的用于处理结果的处理器, 一般情况下不设置
   */
  private final ResultHandler<?> resultHandler;
  /**
   * BoundSql
   */
  private final BoundSql boundSql;
  /**
   * TypeHandler注册器
   */
  private final TypeHandlerRegistry typeHandlerRegistry;
  /**
   * 对象工厂
   */
  private final ObjectFactory objectFactory;
  /**
   * 反射Reflector工厂
   */
  private final ReflectorFactory reflectorFactory;

  // nested resultmaps
  private final Map<CacheKey, Object> nestedResultObjects = new HashMap<>();
  private final Map<String, Object> ancestorObjects = new HashMap<>();
  private Object previousRowValue;

  // multiple resultsets
  private final Map<String, ResultMapping> nextResultMaps = new HashMap<>();
  private final Map<CacheKey, List<PendingRelation>> pendingRelations = new HashMap<>();

  /**
   * 自动映射的缓存, key 为 ResultMap#getId():columnPrefix
   */
  // Cached Automappings
  private final Map<String, List<UnMappedColumnAutoMapping>> autoMappingsCache = new HashMap<>();

  /**
   * 是否使用构造方法创建该结果对象
   */
  // temporary marking flag that indicate using constructor mapping (use field to reduce memory usage)
  private boolean useConstructorMappings;

  private static class PendingRelation {
    public MetaObject metaObject;
    public ResultMapping propertyMapping;
  }

  private static class UnMappedColumnAutoMapping {
    private final String column;
    private final String property;
    private final TypeHandler<?> typeHandler;
    private final boolean primitive;

    public UnMappedColumnAutoMapping(String column, String property, TypeHandler<?> typeHandler, boolean primitive) {
      this.column = column;
      this.property = property;
      this.typeHandler = typeHandler;
      this.primitive = primitive;
    }
  }

  public DefaultResultSetHandler(Executor executor, MappedStatement mappedStatement, ParameterHandler parameterHandler, ResultHandler<?> resultHandler, BoundSql boundSql,
                                 RowBounds rowBounds) {
    //初始化相关对象
    this.executor = executor;
    this.configuration = mappedStatement.getConfiguration();
    this.mappedStatement = mappedStatement;
    this.rowBounds = rowBounds;
    this.parameterHandler = parameterHandler;
    this.boundSql = boundSql;
    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.objectFactory = configuration.getObjectFactory();
    this.reflectorFactory = configuration.getReflectorFactory();
    this.resultHandler = resultHandler;
  }

  //
  // HANDLE OUTPUT PARAMETER
  //

  @Override
  public void handleOutputParameters(CallableStatement cs) throws SQLException {
    final Object parameterObject = parameterHandler.getParameterObject();
    final MetaObject metaParam = configuration.newMetaObject(parameterObject);
    final List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    for (int i = 0; i < parameterMappings.size(); i++) {
      final ParameterMapping parameterMapping = parameterMappings.get(i);
      if (parameterMapping.getMode() == ParameterMode.OUT || parameterMapping.getMode() == ParameterMode.INOUT) {
        if (ResultSet.class.equals(parameterMapping.getJavaType())) {
          handleRefCursorOutputParameter((ResultSet) cs.getObject(i + 1), parameterMapping, metaParam);
        } else {
          final TypeHandler<?> typeHandler = parameterMapping.getTypeHandler();
          metaParam.setValue(parameterMapping.getProperty(), typeHandler.getResult(cs, i + 1));
        }
      }
    }
  }

  private void handleRefCursorOutputParameter(ResultSet rs, ParameterMapping parameterMapping, MetaObject metaParam) throws SQLException {
    if (rs == null) {
      return;
    }
    try {
      final String resultMapId = parameterMapping.getResultMapId();
      final ResultMap resultMap = configuration.getResultMap(resultMapId);
      final ResultSetWrapper rsw = new ResultSetWrapper(rs, configuration);
      if (this.resultHandler == null) {
        final DefaultResultHandler resultHandler = new DefaultResultHandler(objectFactory);
        handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
        metaParam.setValue(parameterMapping.getProperty(), resultHandler.getResultList());
      } else {
        handleRowValues(rsw, resultMap, resultHandler, new RowBounds(), null);
      }
    } finally {
      // issue #228 (close resultsets)
      closeResultSet(rs);
    }
  }

  //
  // HANDLE RESULT SETS
  //

  /**
   * 处理多个 ResultSet
   *
   * - 第一种多结果集
   *  <select id="getAllInfo" statementType="CALLABLE" resultMap="account,bankwater" > {call pro_getinfo()} </select>
   *
   * - 第二种多结果集
   * <select id="selectBlog" resultSets="blogs,authors" resultMap="blogResult" statementType="CALLABLE">
   *   {call getBlogsAndAuthors(#{id,jdbcType=INTEGER,mode=IN})}
   * </select>
   *
   * <resultMap id="blogResult" type="Blog">
   *    <id property="id" column="id" />
   *    <result property="title" column="title"/>
   *    <association property="author" javaType="Author" resultSet="authors" column="author_id" foreignColumn="id">
   *      <id property="id" column="id"/>
   *      <result property="username" column="username"/>
   *      <result property="password" column="password"/>
   *    </association>
   *  </resultMap>
   */
  @Override
  public List<Object> handleResultSets(Statement stmt) throws SQLException {
    //记录日志参数, 如果把错则可以打印出来
    ErrorContext.instance().activity("handling results").object(mappedStatement.getId());

    //多 ResultSet 的结果集合, 每个 ResultSet 对应一个 Object 对象, 而实际上每个 Object 是 List<Object> 对象
    //多 ResultSet 只有存储过程才会出现, 一般只有一个
    final List<Object> multipleResults = new ArrayList<>();

    // resultSet 的索引
    int resultSetCount = 0;
    //获取第一个 ResultSet
    ResultSetWrapper rsw = getFirstResultSet(stmt);

    //获取当前查询的 ResultMap 列表, 这个列表就是配置的 ResultMap , 多个结果对应多个 ResultMap
    List<ResultMap> resultMaps = mappedStatement.getResultMaps();
    // ResultMap 的个数
    int resultMapCount = resultMaps.size();
    //校验 ResultSet 和 ResultMap 的个数
    validateResultMapsCount(rsw, resultMapCount);
    //while 里面的处理对应着上面的第一种多结果集
    //如果 ResultSetWrapper 不为 null , 且 ResultMap 的个数大于 ResultSetCount
    while (rsw != null && resultMapCount > resultSetCount) {
      //获取 ResultSet 对应的 ResultMap
      ResultMap resultMap = resultMaps.get(resultSetCount);
      //处理 ResultSet ，将结果添加到 multipleResults 中
      handleResultSet(rsw, resultMap, multipleResults, null);
      //获得下一个 ResultSet 对象，并封装成 ResultSetWrapper 对象
      rsw = getNextResultSet(stmt);
      //清理
      cleanUpAfterHandlingResultSet();
      //resultSet 索引加1
      resultSetCount++;
    }

    //获取多结果集对应的名称
    String[] resultSets = mappedStatement.getResultSets();
    //如果多结果集名称不为null
    if (resultSets != null) {
      while (rsw != null && resultSetCount < resultSets.length) {
        ResultMapping parentMapping = nextResultMaps.get(resultSets[resultSetCount]);
        if (parentMapping != null) {
          String nestedResultMapId = parentMapping.getNestedResultMapId();
          ResultMap resultMap = configuration.getResultMap(nestedResultMapId);
          handleResultSet(rsw, resultMap, null, parentMapping);
        }
        rsw = getNextResultSet(stmt);
        cleanUpAfterHandlingResultSet();
        resultSetCount++;
      }
    }

    return collapseSingleResultList(multipleResults);
  }

  @Override
  public <E> Cursor<E> handleCursorResultSets(Statement stmt) throws SQLException {
    ErrorContext.instance().activity("handling cursor results").object(mappedStatement.getId());

    ResultSetWrapper rsw = getFirstResultSet(stmt);

    List<ResultMap> resultMaps = mappedStatement.getResultMaps();

    int resultMapCount = resultMaps.size();
    validateResultMapsCount(rsw, resultMapCount);
    if (resultMapCount != 1) {
      throw new ExecutorException("Cursor results cannot be mapped to multiple resultMaps");
    }

    ResultMap resultMap = resultMaps.get(0);
    return new DefaultCursor<>(this, resultMap, rsw, rowBounds);
  }

  /**
   * 获取第一个 ResultSet , getResultSet 和 updateCount 都是获取执行结果, 而 getMoreResults 表示是否还有 ResultSet.
   *
   * 当 rs 为 null 时, 有两种情况
   * - 如果当前的结果可能是真没结果了, 那么 getMoreResultSet 为 false, 而 getUpdateCount 为 -1
   * - 当前结果是 updateCount , 则 getMoreResultSet 为 false, 而 updateCount > -1 , 所以通过 getUpdateCount 可以将当前结果取出
   *
   */
  private ResultSetWrapper getFirstResultSet(Statement stmt) throws SQLException {
    //获取 ResultSet, 当前结果为 ResultSet对象或 null如果结果是更新计数或没有更多的结果
    ResultSet rs = stmt.getResultSet();
    //如果没找到, 当找到了就直接退出
    while (rs == null) {
      //判断是否有更多resultSet, 如果有则往下找, 直接找到第一个为
      // move forward to get the first resultset in case the driver
      // doesn't return the resultset as the first result (HSQLDB 2.1)
      if (stmt.getMoreResults()) {
        rs = stmt.getResultSet();
      } else {
        //当 stmt.getMoreResults() == false && stmt.getUpdateCount() == -1 时, 就真的没有resultSet了
        //没找到, 且当前执行语句不是更新, 则表明真的没有 ResultSet 了, 直接退出循环
        if (stmt.getUpdateCount() == -1) {
          // no more results. Must be no resultset
          break;
        }
      }
    }
    //如果找到则将 resultSet 封成 ResultSetWrapper, 否则返回 null
    return rs != null ? new ResultSetWrapper(rs, configuration) : null;
  }

  /**
   * 获取下一个 ResultSet, 并封装成 ResultSetWrapper
   * todo 这里不需要处理 updateCount 这种类型吗
   */
  private ResultSetWrapper getNextResultSet(Statement stmt) {
    // Making this method tolerant of bad JDBC drivers
    try {
      //如果数据库是支持多 ResultSet 的
      if (stmt.getConnection().getMetaData().supportsMultipleResultSets()) {
        //确定是否还有多的结果集
        // Crazy Standard JDBC way of determining if there are more results
        if (!(!stmt.getMoreResults() && stmt.getUpdateCount() == -1)) {
          //获取结果集
          ResultSet rs = stmt.getResultSet();
          //如果为null, 则继续找
          if (rs == null) {
            return getNextResultSet(stmt);
          } else {
            //不为null, 则直接返回包装的 ResultSetWrapper
            return new ResultSetWrapper(rs, configuration);
          }
        }
      }
    } catch (Exception e) {
      // Intentionally ignored.
    }
    //没找到直接返回null
    return null;
  }

  /**
   * 关闭 ResultSet
   */
  private void closeResultSet(ResultSet rs) {
    try {
      if (rs != null) {
        rs.close();
      }
    } catch (SQLException e) {
      // ignore
    }
  }

  private void cleanUpAfterHandlingResultSet() {
    nestedResultObjects.clear();
  }

  /**
   * 校验 ResultSet 和 ResultMap , 如果有 ResultSet , 则 resultMap 的个数至少要有一个
   */
  private void validateResultMapsCount(ResultSetWrapper rsw, int resultMapCount) {
    if (rsw != null && resultMapCount < 1) {
      throw new ExecutorException("A query was run and no Result Maps were found for the Mapped Statement '" + mappedStatement.getId()
          + "'.  It's likely that neither a Result Type nor a Result Map was specified.");
    }
  }

  /**
   * 处理单个 ResultSet
   */
  private void handleResultSet(ResultSetWrapper rsw, ResultMap resultMap, List<Object> multipleResults, ResultMapping parentMapping) throws SQLException {
    try {
      if (parentMapping != null) {
        handleRowValues(rsw, resultMap, null, RowBounds.DEFAULT, parentMapping);
        //第一次进来, parentMapping 肯定为 null
      } else {
        //如果没有自定义的 ResultHandler , 则创建默认的 DefaultResultHandler 对象
        if (resultHandler == null) {
          //创建默认的 DefaultResultHandler
          DefaultResultHandler defaultResultHandler = new DefaultResultHandler(objectFactory);
          //处理 ResultSet 返回的每一行 row
          handleRowValues(rsw, resultMap, defaultResultHandler, rowBounds, null);
          //添加 DefaultResultHandler 的处理结果到 multipleResults 中
          multipleResults.add(defaultResultHandler.getResultList());
        } else {
          //处理 ResultSet 返回的每一行 Row
          handleRowValues(rsw, resultMap, resultHandler, rowBounds, null);
        }
      }
    } finally {
      //关闭 ResultSet 对象
      // issue #228 (close resultsets)
      closeResultSet(rsw.getResultSet());
    }
  }

  @SuppressWarnings("unchecked")
  private List<Object> collapseSingleResultList(List<Object> multipleResults) {
    return multipleResults.size() == 1 ? (List<Object>) multipleResults.get(0) : multipleResults;
  }

  //
  // HANDLE ROWS FOR SIMPLE RESULTMAP
  //

  public void handleRowValues(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    //处理嵌套映射的情况
    if (resultMap.hasNestedResultMaps()) {
      //嵌套结果集, 确定没有分页
      ensureNoRowBounds();
      //嵌套结果集, 不使用自定义的 ResultHandler
      checkResultHandler();
      // 处理嵌套映射的结果
      handleRowValuesForNestedResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
    } else {
      // 处理简单的映射结果
      handleRowValuesForSimpleResultMap(rsw, resultMap, resultHandler, rowBounds, parentMapping);
    }
  }

  /**
   * 有嵌套结果集, 确认没有使用 RowBounds
   */
  private void ensureNoRowBounds() {
    // safeRowBoundsEnabled 为true, 如果rowBounds不为空且有效的话, 则报异常
    if (configuration.isSafeRowBoundsEnabled() && rowBounds != null && (rowBounds.getLimit() < RowBounds.NO_ROW_LIMIT || rowBounds.getOffset() > RowBounds.NO_ROW_OFFSET)) {
      throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely constrained by RowBounds. "
          + "Use safeRowBoundsEnabled=false setting to bypass this check.");
    }
  }

  /**
   * 有嵌套结果集, 嵌套不使用自定义的 ResultHandler
   */
  protected void checkResultHandler() {
    //ResultHandler 不为null, 且 isSafeResultHandlerEnabled 为 true, 则不能使用自定义 ResultHandler, 但如果能确保结果是有序的(resultOrdered)则可以用
    if (resultHandler != null && configuration.isSafeResultHandlerEnabled() && !mappedStatement.isResultOrdered()) {
      throw new ExecutorException("Mapped Statements with nested result mappings cannot be safely used with a custom ResultHandler. "
          + "Use safeResultHandlerEnabled=false setting to bypass this check "
          + "or ensure your statement returns ordered data and set resultOrdered=true on it.");
    }
  }

  private void handleRowValuesForSimpleResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping)
      throws SQLException {
    // 创建 DefaultResultContext 对象
    DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
    //获取 ResultSet
    ResultSet resultSet = rsw.getResultSet();
    // 跳到 rowBounds 指定的开始位置
    skipRows(resultSet, rowBounds);
    //循环遍历处理, 如果要读取更多的记录且 resultSet 未关闭且 resultSet 还有数据
    while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
      // 根据该行记录以及 ResultMap.discriminator , 决定映射使用的 ResultMap 对象, 这里只用鉴别器中的 ResultMap, 如果没有鉴别器则直接返回原来的 ResultMap
      ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
      //根据最终确定的 ResultMap 对 ResultSet 中的该行记录进行映射，得到映射后的结果对象
      Object rowValue = getRowValue(rsw, discriminatedResultMap, null);
      //将映射创建的结果对象添加到 ResultHandler.resultList 中保存
      storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
    }
  }

  private void storeObject(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue, ResultMapping parentMapping, ResultSet rs) throws SQLException {
    if (parentMapping != null) {
      linkToParents(rs, parentMapping, rowValue);
    } else {
      //调用 ResultHandler 进行处理
      callResultHandler(resultHandler, resultContext, rowValue);
    }
  }

  @SuppressWarnings("unchecked" /* because ResultHandler<?> is always ResultHandler<Object>*/)
  private void callResultHandler(ResultHandler<?> resultHandler, DefaultResultContext<Object> resultContext, Object rowValue) {
    //设置结果对象到 ResultContext 中
    resultContext.nextResultObject(rowValue);
    //使用 ResultHandler 处理结果。
    //如果使用 DefaultResultHandler 实现类的情况，会将映射创建的结果对象添加到 ResultHandler.resultList 中保存
    ((ResultHandler<Object>) resultHandler).handleResult(resultContext);
  }

  /**
   * 判断是否应该处理更多的记录
   */
  private boolean shouldProcessMoreRows(ResultContext<?> context, RowBounds rowBounds) {
    //如果 context 未关闭, 且 context 的结果数小于指定的 limit
    return !context.isStopped() && context.getResultCount() < rowBounds.getLimit();
  }

  /**
   * 跳到指定的行
   */
  private void skipRows(ResultSet rs, RowBounds rowBounds) throws SQLException {
    //如果 ResultSet 类型不只向前移动的
    if (rs.getType() != ResultSet.TYPE_FORWARD_ONLY) {
      //如果偏移量不为 0
      if (rowBounds.getOffset() != RowBounds.NO_ROW_OFFSET) {
        //则直接定位到 offset
        rs.absolute(rowBounds.getOffset());
      }
    } else {
      //如果 ResultSet 是 TYPE_FORWARD_ONLY, 则只能往下读取
      for (int i = 0; i < rowBounds.getOffset(); i++) {
        //如果没有更多行了就直接退出
        if (!rs.next()) {
          break;
        }
      }
    }
  }

  //
  // GET VALUE FROM ROW FOR SIMPLE RESULT MAP
  //

  /**
   * 获取 ResultSet 一行的值对象
   */
  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
    //创建 ResultLoaderMap 对象
    final ResultLoaderMap lazyLoader = new ResultLoaderMap();
    //创建原始类型的结果对象和用构造器创建的结果对象都是已经查询过 ResultSet 的值了, 所以排序了原始类型外的,
    // 只要是用构造器创建的都是至少有一列有值的了, 这样来解析下面if的 foundValues
    //创建映射后的结果对象, 如果有延迟查询, 则返回一个代理的行对象
    Object rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
    //如果结果类型有 TypeHandler 则表明, 可以直接处理, 不需要按字段映射
    //如果 hasTypeHandlerForResultObject(rsw, resultMap.getType()) 返回 true ，意味着 rowValue 是基本类型，无需执行下列逻辑。
    if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
      //创建 MetaObject 对象，用于访问 rowValue 对象
      final MetaObject metaObject = configuration.newMetaObject(rowValue);
      //foundValues 代表，是否成功映射任一属性。若成功，则为 true ，若失败，则为 false
      boolean foundValues = this.useConstructorMappings;
      //判断是否开启自动映射功能
      if (shouldApplyAutomaticMappings(resultMap, false)) {
        //自动映射未明确的列
        foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
      }
      //映射 ResultMap 中明确映射的列
      foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
      //至此，当前 ResultSet 的该行记录的数据，已经完全映射到结果对象 rowValue 的对应属性种
      foundValues = lazyLoader.size() > 0 || foundValues;
      //如果没有成功映射任意属性，则置空 rowValue 对象。
      //当然，如果开启 `configuration.returnInstanceForEmptyRow` 属性，则不置空。默认情况下，该值为 false
      rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
    }
    return rowValue;
  }

  /**
   * 判断是否开启自动映射, 没有配置的话, 默认是 AutoMappingBehavior.PARTIAL
   */
  private boolean shouldApplyAutomaticMappings(ResultMap resultMap, boolean isNested) {
    //如果自动映射配置荐不为空, 则返回配置的值
    if (resultMap.getAutoMapping() != null) {
      return resultMap.getAutoMapping();
    } else {
      //如果有嵌套
      if (isNested) {
        //只在配置了 FULL 才是自动映射
        return AutoMappingBehavior.FULL == configuration.getAutoMappingBehavior();
      } else {
        //非嵌套, 则只要不是 NONE 都是自动映射
        return AutoMappingBehavior.NONE != configuration.getAutoMappingBehavior();
      }
    }
  }

  //
  // PROPERTY MAPPINGS
  //

  /**
   * 处理明确映射的列
   */
  private boolean applyPropertyMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
    //获取有匹配映射的列
    final List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
    boolean foundValues = false;
    //获取明确映射的列
    final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
    //遍历明确映射的列
    for (ResultMapping propertyMapping : propertyMappings) {
      //拼接列的前缀
      String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
      //如果列的嵌套结果集id不为空
      if (propertyMapping.getNestedResultMapId() != null) {
        //将当前列设置为 null
        // the user added a column attribute to a nested result map, ignore it
        column = null;
      }
      // 子查询的组合列
      if (propertyMapping.isCompositeResult()
          // mappedColumnNames中的列
          || (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH)))
          //指定 ResultSet 的列(存储过程)
          || propertyMapping.getResultSet() != null) {
        //获取指定字段的值
        Object value = getPropertyMappingValue(rsw.getResultSet(), metaObject, propertyMapping, lazyLoader, columnPrefix);
        //获取字段名
        // issue #541 make property optional
        final String property = propertyMapping.getProperty();
        //如果属性值为null, 则不处理当前映射
        if (property == null) {
          //todo wolfleong 不知道什么情况下才不填 property
          continue;
          //如果是多结果集的代表对象
        } else if (value == DEFERRED) {
          //设置有值
          foundValues = true;
          continue;
        }
        //如果最终值都不为null
        if (value != null) {
          //设置有值
          foundValues = true;
        }
        //值不为null, 或值为null但配置可以设置null且类型非原始类型
        if (value != null || (configuration.isCallSettersOnNulls() && !metaObject.getSetterType(property).isPrimitive())) {
          //设置结果值
          // gcode issue #377, call setter on nulls (value is not 'found')
          metaObject.setValue(property, value);
        }
      }
    }
    //返回是否有查询到值
    return foundValues;
  }

  /**
   * 获取指定字段的值
   */
  private Object getPropertyMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
    //如果嵌套子查询id 不为 null
    if (propertyMapping.getNestedQueryId() != null) {
      //获取嵌套子查询的值
      return getNestedQueryMappingValue(rs, metaResultObject, propertyMapping, lazyLoader, columnPrefix);
      //如果指定的 resultSet 不为空
    } else if (propertyMapping.getResultSet() != null) {
      addPendingChildRelation(rs, metaResultObject, propertyMapping);   // TODO is that OK?
      return DEFERRED;
    } else {
      //普通的列, 则直接获取对应的 TypeHandler
      final TypeHandler<?> typeHandler = propertyMapping.getTypeHandler();
      //列拼接字符串
      final String column = prependPrefix(propertyMapping.getColumn(), columnPrefix);
      //获取对应的值
      return typeHandler.getResult(rs, column);
    }
  }

  /**
   * 创建自动映射的列的列表
   */
  private List<UnMappedColumnAutoMapping> createAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
    //构建 mapKey
    final String mapKey = resultMap.getId() + ":" + columnPrefix;
    //从缓存中获取
    List<UnMappedColumnAutoMapping> autoMapping = autoMappingsCache.get(mapKey);
    //如果缓存中没有
    if (autoMapping == null) {
      //初始化列表
      autoMapping = new ArrayList<>();
      //从 ResultSetWrapper 中获取未匹配的列映射
      final List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
      //遍历未映射的列
      for (String columnName : unmappedColumnNames) {
        //获取列名
        String propertyName = columnName;
        //如果列前缀不为空
        if (columnPrefix != null && !columnPrefix.isEmpty()) {
          //如果列是以前缀开始的
          // When columnPrefix is specified,
          // ignore columns without the prefix.
          if (columnName.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
            //去掉列前缀的真正属性名
            propertyName = columnName.substring(columnPrefix.length());
          } else {
            //没有前缀则不处理, 如果前缀不为空的话, 默认所有的列都会有前缀的
            continue;
          }
        }
        //根据属性名找到真正的属性
        final String property = metaObject.findProperty(propertyName, configuration.isMapUnderscoreToCamelCase());
        //如果属性不为空, 且有 setter
        if (property != null && metaObject.hasSetter(property)) {
          //当前已经映射的列中已经包括这个属性
          if (resultMap.getMappedProperties().contains(property)) {
            //则不用处理
            continue;
          }
          //获取属性类型
          final Class<?> propertyType = metaObject.getSetterType(property);
          //根据属性类型和对应的JdbcType判断是否有 TypeHandler, 如果有
          if (typeHandlerRegistry.hasTypeHandler(propertyType, rsw.getJdbcType(columnName))) {
            //获取对应的 TypeHandler
            final TypeHandler<?> typeHandler = rsw.getTypeHandler(propertyType, columnName);
            //创建并添加一个 UnMappedColumnAutoMapping
            autoMapping.add(new UnMappedColumnAutoMapping(columnName, property, typeHandler, propertyType.isPrimitive()));
          } else {
            //处理不了的自动映射列, 不做处理
            //全局配置默认是 AutoMappingUnknownColumnBehavior.NONE
            //相当于不做任何处理
            configuration.getAutoMappingUnknownColumnBehavior()
                .doAction(mappedStatement, columnName, property, propertyType);
          }
        } else {
          //处理不了的自动映射列, 不做处理
          configuration.getAutoMappingUnknownColumnBehavior()
              .doAction(mappedStatement, columnName, (property != null) ? property : propertyName, null);
        }
      }
      //将当前的自动映射列表缓存起来
      autoMappingsCache.put(mapKey, autoMapping);
    }
    return autoMapping;
  }

  /**
   * 处理自动映射列
   */
  private boolean applyAutomaticMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String columnPrefix) throws SQLException {
    // 获得 UnMappedColumnAutoMapping 数组
    List<UnMappedColumnAutoMapping> autoMapping = createAutomaticMappings(rsw, resultMap, metaObject, columnPrefix);
    boolean foundValues = false;
    //如果自动映射列不为空
    if (!autoMapping.isEmpty()) {
      //遍历自动映射列
      for (UnMappedColumnAutoMapping mapping : autoMapping) {
        //用 TypeHandler 和 列表获取自动映射列的值
        final Object value = mapping.typeHandler.getResult(rsw.getResultSet(), mapping.column);
        //如果值不为空
        if (value != null) {
          //标记找到值
          foundValues = true;
        }
        //如果值不为null, 或该字段是非基本类型且设置可以设置null
        if (value != null || (configuration.isCallSettersOnNulls() && !mapping.primitive)) {
          //给结果对象设置对应列的值
          // gcode issue #377, call setter on nulls (value is not 'found')
          metaObject.setValue(mapping.property, value);
        }
      }
    }
    return foundValues;
  }

  // MULTIPLE RESULT SETS

  private void linkToParents(ResultSet rs, ResultMapping parentMapping, Object rowValue) throws SQLException {
    CacheKey parentKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getForeignColumn());
    List<PendingRelation> parents = pendingRelations.get(parentKey);
    if (parents != null) {
      for (PendingRelation parent : parents) {
        if (parent != null && rowValue != null) {
          linkObjects(parent.metaObject, parent.propertyMapping, rowValue);
        }
      }
    }
  }

  private void addPendingChildRelation(ResultSet rs, MetaObject metaResultObject, ResultMapping parentMapping) throws SQLException {
    CacheKey cacheKey = createKeyForMultipleResults(rs, parentMapping, parentMapping.getColumn(), parentMapping.getColumn());
    PendingRelation deferLoad = new PendingRelation();
    deferLoad.metaObject = metaResultObject;
    deferLoad.propertyMapping = parentMapping;
    List<PendingRelation> relations = pendingRelations.computeIfAbsent(cacheKey, k -> new ArrayList<>());
    // issue #255
    relations.add(deferLoad);
    ResultMapping previous = nextResultMaps.get(parentMapping.getResultSet());
    if (previous == null) {
      nextResultMaps.put(parentMapping.getResultSet(), parentMapping);
    } else {
      if (!previous.equals(parentMapping)) {
        throw new ExecutorException("Two different properties are mapped to the same resultSet");
      }
    }
  }

  private CacheKey createKeyForMultipleResults(ResultSet rs, ResultMapping resultMapping, String names, String columns) throws SQLException {
    CacheKey cacheKey = new CacheKey();
    cacheKey.update(resultMapping);
    if (columns != null && names != null) {
      String[] columnsArray = columns.split(",");
      String[] namesArray = names.split(",");
      for (int i = 0; i < columnsArray.length; i++) {
        Object value = rs.getString(columnsArray[i]);
        if (value != null) {
          cacheKey.update(namesArray[i]);
          cacheKey.update(value);
        }
      }
    }
    return cacheKey;
  }

  //
  // INSTANTIATION & CONSTRUCTOR MAPPING
  //

  /**
   * 创建映射后的结果对象, 主要做的是, 根据结果对象是否是有延迟加载的嵌套子查询从而创建代理对象
   */
  private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, ResultLoaderMap lazyLoader, String columnPrefix) throws SQLException {
    //设置是否使用构造器为false, 此处将重置
    this.useConstructorMappings = false; // reset previous mapping result
    //构造器参数类型
    final List<Class<?>> constructorArgTypes = new ArrayList<>();
    //构造器参数
    final List<Object> constructorArgs = new ArrayList<>();
    //创建结果对象, 结果可能为空
    Object resultObject = createResultObject(rsw, resultMap, constructorArgTypes, constructorArgs, columnPrefix);
    //如创建的结果对象不为null且非原始类型
    if (resultObject != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
      //获取属性映射列表
      final List<ResultMapping> propertyMappings = resultMap.getPropertyResultMappings();
      //遍历其他属性映射
      for (ResultMapping propertyMapping : propertyMappings) {
        //如果是嵌套子查询且是懒加载
        // issue gcode #109 && issue #149
        if (propertyMapping.getNestedQueryId() != null && propertyMapping.isLazy()) {
          //创建代理对象返回
          resultObject = configuration.getProxyFactory().createProxy(resultObject, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
          break;
        }
      }
    }
    //如果创建了结果对象, 且构造器参数类型为为null, 则代表是使用构造器参数来创建结果对象
    this.useConstructorMappings = resultObject != null && !constructorArgTypes.isEmpty(); // set current mapping result
    //返回结果对象
    return resultObject;
  }

  /**
   * 根据各种条件创建结果对象, 主要四种情况, 返回结果有可能为null
   * - 只有一列, 且有 TypeHandler 处理器, 直接从resultSet获取
   * - 根据构造器映射创建对应的结果对象
   * - 集合接口或Map,或有默认构造方法的, 直接创建结果实例
   * - 根据 ResultSet 结果自动匹配构造器创建结果实例
   *
   */
  private Object createResultObject(ResultSetWrapper rsw, ResultMap resultMap, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix)
      throws SQLException {
    //获取结果类型
    final Class<?> resultType = resultMap.getType();
    //创建结果类型的 MetaClass
    final MetaClass metaType = MetaClass.forClass(resultType, reflectorFactory);
    //获取构造器的字段映射 ResultMapping
    final List<ResultMapping> constructorMappings = resultMap.getConstructorResultMappings();
    //情况一: 判断结果类型是否有 TypeHandler, 有 TypeHandler 表明, ResultSet 中只有一列, 则意味着是基本类型, 直接创建对应结果对象
    if (hasTypeHandlerForResultObject(rsw, resultType)) {
      //如果有 TypeHandler, 则创建原始类型的结果对象
      return createPrimitiveResultObject(rsw, resultMap, columnPrefix);
      //情况二: 如果构造器映射列不为空, 则通过反射调用该构造方法, 创建对应结果对象
    } else if (!constructorMappings.isEmpty()) {
      //创建参数化对象
      return createParameterizedResultObject(rsw, resultType, constructorMappings, constructorArgTypes, constructorArgs, columnPrefix);
      //情况三: 如果是接口, 或有默认的无参的构造方法，则使用该构造方法，创建对应结果对象
    } else if (resultType.isInterface() || metaType.hasDefaultConstructor()) {
      //直接用对象工厂创建对象
      return objectFactory.create(resultType);
      //情况四: 通过自动映射的方式查找合适的构造方法，后使用该构造方法，创建对应结果对象
    } else if (shouldApplyAutomaticMappings(resultMap, false)) {
      //自动映射, 会根据 ResultSet 的所有 jdbcType 选择合适的构造器创建对象
      return createByConstructorSignature(rsw, resultType, constructorArgTypes, constructorArgs);
    }
    throw new ExecutorException("Do not know how to create an instance of " + resultType);
  }

  /**
   * 根据
   */
  Object createParameterizedResultObject(ResultSetWrapper rsw, Class<?> resultType, List<ResultMapping> constructorMappings,
                                         List<Class<?>> constructorArgTypes, List<Object> constructorArgs, String columnPrefix) {
    //是否找到值
    boolean foundValues = false;
    //遍历构造参数映射
    for (ResultMapping constructorMapping : constructorMappings) {
      //获取 constructorMapping 的 JavaType
      final Class<?> parameterType = constructorMapping.getJavaType();
      //获取列名
      final String column = constructorMapping.getColumn();
      final Object value;
      try {
        //如果是内嵌查询
        if (constructorMapping.getNestedQueryId() != null) {
          //则获得内嵌的值
          value = getNestedQueryConstructorValue(rsw.getResultSet(), constructorMapping, columnPrefix);
          //如果是内嵌的 resultMap
        } else if (constructorMapping.getNestedResultMapId() != null) {
          //根据 resultMapId 获取对应的 ResultMap
          final ResultMap resultMap = configuration.getResultMap(constructorMapping.getNestedResultMapId());
          //获取对应的属性值
          value = getRowValue(rsw, resultMap, getColumnPrefix(columnPrefix, constructorMapping));
        } else {
          //获取 TypeHandler
          final TypeHandler<?> typeHandler = constructorMapping.getTypeHandler();
          //通过 TypeHandler 和列名, 从 ResultSet 中获取值
          value = typeHandler.getResult(rsw.getResultSet(), prependPrefix(column, columnPrefix));
        }
      } catch (ResultMapException | SQLException e) {
        throw new ExecutorException("Could not process result for mapping: " + constructorMapping, e);
      }
      //缓存参数类型
      constructorArgTypes.add(parameterType);
      //缓存参数, value 有可能为null
      constructorArgs.add(value);
      //只要有一列值, 则为 true
      foundValues = value != null || foundValues;
    }
    //如果有对应的映射值, 则创建对象, 否则返回null
    return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
  }

  /**
   * 通过返回的 ResultSet 的 jdbcType 列表来找到合适构造器创建对象
   */
  private Object createByConstructorSignature(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) throws SQLException {
    //获取返回类型声明的构造器列表
    final Constructor<?>[] constructors = resultType.getDeclaredConstructors();
    //从构造器列表中获取默认构造器(只有一个构造器或者带自动映射注解的)
    final Constructor<?> defaultConstructor = findDefaultConstructor(constructors);
    //如果指定默认构造器不为null
    if (defaultConstructor != null) {
      //用构造器创建
      return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, defaultConstructor);
      //如果没找到指定默认的构造器
    } else {
      //遍历构造器列表
      for (Constructor<?> constructor : constructors) {
        //如果有构造器跟返回值的类型匹配
        if (allowedConstructorUsingTypeHandlers(constructor, rsw.getJdbcTypes())) {
          //用此构造器创建对象
          return createUsingConstructor(rsw, resultType, constructorArgTypes, constructorArgs, constructor);
        }
      }
    }
    //如果最终都没有合适的构造器, 则报错
    throw new ExecutorException("No constructor found in " + resultType.getName() + " matching " + rsw.getClassNames());
  }

  /**
   * 使用指定构造器创建对象
   * @param constructor 指定的构造器
   */
  private Object createUsingConstructor(ResultSetWrapper rsw, Class<?> resultType, List<Class<?>> constructorArgTypes, List<Object> constructorArgs, Constructor<?> constructor) throws SQLException {
    //记录是否至少有一个值
    boolean foundValues = false;
    //遍历构造器参数类型
    for (int i = 0; i < constructor.getParameterTypes().length; i++) {
      //获取构造器参数类型
      Class<?> parameterType = constructor.getParameterTypes()[i];
      //从 ResultSet 获取同样位置的列名
      String columnName = rsw.getColumnNames().get(i);
      //根据参数类型和列名, 获取 TypeHandler 处理器
      TypeHandler<?> typeHandler = rsw.getTypeHandler(parameterType, columnName);
      //用 TypeHandler 处理器, 从 ResultSet 中获取获取对应列的值
      Object value = typeHandler.getResult(rsw.getResultSet(), columnName);
      //添加参数类型到缓存列表中
      constructorArgTypes.add(parameterType);
      //添加值到
      constructorArgs.add(value);
      //如果有值, 则记录
      foundValues = value != null || foundValues;
    }
    //如果至少有一个值, 则创建对象, 一个都没有, 则返回空
    return foundValues ? objectFactory.create(resultType, constructorArgTypes, constructorArgs) : null;
  }

  /**
   * - 如果只有一个构造器, 则返回, 否则获取有@AutomapConstructor注解的构造器, 都没有就返回null
   */
  private Constructor<?> findDefaultConstructor(final Constructor<?>[] constructors) {
    //如果构造器只有一个
    if (constructors.length == 1) {
      //直接返回
      return constructors[0];
    }

    //遍历构造器
    for (final Constructor<?> constructor : constructors) {
      //从构造器中获取带  @AutomapConstructor 注解的返回
      if (constructor.isAnnotationPresent(AutomapConstructor.class)) {
        return constructor;
      }
    }
    //非一个构造器, 且没有指定注解的, 返回 null
    return null;
  }

  /**
   * 判断构造器是否和返回的数据类型匹配
   */
  private boolean allowedConstructorUsingTypeHandlers(final Constructor<?> constructor, final List<JdbcType> jdbcTypes) {
    //获取构造参数类型
    final Class<?>[] parameterTypes = constructor.getParameterTypes();
    //如果参数类型数不等于resultSet的jdbcType数
    if (parameterTypes.length != jdbcTypes.size()) {
      return false;
    }
    //遍历参数类型
    for (int i = 0; i < parameterTypes.length; i++) {
      //如果参数类型和对应的JdbcType没有 TypeHandler , 则不匹配
      if (!typeHandlerRegistry.hasTypeHandler(parameterTypes[i], jdbcTypes.get(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * 创建原始类型的结果对象.
   * 注意: TypeHandler 只能处理某一列的类型转换, 也就是说, 如果返回的类型如果存在 TypeHandler , 则 ResultSet 的列肯定就只能一列
   */
  private Object createPrimitiveResultObject(ResultSetWrapper rsw, ResultMap resultMap, String columnPrefix) throws SQLException {
    //获取结果类型
    final Class<?> resultType = resultMap.getType();
    //列名
    final String columnName;
    //如果 resultMap 的 ResultMapping 不为空
    if (!resultMap.getResultMappings().isEmpty()) {
      //获取 ResultMapping 列表
      final List<ResultMapping> resultMappingList = resultMap.getResultMappings();
      //获取第一个 ResultMapping
      final ResultMapping mapping = resultMappingList.get(0);
      //获取列并且拼接前缀
      columnName = prependPrefix(mapping.getColumn(), columnPrefix);
      //如果没有配置映射列表
    } else {
      //从 ResultSet 中获取第一列的列名
      columnName = rsw.getColumnNames().get(0);
    }
    //从 ResultSetWrapper 中根据返回类型和列名获取 TypeHandler
    final TypeHandler<?> typeHandler = rsw.getTypeHandler(resultType, columnName);
    //用 TypeHandler 根据 ResultSet 和 列名 获取对象
    return typeHandler.getResult(rsw.getResultSet(), columnName);
  }

  //
  // NESTED QUERY
  //

  /**
   * 获取内嵌查询的构造参数映射值
   */
  private Object getNestedQueryConstructorValue(ResultSet rs, ResultMapping constructorMapping, String columnPrefix) throws SQLException {
    //获取内嵌查询id
    final String nestedQueryId = constructorMapping.getNestedQueryId();
    //获取对应的MappedStatement
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
    //获得内嵌查询的参数类型, ParameterMap 是不可能为空的, 因为 MappedStatement.Builder中会建一个 defaultParameterMap 的默认值
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    //获取内嵌查询的参数对象
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, constructorMapping, nestedQueryParameterType, columnPrefix);
    Object value = null;
    //如果内嵌查询参数对象不为null
    if (nestedQueryParameterObject != null) {
      //根据参数获取对应的子查询 BoundSql
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      //创建一个缓存的 CacheKey
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
      //获取嵌套字段的类型, 即嵌套查询的返回类型
      final Class<?> targetType = constructorMapping.getJavaType();
      //创建结果加载器, 延迟加载器
      final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
      //立即加载结果
      value = resultLoader.loadResult();
    }
    //返回查询结果
    return value;
  }

  /**
   * 获取映射的嵌套查询值
   */
  private Object getNestedQueryMappingValue(ResultSet rs, MetaObject metaResultObject, ResultMapping propertyMapping, ResultLoaderMap lazyLoader, String columnPrefix)
      throws SQLException {
    //获取嵌套查询id
    final String nestedQueryId = propertyMapping.getNestedQueryId();
    //获取属性值
    final String property = propertyMapping.getProperty();
    //根据 selectId 获取 MappedStatement
    final MappedStatement nestedQuery = configuration.getMappedStatement(nestedQueryId);
    //获取嵌套查询的参数类型, 即 property 的 JavaType
    final Class<?> nestedQueryParameterType = nestedQuery.getParameterMap().getType();
    //创建嵌套查询的参数对象, 可能为null
    final Object nestedQueryParameterObject = prepareParameterForNestedQuery(rs, propertyMapping, nestedQueryParameterType, columnPrefix);
    Object value = null;
    //如果参数对象不为null
    if (nestedQueryParameterObject != null) {
      //获取 BoundSql
      final BoundSql nestedBoundSql = nestedQuery.getBoundSql(nestedQueryParameterObject);
      //创建 CacheKey
      final CacheKey key = executor.createCacheKey(nestedQuery, nestedQueryParameterObject, RowBounds.DEFAULT, nestedBoundSql);
      //字段的 JavaType
      final Class<?> targetType = propertyMapping.getJavaType();
      //判断嵌套查询的结果是否有缓存
      if (executor.isCached(nestedQuery, key)) {
        //创建 DeferredLoad 对象，并通过该 DeferredLoad 对象从缓存中加载结采对象
        //todo wolfleong 后面要看回这里做了什么
        executor.deferLoad(nestedQuery, metaResultObject, property, key, targetType);
        value = DEFERRED;
      } else {
        //如果没有缓存
        final ResultLoader resultLoader = new ResultLoader(configuration, executor, nestedQuery, nestedQueryParameterObject, targetType, key, nestedBoundSql);
        //如果要求延迟加载
        if (propertyMapping.isLazy()) {
          // 如果该属性配置了延迟加载，则将其添加到 `ResultLoader.loaderMap` 中，等待真正使用时再执行嵌套查询并得到结果对象。
          lazyLoader.addLoader(property, metaResultObject, resultLoader);
          //返回已经定义
          value = DEFERRED;
        } else {
          //没要求则立即加载
          value = resultLoader.loadResult();
        }
      }
    }
    return value;
  }

  /**
   *
   * @param rs ResultSet
   * @param resultMapping 有子查询的 ResultMapping
   * @param parameterType 子查询的参数类型, 即指定列 column 的类型
   * @param columnPrefix 列前缀
   * @return 返回参数值
   */
  private Object prepareParameterForNestedQuery(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    //如果子查询的参数是复合主键
    if (resultMapping.isCompositeResult()) {
      //column="{prop1=column1, prop2=column2}"
      //处理复合的情况
      return prepareCompositeKeyParameter(rs, resultMapping, parameterType, columnPrefix);
    } else {
      //column="id"
      //处理简单的情况
      return prepareSimpleKeyParameter(rs, resultMapping, parameterType, columnPrefix);
    }
  }

  /**
   * 处理单个参数的子查询参数
   * @param parameterType parameterType 即 column 的类型, 因为 column 的值就是给子查询作为参数用的
   */
  private Object prepareSimpleKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    final TypeHandler<?> typeHandler;
    //如果 column 的列类型有对应的 TypeHandler
    if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
      //获取 TypeHandler
      typeHandler = typeHandlerRegistry.getTypeHandler(parameterType);
    } else {
      //否则给一个默认的 TypeHandler
      typeHandler = typeHandlerRegistry.getUnknownTypeHandler();
    }
    //通过 TypeHandler 获取指定列的值
    return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
  }

  /**
   * 处理复合参数的子查询参数
   */
  private Object prepareCompositeKeyParameter(ResultSet rs, ResultMapping resultMapping, Class<?> parameterType, String columnPrefix) throws SQLException {
    //创建参数参数对象
    final Object parameterObject = instantiateParameterObject(parameterType);
    //创建参数对象的 MetaObject
    final MetaObject metaObject = configuration.newMetaObject(parameterObject);
    //默认没找到值
    boolean foundValues = false;
    //遍历复合映射列表
    for (ResultMapping innerResultMapping : resultMapping.getComposites()) {
      //获取字段在参数对象中的类型
      final Class<?> propType = metaObject.getSetterType(innerResultMapping.getProperty());
      //获取字段类型对应的 TypeHandler
      final TypeHandler<?> typeHandler = typeHandlerRegistry.getTypeHandler(propType);
      //使用 TypeHandler 和 字段名获取对应的值
      final Object propValue = typeHandler.getResult(rs, prependPrefix(innerResultMapping.getColumn(), columnPrefix));
      //如果获取地值不为null
      // issue #353 & #560 do not execute nested query if key is null
      if (propValue != null) {
        //设置到参数中
        metaObject.setValue(innerResultMapping.getProperty(), propValue);
        //标记已经找到值
        foundValues = true;
      }
    }
    //如果复合参数有值, 则返回参数对象, 否则返回 null
    return foundValues ? parameterObject : null;
  }

  /**
   * 初始化参数实例
   */
  private Object instantiateParameterObject(Class<?> parameterType) {
    //如果参数类型是null
    if (parameterType == null) {
      //默认给 HashMap
      return new HashMap<>();
      //如果参数是 ParamMap, 则还是默认给 HashMap
    } else if (ParamMap.class.equals(parameterType)) {
      return new HashMap<>(); // issue #649
    } else {
      //其他对象, 直接用默认构造器创建
      return objectFactory.create(parameterType);
    }
  }

  //
  // DISCRIMINATOR
  //

  /**
   * - 处理鉴别器的 ResultMap , 如果找到鉴别器中的 ResultMap_1 , 则直接返回 ResultMap_1, 忽略父 ResultMap.
   * - 为了可以映射到父的 ResultMap 的字段, 可以配置 ResultMap_1 继成 ResultMap .
   * - 鉴别器的case 可以直接配置成父 ResultMap 或者其他的 ResultMap_0 , ResultMap_0 的鉴别器 又可以引用已经解析过的 ResultMap ,
   *   这样的话就存在 Discriminator 重复引用的问题了
   *
   * <resultMap id="vehicleResult" type="Vehicle">
   *   <id property="id" column="id" />
   *   <result property="vin" column="vin"/>
   *   <result property="year" column="year"/>
   *   <result property="make" column="make"/>
   *   <result property="model" column="model"/>
   *   <result property="color" column="color"/>
   *   <discriminator javaType="int" column="vehicle_type">
   *     <case value="1" resultMap="carResult"/>
   *     <case value="2" resultMap="truckResult"/>
   *     <case value="3" resultMap="vanResult"/>
   *     <case value="4" resultMap="suvResult"/>
   *   </discriminator>
   * </resultMap>
   *
   */
  public ResultMap resolveDiscriminatedResultMap(ResultSet rs, ResultMap resultMap, String columnPrefix) throws SQLException {
    //记录已经处理过 Discriminator 对应的 ResultMapId
    Set<String> pastDiscriminators = new HashSet<>();
    //获取 Discriminator
    Discriminator discriminator = resultMap.getDiscriminator();
    //如果存在 Discriminator 对象, 则基本于其获得的 ResultMap 对象进行处理
    while (discriminator != null) {
      //获取 discriminator 中指定列的值
      final Object value = getDiscriminatorValue(rs, discriminator, columnPrefix);
      //根据值获取对应的 ResultMapId
      final String discriminatedMapId = discriminator.getMapIdFor(String.valueOf(value));
      //如果全局配置上有 discriminatedMapId 指定的 ResultMap 的话
      if (configuration.hasResultMap(discriminatedMapId)) {
        //获取鉴别器中的 ResultMap
        resultMap = configuration.getResultMap(discriminatedMapId);
        //记录最新的 Discriminator
        Discriminator lastDiscriminator = discriminator;
        //从新的 ResultMap 中获取 Discriminator
        discriminator = resultMap.getDiscriminator();
        //主要是避免重复引用的情况, 因为 ResultMap 是可以继承上一层的
        //如果鉴别器跟刚处理的一样, 且在 pastDiscriminators 中已经存在, 则不处理, 直接退出
        if (discriminator == lastDiscriminator || !pastDiscriminators.add(discriminatedMapId)) {
          //进来这里有两种情况
          // 第一种情况:  case 的 ResultMap_1 直接引用父的 ResultMap

          //第二种情况:
          // 第一个 ResultMap 有鉴别器 Discriminator, 鉴别器中的case 引用 ResultMap_1, 然后 ResultMap_1 又有鉴别器 Discriminator_1,
          // ResultMap_1的id会添加 pastDiscriminators, 然后开始第二次循环
          // Discriminator_1 的 case 又引用回 ResultMap, 随着解析, 又会解析出 case 引用 ResultMap_1 , 然后发现 ResultMap_1 已经解析过
          // 在这里就断开了
          break;
        }
      } else {
        //如果没有对应的 ResultMap 也直接退出
        break;
      }
    }
    return resultMap;
  }

  /**
   * 获取鉴别器指定列的值
   */
  private Object getDiscriminatorValue(ResultSet rs, Discriminator discriminator, String columnPrefix) throws SQLException {
    //从 Discriminator 中获取映射的 ResultMapping
    final ResultMapping resultMapping = discriminator.getResultMapping();
    //从 ResultMapping 中获取类型处理器
    final TypeHandler<?> typeHandler = resultMapping.getTypeHandler();
    //用 TypeHandler 从 ResultSet 中获取值
    return typeHandler.getResult(rs, prependPrefix(resultMapping.getColumn(), columnPrefix));
  }

  /**
   * 拼接列前缀
   */
  private String prependPrefix(String columnName, String prefix) {
    //如果列名为空或者 前缀为空, 直接返回
    if (columnName == null || columnName.length() == 0 || prefix == null || prefix.length() == 0) {
      return columnName;
    }
    //否则拼接前缀
    return prefix + columnName;
  }

  //
  // HANDLE NESTED RESULT MAPS
  //

  private void handleRowValuesForNestedResultMap(ResultSetWrapper rsw, ResultMap resultMap, ResultHandler<?> resultHandler, RowBounds rowBounds, ResultMapping parentMapping) throws SQLException {
    final DefaultResultContext<Object> resultContext = new DefaultResultContext<>();
    ResultSet resultSet = rsw.getResultSet();
    skipRows(resultSet, rowBounds);
    Object rowValue = previousRowValue;
    while (shouldProcessMoreRows(resultContext, rowBounds) && !resultSet.isClosed() && resultSet.next()) {
      final ResultMap discriminatedResultMap = resolveDiscriminatedResultMap(resultSet, resultMap, null);
      final CacheKey rowKey = createRowKey(discriminatedResultMap, rsw, null);
      Object partialObject = nestedResultObjects.get(rowKey);
      // issue #577 && #542
      if (mappedStatement.isResultOrdered()) {
        if (partialObject == null && rowValue != null) {
          nestedResultObjects.clear();
          storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
        }
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
      } else {
        rowValue = getRowValue(rsw, discriminatedResultMap, rowKey, null, partialObject);
        if (partialObject == null) {
          storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
        }
      }
    }
    if (rowValue != null && mappedStatement.isResultOrdered() && shouldProcessMoreRows(resultContext, rowBounds)) {
      storeObject(resultHandler, resultContext, rowValue, parentMapping, resultSet);
      previousRowValue = null;
    } else if (rowValue != null) {
      previousRowValue = rowValue;
    }
  }

  //
  // GET VALUE FROM ROW FOR NESTED RESULT MAP
  //

  private Object getRowValue(ResultSetWrapper rsw, ResultMap resultMap, CacheKey combinedKey, String columnPrefix, Object partialObject) throws SQLException {
    final String resultMapId = resultMap.getId();
    Object rowValue = partialObject;
    if (rowValue != null) {
      final MetaObject metaObject = configuration.newMetaObject(rowValue);
      putAncestor(rowValue, resultMapId);
      applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, false);
      ancestorObjects.remove(resultMapId);
    } else {
      final ResultLoaderMap lazyLoader = new ResultLoaderMap();
      rowValue = createResultObject(rsw, resultMap, lazyLoader, columnPrefix);
      if (rowValue != null && !hasTypeHandlerForResultObject(rsw, resultMap.getType())) {
        final MetaObject metaObject = configuration.newMetaObject(rowValue);
        boolean foundValues = this.useConstructorMappings;
        if (shouldApplyAutomaticMappings(resultMap, true)) {
          foundValues = applyAutomaticMappings(rsw, resultMap, metaObject, columnPrefix) || foundValues;
        }
        foundValues = applyPropertyMappings(rsw, resultMap, metaObject, lazyLoader, columnPrefix) || foundValues;
        putAncestor(rowValue, resultMapId);
        foundValues = applyNestedResultMappings(rsw, resultMap, metaObject, columnPrefix, combinedKey, true) || foundValues;
        ancestorObjects.remove(resultMapId);
        foundValues = lazyLoader.size() > 0 || foundValues;
        rowValue = foundValues || configuration.isReturnInstanceForEmptyRow() ? rowValue : null;
      }
      if (combinedKey != CacheKey.NULL_CACHE_KEY) {
        nestedResultObjects.put(combinedKey, rowValue);
      }
    }
    return rowValue;
  }

  private void putAncestor(Object resultObject, String resultMapId) {
    ancestorObjects.put(resultMapId, resultObject);
  }

  //
  // NESTED RESULT MAP (JOIN MAPPING)
  //

  private boolean applyNestedResultMappings(ResultSetWrapper rsw, ResultMap resultMap, MetaObject metaObject, String parentPrefix, CacheKey parentRowKey, boolean newObject) {
    boolean foundValues = false;
    for (ResultMapping resultMapping : resultMap.getPropertyResultMappings()) {
      final String nestedResultMapId = resultMapping.getNestedResultMapId();
      if (nestedResultMapId != null && resultMapping.getResultSet() == null) {
        try {
          final String columnPrefix = getColumnPrefix(parentPrefix, resultMapping);
          final ResultMap nestedResultMap = getNestedResultMap(rsw.getResultSet(), nestedResultMapId, columnPrefix);
          if (resultMapping.getColumnPrefix() == null) {
            // try to fill circular reference only when columnPrefix
            // is not specified for the nested result map (issue #215)
            Object ancestorObject = ancestorObjects.get(nestedResultMapId);
            if (ancestorObject != null) {
              if (newObject) {
                linkObjects(metaObject, resultMapping, ancestorObject); // issue #385
              }
              continue;
            }
          }
          final CacheKey rowKey = createRowKey(nestedResultMap, rsw, columnPrefix);
          final CacheKey combinedKey = combineKeys(rowKey, parentRowKey);
          Object rowValue = nestedResultObjects.get(combinedKey);
          boolean knownValue = rowValue != null;
          instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject); // mandatory
          if (anyNotNullColumnHasValue(resultMapping, columnPrefix, rsw)) {
            rowValue = getRowValue(rsw, nestedResultMap, combinedKey, columnPrefix, rowValue);
            if (rowValue != null && !knownValue) {
              linkObjects(metaObject, resultMapping, rowValue);
              foundValues = true;
            }
          }
        } catch (SQLException e) {
          throw new ExecutorException("Error getting nested result map values for '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
        }
      }
    }
    return foundValues;
  }

  private String getColumnPrefix(String parentPrefix, ResultMapping resultMapping) {
    final StringBuilder columnPrefixBuilder = new StringBuilder();
    if (parentPrefix != null) {
      columnPrefixBuilder.append(parentPrefix);
    }
    if (resultMapping.getColumnPrefix() != null) {
      columnPrefixBuilder.append(resultMapping.getColumnPrefix());
    }
    return columnPrefixBuilder.length() == 0 ? null : columnPrefixBuilder.toString().toUpperCase(Locale.ENGLISH);
  }

  private boolean anyNotNullColumnHasValue(ResultMapping resultMapping, String columnPrefix, ResultSetWrapper rsw) throws SQLException {
    Set<String> notNullColumns = resultMapping.getNotNullColumns();
    if (notNullColumns != null && !notNullColumns.isEmpty()) {
      ResultSet rs = rsw.getResultSet();
      for (String column : notNullColumns) {
        rs.getObject(prependPrefix(column, columnPrefix));
        if (!rs.wasNull()) {
          return true;
        }
      }
      return false;
    } else if (columnPrefix != null) {
      for (String columnName : rsw.getColumnNames()) {
        if (columnName.toUpperCase().startsWith(columnPrefix.toUpperCase())) {
          return true;
        }
      }
      return false;
    }
    return true;
  }

  private ResultMap getNestedResultMap(ResultSet rs, String nestedResultMapId, String columnPrefix) throws SQLException {
    ResultMap nestedResultMap = configuration.getResultMap(nestedResultMapId);
    return resolveDiscriminatedResultMap(rs, nestedResultMap, columnPrefix);
  }

  //
  // UNIQUE RESULT KEY
  //

  private CacheKey createRowKey(ResultMap resultMap, ResultSetWrapper rsw, String columnPrefix) throws SQLException {
    final CacheKey cacheKey = new CacheKey();
    cacheKey.update(resultMap.getId());
    List<ResultMapping> resultMappings = getResultMappingsForRowKey(resultMap);
    if (resultMappings.isEmpty()) {
      if (Map.class.isAssignableFrom(resultMap.getType())) {
        createRowKeyForMap(rsw, cacheKey);
      } else {
        createRowKeyForUnmappedProperties(resultMap, rsw, cacheKey, columnPrefix);
      }
    } else {
      createRowKeyForMappedProperties(resultMap, rsw, cacheKey, resultMappings, columnPrefix);
    }
    if (cacheKey.getUpdateCount() < 2) {
      return CacheKey.NULL_CACHE_KEY;
    }
    return cacheKey;
  }

  private CacheKey combineKeys(CacheKey rowKey, CacheKey parentRowKey) {
    if (rowKey.getUpdateCount() > 1 && parentRowKey.getUpdateCount() > 1) {
      CacheKey combinedKey;
      try {
        combinedKey = rowKey.clone();
      } catch (CloneNotSupportedException e) {
        throw new ExecutorException("Error cloning cache key.  Cause: " + e, e);
      }
      combinedKey.update(parentRowKey);
      return combinedKey;
    }
    return CacheKey.NULL_CACHE_KEY;
  }

  private List<ResultMapping> getResultMappingsForRowKey(ResultMap resultMap) {
    List<ResultMapping> resultMappings = resultMap.getIdResultMappings();
    if (resultMappings.isEmpty()) {
      resultMappings = resultMap.getPropertyResultMappings();
    }
    return resultMappings;
  }

  private void createRowKeyForMappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, List<ResultMapping> resultMappings, String columnPrefix) throws SQLException {
    for (ResultMapping resultMapping : resultMappings) {
      if (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null) {
        // Issue #392
        final ResultMap nestedResultMap = configuration.getResultMap(resultMapping.getNestedResultMapId());
        createRowKeyForMappedProperties(nestedResultMap, rsw, cacheKey, nestedResultMap.getConstructorResultMappings(),
            prependPrefix(resultMapping.getColumnPrefix(), columnPrefix));
      } else if (resultMapping.getNestedQueryId() == null) {
        final String column = prependPrefix(resultMapping.getColumn(), columnPrefix);
        final TypeHandler<?> th = resultMapping.getTypeHandler();
        List<String> mappedColumnNames = rsw.getMappedColumnNames(resultMap, columnPrefix);
        // Issue #114
        if (column != null && mappedColumnNames.contains(column.toUpperCase(Locale.ENGLISH))) {
          final Object value = th.getResult(rsw.getResultSet(), column);
          if (value != null || configuration.isReturnInstanceForEmptyRow()) {
            cacheKey.update(column);
            cacheKey.update(value);
          }
        }
      }
    }
  }

  private void createRowKeyForUnmappedProperties(ResultMap resultMap, ResultSetWrapper rsw, CacheKey cacheKey, String columnPrefix) throws SQLException {
    final MetaClass metaType = MetaClass.forClass(resultMap.getType(), reflectorFactory);
    List<String> unmappedColumnNames = rsw.getUnmappedColumnNames(resultMap, columnPrefix);
    for (String column : unmappedColumnNames) {
      String property = column;
      if (columnPrefix != null && !columnPrefix.isEmpty()) {
        // When columnPrefix is specified, ignore columns without the prefix.
        if (column.toUpperCase(Locale.ENGLISH).startsWith(columnPrefix)) {
          property = column.substring(columnPrefix.length());
        } else {
          continue;
        }
      }
      if (metaType.findProperty(property, configuration.isMapUnderscoreToCamelCase()) != null) {
        String value = rsw.getResultSet().getString(column);
        if (value != null) {
          cacheKey.update(column);
          cacheKey.update(value);
        }
      }
    }
  }

  private void createRowKeyForMap(ResultSetWrapper rsw, CacheKey cacheKey) throws SQLException {
    List<String> columnNames = rsw.getColumnNames();
    for (String columnName : columnNames) {
      final String value = rsw.getResultSet().getString(columnName);
      if (value != null) {
        cacheKey.update(columnName);
        cacheKey.update(value);
      }
    }
  }

  private void linkObjects(MetaObject metaObject, ResultMapping resultMapping, Object rowValue) {
    final Object collectionProperty = instantiateCollectionPropertyIfAppropriate(resultMapping, metaObject);
    if (collectionProperty != null) {
      final MetaObject targetMetaObject = configuration.newMetaObject(collectionProperty);
      targetMetaObject.add(rowValue);
    } else {
      metaObject.setValue(resultMapping.getProperty(), rowValue);
    }
  }

  private Object instantiateCollectionPropertyIfAppropriate(ResultMapping resultMapping, MetaObject metaObject) {
    final String propertyName = resultMapping.getProperty();
    Object propertyValue = metaObject.getValue(propertyName);
    if (propertyValue == null) {
      Class<?> type = resultMapping.getJavaType();
      if (type == null) {
        type = metaObject.getSetterType(propertyName);
      }
      try {
        if (objectFactory.isCollection(type)) {
          propertyValue = objectFactory.create(type);
          metaObject.setValue(propertyName, propertyValue);
          return propertyValue;
        }
      } catch (Exception e) {
        throw new ExecutorException("Error instantiating collection property for result '" + resultMapping.getProperty() + "'.  Cause: " + e, e);
      }
    } else if (objectFactory.isCollection(propertyValue.getClass())) {
      return propertyValue;
    }
    return null;
  }

  /**
   * 判断类型注册器上是否有结果类型 resultType 的 TypeHandler
   */
  private boolean hasTypeHandlerForResultObject(ResultSetWrapper rsw, Class<?> resultType) {
    //如果 ResultSet 上面只有一列的话
    if (rsw.getColumnNames().size() == 1) {
      //获取这一列的 jdbcType , 并且通过结果类型和jdbcType 来判断有没有 TypeHandler
      return typeHandlerRegistry.hasTypeHandler(resultType, rsw.getJdbcType(rsw.getColumnNames().get(0)));
    }
    //通过结果类型来判断有没有TypeHandler
    return typeHandlerRegistry.hasTypeHandler(resultType);
  }

}

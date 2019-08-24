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
package org.apache.ibatis.executor;

import static org.apache.ibatis.executor.ExecutionPlaceholder.EXECUTION_PLACEHOLDER;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.impl.PerpetualCache;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementUtil;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.logging.jdbc.ConnectionLogger;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * 实现 Executor 接口，提供骨架方法，从而使子类只要实现指定的几个抽象方法即可.
 * 通用逻辑的抽象类
 * - 维护缓存/延迟加载队列/事务对象等属性
 * - 在查询时, 对查询结果做一级缓存处理
 * - 刷新时或关闭或更新时, 清空缓存
 * @author Clinton Begin
 */
public abstract class BaseExecutor implements Executor {

  /**
   * 日志对象
   */
  private static final Log log = LogFactory.getLog(BaseExecutor.class);

  /**
   * 事务对象
   */
  protected Transaction transaction;
  /**
   * 包装的 Executor 对象,
   * todo wolfleong 得了解一下, 为什么要设置这个 wrapper 来记录包装当前 Executor 的对象呢
   * 我觉得主要是, 在 BaseExecutor 中有些方法是需要用到具体的包装类来调用的, 而不是当前的this
   */
  protected Executor wrapper;
  /**
   * 延迟加载队列
   */
  protected ConcurrentLinkedQueue<DeferredLoad> deferredLoads;
  /**
   * 本地缓存, 即一级缓存, sqlSession级别的缓存
   */
  protected PerpetualCache localCache;
  /**
   * 本地输出类型的参数缓存
   */
  protected PerpetualCache localOutputParameterCache;
  /**
   * 全局配置
   */
  protected Configuration configuration;
  /**
   * 记录递归嵌套查询的层级
   */
  protected int queryStack;
  /**
   * 记录当前 Executor 是否已经关闭
   */
  private boolean closed;

  protected BaseExecutor(Configuration configuration, Transaction transaction) {
    this.transaction = transaction;
    //创建一个支持并发的队列
    this.deferredLoads = new ConcurrentLinkedQueue<>();
    //创建一个本地缓存对象
    this.localCache = new PerpetualCache("LocalCache");
    //创建输出缓存对象
    this.localOutputParameterCache = new PerpetualCache("LocalOutputParameterCache");
    //默认非关闭
    this.closed = false;
    this.configuration = configuration;
    //初始化自己, todo wolfleong 不懂为什么这么操作
    this.wrapper = this;
  }

  @Override
  public Transaction getTransaction() {
    //判断半闭状态
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    //返回事务
    return transaction;
  }

  @Override
  public void close(boolean forceRollback) {
    try {
      try {
        //回滚
        rollback(forceRollback);
      } finally {
        //事务不为null
        if (transaction != null) {
          //关闭事务
          transaction.close();
        }
      }
    } catch (SQLException e) {
      // Ignore.  There's nothing that can be done at this point.
      log.warn("Unexpected exception on closing transaction.  Cause: " + e);
    } finally {
      //回收事务对象
      transaction = null;
      //回收列表对象
      deferredLoads = null;
      //回收本地缓存对象
      localCache = null;
      //回收输出参数缓存对象
      localOutputParameterCache = null;
      //设置已经关闭
      closed = true;
    }
  }

  @Override
  public boolean isClosed() {
    return closed;
  }

  @Override
  public int update(MappedStatement ms, Object parameter) throws SQLException {
    ErrorContext.instance().resource(ms.getResource()).activity("executing an update").object(ms.getId());
    //校验关闭状态
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    //清理缓存
    clearLocalCache();
    //执行查询
    return doUpdate(ms, parameter);
  }

  @Override
  public List<BatchResult> flushStatements() throws SQLException {
    //执行刷新语句
    return flushStatements(false);
  }

  public List<BatchResult> flushStatements(boolean isRollBack) throws SQLException {
    //判断关闭状态
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    //执行刷新语句
    return doFlushStatements(isRollBack);
  }

  @Override
  public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
    //根据参数获取 BoundSql 对象
    BoundSql boundSql = ms.getBoundSql(parameter);
    //创建 CacheKey 对象
    CacheKey key = createCacheKey(ms, parameter, rowBounds, boundSql);
    //查询
    return query(ms, parameter, rowBounds, resultHandler, key, boundSql);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
    ErrorContext.instance().resource(ms.getResource()).activity("executing a query").object(ms.getId());
    //如果已经关闭, 则不允许操作
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    //如果 queryStack 为零, 并且要求清空缓存, 则清空本地缓存
    if (queryStack == 0 && ms.isFlushCacheRequired()) {
      //清空缓存
      clearLocalCache();
    }
    //记录查询的结果
    List<E> list;
    try {
      // queryStack 加 1 , 代表第一层查询
      queryStack++;
      // 从一级缓存中, 获取查询结果
      list = resultHandler == null ? (List<E>) localCache.getObject(key) : null;
      //缓存中有查询到
      if (list != null) {
        //设置将输出参数的缓存设置到
        handleLocallyCachedOutputParameters(ms, key, parameter, boundSql);
        //如果在缓存中没有查到
      } else {
        //查询不到, 则去数据库中查询
        list = queryFromDatabase(ms, parameter, rowBounds, resultHandler, key, boundSql);
      }
    } finally {
      // queryStack - 1
      queryStack--;
    }
    if (queryStack == 0) {
      // 遍历延迟加载队列
      for (DeferredLoad deferredLoad : deferredLoads) {
        //执行延迟加载
        deferredLoad.load();
      }
      //执行完, 清空队列
      // issue #601
      deferredLoads.clear();
      //如果缓存级别是 LocalCacheScope.STATEMENT
      if (configuration.getLocalCacheScope() == LocalCacheScope.STATEMENT) {
        //清空缓存
        // issue #482
        clearLocalCache();
      }
    }
    return list;
  }

  @Override
  public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
    // 获取 BoundSql 对象
    BoundSql boundSql = ms.getBoundSql(parameter);
    //执行查询
    return doQueryCursor(ms, parameter, rowBounds, boundSql);
  }

  @Override
  public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
    //判断是否关闭
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    //创建 DeferredLoad 对象
    DeferredLoad deferredLoad = new DeferredLoad(resultObject, property, key, localCache, configuration, targetType);
    //判断是否可以立即加载
    if (deferredLoad.canLoad()) {
      //如果可以就加载
      deferredLoad.load();
    } else {
      //不可以就先加到延迟加载队列
      deferredLoads.add(new DeferredLoad(resultObject, property, key, localCache, configuration, targetType));
    }
  }

  @Override
  public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
    //如果已经关闭, 则不允许操作
    if (closed) {
      throw new ExecutorException("Executor was closed.");
    }
    //创建 CacheKey 对象
    CacheKey cacheKey = new CacheKey();
    //设置 查询id
    cacheKey.update(ms.getId());
    //设置 offset
    cacheKey.update(rowBounds.getOffset());
    //设置 limit
    cacheKey.update(rowBounds.getLimit());
    //设置sql语句
    cacheKey.update(boundSql.getSql());
    //获取参数映射列表
    List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    //获取 TypeHandlerRegistry
    TypeHandlerRegistry typeHandlerRegistry = ms.getConfiguration().getTypeHandlerRegistry();
    //遍历参数映射, 这里的逻辑跟 DefaultParameterHandler 差不多
    // mimic DefaultParameterHandler logic
    for (ParameterMapping parameterMapping : parameterMappings) {
      //如果参数不是输出
      if (parameterMapping.getMode() != ParameterMode.OUT) {
        //参数值
        Object value;
        //获取参数属性名
        String propertyName = parameterMapping.getProperty();
        //如果参数在 additional 中存在
        if (boundSql.hasAdditionalParameter(propertyName)) {
          //获取参数值
          value = boundSql.getAdditionalParameter(propertyName);
          //如果参数对象是null, 则设置参数值为null
        } else if (parameterObject == null) {
          value = null;
          //如果参数类型对应的TypeHandler不为null
        } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
          //则将参数对象作为参数值
          value = parameterObject;
        } else {
          //创建参数对象的 MetaObject
          MetaObject metaObject = configuration.newMetaObject(parameterObject);
          //获取参数对象对应属性的 value
          value = metaObject.getValue(propertyName);
        }
        //更新参数值到 cacheKey
        cacheKey.update(value);
      }
    }
    //如果环境配置不为null, 则添加环境 id 到 cacheKey
    if (configuration.getEnvironment() != null) {
      // issue #176
      cacheKey.update(configuration.getEnvironment().getId());
    }
    return cacheKey;
  }

  @Override
  public boolean isCached(MappedStatement ms, CacheKey key) {
    //从一级缓存中如果能获取, 则表明这个 key 是有缓存的
    return localCache.getObject(key) != null;
  }

  @Override
  public void commit(boolean required) throws SQLException {
    //如果 Executor 已经关闭, 则不允许操作
    if (closed) {
      throw new ExecutorException("Cannot commit, transaction is already closed");
    }
    //添加缓存
    clearLocalCache();
    //批量刷入语句
    flushStatements();
    if (required) {
      //提交事务
      transaction.commit();
    }
  }

  @Override
  public void rollback(boolean required) throws SQLException {
    if (!closed) {
      try {
        //清空本地缓存
        clearLocalCache();
        //刷新 批量执行的 Statement
        flushStatements(true);
      } finally {
        //如果 required 为 true
        if (required) {
          //进行事务回滚
          transaction.rollback();
        }
      }
    }
  }

  @Override
  public void clearLocalCache() {
    //如果未关闭
    if (!closed) {
      //清空 localCache
      localCache.clear();
      //清空 localOutputParameterCache
      localOutputParameterCache.clear();
    }
  }

  protected abstract int doUpdate(MappedStatement ms, Object parameter)
      throws SQLException;

  protected abstract List<BatchResult> doFlushStatements(boolean isRollback)
      throws SQLException;

  protected abstract <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
      throws SQLException;

  protected abstract <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql)
      throws SQLException;

  /**
   * 关闭 statement
   */
  protected void closeStatement(Statement statement) {
    //如果 statement 不为null
    if (statement != null) {
      try {
        //关闭 statement
        statement.close();
      } catch (SQLException e) {
        // ignore
      }
    }
  }

  /**
   * Apply a transaction timeout.
   * @param statement a current statement
   * @throws SQLException if a database access error occurs, this method is called on a closed <code>Statement</code>
   * @since 3.4.0
   * @see StatementUtil#applyTransactionTimeout(Statement, Integer, Integer)
   */
  protected void applyTransactionTimeout(Statement statement) throws SQLException {
    //设置事务的超时时间
    StatementUtil.applyTransactionTimeout(statement, statement.getQueryTimeout(), transaction.getTimeout());
  }

  private void handleLocallyCachedOutputParameters(MappedStatement ms, CacheKey key, Object parameter, BoundSql boundSql) {
    //如果 sql 语句是存储过程类型
    if (ms.getStatementType() == StatementType.CALLABLE) {
      //获取缓存的参数
      final Object cachedParameter = localOutputParameterCache.getObject(key);
      //缓存的参数和参数都不为null
      if (cachedParameter != null && parameter != null) {
        //创建 cachedParameter 的 MetaObject
        final MetaObject metaCachedParameter = configuration.newMetaObject(cachedParameter);
        //创建 parameter 的 MetaObject
        final MetaObject metaParameter = configuration.newMetaObject(parameter);
        //遍历所有的参数列表
        for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
          //如果不只是输入参数
          if (parameterMapping.getMode() != ParameterMode.IN) {
            //获取参数名
            final String parameterName = parameterMapping.getProperty();
            //获取缓存的值
            final Object cachedValue = metaCachedParameter.getValue(parameterName);
            //设置到当前指定属性中
            metaParameter.setValue(parameterName, cachedValue);
          }
        }
      }
    }
  }

  /**
   * 查询数据库
   */
  private <E> List<E> queryFromDatabase(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql) throws SQLException {
    List<E> list;
    //在缓存中，添加占位对象。此处的占位符，和延迟加载有关，可见 `DeferredLoad#canLoad()` 方法
    localCache.putObject(key, EXECUTION_PLACEHOLDER);
    try {
      //执行查询操作
      list = doQuery(ms, parameter, rowBounds, resultHandler, boundSql);
    } finally {
      //从缓存中, 移除占位对象
      localCache.removeObject(key);
    }
    //添加到缓存中
    localCache.putObject(key, list);
    //如果执行语句是存储过程
    if (ms.getStatementType() == StatementType.CALLABLE) {
      //将参数缓存起来
      localOutputParameterCache.putObject(key, parameter);
    }
    return list;
  }

  /**
   * 获取 Connection 对象
   */
  protected Connection getConnection(Log statementLog) throws SQLException {
    //通过事务对象获取 Connection
    Connection connection = transaction.getConnection();
    //如果日志是启用 debug
    if (statementLog.isDebugEnabled()) {
      //创建日志的代理对象, 返回
      return ConnectionLogger.newInstance(connection, statementLog, queryStack);
    } else {
      //返回当前连接
      return connection;
    }
  }

  @Override
  public void setExecutorWrapper(Executor wrapper) {
    //设置包装器
    this.wrapper = wrapper;
  }

  private static class DeferredLoad {

    private final MetaObject resultObject;
    private final String property;
    private final Class<?> targetType;
    private final CacheKey key;
    private final PerpetualCache localCache;
    private final ObjectFactory objectFactory;
    private final ResultExtractor resultExtractor;

    // issue #781
    public DeferredLoad(MetaObject resultObject,
                        String property,
                        CacheKey key,
                        PerpetualCache localCache,
                        Configuration configuration,
                        Class<?> targetType) {
      this.resultObject = resultObject;
      this.property = property;
      this.key = key;
      this.localCache = localCache;
      this.objectFactory = configuration.getObjectFactory();
      this.resultExtractor = new ResultExtractor(configuration, objectFactory);
      this.targetType = targetType;
    }

    public boolean canLoad() {
      return localCache.getObject(key) != null && localCache.getObject(key) != EXECUTION_PLACEHOLDER;
    }

    public void load() {
      @SuppressWarnings("unchecked")
      // we suppose we get back a List
      List<Object> list = (List<Object>) localCache.getObject(key);
      Object value = resultExtractor.extractObjectFromList(list, targetType);
      resultObject.setValue(property, value);
    }

  }

}

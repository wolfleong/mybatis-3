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

import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.cache.TransactionalCacheManager;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class CachingExecutor implements Executor {

  /**
   * 代理对象, 被委托的 Executor 对象
   */
  private final Executor delegate;
  /**
   * TransactionalCacheManager 对象
   * tcm 属性，TransactionalCacheManager 对象，支持事务的缓存管理器。因为二级缓存是支持跨 Session 进行共享，此处需要考虑事务，
   * 那么，必然需要做到事务提交时，才将当前事务中查询时产生的缓存，同步到二级缓存中。这个功能，就通过 TransactionalCacheManager 来实现。
   */
  private final TransactionalCacheManager tcm = new TransactionalCacheManager();

  public CachingExecutor(Executor delegate) {
    this.delegate = delegate;
    //设置 delegate 被当前执行器所包装的实例, 这里有点巧妙, 可以学习一下
    delegate.setExecutorWrapper(this);
  }

  @Override
  public Transaction getTransaction() {
    //调用代理对象获取事务
    return delegate.getTransaction();
  }

  @Override
  public void close(boolean forceRollback) {
    try {
      //在关闭前, 要强制回滚的话
      //issues #499, #524 and #573
      if (forceRollback) {
        //回滚
        tcm.rollback();
      } else {
        //否则, 提交
        tcm.commit();
      }
    } finally {
      //最终才关闭
      delegate.close(forceRollback);
    }
  }

  @Override
  public boolean isClosed() {
    //调用代理对象的方法
    return delegate.isClosed();
  }

  @Override
  public int update(MappedStatement ms, Object parameterObject) throws SQLException {
    flushCacheIfRequired(ms);
    return delegate.update(ms, parameterObject);
  }

  @Override
  public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler) throws SQLException {
    //根据参数获取 BoundSql
    BoundSql boundSql = ms.getBoundSql(parameterObject);
    //创建 CacheKey
    CacheKey key = createCacheKey(ms, parameterObject, rowBounds, boundSql);
    //查询
    return query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
  }

  @Override
  public <E> Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws SQLException {
    //如果需要清空缓存, 则进行清空
    flushCacheIfRequired(ms);
    //执行 delegate 对应的方法, 游标是没办法缓存的
    return delegate.queryCursor(ms, parameter, rowBounds);
  }

  @Override
  public <E> List<E> query(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, CacheKey key, BoundSql boundSql)
      throws SQLException {
    //获取 Cache 对象
    Cache cache = ms.getCache();
    //如果为null的话, 说明当前 MappedStatement 没有设置二级缓存
    //如果 Cache 对象不为null
    if (cache != null) {
      //如果需要清空缓存, 则清空一下
      flushCacheIfRequired(ms);
      //如果当前语句使用缓存且 resultHandler为null
      if (ms.isUseCache() && resultHandler == null) {
        ensureNoOutParams(ms, boundSql);
        //从二级缓存中, 获取结果
        @SuppressWarnings("unchecked")
        List<E> list = (List<E>) tcm.getObject(cache, key);
        //如果缓存中不存在
        if (list == null) {
          //调用 executor 去查询
          list = delegate.query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
          //查询完后, 保存到二级缓存中
          tcm.putObject(cache, key, list); // issue #578 and #116
        }
        //如果缓存中有, 直接返回结果
        return list;
      }
    }
    //如果当前namespace没有设置缓存, 或当前sql操作语句不使用缓存, 则直接调用 executor 去查询
    return delegate.query(ms, parameterObject, rowBounds, resultHandler, key, boundSql);
  }

  @Override
  public List<BatchResult> flushStatements() throws SQLException {
    return delegate.flushStatements();
  }

  @Override
  public void commit(boolean required) throws SQLException {
    //代理的Executor先提交
    delegate.commit(required);
    //然后事务缓存管理器再提交
    tcm.commit();
  }

  @Override
  public void rollback(boolean required) throws SQLException {
    try {
      //代理的 Exector 先回滚
      delegate.rollback(required);
    } finally {
      if (required) {
        //事务缓存管理器再回滚
        tcm.rollback();
      }
    }
  }

  private void ensureNoOutParams(MappedStatement ms, BoundSql boundSql) {
    if (ms.getStatementType() == StatementType.CALLABLE) {
      for (ParameterMapping parameterMapping : boundSql.getParameterMappings()) {
        if (parameterMapping.getMode() != ParameterMode.IN) {
          throw new ExecutorException("Caching stored procedures with OUT params is not supported.  Please configure useCache=false in " + ms.getId() + " statement.");
        }
      }
    }
  }

  @Override
  public CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) {
    return delegate.createCacheKey(ms, parameterObject, rowBounds, boundSql);
  }

  @Override
  public boolean isCached(MappedStatement ms, CacheKey key) {
    return delegate.isCached(ms, key);
  }

  @Override
  public void deferLoad(MappedStatement ms, MetaObject resultObject, String property, CacheKey key, Class<?> targetType) {
    delegate.deferLoad(ms, resultObject, property, key, targetType);
  }

  @Override
  public void clearLocalCache() {
    delegate.clearLocalCache();
  }

  private void flushCacheIfRequired(MappedStatement ms) {
    //获取缓存对象
    Cache cache = ms.getCache();
    //如果缓存对象不为null, 且当前 MappedStatement 要刷新缓存
    if (cache != null && ms.isFlushCacheRequired()) {
      //事务缓存管理器清空缓存
      tcm.clear(cache);
    }
  }

  @Override
  public void setExecutorWrapper(Executor executor) {
    throw new UnsupportedOperationException("This method should not be called");
  }

}

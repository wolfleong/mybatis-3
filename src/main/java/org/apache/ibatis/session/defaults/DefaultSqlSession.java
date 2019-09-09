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
package org.apache.ibatis.session.defaults;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.binding.BindingException;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.exceptions.ExceptionFactory;
import org.apache.ibatis.exceptions.TooManyResultsException;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.result.DefaultMapResultHandler;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSession;

/**
 * SqlSession 的默认实现
 * The default implementation for {@link SqlSession}.
 * Note that this class is not Thread-Safe.
 *
 * @author Clinton Begin
 */
public class DefaultSqlSession implements SqlSession {

  /**
   * 全局配置
   */
  private final Configuration configuration;
  /**
   * 执行器
   */
  private final Executor executor;

  /**
   * 是否自动提交
   */
  private final boolean autoCommit;
  /**
   * 是否发现数据变更
   */
  private boolean dirty;
  /**
   * Cursor 数组
   */
  private List<Cursor<?>> cursorList;

  public DefaultSqlSession(Configuration configuration, Executor executor, boolean autoCommit) {
    this.configuration = configuration;
    this.executor = executor;
    this.dirty = false;
    this.autoCommit = autoCommit;
  }

  public DefaultSqlSession(Configuration configuration, Executor executor) {
    this(configuration, executor, false);
  }

  @Override
  public <T> T selectOne(String statement) {
    //查询单个值
    return this.selectOne(statement, null);
  }

  @Override
  public <T> T selectOne(String statement, Object parameter) {
    //获取列表
    // Popular vote was to return null on 0 results and throw exception on too many.
    List<T> list = this.selectList(statement, parameter);
    //如果 list 列表长度为 1
    if (list.size() == 1) {
      //获取结果
      return list.get(0);
      //如果多于 1, 则抛错
    } else if (list.size() > 1) {
      throw new TooManyResultsException("Expected one result (or null) to be returned by selectOne(), but found: " + list.size());
    } else {
      //没有结果, 则返回 null
      return null;
    }
  }

  /**
   * 根据 mapKey 聚合查询 Map
   */
  @Override
  public <K, V> Map<K, V> selectMap(String statement, String mapKey) {
    return this.selectMap(statement, null, mapKey, RowBounds.DEFAULT);
  }

  @Override
  public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey) {
    return this.selectMap(statement, parameter, mapKey, RowBounds.DEFAULT);
  }

  @Override
  public <K, V> Map<K, V> selectMap(String statement, Object parameter, String mapKey, RowBounds rowBounds) {
    //查询列表
    final List<? extends V> list = selectList(statement, parameter, rowBounds);
    //创建 DefaultMapResultHandler , 为什么要这么处理呢, 因为需要根据 mapKey 将 list 聚合成 Map
    final DefaultMapResultHandler<K, V> mapResultHandler = new DefaultMapResultHandler<>(mapKey,
            configuration.getObjectFactory(), configuration.getObjectWrapperFactory(), configuration.getReflectorFactory());
    //创建 DefaultResultContext
    final DefaultResultContext<V> context = new DefaultResultContext<>();
    //遍历结果
    for (V o : list) {
      //添加到 DefaultResultContext
      context.nextResultObject(o);
      //处理 DefaultResultContext
      mapResultHandler.handleResult(context);
    }
    //返回查询的结果
    return mapResultHandler.getMappedResults();
  }

  @Override
  public <T> Cursor<T> selectCursor(String statement) {
    return selectCursor(statement, null);
  }

  @Override
  public <T> Cursor<T> selectCursor(String statement, Object parameter) {
    return selectCursor(statement, parameter, RowBounds.DEFAULT);
  }

  @Override
  public <T> Cursor<T> selectCursor(String statement, Object parameter, RowBounds rowBounds) {
    try {
      //获取 MappedStatement
      MappedStatement ms = configuration.getMappedStatement(statement);
      //执行返回 Cursor
      Cursor<T> cursor = executor.queryCursor(ms, wrapCollection(parameter), rowBounds);
      //注册 Cursor
      registerCursor(cursor);
      //返回查询出来的 Cursor
      return cursor;
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error querying database.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  @Override
  public <E> List<E> selectList(String statement) {
    //不带参数查询
    return this.selectList(statement, null);
  }

  @Override
  public <E> List<E> selectList(String statement, Object parameter) {
    //带参数查询
    return this.selectList(statement, parameter, RowBounds.DEFAULT);
  }

  @Override
  public <E> List<E> selectList(String statement, Object parameter, RowBounds rowBounds) {
    try {
      //获取 MappedStatement 对象
      MappedStatement ms = configuration.getMappedStatement(statement);
      // wrapCollection 处理集合
      //调用 executor 查询
      return executor.query(ms, wrapCollection(parameter), rowBounds, Executor.NO_RESULT_HANDLER);
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error querying database.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  @Override
  public void select(String statement, Object parameter, ResultHandler handler) {
    //不带返回结果的查询
    select(statement, parameter, RowBounds.DEFAULT, handler);
  }

  @Override
  public void select(String statement, ResultHandler handler) {
    select(statement, null, RowBounds.DEFAULT, handler);
  }

  @Override
  public void select(String statement, Object parameter, RowBounds rowBounds, ResultHandler handler) {
    try {
      //获取 MappedStatement
      MappedStatement ms = configuration.getMappedStatement(statement);
      //查询
      executor.query(ms, wrapCollection(parameter), rowBounds, handler);
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error querying database.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  @Override
  public int insert(String statement) {
    return insert(statement, null);
  }

  @Override
  public int insert(String statement, Object parameter) {
    return update(statement, parameter);
  }

  @Override
  public int update(String statement) {
    return update(statement, null);
  }

  @Override
  public int update(String statement, Object parameter) {
    try {
      //标记数据已经改变
      dirty = true;
      //获取 MappedStatement
      MappedStatement ms = configuration.getMappedStatement(statement);
      //调用更新接口
      return executor.update(ms, wrapCollection(parameter));
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error updating database.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  @Override
  public int delete(String statement) {
    return update(statement, null);
  }

  @Override
  public int delete(String statement, Object parameter) {
    return update(statement, parameter);
  }

  @Override
  public void commit() {
    commit(false);
  }

  @Override
  public void commit(boolean force) {
    try {
      //调用提交
      executor.commit(isCommitOrRollbackRequired(force));
      //标记
      dirty = false;
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error committing transaction.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  @Override
  public void rollback() {
    //回滚
    rollback(false);
  }

  @Override
  public void rollback(boolean force) {
    try {
      //回滚 , 只有非自动提交且执行过update/insert/delete才会回滚事务, isCommitOrRollbackRequired
      executor.rollback(isCommitOrRollbackRequired(force));
      dirty = false;
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error rolling back transaction.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  @Override
  public List<BatchResult> flushStatements() {
    try {
      //刷新批量执行语句
      return executor.flushStatements();
    } catch (Exception e) {
      throw ExceptionFactory.wrapException("Error flushing statements.  Cause: " + e, e);
    } finally {
      ErrorContext.instance().reset();
    }
  }

  @Override
  public void close() {
    try {
      //关闭 executor
      executor.close(isCommitOrRollbackRequired(false));
      //关闭所有的游标
      closeCursors();
      //设置数据未变更
      dirty = false;
    } finally {
      ErrorContext.instance().reset();
    }
  }

  private void closeCursors() {
    //如果缓存的游标不为空
    if (cursorList != null && cursorList.size() != 0) {
      //遍历 Cursor
      for (Cursor<?> cursor : cursorList) {
        try {
          //关闭
          cursor.close();
        } catch (IOException e) {
          throw ExceptionFactory.wrapException("Error closing cursor.  Cause: " + e, e);
        }
      }
      //清空缓存
      cursorList.clear();
    }
  }

  @Override
  public Configuration getConfiguration() {
    return configuration;
  }

  @Override
  public <T> T getMapper(Class<T> type) {
    //根据 javaType 获取 Mapper
    return configuration.getMapper(type, this);
  }

  @Override
  public Connection getConnection() {
    try {
      //获取连接
      return executor.getTransaction().getConnection();
    } catch (SQLException e) {
      throw ExceptionFactory.wrapException("Error getting a new connection.  Cause: " + e, e);
    }
  }

  @Override
  public void clearCache() {
    //清空本地缓存
    executor.clearLocalCache();
  }

  private <T> void registerCursor(Cursor<T> cursor) {
    //如果 cursor 列表为 null
    if (cursorList == null) {
      //初始化
      cursorList = new ArrayList<>();
    }
    //添加 Cursor
    cursorList.add(cursor);
  }

  /**
   * 是否必须提交或回滚
   */
  private boolean isCommitOrRollbackRequired(boolean force) {
    //非自动提交且有数据变更
    return (!autoCommit && dirty) || force;
  }

  /**
   * 如果参数是 Collection 或 list 或 数据, 用 Map 包装起来
   */
  private Object wrapCollection(final Object object) {
    if (object instanceof Collection) {
      StrictMap<Object> map = new StrictMap<>();
      map.put("collection", object);
      if (object instanceof List) {
        map.put("list", object);
      }
      return map;
    } else if (object != null && object.getClass().isArray()) {
      StrictMap<Object> map = new StrictMap<>();
      map.put("array", object);
      return map;
    }
    return object;
  }

  /**
   * 严格的 Map
   */
  public static class StrictMap<V> extends HashMap<String, V> {

    private static final long serialVersionUID = -5741767162221585340L;

    @Override
    public V get(Object key) {
      //如果没找到对应 key 的值, 抛异常
      if (!super.containsKey(key)) {
        throw new BindingException("Parameter '" + key + "' not found. Available parameters are " + this.keySet());
      }
      return super.get(key);
    }

  }

}

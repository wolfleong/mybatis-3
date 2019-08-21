/**
 *    Copyright 2009-2018 the original author or authors.
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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * 可重用的 Executor 实现类, 主要跟 SimpleExecutor 的区别在于 prepareStatement 方法
 * reuse: 可重用的
 * - 每次开始读或写操作，优先从缓存中获取对应的 Statement 对象。如果不存在，才进行创建
 * - 执行完成后，不关闭该 Statement 对象
 * @author Clinton Begin
 */
public class ReuseExecutor extends BaseExecutor {

  /**
   * Statement 的缓存, sql字符串作key, Statement对象做value
   */
  private final Map<String, Statement> statementMap = new HashMap<>();

  public ReuseExecutor(Configuration configuration, Transaction transaction) {
    super(configuration, transaction);
  }

  @Override
  public int doUpdate(MappedStatement ms, Object parameter) throws SQLException {
    //获取全局配置
    Configuration configuration = ms.getConfiguration();
    //创建 StatementHandler
    StatementHandler handler = configuration.newStatementHandler(this, ms, parameter, RowBounds.DEFAULT, null, null);
    //初始化 StatementHandler 对象, 并返回 Statement
    Statement stmt = prepareStatement(handler, ms.getStatementLog());
    return handler.update(stmt);
  }

  @Override
  public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
    //操作与 SimpleExecutor 一样, 但执行完后不关闭 Statement
    Configuration configuration = ms.getConfiguration();
    StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, resultHandler, boundSql);
    Statement stmt = prepareStatement(handler, ms.getStatementLog());
    return handler.query(stmt, resultHandler);
  }

  @Override
  protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql) throws SQLException {
    //操作与 SimpleExecutor 一样, 但执行完后不关闭 Statement
    Configuration configuration = ms.getConfiguration();
    StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
    Statement stmt = prepareStatement(handler, ms.getStatementLog());
    return handler.queryCursor(stmt);
  }

  /**
   * - ReuseExecutor 考虑到重用性，但是 Statement 最终还是需要有地方关闭。答案就在 #doFlushStatements(boolean isRollback) 方法中。
   * 而 BaseExecutor 在关闭 #close() 方法中，最终也会调用该方法，从而完成关闭缓存的 Statement 对象们.
   * - 另外，BaseExecutor 在提交或者回滚事务方法中，最终也会调用该方法，也能完成关闭缓存的 Statement 对象们
   */
  @Override
  public List<BatchResult> doFlushStatements(boolean isRollback) {
    //遍历缓存的所有 Statement
    for (Statement stmt : statementMap.values()) {
      //关闭 Statement
      closeStatement(stmt);
    }
    //清空缓存
    statementMap.clear();
    //返回空列表
    return Collections.emptyList();
  }

  private Statement prepareStatement(StatementHandler handler, Log statementLog) throws SQLException {
    Statement stmt;
    //获取 BoundSql
    BoundSql boundSql = handler.getBoundSql();
    //获取sql
    String sql = boundSql.getSql();
    //判断缓存中是否存在 sql 对应的 statement
    if (hasStatementFor(sql)) {
      //如果存在, 则获取 sql 对应的 Statement
      stmt = getStatement(sql);
      //设置事务超时时间, 这里与 SimpleExecutor 不一样
      applyTransactionTimeout(stmt);
      //如果缓存中不存在对应的 Statement
    } else {
      //获取 数据库连接
      Connection connection = getConnection(statementLog);
      //创建 Statement 对象
      stmt = handler.prepare(connection, transaction.getTimeout());
      //缓存 Statement
      putStatement(sql, stmt);
    }
    //初始化参数
    handler.parameterize(stmt);
    return stmt;
  }

  /**
   * 判断是否存在 Statement 缓存
   */
  private boolean hasStatementFor(String sql) {
    try {
      return statementMap.keySet().contains(sql) && !statementMap.get(sql).getConnection().isClosed();
    } catch (SQLException e) {
      return false;
    }
  }

  /**
   * 根据 sql 获取 Statement
   */
  private Statement getStatement(String s) {
    return statementMap.get(s);
  }

  /**
   * 缓存 Statement
   */
  private void putStatement(String sql, Statement stmt) {
    statementMap.put(sql, stmt);
  }

}

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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;

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
 * 简单的 Executor 实现类
 * - 每次开始读或写操作, 都创建对应的Statement对象
 * - 执行完成后, 关闭 Statement 对象
 * @author Clinton Begin
 */
public class SimpleExecutor extends BaseExecutor {

  public SimpleExecutor(Configuration configuration, Transaction transaction) {
    super(configuration, transaction);
  }

  @Override
  public int doUpdate(MappedStatement ms, Object parameter) throws SQLException {
    Statement stmt = null;
    try {
      //获取全局配置
      Configuration configuration = ms.getConfiguration();
      //创建 StatementHandler 对象
      StatementHandler handler = configuration.newStatementHandler(this, ms, parameter, RowBounds.DEFAULT, null, null);
      //初始化 prepareStatement
      stmt = prepareStatement(handler, ms.getStatementLog());
      //执行更新
      return handler.update(stmt);
    } finally {
      //关闭 Statement
      closeStatement(stmt);
    }
  }

  @Override
  public <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
    //statement 对象
    Statement stmt = null;
    try {
      //获取全局配置
      Configuration configuration = ms.getConfiguration();
      //创建一个 StatementHandler 对象
      StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, resultHandler, boundSql);
      //初始化 StatementHandler 对象
      stmt = prepareStatement(handler, ms.getStatementLog());
      //进行读操作
      return handler.query(stmt, resultHandler);
    } finally {
      //判断 StatementHandler
      closeStatement(stmt);
    }
  }

  @Override
  protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql) throws SQLException {
    //获取全局配置
    Configuration configuration = ms.getConfiguration();
    //创建一个 StatementHandler 对象
    StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
    //初始化 StatementHandler 对象
    Statement stmt = prepareStatement(handler, ms.getStatementLog());
    //设置 Statement , 如果执行完成, 则进行自动关闭
    stmt.closeOnCompletion();
    //执行 StatementHandler 进行读操作
    return handler.queryCursor(stmt);
  }

  @Override
  public List<BatchResult> doFlushStatements(boolean isRollback) {
    //不存在批量操作的情况, 所以直接返回空数组
    return Collections.emptyList();
  }

  /**
   * 创建 Statement 对象, 并做一些初始化操作
   */
  private Statement prepareStatement(StatementHandler handler, Log statementLog) throws SQLException {
    Statement stmt;
    //处理 Connection, 如果日志是debug级别, 则返回日志代理Connection
    Connection connection = getConnection(statementLog);
    //创建 Statement 或 PrepareStatement 对象
    stmt = handler.prepare(connection, transaction.getTimeout());
    // 设置 SQL 上的参数，例如 PrepareStatement 对象上的占位符
    handler.parameterize(stmt);
    return stmt;
  }

}

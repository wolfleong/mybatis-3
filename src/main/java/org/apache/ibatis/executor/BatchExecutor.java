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

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;

/**
 * 批量执行的 Executor 实现类
 * @author Jeff Butler
 */
public class BatchExecutor extends BaseExecutor {

  public static final int BATCH_UPDATE_RETURN_VALUE = Integer.MIN_VALUE + 1002;

  /**
   * statement 列表
   */
  private final List<Statement> statementList = new ArrayList<>();
  /**
   * BatchResult 列表
   * - 每一个 BatchResult 元素, 对应一个 statementList 中的一个 Statement 元素
   */
  private final List<BatchResult> batchResultList = new ArrayList<>();
  /**
   * 当前的sql
   */
  private String currentSql;
  /**
   * 当前的 MappedStatement
   */
  private MappedStatement currentStatement;

  public BatchExecutor(Configuration configuration, Transaction transaction) {
    super(configuration, transaction);
  }

  @Override
  public int doUpdate(MappedStatement ms, Object parameterObject) throws SQLException {
    //获取全局配置
    final Configuration configuration = ms.getConfiguration();
    //创建 StatementHandler 对象
    final StatementHandler handler = configuration.newStatementHandler(this, ms, parameterObject, RowBounds.DEFAULT, null, null);
    //获取 BoundSql
    final BoundSql boundSql = handler.getBoundSql();
    //获取Sql字符串
    final String sql = boundSql.getSql();
    final Statement stmt;
    //如果执行的sql是当前的 currentSql 且 MappedStatement 是 currentStatement
    if (sql.equals(currentSql) && ms.equals(currentStatement)) {
      //获取最后一个 Statement 的索引
      int last = statementList.size() - 1;
      //获取当前列表的最后一个 Statement
      stmt = statementList.get(last);
      //设置事务超时时间
      applyTransactionTimeout(stmt);
      //设置参数
      handler.parameterize(stmt);//fix Issues 322
      //获取最后一个 BatchResult
      BatchResult batchResult = batchResultList.get(last);
      //添加参数对象
      batchResult.addParameterObject(parameterObject);
      //如果不是当前的 currentSql 和 currentStatement
    } else {
      //获取 Connection
      Connection connection = getConnection(ms.getStatementLog());
      //创建一个Statemrnt
      stmt = handler.prepare(connection, transaction.getTimeout());
      //设置参数
      handler.parameterize(stmt);    //fix Issues 322
      //复盖当前的 currentSql
      currentSql = sql;
      //复盖当前的 currentMappedStatement
      currentStatement = ms;
      //添加到 statementList
      statementList.add(stmt);
      //创建并添加一个 BatchResult
      batchResultList.add(new BatchResult(ms, sql, parameterObject));
    }
    //批量处理 语句
    handler.batch(stmt);
    return BATCH_UPDATE_RETURN_VALUE;
  }

  @Override
  public <E> List<E> doQuery(MappedStatement ms, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql)
      throws SQLException {
    Statement stmt = null;
    try {
      //执行查询前, 刷入批处理语句
      flushStatements();
      //获取全局配置
      Configuration configuration = ms.getConfiguration();
      //创建 StatementHandler
      StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameterObject, rowBounds, resultHandler, boundSql);
      //获取 Connection
      Connection connection = getConnection(ms.getStatementLog());
      //创建 Statement
      stmt = handler.prepare(connection, transaction.getTimeout());
      //设置参数
      handler.parameterize(stmt);
      //查询
      return handler.query(stmt, resultHandler);
    } finally {
      //关闭 Statement
      closeStatement(stmt);
    }
  }

  @Override
  protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql) throws SQLException {
    //执行查询前, 刷入批处理语句
    flushStatements();
    //获取全局配置
    Configuration configuration = ms.getConfiguration();
    //创建 StatementHandler
    StatementHandler handler = configuration.newStatementHandler(wrapper, ms, parameter, rowBounds, null, boundSql);
    //获取 Connection
    Connection connection = getConnection(ms.getStatementLog());
    //创建 Statement
    Statement stmt = handler.prepare(connection, transaction.getTimeout());
    //设置完成时, 关闭
    stmt.closeOnCompletion();
    //设置参数
    handler.parameterize(stmt);
    //查询
    return handler.queryCursor(stmt);
  }

  @Override
  public List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {
    try {
      List<BatchResult> results = new ArrayList<>();
      //如果 isRollback 为 true, 则返回空数组
      if (isRollback) {
        return Collections.emptyList();
      }
      //遍历 statementList
      for (int i = 0, n = statementList.size(); i < n; i++) {
        //获取 Statement 对象
        Statement stmt = statementList.get(i);
        //设置事务超时时间
        applyTransactionTimeout(stmt);
        //获取 BatchResult
        BatchResult batchResult = batchResultList.get(i);
        try {
          //批量执行 Statement , 并记录执行结果
          batchResult.setUpdateCounts(stmt.executeBatch());
          //获取 MappedStatement
          MappedStatement ms = batchResult.getMappedStatement();
          //获取参数对象
          List<Object> parameterObjects = batchResult.getParameterObjects();
          //获取 KeyGenerator
          KeyGenerator keyGenerator = ms.getKeyGenerator();
          //如果是 Jdbc3KeyGenerator
          if (Jdbc3KeyGenerator.class.equals(keyGenerator.getClass())) {
            //强转
            Jdbc3KeyGenerator jdbc3KeyGenerator = (Jdbc3KeyGenerator) keyGenerator;
            //批量处理
            jdbc3KeyGenerator.processBatch(ms, stmt, parameterObjects);
            //如果不是 NoKeyGenerator
          } else if (!NoKeyGenerator.class.equals(keyGenerator.getClass())) { //issue #141
            //遍历参数, 逐个处理
            for (Object parameter : parameterObjects) {
              keyGenerator.processAfter(this, ms, stmt, parameter);
            }
          }
          //关闭 Statement
          // Close statement to close cursor #1109
          closeStatement(stmt);
        } catch (BatchUpdateException e) {
          //如果发生异常, 则抛出
          StringBuilder message = new StringBuilder();
          message.append(batchResult.getMappedStatement().getId())
              .append(" (batch index #")
              .append(i + 1)
              .append(")")
              .append(" failed.");
          if (i > 0) {
            message.append(" ")
                .append(i)
                .append(" prior sub executor(s) completed successfully, but will be rolled back.");
          }
          throw new BatchExecutorException(message.toString(), e, results, batchResult);
        }
        //记录结果
        results.add(batchResult);
      }
      //返回
      return results;
    } finally {
      //代码执行完成后, 逐个关闭 Statement
      for (Statement stmt : statementList) {
        closeStatement(stmt);
      }
      //清空 currentSql
      currentSql = null;
      //清空 statementList
      statementList.clear();
      //清空 batchResultList
      batchResultList.clear();
    }
  }

}

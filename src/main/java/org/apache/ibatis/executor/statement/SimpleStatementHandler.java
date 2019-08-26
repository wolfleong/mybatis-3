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
package org.apache.ibatis.executor.statement;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * 普通的 Statement 处理器
 * @author Clinton Begin
 */
public class SimpleStatementHandler extends BaseStatementHandler {

  public SimpleStatementHandler(Executor executor, MappedStatement mappedStatement, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
    super(executor, mappedStatement, parameter, rowBounds, resultHandler, boundSql);
  }

  @Override
  public int update(Statement statement) throws SQLException {
    //获取 sql
    String sql = boundSql.getSql();
    //获取参数列表
    Object parameterObject = boundSql.getParameterObject();
    //获取 keyGenerator
    KeyGenerator keyGenerator = mappedStatement.getKeyGenerator();
    int rows;
    //如果 keyGenerator 是 Jdbc3KeyGenerator
    if (keyGenerator instanceof Jdbc3KeyGenerator) {
      //执行 sql, 设置获取自动递增的id号
      statement.execute(sql, Statement.RETURN_GENERATED_KEYS);
      //返回更新的条数
      rows = statement.getUpdateCount();
      //处理结果
      keyGenerator.processAfter(executor, mappedStatement, statement, parameterObject);
      //如果 keyGenerator 是 SelectKeyGenerator
    } else if (keyGenerator instanceof SelectKeyGenerator) {
      //执行 sql
      statement.execute(sql);
      //获取更新的条数
      rows = statement.getUpdateCount();
      //处理返回的主键
      keyGenerator.processAfter(executor, mappedStatement, statement, parameterObject);
      //其他情况
    } else {
      //直接执行 sql
      statement.execute(sql);
      //获取更新的条数
      rows = statement.getUpdateCount();
    }
    //返回更新结果
    return rows;
  }

  @Override
  public void batch(Statement statement) throws SQLException {
    String sql = boundSql.getSql();
    //添加 sql 到批处理
    statement.addBatch(sql);
  }

  @Override
  public <E> List<E> query(Statement statement, ResultHandler resultHandler) throws SQLException {
    //获取 sql
    String sql = boundSql.getSql();
    //执行查询
    statement.execute(sql);
    // 处理返回的 Cursor 结果
    return resultSetHandler.handleResultSets(statement);
  }

  @Override
  public <E> Cursor<E> queryCursor(Statement statement) throws SQLException {
    String sql = boundSql.getSql();
    statement.execute(sql);
    return resultSetHandler.handleCursorResultSets(statement);
  }

  /**
   * 创建 Statement 对象
   */
  @Override
  protected Statement instantiateStatement(Connection connection) throws SQLException {
    //如果 resultSetType 是 ResultSetType.DEFAULT
    if (mappedStatement.getResultSetType() == ResultSetType.DEFAULT) {
      //直接创建
      return connection.createStatement();
    } else {
      //创建指定 ResultSetType 的
      return connection.createStatement(mappedStatement.getResultSetType().getValue(), ResultSet.CONCUR_READ_ONLY);
    }
  }

  @Override
  public void parameterize(Statement statement) {
    // N/A
  }

}

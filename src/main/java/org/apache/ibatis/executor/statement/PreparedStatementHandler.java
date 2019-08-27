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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * PreparedStatement的 StatementHandler 实现类
 * @author Clinton Begin
 */
public class PreparedStatementHandler extends BaseStatementHandler {

  public PreparedStatementHandler(Executor executor, MappedStatement mappedStatement, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
    super(executor, mappedStatement, parameter, rowBounds, resultHandler, boundSql);
  }

  @Override
  public int update(Statement statement) throws SQLException {
    //强转
    PreparedStatement ps = (PreparedStatement) statement;
    //执行 sql
    ps.execute();
    //返回处理的结果
    int rows = ps.getUpdateCount();
    //获取参数对象
    Object parameterObject = boundSql.getParameterObject();
    //获取 keyGenerator
    KeyGenerator keyGenerator = mappedStatement.getKeyGenerator();
    //处理返回的主键
    keyGenerator.processAfter(executor, mappedStatement, ps, parameterObject);
    return rows;
  }

  @Override
  public void batch(Statement statement) throws SQLException {
    PreparedStatement ps = (PreparedStatement) statement;
    //添加到批处理
    ps.addBatch();
  }

  @Override
  public <E> List<E> query(Statement statement, ResultHandler resultHandler) throws SQLException {
    //强转
    PreparedStatement ps = (PreparedStatement) statement;
    //执行 sql
    ps.execute();
    //处理结果
    return resultSetHandler.handleResultSets(ps);
  }

  @Override
  public <E> Cursor<E> queryCursor(Statement statement) throws SQLException {
    //强转
    PreparedStatement ps = (PreparedStatement) statement;
    //执行sql
    ps.execute();
    //用 ResultSetHandler 处理结果
    return resultSetHandler.handleCursorResultSets(ps);
  }

  @Override
  protected Statement instantiateStatement(Connection connection) throws SQLException {
    //获取 sql
    String sql = boundSql.getSql();
    //  处理 Jdbc3KeyGenerator 的情况
    if (mappedStatement.getKeyGenerator() instanceof Jdbc3KeyGenerator) {
      //获取指定的 keyColumnNames
      String[] keyColumnNames = mappedStatement.getKeyColumns();
      //如果指定的 keyColumnNames 为 null
      if (keyColumnNames == null) {
        //指定自动生成主键要返回
        return connection.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS);
      } else {
        //创建一个能返回由给定数组指定的自动生成键的默认 PreparedStatement 对象
        return connection.prepareStatement(sql, keyColumnNames);
      }
      //如果 ResultSetType 是 ResultSetType.DEFAULT
    } else if (mappedStatement.getResultSetType() == ResultSetType.DEFAULT) {
      //直接创建 PrepareStatement, 默认游标只能向前移动, 并且不能改 ResultSet 的数据
      return connection.prepareStatement(sql);
    } else {
      // 创建指定的 ResultSetType 的 ResultSet , 并且 ResultSet 只读
      return connection.prepareStatement(sql, mappedStatement.getResultSetType().getValue(), ResultSet.CONCUR_READ_ONLY);
    }
  }

  @Override
  public void parameterize(Statement statement) throws SQLException {
    //调用 ParameterHandler 来设置参数
    parameterHandler.setParameters((PreparedStatement) statement);
  }

}

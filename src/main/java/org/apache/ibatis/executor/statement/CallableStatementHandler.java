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

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;

/**
 * 存储过程相关的 StatementHandler
 * @author Clinton Begin
 */
public class CallableStatementHandler extends BaseStatementHandler {

  public CallableStatementHandler(Executor executor, MappedStatement mappedStatement, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
    super(executor, mappedStatement, parameter, rowBounds, resultHandler, boundSql);
  }

  @Override
  public int update(Statement statement) throws SQLException {
    //获取 CallableStatement
    CallableStatement cs = (CallableStatement) statement;
    //执行存储过程
    cs.execute();
    //获取执行的结果
    int rows = cs.getUpdateCount();
    //获取参数
    Object parameterObject = boundSql.getParameterObject();
    //获取 keyGenerator
    KeyGenerator keyGenerator = mappedStatement.getKeyGenerator();
    //处理返回的主键
    keyGenerator.processAfter(executor, mappedStatement, cs, parameterObject);
    //处理输出参数
    resultSetHandler.handleOutputParameters(cs);
    return rows;
  }

  @Override
  public void batch(Statement statement) throws SQLException {
    CallableStatement cs = (CallableStatement) statement;
    //添加批次
    cs.addBatch();
  }

  @Override
  public <E> List<E> query(Statement statement, ResultHandler resultHandler) throws SQLException {
    //获取 CallableStatement
    CallableStatement cs = (CallableStatement) statement;
    //执行
    cs.execute();
    //处理结果
    List<E> resultList = resultSetHandler.handleResultSets(cs);
    //处理输出参数
    resultSetHandler.handleOutputParameters(cs);
    return resultList;
  }

  @Override
  public <E> Cursor<E> queryCursor(Statement statement) throws SQLException {
    //获取 CallableStatement
    CallableStatement cs = (CallableStatement) statement;
    //执行
    cs.execute();
    //处理查询结果
    Cursor<E> resultList = resultSetHandler.handleCursorResultSets(cs);
    //处理输出参数
    resultSetHandler.handleOutputParameters(cs);
    return resultList;
  }

  @Override
  protected Statement instantiateStatement(Connection connection) throws SQLException {
    //获取 sql
    String sql = boundSql.getSql();
    if (mappedStatement.getResultSetType() == ResultSetType.DEFAULT) {
      //创建默认的 CallableStatement
      return connection.prepareCall(sql);
    } else {
      //创建指定的 ResultSetType 且 只读的 CallableStatement
      return connection.prepareCall(sql, mappedStatement.getResultSetType().getValue(), ResultSet.CONCUR_READ_ONLY);
    }
  }

  @Override
  public void parameterize(Statement statement) throws SQLException {
    //设置查询参数
    registerOutputParameters((CallableStatement) statement);
    parameterHandler.setParameters((CallableStatement) statement);
  }

  private void registerOutputParameters(CallableStatement cs) throws SQLException {
    //获取参数映射列表
    List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    //遍历参数映射
    for (int i = 0, n = parameterMappings.size(); i < n; i++) {
      //获取 ParameterMapping
      ParameterMapping parameterMapping = parameterMappings.get(i);
      //如果此参数有输出类型
      if (parameterMapping.getMode() == ParameterMode.OUT || parameterMapping.getMode() == ParameterMode.INOUT) {
        //如果些输出参数没有设置 jdbcType, 则报错
        if (null == parameterMapping.getJdbcType()) {
          throw new ExecutorException("The JDBC Type must be specified for output parameter.  Parameter: " + parameterMapping.getProperty());
          //如果有设置 jdbcType
        } else {
          //如果参数有配置数字精度, 且参数的JdbcType是数字类型或者是decimal类型
          if (parameterMapping.getNumericScale() != null && (parameterMapping.getJdbcType() == JdbcType.NUMERIC || parameterMapping.getJdbcType() == JdbcType.DECIMAL)) {
            //注册一个输出参数, 并设置精度
            cs.registerOutParameter(i + 1, parameterMapping.getJdbcType().TYPE_CODE, parameterMapping.getNumericScale());
          } else {
            //如果参数的 JdbcTypeName 不为null
            if (parameterMapping.getJdbcTypeName() == null) {
              //注册输出参数
              cs.registerOutParameter(i + 1, parameterMapping.getJdbcType().TYPE_CODE);
            } else {
              //注册指定JdbcTypeName的输出参数
              cs.registerOutParameter(i + 1, parameterMapping.getJdbcType().TYPE_CODE, parameterMapping.getJdbcTypeName());
            }
          }
        }
      }
    }
  }

}

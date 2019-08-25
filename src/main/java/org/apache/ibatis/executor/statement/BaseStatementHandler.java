/**
 *    Copyright 2009-2016 the original author or authors.
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
import java.sql.SQLException;
import java.sql.Statement;

import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.resultset.ResultSetHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * 实现 StatementHandler 接口，StatementHandler 基类，提供骨架方法，从而使子类只要实现指定的几个抽象方法即可。
 * @author Clinton Begin
 */
public abstract class BaseStatementHandler implements StatementHandler {

  /**
   * 全局配置对象
   */
  protected final Configuration configuration;
  /**
   * 对象创建工厂
   */
  protected final ObjectFactory objectFactory;
  /**
   * TypeHandlerRegistry 注册器
   */
  protected final TypeHandlerRegistry typeHandlerRegistry;
  /**
   * 结果处理器
   */
  protected final ResultSetHandler resultSetHandler;
  /**
   * 参数处理器
   */
  protected final ParameterHandler parameterHandler;

  /**
   * Executor
   */
  protected final Executor executor;
  /**
   * MappedStatement
   */
  protected final MappedStatement mappedStatement;
  /**
   * 分页参数
   */
  protected final RowBounds rowBounds;

  /**
   * BoundSql
   */
  protected BoundSql boundSql;

  protected BaseStatementHandler(Executor executor, MappedStatement mappedStatement, Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
    //赋值
    this.configuration = mappedStatement.getConfiguration();
    this.executor = executor;
    this.mappedStatement = mappedStatement;
    this.rowBounds = rowBounds;

    // 获得 TypeHandlerRegistry 和 ObjectFactory 对象
    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.objectFactory = configuration.getObjectFactory();

    //如果 boundSql 为空，一般是写类操作，例如：insert、update、delete ，则先获得自增主键，然后再创建 BoundSql 对象
    if (boundSql == null) { // issue #435, get the key before calculating the statement
      //获取自增主键
      generateKeys(parameterObject);
      //获取 BoundSql
      boundSql = mappedStatement.getBoundSql(parameterObject);
    }

    this.boundSql = boundSql;

    //创建 ParameterHandler
    this.parameterHandler = configuration.newParameterHandler(mappedStatement, parameterObject, boundSql);
    //创建 ResultSetHandler
    this.resultSetHandler = configuration.newResultSetHandler(executor, mappedStatement, rowBounds, parameterHandler, resultHandler, boundSql);
  }

  @Override
  public BoundSql getBoundSql() {
    return boundSql;
  }

  @Override
  public ParameterHandler getParameterHandler() {
    return parameterHandler;
  }

  /**
   * 准备并且创建 Statement
   * @param connection 连接对象
   * @param transactionTimeout 事务超时时间
   */
  @Override
  public Statement prepare(Connection connection, Integer transactionTimeout) throws SQLException {
    //记录一下要执行的sql
    ErrorContext.instance().sql(boundSql.getSql());
    Statement statement = null;
    try {
      //初始化 Statement
      statement = instantiateStatement(connection);
      //设置超时时间
      setStatementTimeout(statement, transactionTimeout);
      //设置 FetchSize
      setFetchSize(statement);
      return statement;
    } catch (SQLException e) {
      //抛异常就关闭 Statement
      closeStatement(statement);
      throw e;
    } catch (Exception e) {
      closeStatement(statement);
      throw new ExecutorException("Error preparing statement.  Cause: " + e, e);
    }
  }

  protected abstract Statement instantiateStatement(Connection connection) throws SQLException;

  /**
   * 设置超时时间
   */
  protected void setStatementTimeout(Statement stmt, Integer transactionTimeout) throws SQLException {
    Integer queryTimeout = null;
    //如果 MappedStatement 有设置超时时间, 则用这个
    if (mappedStatement.getTimeout() != null) {
      queryTimeout = mappedStatement.getTimeout();
      //如果 MappedStatement 没有设置超时时间, 则获取全局配置的超时间
    } else if (configuration.getDefaultStatementTimeout() != null) {
      queryTimeout = configuration.getDefaultStatementTimeout();
    }
    //如果statement的超时时间最终有配置, 则设置
    if (queryTimeout != null) {
      stmt.setQueryTimeout(queryTimeout);
    }
    //设置事务超时时间
    //比较 queryTimeout 和 transactionTimeout 那个短取那个
    StatementUtil.applyTransactionTimeout(stmt, queryTimeout, transactionTimeout);
  }

  protected void setFetchSize(Statement stmt) throws SQLException {
    //获取 fetchSize . 非空则设置
    Integer fetchSize = mappedStatement.getFetchSize();
    if (fetchSize != null) {
      stmt.setFetchSize(fetchSize);
      return;
    }
    //如果没有配置 fetchSize , 取获取全局默认配置的 defaultFetchSize, 如果不为null, 则设置
    Integer defaultFetchSize = configuration.getDefaultFetchSize();
    if (defaultFetchSize != null) {
      stmt.setFetchSize(defaultFetchSize);
    }
  }

  /**
   * 关闭 Statement
   */
  protected void closeStatement(Statement statement) {
    try {
      if (statement != null) {
        statement.close();
      }
    } catch (SQLException e) {
      //ignore
    }
  }

  /**
   * 获取自增主键
   */
  protected void generateKeys(Object parameter) {
    KeyGenerator keyGenerator = mappedStatement.getKeyGenerator();
    ErrorContext.instance().store();
    //在 sql 执行之前执行
    keyGenerator.processBefore(executor, mappedStatement, null, parameter);
    ErrorContext.instance().recall();
  }

}

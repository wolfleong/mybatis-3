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
import java.util.List;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.ResultHandler;

/**
 *  StatementHandler 主要对 JDBC Statement 的各种操作
 * @author Clinton Begin
 */
public interface StatementHandler {

  /**
   * 准备操作, 可以理解成创建 Statement 对象
   * @param connection 连接对象
   * @param transactionTimeout 事务超时时间
   */
  Statement prepare(Connection connection, Integer transactionTimeout)
      throws SQLException;

  /**
   * 设置 Statement 对象的参数
   */
  void parameterize(Statement statement)
      throws SQLException;

  /**
   * 添加 Statement 对象的批量操作
   */
  void batch(Statement statement)
      throws SQLException;

  /**
   * 执行写操作
   */
  int update(Statement statement)
      throws SQLException;

  /**
   * 查询返回列表
   */
  <E> List<E> query(Statement statement, ResultHandler resultHandler)
      throws SQLException;

  /**
   * 查询返回 Cursor 对象
   */
  <E> Cursor<E> queryCursor(Statement statement)
      throws SQLException;

  /**
   * 返回 BoundSql 对象
   */
  BoundSql getBoundSql();

  /**
   * 获取 ParameterHandler
   */
  ParameterHandler getParameterHandler();

}

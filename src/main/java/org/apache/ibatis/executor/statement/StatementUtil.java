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

import java.sql.SQLException;
import java.sql.Statement;

/**
 * Statement 工具类
 * Utility for {@link java.sql.Statement}.
 *
 * @since 3.4.0
 * @author Kazuki Shimizu
 */
public class StatementUtil {

  private StatementUtil() {
    // NOP
  }

  /**
   * queryTimeout 为 statement中已经设置的
   * Apply a transaction timeout.
   * <p>
   * Update a query timeout to apply a transaction timeout.
   * </p>
   * @param statement a target statement
   * @param queryTimeout a query timeout
   * @param transactionTimeout a transaction timeout
   * @throws SQLException if a database access error occurs, this method is called on a closed <code>Statement</code>
   */
  public static void applyTransactionTimeout(Statement statement, Integer queryTimeout, Integer transactionTimeout) throws SQLException {
    //如果超时时间为null, 不做处理
    if (transactionTimeout == null){
      return;
    }
    Integer timeToLiveOfQuery = null;
    //如果 queryTimeout 时间未设置, 则取 transactionTimeout
    if (queryTimeout == null || queryTimeout == 0) {
      timeToLiveOfQuery = transactionTimeout;
      //如果 queryTimeout 时间有设置, 当 transactionTimeout 比 queryTimeout 小时, 才取 transactionTimeout
    } else if (transactionTimeout < queryTimeout) {
      timeToLiveOfQuery = transactionTimeout;
    }
    //如果要设置的时候不为null, 设置查询超时时间
    if (timeToLiveOfQuery != null) {
      statement.setQueryTimeout(timeToLiveOfQuery);
    }
  }

}

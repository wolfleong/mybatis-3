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
package org.apache.ibatis.logging.jdbc;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashSet;
import java.util.Set;
import java.util.StringJoiner;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.reflection.ExceptionUtil;

/**
 * ResultSet proxy to add logging.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 *
 */
public final class ResultSetLogger extends BaseJdbcLogger implements InvocationHandler {

  /**
   * 二进制类型或大对象类型列表
   */
  private static final Set<Integer> BLOB_TYPES = new HashSet<>();
  /**
   * 是否第一行, 如果是则打印列名
   */
  private boolean first = true;
  /**
   * 记录查询结果的条数
   */
  private int rows;
  private final ResultSet rs;
  /**
   * 记录二进制类型或大对象类型的索引, 用于打印具体列的值时用特殊字符代替
   */
  private final Set<Integer> blobColumns = new HashSet<>();

  static {
    //初始化二进制类型或大对象类型列表
    BLOB_TYPES.add(Types.BINARY);
    BLOB_TYPES.add(Types.BLOB);
    BLOB_TYPES.add(Types.CLOB);
    BLOB_TYPES.add(Types.LONGNVARCHAR);
    BLOB_TYPES.add(Types.LONGVARBINARY);
    BLOB_TYPES.add(Types.LONGVARCHAR);
    BLOB_TYPES.add(Types.NCLOB);
    BLOB_TYPES.add(Types.VARBINARY);
  }

  private ResultSetLogger(ResultSet rs, Log statementLog, int queryStack) {
    super(statementLog, queryStack);
    this.rs = rs;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] params) throws Throwable {
    try {
      //忽略Object对象的方法
      if (Object.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, params);
      }
      Object o = method.invoke(rs, params);
      if ("next".equals(method.getName())) {
        if ((Boolean) o) {
          //如果next取到值, 行数加一
          rows++;
          //如果是trace级别的日志, 记录更加详细的日志
          if (isTraceEnabled()) {
            //获取元数据
            ResultSetMetaData rsmd = rs.getMetaData();
            //获取列数
            final int columnCount = rsmd.getColumnCount();
            if (first) {
              //设置first为非第一行
              first = false;
              //第一行, 打印列名
              printColumnHeaders(rsmd, columnCount);
            }
            //打印列的值
            printColumnValues(columnCount);
          }
        } else {
          //最后一次获取不到值时, 打印sql总条数
          debug("     Total: " + rows, false);
        }
      }
      //这行不知道想干嘛, 对当前ResultSet的日志没影响
      clearColumnInfo();
      return o;
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }
  }

  /**
   * 打印列的表头
   */
  private void printColumnHeaders(ResultSetMetaData rsmd, int columnCount) throws SQLException {
    StringJoiner row = new StringJoiner(", ", "   Columns: ", "");
    for (int i = 1; i <= columnCount; i++) {
      //记录当前i列是否是BLOB_TYPE类型
      if (BLOB_TYPES.contains(rsmd.getColumnType(i))) {
        blobColumns.add(i);
      }
      //将列表放到字符串中
      row.add(rsmd.getColumnLabel(i));
    }
    //打印
    trace(row.toString(), false);
  }

  /**
   * 打印的值
   */
  private void printColumnValues(int columnCount) {
    StringJoiner row = new StringJoiner(", ", "       Row: ", "");
    for (int i = 1; i <= columnCount; i++) {
      try {
        //BLOB_TYPE类型的值不能显示, 用<<BLOB>>来显示
        if (blobColumns.contains(i)) {
          row.add("<<BLOB>>");
        } else {
          row.add(rs.getString(i));
        }
      } catch (SQLException e) {
        // generally can't call getString() on a BLOB column
        row.add("<<Cannot Display>>");
      }
    }
    trace(row.toString(), false);
  }

  /**
   * Creates a logging version of a ResultSet.
   *
   * @param rs - the ResultSet to proxy
   * @return - the ResultSet with logging
   */
  public static ResultSet newInstance(ResultSet rs, Log statementLog, int queryStack) {
    InvocationHandler handler = new ResultSetLogger(rs, statementLog, queryStack);
    ClassLoader cl = ResultSet.class.getClassLoader();
    return (ResultSet) Proxy.newProxyInstance(cl, new Class[]{ResultSet.class}, handler);
  }

  /**
   * Get the wrapped result set.
   *
   * @return the resultSet
   */
  public ResultSet getRs() {
    return rs;
  }

}

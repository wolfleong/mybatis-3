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

import java.lang.reflect.Method;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.stream.Collectors;

import org.apache.ibatis.logging.Log;
import org.apache.ibatis.reflection.ArrayUtil;

/**
 * Base class for proxies to do logging.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public abstract class BaseJdbcLogger {

  /**
   * 缓存所有set方法的名称
   */
  protected static final Set<String> SET_METHODS;
  /**
   * 存所有执行sql的方法
   */
  protected static final Set<String> EXECUTE_METHODS = new HashSet<>();

  /**
   * 保存sql的参数名和参数值
   */
  private final Map<Object, Object> columnMap = new HashMap<>();

  /**
   * 保存sql的参数名
   */
  private final List<Object> columnNames = new ArrayList<>();
  /**
   * 保存执行sql的参数值
   */
  private final List<Object> columnValues = new ArrayList<>();

  /**
   * 日志实例
   */
  protected final Log statementLog;
  /**
   * 目前主要发现用于控制prefix的=号的长度, 默认是1
   */
  protected final int queryStack;

  /*
   * Default constructor
   */
  public BaseJdbcLogger(Log log, int queryStack) {
    this.statementLog = log;
    if (queryStack == 0) {
      this.queryStack = 1;
    } else {
      this.queryStack = queryStack;
    }
  }

  static {
    //初始化所有set方法的名称, 只要PreparedStatement方法下, 以set开头, 且参数个数大于1的方法名
    SET_METHODS = Arrays.stream(PreparedStatement.class.getDeclaredMethods())
            .filter(method -> method.getName().startsWith("set"))
            .filter(method -> method.getParameterCount() > 1)
            .map(Method::getName)
            .collect(Collectors.toSet());
    //初始化要执行sql的方法名
    EXECUTE_METHODS.add("execute");
    EXECUTE_METHODS.add("executeUpdate");
    EXECUTE_METHODS.add("executeQuery");
    EXECUTE_METHODS.add("addBatch");
  }

  /**
   * 缓存列名和列值
   */
  protected void setColumn(Object key, Object value) {
    columnMap.put(key, value);
    columnNames.add(key);
    columnValues.add(value);
  }

  protected Object getColumn(Object key) {
    return columnMap.get(key);
  }

  /**
   * 将所有参数值拼接成字符串, 除了null之外的其他值都加上类开信息, 如 null,1(Integer),abc(String)
   */
  protected String getParameterValueString() {
    List<Object> typeList = new ArrayList<>(columnValues.size());
    for (Object value : columnValues) {
      if (value == null) {
        typeList.add("null");
      } else {
        typeList.add(objectValueString(value) + "(" + value.getClass().getSimpleName() + ")");
      }
    }
    //[null,1(Integer),abc(String)]
    final String parameters = typeList.toString();
    //去掉[], 变成null,1(Integer),abc(String)
    return parameters.substring(1, parameters.length() - 1);
  }

  /**
   * 如果值是数组类型, 返回[1,2]之类的值
   */
  protected String objectValueString(Object value) {
    if (value instanceof Array) {
      try {
        return ArrayUtil.toString(((Array) value).getArray());
      } catch (SQLException e) {
        return value.toString();
      }
    }
    return value.toString();
  }

  protected String getColumnString() {
    return columnNames.toString();
  }

  /**
   * 清空缓存
   */
  protected void clearColumnInfo() {
    columnMap.clear();
    columnNames.clear();
    columnValues.clear();
  }

  /**
   * 将original字符串中的" \t\n\r\f"这些字符替换成" "空串
   */
  protected String removeBreakingWhitespace(String original) {
    StringTokenizer whitespaceStripper = new StringTokenizer(original);
    StringBuilder builder = new StringBuilder();
    while (whitespaceStripper.hasMoreTokens()) {
      builder.append(whitespaceStripper.nextToken());
      builder.append(" ");
    }
    return builder.toString();
  }

  protected boolean isDebugEnabled() {
    return statementLog.isDebugEnabled();
  }

  protected boolean isTraceEnabled() {
    return statementLog.isTraceEnabled();
  }

  protected void debug(String text, boolean input) {
    if (statementLog.isDebugEnabled()) {
      //打印参数前, 加个前缀
      statementLog.debug(prefix(input) + text);
    }
  }

  protected void trace(String text, boolean input) {
    if (statementLog.isTraceEnabled()) {
      //打印参数前, 加个前缀
      statementLog.trace(prefix(input) + text);
    }
  }

  /**
   * 主要用于产生日志前缀, 如: ==>,<==
   * - ==> Parameters: 527(Long)
   * - <==    Updates: 1
   */
  private String prefix(boolean isInput) {
    char[] buffer = new char[queryStack * 2 + 2];
    Arrays.fill(buffer, '=');
    //最后面加个空格, queryStack * 2 + 1 = buffer.length - 1
    buffer[queryStack * 2 + 1] = ' ';
    if (isInput) {
      buffer[queryStack * 2] = '>';
    } else {
      buffer[0] = '<';
    }
    return new String(buffer);
  }

}

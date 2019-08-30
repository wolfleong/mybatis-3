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
package org.apache.ibatis.executor.resultset;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.ObjectTypeHandler;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;
import org.apache.ibatis.type.UnknownTypeHandler;

/**
 * ResultSet 的包装器, 可以理解成 ResultSet 的工具类
 * @author Iwao AVE!
 */
public class ResultSetWrapper {

  /**
   * ResultSet 对象
   */
  private final ResultSet resultSet;
  /**
   * 类型注册器
   */
  private final TypeHandlerRegistry typeHandlerRegistry;
  /**
   * 字段名列表
   */
  private final List<String> columnNames = new ArrayList<>();
  /**
   * 字段 JavaType 数组
   */
  private final List<String> classNames = new ArrayList<>();
  /**
   * 字段 JdbcType 数组
   */
  private final List<JdbcType> jdbcTypes = new ArrayList<>();
  /**
   * TypeHandler 的映射缓存, 缓存每个字段下, 不同 JavaType 对应的 TypeHandler
   */
  private final Map<String, Map<Class<?>, TypeHandler<?>>> typeHandlerMap = new HashMap<>();
  /**
   * 保存每个 ResultMap 每个 columnPrefix 匹配的列名
   */
  private final Map<String, List<String>> mappedColumnNamesMap = new HashMap<>();
  /**
   * 保存每个 ResultMap 每个 columnPrefix 不匹配的列名
   * key: ${resultMapId}:${columnPrefix}
   */
  private final Map<String, List<String>> unMappedColumnNamesMap = new HashMap<>();

  public ResultSetWrapper(ResultSet rs, Configuration configuration) throws SQLException {
    super();
    //获取 TypeHandlerRegistry
    this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
    this.resultSet = rs;
    //从ResultSet 元数据中列名, 列的jdbcType, 还有JavaType
    final ResultSetMetaData metaData = rs.getMetaData();
    final int columnCount = metaData.getColumnCount();
    for (int i = 1; i <= columnCount; i++) {
      columnNames.add(configuration.isUseColumnLabel() ? metaData.getColumnLabel(i) : metaData.getColumnName(i));
      jdbcTypes.add(JdbcType.forCode(metaData.getColumnType(i)));
      classNames.add(metaData.getColumnClassName(i));
    }
  }

  public ResultSet getResultSet() {
    return resultSet;
  }

  public List<String> getColumnNames() {
    return this.columnNames;
  }

  public List<String> getClassNames() {
    return Collections.unmodifiableList(classNames);
  }

  public List<JdbcType> getJdbcTypes() {
    return jdbcTypes;
  }

  public JdbcType getJdbcType(String columnName) {
    //根据列名获取JdbcType
    for (int i = 0 ; i < columnNames.size(); i++) {
      if (columnNames.get(i).equalsIgnoreCase(columnName)) {
        return jdbcTypes.get(i);
      }
    }
    return null;
  }

  /**
   *
   * 获取指定字段名的指定 JavaType 类型的 TypeHandler 对象
   * - 先尝试用 propertyType 作 JavaType 去找
   * - 找不到则用 jdbcType 对应的 JavaType 去找
   * - 最后还找不到, 则用 jdbcType 直接找对应的 TypeHandler
   * -
   *
   * Gets the type handler to use when reading the result set.
   * Tries to get from the TypeHandlerRegistry by searching for the property type.
   * If not found it gets the column JDBC type and tries to get a handler for it.
   *
   * @param propertyType
   * @param columnName
   * @return
   */
  public TypeHandler<?> getTypeHandler(Class<?> propertyType, String columnName) {
    TypeHandler<?> handler = null;
    //根据字段名从缓存中获取 Map<Class, TypeHandler>
    Map<Class<?>, TypeHandler<?>> columnHandlers = typeHandlerMap.get(columnName);
    //如果没找到, 则初始化
    if (columnHandlers == null) {
      //创建 HashMap
      columnHandlers = new HashMap<>();
      //初始化到 typeHandlerMap
      typeHandlerMap.put(columnName, columnHandlers);
    } else {
      //如果有缓存, 则根据 JavaType 从缓存中获取 TypeHandler
      handler = columnHandlers.get(propertyType);
    }
    //如果没有找到 TypeHandler
    if (handler == null) {
      //获取字段的 JdbcType, 来自 ResultSet 的
      JdbcType jdbcType = getJdbcType(columnName);
      //根据 JavaType 和 JdbcType 来获取 TypeHandler
      handler = typeHandlerRegistry.getTypeHandler(propertyType, jdbcType);
      //如果没找到 handler 或 handler 是 UnknownTypeHandler
      // Replicate logic of UnknownTypeHandler#resolveTypeHandler
      // See issue #59 comment 10
      if (handler == null || handler instanceof UnknownTypeHandler) {
        //获取字段的位置
        final int index = columnNames.indexOf(columnName);
        //获取 JavaType
        final Class<?> javaType = resolveClass(classNames.get(index));
        //根据 JavaType 和 JdbcType 的值来调用不同的方法获取 TypeHandler
        if (javaType != null && jdbcType != null) {
          handler = typeHandlerRegistry.getTypeHandler(javaType, jdbcType);
        } else if (javaType != null) {
          handler = typeHandlerRegistry.getTypeHandler(javaType);
        } else if (jdbcType != null) {
          handler = typeHandlerRegistry.getTypeHandler(jdbcType);
        }
      }
      //如果最终还是没找到, 则返回 ObjectTypeHandler
      if (handler == null || handler instanceof UnknownTypeHandler) {
        handler = new ObjectTypeHandler();
      }
      //缓存结果起来
      columnHandlers.put(propertyType, handler);
    }
    return handler;
  }

  /**
   * 解析类名为类
   */
  private Class<?> resolveClass(String className) {
    try {
      // #699 className could be null
      if (className != null) {
        return Resources.classForName(className);
      }
    } catch (ClassNotFoundException e) {
      // ignore
    }
    return null;
  }

  /**
   * 初始化有 mapped 和无 mapped 的字段的名字数组.
   */
  private void loadMappedAndUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
    //有匹配上的列名
    List<String> mappedColumnNames = new ArrayList<>();
    //没有匹配上的列名
    List<String> unmappedColumnNames = new ArrayList<>();
    //如果表名前缀为null, 则返回null, 否则返回前缀的大写
    final String upperColumnPrefix = columnPrefix == null ? null : columnPrefix.toUpperCase(Locale.ENGLISH);
    //将所有列名拼接上前缀
    final Set<String> mappedColumns = prependPrefixes(resultMap.getMappedColumns(), upperColumnPrefix);
    //遍历列名
    for (String columnName : columnNames) {
      //获取列名大写
      final String upperColumnName = columnName.toUpperCase(Locale.ENGLISH);
      //如果匹配, 则添加的 mapped 中
      if (mappedColumns.contains(upperColumnName)) {
        mappedColumnNames.add(upperColumnName);
      } else {
        //如果不匹配, 则添加到 unmapped 中
        unmappedColumnNames.add(columnName);
      }
    }
    //添加到 mappedColumnNamesMap
    mappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), mappedColumnNames);
    //添加到 unMappedColumnNamesMap
    unMappedColumnNamesMap.put(getMapKey(resultMap, columnPrefix), unmappedColumnNames);
  }

  /**
   * 根据 ResultMap 和 columnPrefix 获取匹配的列
   */
  public List<String> getMappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
    //拼接 MapKey
    List<String> mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    // 如果没有缓存
    if (mappedColumnNames == null) {
      //加载
      loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
      //再次获取
      mappedColumnNames = mappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    }
    //返回匹配的列
    return mappedColumnNames;
  }

  /**
   * 根据 ResultMap 和 columnPrefix 获取不匹配的列
   */
  public List<String> getUnmappedColumnNames(ResultMap resultMap, String columnPrefix) throws SQLException {
    //从缓存中获取
    List<String> unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    //如果取不到
    if (unMappedColumnNames == null) {
      //重新加载一下
      loadMappedAndUnmappedColumnNames(resultMap, columnPrefix);
      //再次获取
      unMappedColumnNames = unMappedColumnNamesMap.get(getMapKey(resultMap, columnPrefix));
    }
    //返回不匹配的列
    return unMappedColumnNames;
  }

  /**
   * 获取 MapKey
   * ${resultMapId}:${columnPrefix}
   */
  private String getMapKey(ResultMap resultMap, String columnPrefix) {
    return resultMap.getId() + ":" + columnPrefix;
  }

  /**
   * 列名拼接上前缀
   */
  private Set<String> prependPrefixes(Set<String> columnNames, String prefix) {
    //如果参数有一个为空, 则直接返回列名列表
    if (columnNames == null || columnNames.isEmpty() || prefix == null || prefix.length() == 0) {
      return columnNames;
    }
    //用于保存合并后的结果
    final Set<String> prefixed = new HashSet<>();
    //遍历列名
    for (String columnName : columnNames) {
      //合并
      prefixed.add(prefix + columnName);
    }
    //返回结果
    return prefixed;
  }

}

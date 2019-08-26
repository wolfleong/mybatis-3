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
package org.apache.ibatis.executor.keygen;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.ArrayUtil;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.defaults.DefaultSqlSession.StrictMap;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class Jdbc3KeyGenerator implements KeyGenerator {

  /**
   * A shared instance.
   *
   * @since 3.4.3
   */
  public static final Jdbc3KeyGenerator INSTANCE = new Jdbc3KeyGenerator();

  private static final String MSG_TOO_MANY_KEYS = "Too many keys are generated. There are only %d target objects. "
      + "You either specified a wrong 'keyProperty' or encountered a driver bug like #1523.";

  @Override
  public void processBefore(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
    // do nothing
  }

  @Override
  public void processAfter(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
    //批量处理
    processBatch(ms, stmt, parameter);
  }

  public void processBatch(MappedStatement ms, Statement stmt, Object parameter) {
    //获取 keyProperties
    final String[] keyProperties = ms.getKeyProperties();
    //如果没有设置 keyProperties 直接返回
    if (keyProperties == null || keyProperties.length == 0) {
      return;
    }
    //获取生成key的 ResultSet
    try (ResultSet rs = stmt.getGeneratedKeys()) {
      //获取元数据
      final ResultSetMetaData rsmd = rs.getMetaData();
      //获取全局配置
      final Configuration configuration = ms.getConfiguration();
      //如果生成的列数跟 keyProperties 对不上, 则不处理
      if (rsmd.getColumnCount() < keyProperties.length) {
        // Error?
      } else {
        //处理 key
        assignKeys(configuration, rs, rsmd, keyProperties, parameter);
      }
    } catch (Exception e) {
      throw new ExecutorException("Error getting generated key or setting result to parameter object. Cause: " + e, e);
    }
  }

  @SuppressWarnings("unchecked")
  private void assignKeys(Configuration configuration, ResultSet rs, ResultSetMetaData rsmd, String[] keyProperties,
      Object parameter) throws SQLException {
    //如果参数是 ParamMap 或 StrictMap
    if (parameter instanceof ParamMap || parameter instanceof StrictMap) {
      //多个参数或者用了 @Param 注解的
      // Multi-param or single param with @Param
      assignKeysToParamMap(configuration, rs, rsmd, keyProperties, (Map<String, ?>) parameter);
      //如果参数是列表, 但列表不为空且参数第一个是 ParamMap 的情况
    } else if (parameter instanceof ArrayList && !((ArrayList<?>) parameter).isEmpty()
        && ((ArrayList<?>) parameter).get(0) instanceof ParamMap) {
      // Multi-param or single param with @Param in batch operation
      assignKeysToParamMapList(configuration, rs, rsmd, keyProperties, ((ArrayList<ParamMap<?>>) parameter));
    } else {
      //处理理单个参数, 没有用 @Param 注解的
      // Single param without @Param
      assignKeysToParam(configuration, rs, rsmd, keyProperties, parameter);
    }
  }

  private void assignKeysToParam(Configuration configuration, ResultSet rs, ResultSetMetaData rsmd,
      String[] keyProperties, Object parameter) throws SQLException {
    //将参数变集合
    Collection<?> params = collectionize(parameter);
    //如果集合为空, 则直接不处理
    if (params.isEmpty()) {
      return;
    }
    //保存 KeyAssigner 的列表
    List<KeyAssigner> assignerList = new ArrayList<>();
    //遍历 keyProperties
    for (int i = 0; i < keyProperties.length; i++) {
      //创建 KeyAssigner 并添加到 assignerList
      assignerList.add(new KeyAssigner(configuration, rsmd, i + 1, null, keyProperties[i]));
    }
    //遍历参数
    Iterator<?> iterator = params.iterator();
    while (rs.next()) {
      //如果一个参数都没有
      if (!iterator.hasNext()) {
        //报错
        throw new ExecutorException(String.format(MSG_TOO_MANY_KEYS, params.size()));
      }
      //获取参数
      Object param = iterator.next();
      //应用 key
      assignerList.forEach(x -> x.assign(rs, param));
    }
  }

  private void assignKeysToParamMapList(Configuration configuration, ResultSet rs, ResultSetMetaData rsmd,
      String[] keyProperties, ArrayList<ParamMap<?>> paramMapList) throws SQLException {
    Iterator<ParamMap<?>> iterator = paramMapList.iterator();
    List<KeyAssigner> assignerList = new ArrayList<>();
    long counter = 0;
    while (rs.next()) {
      if (!iterator.hasNext()) {
        throw new ExecutorException(String.format(MSG_TOO_MANY_KEYS, counter));
      }
      ParamMap<?> paramMap = iterator.next();
      if (assignerList.isEmpty()) {
        for (int i = 0; i < keyProperties.length; i++) {
          assignerList
              .add(getAssignerForParamMap(configuration, rsmd, i + 1, paramMap, keyProperties[i], keyProperties, false)
                  .getValue());
        }
      }
      assignerList.forEach(x -> x.assign(rs, paramMap));
      counter++;
    }
  }

  private void assignKeysToParamMap(Configuration configuration, ResultSet rs, ResultSetMetaData rsmd,
      String[] keyProperties, Map<String, ?> paramMap) throws SQLException {
    if (paramMap.isEmpty()) {
      return;
    }
    Map<String, Entry<Iterator<?>, List<KeyAssigner>>> assignerMap = new HashMap<>();
    for (int i = 0; i < keyProperties.length; i++) {
      Entry<String, KeyAssigner> entry = getAssignerForParamMap(configuration, rsmd, i + 1, paramMap, keyProperties[i],
          keyProperties, true);
      Entry<Iterator<?>, List<KeyAssigner>> iteratorPair = assignerMap.computeIfAbsent(entry.getKey(),
          k -> entry(collectionize(paramMap.get(k)).iterator(), new ArrayList<>()));
      iteratorPair.getValue().add(entry.getValue());
    }
    long counter = 0;
    while (rs.next()) {
      for (Entry<Iterator<?>, List<KeyAssigner>> pair : assignerMap.values()) {
        if (!pair.getKey().hasNext()) {
          throw new ExecutorException(String.format(MSG_TOO_MANY_KEYS, counter));
        }
        Object param = pair.getKey().next();
        pair.getValue().forEach(x -> x.assign(rs, param));
      }
      counter++;
    }
  }

  private Entry<String, KeyAssigner> getAssignerForParamMap(Configuration config, ResultSetMetaData rsmd,
      int columnPosition, Map<String, ?> paramMap, String keyProperty, String[] keyProperties, boolean omitParamName) {
    boolean singleParam = paramMap.values().stream().distinct().count() == 1;
    int firstDot = keyProperty.indexOf('.');
    if (firstDot == -1) {
      if (singleParam) {
        return getAssignerForSingleParam(config, rsmd, columnPosition, paramMap, keyProperty, omitParamName);
      }
      throw new ExecutorException("Could not determine which parameter to assign generated keys to. "
          + "Note that when there are multiple parameters, 'keyProperty' must include the parameter name (e.g. 'param.id'). "
          + "Specified key properties are " + ArrayUtil.toString(keyProperties) + " and available parameters are "
          + paramMap.keySet());
    }
    String paramName = keyProperty.substring(0, firstDot);
    if (paramMap.containsKey(paramName)) {
      String argParamName = omitParamName ? null : paramName;
      String argKeyProperty = keyProperty.substring(firstDot + 1);
      return entry(paramName, new KeyAssigner(config, rsmd, columnPosition, argParamName, argKeyProperty));
    } else if (singleParam) {
      return getAssignerForSingleParam(config, rsmd, columnPosition, paramMap, keyProperty, omitParamName);
    } else {
      throw new ExecutorException("Could not find parameter '" + paramName + "'. "
          + "Note that when there are multiple parameters, 'keyProperty' must include the parameter name (e.g. 'param.id'). "
          + "Specified key properties are " + ArrayUtil.toString(keyProperties) + " and available parameters are "
          + paramMap.keySet());
    }
  }

  private Entry<String, KeyAssigner> getAssignerForSingleParam(Configuration config, ResultSetMetaData rsmd,
      int columnPosition, Map<String, ?> paramMap, String keyProperty, boolean omitParamName) {
    // Assume 'keyProperty' to be a property of the single param.
    String singleParamName = nameOfSingleParam(paramMap);
    String argParamName = omitParamName ? null : singleParamName;
    return entry(singleParamName, new KeyAssigner(config, rsmd, columnPosition, argParamName, keyProperty));
  }

  private static String nameOfSingleParam(Map<String, ?> paramMap) {
    // There is virtually one parameter, so any key works.
    return paramMap.keySet().iterator().next();
  }

  /**
   * 参数变集合
   */
  private static Collection<?> collectionize(Object param) {
    //如果参数是集合, 则直接强转返回
    if (param instanceof Collection) {
      return (Collection<?>) param;
      //如果参数是数组, 转换成集合返回
    } else if (param instanceof Object[]) {
      return Arrays.asList((Object[]) param);
    } else {
      //转成集合返回
      return Arrays.asList(param);
    }
  }

  private static <K, V> Entry<K, V> entry(K key, V value) {
    // Replace this with Map.entry(key, value) in Java 9.
    return new AbstractMap.SimpleImmutableEntry<>(key, value);
  }

  private class KeyAssigner {
    private final Configuration configuration;
    private final ResultSetMetaData rsmd;
    private final TypeHandlerRegistry typeHandlerRegistry;
    private final int columnPosition;
    private final String paramName;
    private final String propertyName;
    private TypeHandler<?> typeHandler;

    protected KeyAssigner(Configuration configuration, ResultSetMetaData rsmd, int columnPosition, String paramName,
        String propertyName) {
      super();
      this.configuration = configuration;
      this.rsmd = rsmd;
      this.typeHandlerRegistry = configuration.getTypeHandlerRegistry();
      this.columnPosition = columnPosition;
      this.paramName = paramName;
      this.propertyName = propertyName;
    }

    protected void assign(ResultSet rs, Object param) {
      if (paramName != null) {
        // If paramName is set, param is ParamMap
        param = ((ParamMap<?>) param).get(paramName);
      }
      MetaObject metaParam = configuration.newMetaObject(param);
      try {
        if (typeHandler == null) {
          if (metaParam.hasSetter(propertyName)) {
            Class<?> propertyType = metaParam.getSetterType(propertyName);
            typeHandler = typeHandlerRegistry.getTypeHandler(propertyType,
                JdbcType.forCode(rsmd.getColumnType(columnPosition)));
          } else {
            throw new ExecutorException("No setter found for the keyProperty '" + propertyName + "' in '"
                + metaParam.getOriginalObject().getClass().getName() + "'.");
          }
        }
        if (typeHandler == null) {
          // Error?
        } else {
          Object value = typeHandler.getResult(rs, columnPosition);
          metaParam.setValue(propertyName, value);
        }
      } catch (SQLException e) {
        throw new ExecutorException("Error getting generated key or setting result to parameter object. Cause: " + e,
            e);
      }
    }
  }
}

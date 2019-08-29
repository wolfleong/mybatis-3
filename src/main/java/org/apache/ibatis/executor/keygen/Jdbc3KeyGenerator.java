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
 * 主键生成器
 * - 适用于 MySQL、H2 主键生成
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class Jdbc3KeyGenerator implements KeyGenerator {

  /**
   * 共享的单例
   * A shared instance.
   *
   * @since 3.4.3
   */
  public static final Jdbc3KeyGenerator INSTANCE = new Jdbc3KeyGenerator();

  private static final String MSG_TOO_MANY_KEYS = "Too many keys are generated. There are only %d target objects. "
      + "You either specified a wrong 'keyProperty' or encountered a driver bug like #1523.";

  @Override
  public void processBefore(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
    //空实现, 因为对于 Jdbc3KeyGenerator 类的主键, 是在 SQL 执行后, 才生成的
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
      //多个参数或者用了 @Param 注解的, 如: insert(@Param(..) Country country) ,  insert(@Param(..)List<Country> country)
      // Multi-param or single param with @Param
      assignKeysToParamMap(configuration, rs, rsmd, keyProperties, (Map<String, ?>) parameter);
      //这个只有在 BatchExecutor 中进来, 正常的是不会进来的
      //如果参数是列表, 但列表不为空且参数第一个是 ParamMap 的情况, 相当于判定 parameter 是 ArrayList<ParamMap<?>>
    } else if (parameter instanceof ArrayList && !((ArrayList<?>) parameter).isEmpty()
        && ((ArrayList<?>) parameter).get(0) instanceof ParamMap) {
      // Multi-param or single param with @Param in batch operation
      assignKeysToParamMapList(configuration, rs, rsmd, keyProperties, ((ArrayList<ParamMap<?>>) parameter));
    } else {
      //处理理单个参数, 没有用 @Param 注解的,如 insert(Country country) , insert(List<Country> country)
      // Single param without @Param
      assignKeysToParam(configuration, rs, rsmd, keyProperties, parameter);
    }
  }

  /**
   * 非 ParamMap 的单个参数的 key 的应用
   */
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
    //遍历 keyProperties, 每个 key 生成一个 KeyAssigner
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

  /**
   * 处理带 @Param 注解的批量插入, 即 List<ParamMap> 这种类型
   */
  private void assignKeysToParamMapList(Configuration configuration, ResultSet rs, ResultSetMetaData rsmd,
      String[] keyProperties, ArrayList<ParamMap<?>> paramMapList) throws SQLException {
    //参数的迭代器
    Iterator<ParamMap<?>> iterator = paramMapList.iterator();
    List<KeyAssigner> assignerList = new ArrayList<>();
    long counter = 0;
    //迭代 key 生成的结果
    while (rs.next()) {
      //如果结果有生成的key, 但插入的对象不够, 代表 key 生成太多了
      if (!iterator.hasNext()) {
        throw new ExecutorException(String.format(MSG_TOO_MANY_KEYS, counter));
      }
      //获取插入参数
      ParamMap<?> paramMap = iterator.next();
      //如果列的assigner 没有生成
      if (assignerList.isEmpty()) {
        for (int i = 0; i < keyProperties.length; i++) {
          //每个 keyProperty 生成一个 KeyAssigner
          assignerList
              .add(getAssignerForParamMap(configuration, rsmd, i + 1, paramMap, keyProperties[i], keyProperties, false)
                  .getValue());
        }
      }
      //每行结果处理一轮全部的 keyProperty
      assignerList.forEach(x -> x.assign(rs, paramMap));
      counter++;
    }
  }

  private void assignKeysToParamMap(Configuration configuration, ResultSet rs, ResultSetMetaData rsmd,
      String[] keyProperties, Map<String, ?> paramMap) throws SQLException {
    //如果参数没值, 则返回
    if (paramMap.isEmpty()) {
      return;
    }
    //todo wolfleong 为什么要这种存储结构
    Map<String, Entry<Iterator<?>, List<KeyAssigner>>> assignerMap = new HashMap<>();
    //遍历 keyProperties, 每一个 keyProperty 一个 Entry<String, keyAssigner>
    for (int i = 0; i < keyProperties.length; i++) {
      //从 ParamMap 中获取 KeyAssigner
      Entry<String, KeyAssigner> entry = getAssignerForParamMap(configuration, rsmd, i + 1, paramMap, keyProperties[i],
          keyProperties, true);
      //在添加 Entry 时, 已经从ParamMap中将对象取出
      //如果 assignerMap 为null , 则初始化 assignerMap, 并返回 iteratorPair
      //todo wolfleong 不明白, 为什么每一个entry.key有一个 iterator
      Entry<Iterator<?>, List<KeyAssigner>> iteratorPair = assignerMap.computeIfAbsent(entry.getKey(),
          k -> entry(collectionize(paramMap.get(k)).iterator(), new ArrayList<>()));
      //添加当前 entry
      iteratorPair.getValue().add(entry.getValue());
    }
    long counter = 0;
    //这里做得好巧妙, 理论是, 每插入一行rs就会生成一行的key对象, 也就是 rs 的行数肯定等于 Iterator 对象数
    // assignerMap 对kv对代表着同一行不同的列组合
    //迭代生成的key的resultSet
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

  /**
   * ParamMap 的三种情况, keyProperty 有.的情况下, 只能出现最后两种情况
   * - keyProperty 是 country, ParamMap 只有一个参数
   * - keyProperty 是 country.id , ParamMap 只有一个参数
   * - keyProperty 是 country.id , ParamMap 有多个参数, 但必须 ParamMap('country') 存在
   *
   * @param config 全局配置
   * @param rsmd jdbc元数据
   * @param columnPosition 列的位置
   * @param paramMap 参数Map
   * @param keyProperty 字段名
   * @param keyProperties 所有的keyProperties
   * @param omitParamName 是否省略参数名
   */
  private Entry<String, KeyAssigner> getAssignerForParamMap(Configuration config, ResultSetMetaData rsmd,
      int columnPosition, Map<String, ?> paramMap, String keyProperty, String[] keyProperties, boolean omitParamName) {
    //是否参数只有一个, distinct 会将一些不同参数名重复参数去重
    boolean singleParam = paramMap.values().stream().distinct().count() == 1;
    //第一个 .  的位置
    int firstDot = keyProperty.indexOf('.');
    //如果找不到 .
    if (firstDot == -1) {
      //如果是单个参数
      if (singleParam) {
        //没有 . 且是单个参数, 则直接创建
        return getAssignerForSingleParam(config, rsmd, columnPosition, paramMap, keyProperty, omitParamName);
      }
      //如果有多个参数, 但是不包含 . , 会抛出错误
      throw new ExecutorException("Could not determine which parameter to assign generated keys to. "
          + "Note that when there are multiple parameters, 'keyProperty' must include the parameter name (e.g. 'param.id'). "
          + "Specified key properties are " + ArrayUtil.toString(keyProperties) + " and available parameters are "
          + paramMap.keySet());
    }
    //如果有多层, 则获取第一层参数名
    String paramName = keyProperty.substring(0, firstDot);
    //如果参数Map包含这个key
    if (paramMap.containsKey(paramName)) {
      //如果忽略参数名, 则返回null, 否则返回参数名
      String argParamName = omitParamName ? null : paramName;
      //截取除 paramMapKey 后面的内容
      String argKeyProperty = keyProperty.substring(firstDot + 1);
      //返回
      return entry(paramName, new KeyAssigner(config, rsmd, columnPosition, argParamName, argKeyProperty));
    } else if (singleParam) {
      //有 . 的单个参数
      return getAssignerForSingleParam(config, rsmd, columnPosition, paramMap, keyProperty, omitParamName);
    } else {
      //有点. 然后多参数, 当前 map 又不包括第一层的key, 则报错
      throw new ExecutorException("Could not find parameter '" + paramName + "'. "
          + "Note that when there are multiple parameters, 'keyProperty' must include the parameter name (e.g. 'param.id'). "
          + "Specified key properties are " + ArrayUtil.toString(keyProperties) + " and available parameters are "
          + paramMap.keySet());
    }
  }

  /**
   * 单个参数的ParamMap, 直接获取第一个参数的key值来创建
   */
  private Entry<String, KeyAssigner> getAssignerForSingleParam(Configuration config, ResultSetMetaData rsmd,
      int columnPosition, Map<String, ?> paramMap, String keyProperty, boolean omitParamName) {
    //单个参数, 直接获取第一个参数名
    // Assume 'keyProperty' to be a property of the single param.
    String singleParamName = nameOfSingleParam(paramMap);
    //如果忽略参数名则返回 null, 否则返回参数名
    String argParamName = omitParamName ? null : singleParamName;
    //返回参数 Entry<singleParamName, KeyAssigner>
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

  /**
   * 每一个 KeyAssigner 相当于一列, 每一个对象可能有多个 keyProperty, 即多列
   */
  private class KeyAssigner {
    /**
     * 全局配置
     */
    private final Configuration configuration;
    /**
     * 元数据
     */
    private final ResultSetMetaData rsmd;
    /**
     * 类型处理注册器
     */
    private final TypeHandlerRegistry typeHandlerRegistry;
    /**
     * 列的位置
     */
    private final int columnPosition;
    /**
     * 参数名
     */
    private final String paramName;
    /**
     * 属性名
     */
    private final String propertyName;
    /**
     * 类型处理器
     */
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
      //如果指定参数名不为null, 则 param 肯定是 ParamMap
      if (paramName != null) {
        //insert(@Param("person") Person person)
        //从Map中获取参数对象出来
        // If paramName is set, param is ParamMap
        param = ((ParamMap<?>) param).get(paramName);
      }
      //创建参数对象的 MetaObject
      MetaObject metaParam = configuration.newMetaObject(param);
      try {
        //如果没有类型处理器
        if (typeHandler == null) {
          //判断参数对象是否有这个属性
          if (metaParam.hasSetter(propertyName)) {
            //获取参数类型
            Class<?> propertyType = metaParam.getSetterType(propertyName);
            //查询参数处理器
            typeHandler = typeHandlerRegistry.getTypeHandler(propertyType,
                JdbcType.forCode(rsmd.getColumnType(columnPosition)));
          } else {
            throw new ExecutorException("No setter found for the keyProperty '" + propertyName + "' in '"
                + metaParam.getOriginalObject().getClass().getName() + "'.");
          }
        }
        //如果参数处理器为null, 则不处理
        if (typeHandler == null) {
          // Error?
        } else {
          //参数处理器不为null, 根据列的位置, 获取参数值
          Object value = typeHandler.getResult(rs, columnPosition);
          //设置值到参数
          metaParam.setValue(propertyName, value);
        }
      } catch (SQLException e) {
        throw new ExecutorException("Error getting generated key or setting result to parameter object. Cause: " + e,
            e);
      }
    }
  }
}

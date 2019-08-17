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
package org.apache.ibatis.scripting.xmltags;

import java.util.HashMap;
import java.util.Map;
import java.util.StringJoiner;

import ognl.OgnlContext;
import ognl.OgnlRuntime;
import ognl.PropertyAccessor;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;

/**
 * 动态sql内容上下文
 * - 动态 SQL ，用于每次执行 SQL 操作时，记录动态 SQL 处理后的最终 SQL 字符串
 * @author Clinton Begin
 */
public class DynamicContext {

  /**
   * _parameter 的键，参数
   */
  public static final String PARAMETER_OBJECT_KEY = "_parameter";
  /**
   * _databaseId 的键，数据库编号
   */
  public static final String DATABASE_ID_KEY = "_databaseId";

  static {
    //设置 OGNL 的属性访问器
    OgnlRuntime.setPropertyAccessor(ContextMap.class, new ContextAccessor());
  }

  /**
   * 上下文的参数集合
   */
  private final ContextMap bindings;
  /**
   * 生成sql
   */
  private final StringJoiner sqlBuilder = new StringJoiner(" ");
  /**
   * 唯一编号。在 org.apache.ibatis.scripting.xmltags.XMLScriptBuilder.ForEachHandler 使用
   */
  private int uniqueNumber = 0;

  public DynamicContext(Configuration configuration, Object parameterObject) {
    //如果参数对象不是null, 且不是Map对象
    if (parameterObject != null && !(parameterObject instanceof Map)) {
      //创建对象的 MetaObject
      MetaObject metaObject = configuration.newMetaObject(parameterObject);
      //是否有当前类的TypeHandler处理器
      boolean existsTypeHandler = configuration.getTypeHandlerRegistry().hasTypeHandler(parameterObject.getClass());
      //创建 ContextMap
      bindings = new ContextMap(metaObject, existsTypeHandler);
    } else {
      //创建空的 ContextMap
      bindings = new ContextMap(null, false);
    }
    //添加对应的参数
    bindings.put(PARAMETER_OBJECT_KEY, parameterObject);
    bindings.put(DATABASE_ID_KEY, configuration.getDatabaseId());
  }

  /**
   * 获取 bindings
   */
  public Map<String, Object> getBindings() {
    return bindings;
  }

  /**
   * 添加键值
   */
  public void bind(String name, Object value) {
    bindings.put(name, value);
  }

  /**
   * 添加sql段
   */
  public void appendSql(String sql) {
    sqlBuilder.add(sql);
  }

  /**
   * 获取sql
   */
  public String getSql() {
    return sqlBuilder.toString().trim();
  }

  /**
   * 每次请求，获得新的序号。
   */
  public int getUniqueNumber() {
    return uniqueNumber++;
  }

  /**
   * 上下文参数的集合
   * 该类在 HashMap 的基础上，增加支持对 parameterMetaObject 属性的访问
   */
  static class ContextMap extends HashMap<String, Object> {
    private static final long serialVersionUID = 2977601501966151582L;
    /**
     * parameter 对应的 MetaObject 对象
     */
    private final MetaObject parameterMetaObject;
    private final boolean fallbackParameterObject;

    public ContextMap(MetaObject parameterMetaObject, boolean fallbackParameterObject) {
      this.parameterMetaObject = parameterMetaObject;
      this.fallbackParameterObject = fallbackParameterObject;
    }

    @Override
    public Object get(Object key) {
      //如果有 key 对应的值, 直接获得
      String strKey = (String) key;
      if (super.containsKey(strKey)) {
        return super.get(strKey);
      }

      //如果没有对应key的值, 且 parameterMetaObject 为null, 直接返回null
      if (parameterMetaObject == null) {
        return null;
      }

      //如果有对应的TypeHandler且 parameterMetaObject 没有这个 key
      if (fallbackParameterObject && !parameterMetaObject.hasGetter(strKey)) {
        //直接返回源对象
        return parameterMetaObject.getOriginalObject();
      } else {
        //从 metaObject 对象中获取值
        // issue #61 do not modify the context when reading
        return parameterMetaObject.getValue(strKey);
      }
    }
  }

  static class ContextAccessor implements PropertyAccessor {

    @Override
    public Object getProperty(Map context, Object target, Object name) {
      Map map = (Map) target;

      //  优先从 ContextMap 中，获得属性
      Object result = map.get(name);
      if (map.containsKey(name) || result != null) {
        return result;
      }

      //如果没有，则从 PARAMETER_OBJECT_KEY 对应的 Map 中，获得属性
      Object parameterObject = map.get(PARAMETER_OBJECT_KEY);
      if (parameterObject instanceof Map) {
        return ((Map)parameterObject).get(name);
      }

      return null;
    }

    @Override
    public void setProperty(Map context, Object target, Object name, Object value) {
      Map<Object, Object> map = (Map<Object, Object>) target;
      map.put(name, value);
    }

    @Override
    public String getSourceAccessor(OgnlContext arg0, Object arg1, Object arg2) {
      return null;
    }

    @Override
    public String getSourceSetter(OgnlContext arg0, Object arg1, Object arg2) {
      return null;
    }
  }
}

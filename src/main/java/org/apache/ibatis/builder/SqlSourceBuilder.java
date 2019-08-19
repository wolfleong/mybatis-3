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
package org.apache.ibatis.builder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;

/**
 * SqlSource 构建器, 负责将sql语句中的 #{} 替换成相应的 ? 占位符,
 * 并获取该 ? 占位符对应的 org.apache.ibatis.mapping.ParameterMapping 对象
 * @author Clinton Begin
 */
public class SqlSourceBuilder extends BaseBuilder {

  private static final String PARAMETER_PROPERTIES = "javaType,jdbcType,mode,numericScale,resultMap,typeHandler,jdbcTypeName";

  public SqlSourceBuilder(Configuration configuration) {
    super(configuration);
  }

  /**
   * 执行解析原始 SQL , 成为 SqlSource 对象
   * @param originalSql 原始 SQL
   * @param parameterType 参数类型
   * @param additionalParameters 附加参数集合. 可能是空集合, 也可能是 DynamicContext#bindings 的集合,
   *                             在解析sql过程中会生成相应的参数(非用户传递的)
   * @return SqlSource
   */
  public SqlSource parse(String originalSql, Class<?> parameterType, Map<String, Object> additionalParameters) {
    //创建 ParameterMappingTokenHandler 对象
    ParameterMappingTokenHandler handler = new ParameterMappingTokenHandler(configuration, parameterType, additionalParameters);
    //创建 GenericTokenParser 对象
    GenericTokenParser parser = new GenericTokenParser("#{", "}", handler);
    //执行解析
    String sql = parser.parse(originalSql);
    //创建 StaticSqlSource
    return new StaticSqlSource(configuration, sql, handler.getParameterMappings());
  }

  /**
   * 负责将匹配到的 #{ 和 } 对，替换成相应的 ? 占位符，并获取该 ? 占位符对应的 org.apache.ibatis.mapping.ParameterMapping 对象
   */
  private static class ParameterMappingTokenHandler extends BaseBuilder implements TokenHandler {

    /**
     * ParameterMapping 列表
     */
    private List<ParameterMapping> parameterMappings = new ArrayList<>();
    /**
     * 参数类型
     */
    private Class<?> parameterType;
    /**
     * additionalParameters 参数的对应的 MetaObject 对象
     */
    private MetaObject metaParameters;

    public ParameterMappingTokenHandler(Configuration configuration, Class<?> parameterType, Map<String, Object> additionalParameters) {
      super(configuration);
      this.parameterType = parameterType;
      // 创建 additionalParameters 参数的对应的 MetaObject 对象
      this.metaParameters = configuration.newMetaObject(additionalParameters);
    }

    /**
     * 获取参数映射列表
     */
    public List<ParameterMapping> getParameterMappings() {
      return parameterMappings;
    }

    @Override
    public String handleToken(String content) {
      //构建并添加 ParameterMapping
      parameterMappings.add(buildParameterMapping(content));
      //返回 ?
      return "?";
    }

    /**
     *
     * @param content 可能为 age,javaType=int,jdbcType=NUMERIC,typeHandler=MyTypeHandler 这些复杂的字符串
     */
    private ParameterMapping buildParameterMapping(String content) {
      //将所有参数的所有配置组成的Map
      Map<String, String> propertiesMap = parseParameterMapping(content);
      //获取property属性名, 不需要判断 property是否为null, 当没有property时, 获取肯定报错
      String property = propertiesMap.get("property");
      //属性的类型
      Class<?> propertyType;
      // 首先 additional 的参数如果有对应的getter
      if (metaParameters.hasGetter(property)) { // issue #448 get type from additional params
        //从 additional 中获取对应的类型
        propertyType = metaParameters.getGetterType(property);
        //如果 additional 参数中没有, 则将判断用户参数类型有没有对应的参数类型处理器, 如简单类型参数, Integer, Long 这些
      } else if (typeHandlerRegistry.hasTypeHandler(parameterType)) {
        //如果有对应的参数处理器, 则直接处理
        propertyType = parameterType;
        //如果 jdbcType 是 JdbcType.CURSOR
      } else if (JdbcType.CURSOR.name().equals(propertiesMap.get("jdbcType"))) {
        //todo wolfleong, 不知道这里是怎么用的
        //则属性的类型是 ResultSet
        propertyType = java.sql.ResultSet.class;
        //如果属性不存在, 或参数的类型是Map
      } else if (property == null || Map.class.isAssignableFrom(parameterType)) {
        //则不知道这个属性类型, 直接给 Object
        propertyType = Object.class;
      } else {
        //最后, 单个参数, 复杂的类, 获取给定的 MetaClass
        MetaClass metaClass = MetaClass.forClass(parameterType, configuration.getReflectorFactory());
        //如果 MetaClass 中有这个属性
        if (metaClass.hasGetter(property)) {
          //获取这个属性的类型
          propertyType = metaClass.getGetterType(property);
        } else {
          //如果没有就用默认Object
          propertyType = Object.class;
        }
      }
      //创建 ParameterMapping.Builder
      ParameterMapping.Builder builder = new ParameterMapping.Builder(configuration, property, propertyType);
      //javaType
      Class<?> javaType = propertyType;
      //typeHandler的别名
      String typeHandlerAlias = null;
      //遍历自定义的参数
      for (Map.Entry<String, String> entry : propertiesMap.entrySet()) {
        //名称
        String name = entry.getKey();
        //配置的值
        String value = entry.getValue();
        //处理 javaType
        if ("javaType".equals(name)) {
          //如果有配置的javaType, 则用自定义的JavaType, 否则用默认解析的
          javaType = resolveClass(value);
          //设置javaType
          builder.javaType(javaType);
          //处理jdbcType
        } else if ("jdbcType".equals(name)) {
          //设置jdbcType
          builder.jdbcType(resolveJdbcType(value));
        } else if ("mode".equals(name)) {
          //设置mode
          builder.mode(resolveParameterMode(value));
        } else if ("numericScale".equals(name)) {
          //设置 numericScale
          builder.numericScale(Integer.valueOf(value));
        } else if ("resultMap".equals(name)) {
          //设置resultMapId
          builder.resultMapId(value);
        } else if ("typeHandler".equals(name)) {
          //记录typeHandler别名
          typeHandlerAlias = value;
        } else if ("jdbcTypeName".equals(name)) {
          //设置jdbcTypeName
          builder.jdbcTypeName(value);
          //property不用处理
        } else if ("property".equals(name)) {
          // Do Nothing
          // expression 暂时未支持
        } else if ("expression".equals(name)) {
          throw new BuilderException("Expression based parameters are not supported yet");
        } else {
          //其他类型不能处理, 报错
          throw new BuilderException("An invalid property '" + name + "' was found in mapping #{" + content + "}.  Valid properties are " + PARAMETER_PROPERTIES);
        }
      }
      //如果 typeHandlerAlias 不为null, 解析TypeHandler, 为什么要在这里处理, 因为要等javaType解析完
      if (typeHandlerAlias != null) {
        builder.typeHandler(resolveTypeHandler(javaType, typeHandlerAlias));
      }
      //构建
      return builder.build();
    }

    /**
     * 解析参数字符串
     */
    private Map<String, String> parseParameterMapping(String content) {
      try {
        return new ParameterExpression(content);
      } catch (BuilderException ex) {
        throw ex;
      } catch (Exception ex) {
        throw new BuilderException("Parsing error was found in mapping #{" + content + "}.  Check syntax #{property|(expression), var1=value1, var2=value2, ...} ", ex);
      }
    }
  }

}

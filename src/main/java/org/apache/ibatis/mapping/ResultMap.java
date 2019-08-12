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
package org.apache.ibatis.mapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.reflection.ParamNameUtil;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 */
public class ResultMap {
  /**
   * 全局配置
   */
  private Configuration configuration;

  /**
   * resultMap的id
   */
  private String id;
  /**
   * 返回的类型
   */
  private Class<?> type;
  /**
   * 所有映射
   */
  private List<ResultMapping> resultMappings;
  /**
   * id的映射
   */
  private List<ResultMapping> idResultMappings;
  /**
   * 构造器字段的映射
   */
  private List<ResultMapping> constructorResultMappings;
  /**
   * 普通属性的映射
   */
  private List<ResultMapping> propertyResultMappings;
  /**
   * 有映射到数据库的列
   */
  private Set<String> mappedColumns;
  /**
   * 有映射的属性
   */
  private Set<String> mappedProperties;
  /**
   * 鉴别器
   */
  private Discriminator discriminator;
  /**
   * 是否有嵌套的ResultMaps
   */
  private boolean hasNestedResultMaps;
  /**
   * 是否有子查询
   */
  private boolean hasNestedQueries;
  /**
   * 是否自动映射
   */
  private Boolean autoMapping;

  private ResultMap() {
  }

  public static class Builder {
    private static final Log log = LogFactory.getLog(Builder.class);

    private ResultMap resultMap = new ResultMap();

    public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings) {
      this(configuration, id, type, resultMappings, null);
    }

    public Builder(Configuration configuration, String id, Class<?> type, List<ResultMapping> resultMappings, Boolean autoMapping) {
      resultMap.configuration = configuration;
      resultMap.id = id;
      resultMap.type = type;
      resultMap.resultMappings = resultMappings;
      resultMap.autoMapping = autoMapping;
    }

    public Builder discriminator(Discriminator discriminator) {
      resultMap.discriminator = discriminator;
      return this;
    }

    public Class<?> type() {
      return resultMap.type;
    }

    public ResultMap build() {
      //resultMap不为null
      if (resultMap.id == null) {
        throw new IllegalArgumentException("ResultMaps must have an id");
      }
      resultMap.mappedColumns = new HashSet<>();
      resultMap.mappedProperties = new HashSet<>();
      resultMap.idResultMappings = new ArrayList<>();
      resultMap.constructorResultMappings = new ArrayList<>();
      resultMap.propertyResultMappings = new ArrayList<>();
      final List<String> constructorArgNames = new ArrayList<>();
      //遍历映射
      for (ResultMapping resultMapping : resultMap.resultMappings) {
        //设置这个resultMap是否嵌套子查询, 这里为什么要用 || 呢, 是因为在for中会多次调用
        resultMap.hasNestedQueries = resultMap.hasNestedQueries || resultMapping.getNestedQueryId() != null;
        //是否有嵌套映射, 只要其中一个resultMapping有, 就算当前ResultMap有嵌套映射了
        resultMap.hasNestedResultMaps = resultMap.hasNestedResultMaps || (resultMapping.getNestedResultMapId() != null && resultMapping.getResultSet() == null);
        //获取当前列
        final String column = resultMapping.getColumn();
        //如果指定的列不为null
        if (column != null) {
          //加到匹配的列中
          resultMap.mappedColumns.add(column.toUpperCase(Locale.ENGLISH));
          //如果是子查询的多列
        } else if (resultMapping.isCompositeResult()) {
          //遍历复合列
          for (ResultMapping compositeResultMapping : resultMapping.getComposites()) {
            //获取复合列的内容
            final String compositeColumn = compositeResultMapping.getColumn();
            //如果不为null
            if (compositeColumn != null) {
              //加到映射列
              resultMap.mappedColumns.add(compositeColumn.toUpperCase(Locale.ENGLISH));
            }
          }
        }
        //获取属性
        final String property = resultMapping.getProperty();
        if (property != null) {
          //属性不为null, 加到映射的属性中
          resultMap.mappedProperties.add(property);
        }
        //如果当前resultMapping有构造器的标记
        if (resultMapping.getFlags().contains(ResultFlag.CONSTRUCTOR)) {
          //记录构造器
          resultMap.constructorResultMappings.add(resultMapping);
          //构造器的属性有可能为null, 它可能按顺序来
          if (resultMapping.getProperty() != null) {
            //添加构造器的参数名
            constructorArgNames.add(resultMapping.getProperty());
          }
        } else {
          //不是构造器列的普通列
          resultMap.propertyResultMappings.add(resultMapping);
        }
        //是否id列, 可以多id列
        if (resultMapping.getFlags().contains(ResultFlag.ID)) {
          resultMap.idResultMappings.add(resultMapping);
        }
      }
      //如果没有id列, 则将全部列作为组合id列
      if (resultMap.idResultMappings.isEmpty()) {
        resultMap.idResultMappings.addAll(resultMap.resultMappings);
      }
      //如果构造器参数名不为空
      if (!constructorArgNames.isEmpty()) {
        final List<String> actualArgNames = argNamesOfMatchingConstructor(constructorArgNames);
        if (actualArgNames == null) {
          throw new BuilderException("Error in result map '" + resultMap.id
              + "'. Failed to find a constructor in '"
              + resultMap.getType().getName() + "' by arg names " + constructorArgNames
              + ". There might be more info in debug log.");
        }
        resultMap.constructorResultMappings.sort((o1, o2) -> {
          int paramIdx1 = actualArgNames.indexOf(o1.getProperty());
          int paramIdx2 = actualArgNames.indexOf(o2.getProperty());
          return paramIdx1 - paramIdx2;
        });
      }
      // lock down collections
      resultMap.resultMappings = Collections.unmodifiableList(resultMap.resultMappings);
      resultMap.idResultMappings = Collections.unmodifiableList(resultMap.idResultMappings);
      resultMap.constructorResultMappings = Collections.unmodifiableList(resultMap.constructorResultMappings);
      resultMap.propertyResultMappings = Collections.unmodifiableList(resultMap.propertyResultMappings);
      resultMap.mappedColumns = Collections.unmodifiableSet(resultMap.mappedColumns);
      return resultMap;
    }

    /**
     * 获取参数名和参数类型对应上的参数名列表
     */
    private List<String> argNamesOfMatchingConstructor(List<String> constructorArgNames) {
      //获取当前ResultMap返回类型声明的构造器
      Constructor<?>[] constructors = resultMap.type.getDeclaredConstructors();
      //遍历构造器
      for (Constructor<?> constructor : constructors) {
        //获取当前构造器的参数类型列表
        Class<?>[] paramTypes = constructor.getParameterTypes();
        //如果当前参数名列表长度跟构造器参数类型列表长度一样
        if (constructorArgNames.size() == paramTypes.length) {
          //获取构造器的参数名列表
          List<String> paramNames = getArgNames(constructor);
          //如果参数名配置的跟构造器一致且类型也对得上, 就返回参数名列表
          if (constructorArgNames.containsAll(paramNames)
              && argTypesMatch(constructorArgNames, paramTypes, paramNames)) {
            return paramNames;
          }
        }
      }
      //参数名和参数类型对应不上, 返回null
      return null;
    }

    /**
     * 校验参数名对应类型是否匹配
     */
    private boolean argTypesMatch(final List<String> constructorArgNames,
        Class<?>[] paramTypes, List<String> paramNames) {
      for (int i = 0; i < constructorArgNames.size(); i++) {
        //根据参数名找出对应位置的参数类型
        Class<?> actualType = paramTypes[paramNames.indexOf(constructorArgNames.get(i))];
        //找出构造器resultMapping对应的javaType, javaType如果不配置的话, 会自动推断出来
        Class<?> specifiedType = resultMap.constructorResultMappings.get(i).getJavaType();
        //如果有任意一个参数的类型对不上, 返回false
        if (!actualType.equals(specifiedType)) {
          if (log.isDebugEnabled()) {
            log.debug("While building result map '" + resultMap.id
                + "', found a constructor with arg names " + constructorArgNames
                + ", but the type of '" + constructorArgNames.get(i)
                + "' did not match. Specified: [" + specifiedType.getName() + "] Declared: ["
                + actualType.getName() + "]");
          }
          return false;
        }
      }
      return true;
    }

    /**
     * 获取构造器的参数名列表.
     * - 如果有指定@Param参数, 则返回指定的参数名
     * - 如果没有指定参数名, 但启用使用 真正的参数名, 获取真正的参数名使用
     * - 没有指定参数名, 也没有启用使用真正参数名, 返回系统生成的默认名称
     */
    private List<String> getArgNames(Constructor<?> constructor) {
      //记录参数名的列表
      List<String> paramNames = new ArrayList<>();
      //实际参数名
      List<String> actualParamNames = null;
      //获取构造器参数的注解
      final Annotation[][] paramAnnotations = constructor.getParameterAnnotations();
      int paramCount = paramAnnotations.length;
      //遍历参数注解
      for (int paramIndex = 0; paramIndex < paramCount; paramIndex++) {
        String name = null;
        //遍历当前的注解, 获取@Param参数的值作为name
        for (Annotation annotation : paramAnnotations[paramIndex]) {
          //如果找到Param, 直接退出
          if (annotation instanceof Param) {
            name = ((Param) annotation).value();
            break;
          }
        }
        //如果name不为null且有配置可以使用真实参数名
        if (name == null && resultMap.configuration.isUseActualParamName()) {
          //如果直实参数名列表为null
          if (actualParamNames == null) {
            //获取构造器的真实参数名
            actualParamNames = ParamNameUtil.getParamNames(constructor);
          }
          //如果当前实际参数名长度比当前索引大
          if (actualParamNames.size() > paramIndex) {
            //获取当前索引的真实参数名
            name = actualParamNames.get(paramIndex);
          }
        }
        //如果name不为null, 直接返回, 否则给一个默认的参数名
        paramNames.add(name != null ? name : "arg" + paramIndex);
      }
      return paramNames;
    }
  }

  public String getId() {
    return id;
  }

  public boolean hasNestedResultMaps() {
    return hasNestedResultMaps;
  }

  public boolean hasNestedQueries() {
    return hasNestedQueries;
  }

  public Class<?> getType() {
    return type;
  }

  public List<ResultMapping> getResultMappings() {
    return resultMappings;
  }

  public List<ResultMapping> getConstructorResultMappings() {
    return constructorResultMappings;
  }

  public List<ResultMapping> getPropertyResultMappings() {
    return propertyResultMappings;
  }

  public List<ResultMapping> getIdResultMappings() {
    return idResultMappings;
  }

  public Set<String> getMappedColumns() {
    return mappedColumns;
  }

  public Set<String> getMappedProperties() {
    return mappedProperties;
  }

  public Discriminator getDiscriminator() {
    return discriminator;
  }

  public void forceNestedResultMaps() {
    hasNestedResultMaps = true;
  }

  public Boolean getAutoMapping() {
    return autoMapping;
  }

}

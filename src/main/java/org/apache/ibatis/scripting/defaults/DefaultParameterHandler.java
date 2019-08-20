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
package org.apache.ibatis.scripting.defaults;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.ParameterMode;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeException;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.TypeHandlerRegistry;

/**
 * 默认的参数设置实现
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class DefaultParameterHandler implements ParameterHandler {

  /**
   * TypeHandler注册器
   */
  private final TypeHandlerRegistry typeHandlerRegistry;

  /**
   * MappedStatement对象
   */
  private final MappedStatement mappedStatement;
  /**
   * 参数
   */
  private final Object parameterObject;
  /**
   * BoundSql
   */
  private final BoundSql boundSql;
  /**
   * 全局配置
   */
  private final Configuration configuration;

  public DefaultParameterHandler(MappedStatement mappedStatement, Object parameterObject, BoundSql boundSql) {
    this.mappedStatement = mappedStatement;
    this.configuration = mappedStatement.getConfiguration();
    //从配置中获取TypeHandler注册器
    this.typeHandlerRegistry = mappedStatement.getConfiguration().getTypeHandlerRegistry();
    this.parameterObject = parameterObject;
    this.boundSql = boundSql;
  }

  @Override
  public Object getParameterObject() {
    return parameterObject;
  }

  @Override
  public void setParameters(PreparedStatement ps) {
    ErrorContext.instance().activity("setting parameters").object(mappedStatement.getParameterMap().getId());
    //获取参数映射列表
    List<ParameterMapping> parameterMappings = boundSql.getParameterMappings();
    //如果参数列不为空
    if (parameterMappings != null) {
      //遍历参数列表
      for (int i = 0; i < parameterMappings.size(); i++) {
        //获取参数映射
        ParameterMapping parameterMapping = parameterMappings.get(i);
        //如果参数不是返回值, 存储过程可以从参数中返回数据
        if (parameterMapping.getMode() != ParameterMode.OUT) {
          //参数值
          Object value;
          //参数名
          String propertyName = parameterMapping.getProperty();
          //如果 additional 参数中有这个属性
          if (boundSql.hasAdditionalParameter(propertyName)) { // issue #448 ask first for additional params
            //找到这个属性
            value = boundSql.getAdditionalParameter(propertyName);
            //如果参数为null
          } else if (parameterObject == null) {
            //那么参数值也为null
            value = null;
            //如果参数类型有对应的TypeHandler
          } else if (typeHandlerRegistry.hasTypeHandler(parameterObject.getClass())) {
            //将参数当作值
            value = parameterObject;
          } else {
            //否则, 创建参数的MetaObject
            MetaObject metaObject = configuration.newMetaObject(parameterObject);
            //从参数中获取对应属性的值
            value = metaObject.getValue(propertyName);
          }
          //获取参数映射的typeHandler
          TypeHandler typeHandler = parameterMapping.getTypeHandler();
          //获取jdbcType
          JdbcType jdbcType = parameterMapping.getJdbcType();
          //如果值为null, 或者jdbcType为null
          if (value == null && jdbcType == null) {
            //则给定一个默认的jdbcType的尝试
            jdbcType = configuration.getJdbcTypeForNull();
          }
          try {
            //调用typeHander来设置参数
            typeHandler.setParameter(ps, i + 1, value, jdbcType);
          } catch (TypeException | SQLException e) {
            throw new TypeException("Could not set parameters for mapping: " + parameterMapping + ". Cause: " + e, e);
          }
        }
      }
    }
  }

}

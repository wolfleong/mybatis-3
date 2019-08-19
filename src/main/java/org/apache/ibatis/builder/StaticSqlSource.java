/**
 *    Copyright 2009-2017 the original author or authors.
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

import java.util.List;

import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

/**
 * StaticSqlSource 的静态，是相对于 DynamicSqlSource 和 RawSqlSource 来说呢。实际上，StaticSqlSource.sql 属性，上面还是可能包括 ? 占位符
 * @author Clinton Begin
 */
public class StaticSqlSource implements SqlSource {

  /**
   * 静态的 SQL
   */
  private final String sql;
  /**
   * ParameterMapping集合
   */
  private final List<ParameterMapping> parameterMappings;
  private final Configuration configuration;

  /**
   * 不带参数的sqlSource
   */
  public StaticSqlSource(Configuration configuration, String sql) {
    this(configuration, sql, null);
  }

  /**
   * 带参数的sqlSource
   */
  public StaticSqlSource(Configuration configuration, String sql, List<ParameterMapping> parameterMappings) {
    this.sql = sql;
    this.parameterMappings = parameterMappings;
    this.configuration = configuration;
  }

  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    //创建 BoundSql 对象
    return new BoundSql(configuration, sql, parameterMappings, parameterObject);
  }

}

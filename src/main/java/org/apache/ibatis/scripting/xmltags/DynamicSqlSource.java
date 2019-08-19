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

import org.apache.ibatis.builder.SqlSourceBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.session.Configuration;

/**
 * 适用于使用了 OGNL 表达式，或者使用了 ${} 表达式的 SQL ，所以它是动态的，
 * 需要在每次执行 #getBoundSql(Object parameterObject) 方法，根据参数，生成对应的 SQL 。
 * @author Clinton Begin
 */
public class DynamicSqlSource implements SqlSource {

  private final Configuration configuration;
  private final SqlNode rootSqlNode;

  public DynamicSqlSource(Configuration configuration, SqlNode rootSqlNode) {
    this.configuration = configuration;
    this.rootSqlNode = rootSqlNode;
  }

  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    //创建相关的 DynamicContext
    DynamicContext context = new DynamicContext(configuration, parameterObject);
    //应用 sqlNode
    rootSqlNode.apply(context);
    //创建 SqlSourceBuilder
    SqlSourceBuilder sqlSourceParser = new SqlSourceBuilder(configuration);
    //获取参数类型
    Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
    //解析出来 sqlSource 对象, 实际上是StaticSqlSource对象
    SqlSource sqlSource = sqlSourceParser.parse(context.getSql(), parameterType, context.getBindings());
    //获取BoundSql
    BoundSql boundSql = sqlSource.getBoundSql(parameterObject);
    //设置额外绑定的参数, 因为在 ForEachSqlNode和 VarDeclSqlNode 中会添加一些非用户传的的参数进来
    context.getBindings().forEach(boundSql::setAdditionalParameter);
    return boundSql;
  }

}

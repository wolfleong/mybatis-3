/**
 *    Copyright 2009-2015 the original author or authors.
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

import org.apache.ibatis.builder.xml.XMLMapperEntityResolver;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;

/**
 * 实现 LanguageDriver 接口，XML 语言驱动实现类
 * @author Eduardo Macarron
 */
public class XMLLanguageDriver implements LanguageDriver {

  @Override
  public ParameterHandler createParameterHandler(MappedStatement mappedStatement, Object parameterObject, BoundSql boundSql) {
    //创建 DefaultParameterHandler
    return new DefaultParameterHandler(mappedStatement, parameterObject, boundSql);
  }

  @Override
  public SqlSource createSqlSource(Configuration configuration, XNode script, Class<?> parameterType) {
    //创建 XMLScriptBuilder 对象
    XMLScriptBuilder builder = new XMLScriptBuilder(configuration, script, parameterType);
    //解析节点
    return builder.parseScriptNode();
  }

  @Override
  public SqlSource createSqlSource(Configuration configuration, String script, Class<?> parameterType) {
    //如果文本是以<script>开头
    // issue #3
    if (script.startsWith("<script>")) {
      //创建XPath解析器
      XPathParser parser = new XPathParser(script, false, configuration.getVariables(), new XMLMapperEntityResolver());
      //获取<script>节点内容并调用 createSqlSource 创建 SqlSource
      return createSqlSource(configuration, parser.evalNode("/script"), parameterType);
    } else {
      //如果不是<script>开头的sql文本
      // issue #127
      //替换sql中${XXX}的动态变量
      script = PropertyParser.parse(script, configuration.getVariables());
      //创建TextSqlNode对象
      TextSqlNode textSqlNode = new TextSqlNode(script);
      //判断是否动态sql, 如果替换过一次之后还有${}变量, 则需要根据sql参数来填充
      if (textSqlNode.isDynamic()) {
        //如果是, 则创建 DynamicSqlSource 的 SqlSource
        return new DynamicSqlSource(configuration, textSqlNode);
      } else {
        //如果不是则创建 RawSqlSource
        return new RawSqlSource(configuration, script, parameterType);
      }
    }
  }

}

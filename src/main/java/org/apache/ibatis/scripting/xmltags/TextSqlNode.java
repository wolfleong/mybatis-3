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

import java.util.regex.Pattern;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.parsing.TokenHandler;
import org.apache.ibatis.scripting.ScriptingException;
import org.apache.ibatis.type.SimpleTypeRegistry;

/**
 * 普通文本
 * @author Clinton Begin
 */
public class TextSqlNode implements SqlNode {
  /**
   * 文本
   */
  private final String text;
  /**
   * 目前该属性只在单元测试中使用，暂时无视
   */
  private final Pattern injectionFilter;

  public TextSqlNode(String text) {
    this(text, null);
  }

  public TextSqlNode(String text, Pattern injectionFilter) {
    this.text = text;
    this.injectionFilter = injectionFilter;
  }

  /**
   * 是否动态
   */
  public boolean isDynamic() {
    //创建 DynamicCheckerTokenParser
    DynamicCheckerTokenParser checker = new DynamicCheckerTokenParser();
    //创建 GenericTokenParser
    GenericTokenParser parser = createParser(checker);
    //解析文本
    parser.parse(text);
    //返回是否太态
    return checker.isDynamic();
  }

  @Override
  public boolean apply(DynamicContext context) {
    //创建  BindingTokenParser 和 GenericTokenParser
    GenericTokenParser parser = createParser(new BindingTokenParser(context, injectionFilter));
    //执行解析
    //将解析结果, 添加到context中
    context.appendSql(parser.parse(text));
    return true;
  }

  private GenericTokenParser createParser(TokenHandler handler) {
    return new GenericTokenParser("${", "}", handler);
  }

  private static class BindingTokenParser implements TokenHandler {

    private DynamicContext context;
    private Pattern injectionFilter;

    public BindingTokenParser(DynamicContext context, Pattern injectionFilter) {
      this.context = context;
      this.injectionFilter = injectionFilter;
    }

    @Override
    public String handleToken(String content) {
      // 初始化 value 属性到 context 中
      //todo wolfleong 为什么要设置 value
      //获取 _parameter
      Object parameter = context.getBindings().get("_parameter");
      //如果 _parameter 为null
      if (parameter == null) {
        //设置 value 为 null
        context.getBindings().put("value", null);
        //如果 parameter 是简单类型
      } else if (SimpleTypeRegistry.isSimpleType(parameter.getClass())) {
        //设置 value 的值
        context.getBindings().put("value", parameter);
      }
      //根据ognl表达式获取值
      Object value = OgnlCache.getValue(content, context.getBindings());
      //如果为null, 则变空字符串
      String srtValue = value == null ? "" : String.valueOf(value); // issue #274 return "" instead of "null"
      checkInjection(srtValue);
      return srtValue;
    }

    private void checkInjection(String value) {
      if (injectionFilter != null && !injectionFilter.matcher(value).matches()) {
        throw new ScriptingException("Invalid input. Please conform to regex" + injectionFilter.pattern());
      }
    }
  }

  /**
   * 发现token时, 将文本标记为动态
   */
  private static class DynamicCheckerTokenParser implements TokenHandler {

    private boolean isDynamic;

    public DynamicCheckerTokenParser() {
      // Prevent Synthetic Access
    }

    public boolean isDynamic() {
      return isDynamic;
    }

    @Override
    public String handleToken(String content) {
      this.isDynamic = true;
      return null;
    }
  }

}

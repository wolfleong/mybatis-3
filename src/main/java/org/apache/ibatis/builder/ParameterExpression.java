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

import java.util.HashMap;

/**
 * 解析文本表达式, 可以看一下测试单元. 如:
 * - id:VARCHAR, attr1=val1, attr2=val2, attr3=val3
 * - id , attr1=val1, attr2=val2, attr3=val3
 * - (id.toString()):VARCHAR,name=value
 * - (id.toString()),name=value
 *
 * Inline parameter expression parser. Supported grammar (simplified):
 *
 * <pre>
 * inline-parameter = (propertyName | expression) oldJdbcType attributes
 * propertyName = /expression language's property navigation path/
 * expression = '(' /expression language's expression/ ')'
 * oldJdbcType = ':' /any valid jdbc type/
 * attributes = (',' attribute)*
 * attribute = name '=' value
 * </pre>
 *
 * @author Frank D. Martinez [mnesarco]
 */
public class ParameterExpression extends HashMap<String, String> {

  private static final long serialVersionUID = -2417552199605158680L;

  public ParameterExpression(String expression) {
    parse(expression);
  }

  private void parse(String expression) {
    //跳过前面空格, 获取第一个非空格字符索引 p
    int p = skipWS(expression, 0);
    //如果当前字符是 '(', 则表明是表达式开始的
    if (expression.charAt(p) == '(') {
      //则从忽略当前的 (, 从 p + 1 个开始
      expression(expression, p + 1);
    } else {
      //非 '(' 开始的
      property(expression, p);
    }
  }

  /**
   * 好东西, 搞了关天才知道为什么要这么处理. ()肯定是成对出现的
   * 解析表达式, 为什么要这么处理呢, 主要因为表达式中可能会有多个()对, 一个match就代表一个(, 那么就需要一个)来消除
   * @param expression 表达式
   * @param left 左
   */
  private void expression(String expression, int left) {
    //目前只有一个 (
    int match = 1;
    //从下一个字符开始
    int right = left + 1;
    //如果 ( 个数大于0, 则从 right 索引开始迭代字符, 当match为0时, right索引肯定是最后一个)的下一个字符
    while (match > 0) {
      //遇到一个 ) 则减一个
      if (expression.charAt(right) == ')') {
        match--;
        //遇到一个 ( 则加一个
      } else if (expression.charAt(right) == '(') {
        match++;
      }
      right++;
    }
    //获取表达式, 获取括号中的表达式 (expression)
    put("expression", expression.substring(left, right - 1));
    //获取禁接的jdbcType和后面的键值对
    jdbcTypeOpt(expression, right);
  }

  private void property(String expression, int left) {
    //如果非空偏移位置正常
    if (left < expression.length()) {
      //找到,:字符为结束符的索引, ,字符后面接键值对, :字符后面接jdbcType
      int right = skipUntil(expression, left, ",:");
      //获取property, 获取前先去掉前后空格
      put("property", trimmedStr(expression, left, right));
      //获取jdbcType或其他键值对option
      jdbcTypeOpt(expression, right);
    }
  }

  /**
   * 跳过空格
   * - 例如 "  1233" 返回是1对应索引位置
   * - " " 空格是ASCII可显示字符中最小的
   * @param expression 表达式
   * @param p  开始的位置
   * @return 返回没有空格字符的索引的位置
   */
  private int skipWS(String expression, int p) {
    for (int i = p; i < expression.length(); i++) {
      if (expression.charAt(i) > 0x20) {
        return i;
      }
    }
    return expression.length();
  }

  /**
   * 判断包含 endChars 的索引的位置，否则返回整个表达式字符串的长度
   * @param expression 表达式
   * @param p 开始位置
   * @param endChars 结束字符
   */
  private int skipUntil(String expression, int p, final String endChars) {
    //遍历表达式的字符
    for (int i = p; i < expression.length(); i++) {
      //获取表达式字符
      char c = expression.charAt(i);
      //如果存在任意一个字符是结束字符, 则返回位置
      if (endChars.indexOf(c) > -1) {
        return i;
      }
    }
    //如果没找到结束字符, 则返回整段字符串的长度
    return expression.length();
  }

  /**
   * 解析jdbc类型和可选项的值
   * @param expression 表达式
   * @param p 索引
   */
  private void jdbcTypeOpt(String expression, int p) {
    //跳过p后面的空格
    p = skipWS(expression, p);
    //如果p未超过字符串长度, 则表示字符串有值
    if (p < expression.length()) {
      //如开始字符是:, 则表示冒号后面紧接着就是jdbcType, 这个是旧的写法
      if (expression.charAt(p) == ':') {
        //忽略当前字符:, 取下一个索引 p + 1
        jdbcType(expression, p + 1);
        //如果是,字符
      } else if (expression.charAt(p) == ',') {
        // 则取下一个索引 p + 1
        option(expression, p + 1);
      } else {
        throw new BuilderException("Parsing error in {" + expression + "} in position " + p);
      }
    }
  }

  private void jdbcType(String expression, int p) {
    //跳过p后面的空格
    int left = skipWS(expression, p);
    //找到,字符结束的索引
    int right = skipUntil(expression, left, ",");
    //如果索引没有异常
    if (right > left) {
      //获取jdbcType, jdbcType是去掉前后空格的
      put("jdbcType", trimmedStr(expression, left, right));
    } else {
      //没找到, 则返回
      throw new BuilderException("Parsing error in {" + expression + "} in position " + p);
    }
    option(expression, right + 1);
  }

  private void option(String expression, int p) {
    //跳过前面字符
    int left = skipWS(expression, p);
    //如果剩下字符有值
    if (left < expression.length()) {
      //获取等号的位置
      int right = skipUntil(expression, left, "=");
      //获取等号前面的字符串,即name
      String name = trimmedStr(expression, left, right);
      //跳过=号的索引
      left = right + 1;
      //获取下一个结束字符,
      right = skipUntil(expression, left, ",");
      //获取=号到结束字符的值
      String value = trimmedStr(expression, left, right);
      //添加到map
      put(name, value);
      //取下一个键值对
      option(expression, right + 1);
    }
  }

  /**
   * 去掉前后的空格
   * @param str 字符串
   * @param start 开始索引
   * @param end 结束索引
   * @return 去掉前后空格, 相当于 " abc ".trim()
   */
  private String trimmedStr(String str, int start, int end) {
    //去掉字符串前面的空格
    while (str.charAt(start) <= 0x20) {
      start++;
    }
    //去掉字符串后面的空格
    while (str.charAt(end - 1) <= 0x20) {
      end--;
    }
    //如果终发现索引异常, 即表示这段字符串没有可见字符, 就返回空串
    //如果最终索引正常, 则截取字符串
    return start >= end ? "" : str.substring(start, end);
  }

}

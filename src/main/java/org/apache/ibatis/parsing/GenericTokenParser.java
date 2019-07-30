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
package org.apache.ibatis.parsing;

/**
 * 通用的token解析器, 每解析完一个token则用TokenHandler来处理一下, 再拼接回原来的字符串返回
 * 主要用于处理动态sql的占位符
 * @author Clinton Begin
 */
public class GenericTokenParser {

  /**
   * token的开始字符
   */
  private final String openToken;
  /**
   * token的结束字符
   */
  private final String closeToken;
  /**
   * token的处理器
   */
  private final TokenHandler handler;

  public GenericTokenParser(String openToken, String closeToken, TokenHandler handler) {
    this.openToken = openToken;
    this.closeToken = closeToken;
    this.handler = handler;
  }

  public String parse(String text) {
    //如果字符串为空, 则返回空串
    if (text == null || text.isEmpty()) {
      return "";
    }
    //如果整段字符串的没有开始的token, 直接返回原字符串, 如果有, 获取最开始的start
    // search open token
    int start = text.indexOf(openToken);
    if (start == -1) {
      return text;
    }
    char[] src = text.toCharArray();
    int offset = 0;
    final StringBuilder builder = new StringBuilder();
    StringBuilder expression = null;
    //如果字符串有token
    while (start > -1) {
      //如果token前面有转译字符, 则忽略当前token的start
      if (start > 0 && src[start - 1] == '\\') {
        //插入除了转译字符外的已经被转译的openToken和openToken前面的字符, 如: "{"
        // this open token is escaped. remove the backslash and continue.
        builder.append(src, offset, start - offset - 1).append(openToken);
        offset = start + openToken.length();
      } else {
        // found open token. let's search close token.
        //找到openToken, 然后开始找closeToken
        if (expression == null) {
          //如果是null, 就初始化
          expression = new StringBuilder();
        } else {
          //不为null就重置
          expression.setLength(0);
        }
        //插入openToken前的字符, 如: "abc{"前的"abc"
        builder.append(src, offset, start - offset);
        //找到token的起始位置
        offset = start + openToken.length();
        //找到第一个closeToken的位置
        int end = text.indexOf(closeToken, offset);
        //循环迭代
        while (end > -1) {
          //如果第一个有转译字符
          if (end > offset && src[end - 1] == '\\') {
            //保存结束前的字符串, 如: "abc\\}aaa"中的"}"
            // this close token is escaped. remove the backslash and continue.
            expression.append(src, offset, end - offset - 1).append(closeToken);
            //根据closeToken的长度, 重新定义offset的位置
            offset = end + closeToken.length();
            //再次找到结束的位置
            end = text.indexOf(closeToken, offset);
          } else {
            //如果找到, 剩余的字符串保存
            expression.append(src, offset, end - offset);
            break;
          }
        }
        //找不到结束字符
        if (end == -1) {
          //插入剩下整段字符, 如:"{XXXX"
          // close token was not found.
          builder.append(src, start, src.length - start);
          offset = src.length;
        } else {
          //找到结束字符, 用处理器处理, 如: "{abc}"中的"abc"
          builder.append(handler.handleToken(expression.toString()));
          //重新定义下一个token的偏移量
          offset = end + closeToken.length();
        }
      }
      //寻找下一个openToken
      start = text.indexOf(openToken, offset);
    }
    //将剩下的字符插入
    if (offset < src.length) {
      builder.append(src, offset, src.length - offset);
    }
    return builder.toString();
  }
}

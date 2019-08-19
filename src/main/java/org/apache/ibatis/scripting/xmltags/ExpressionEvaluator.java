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

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.BuilderException;

/**
 * OGNL 表达式计算器
 * @author Clinton Begin
 */
public class ExpressionEvaluator {

  /**
   * 获取表达式的 boolean 值
   */
  public boolean evaluateBoolean(String expression, Object parameterObject) {
    //获取表达式的值
    Object value = OgnlCache.getValue(expression, parameterObject);
    //如果是boolean, 则直接返回
    if (value instanceof Boolean) {
      return (Boolean) value;
    }
    //如果是数字, 则不为0则为真
    if (value instanceof Number) {
      return new BigDecimal(String.valueOf(value)).compareTo(BigDecimal.ZERO) != 0;
    }
    //不为null则为真
    return value != null;
  }

  /**
   * 获取表达式对应的集合
   */
  public Iterable<?> evaluateIterable(String expression, Object parameterObject) {
    //获取表达
    Object value = OgnlCache.getValue(expression, parameterObject);
    //如果值为null, 则报错
    if (value == null) {
      throw new BuilderException("The expression '" + expression + "' evaluated to a null value.");
    }
    //如果是 Iterable 直接返回
    if (value instanceof Iterable) {
      return (Iterable<?>) value;
    }
    //如果是数组, 则将数组变列表, 再返回
    if (value.getClass().isArray()) {
      // the array may be primitive, so Arrays.asList() may throw
      // a ClassCastException (issue 209).  Do the work manually
      // Curse primitives! :) (JGB)
      int size = Array.getLength(value);
      List<Object> answer = new ArrayList<>();
      for (int i = 0; i < size; i++) {
        Object o = Array.get(value, i);
        answer.add(o);
      }
      return answer;
    }
    //如果是Map, 则返回 Map.EntrySet
    if (value instanceof Map) {
      return ((Map) value).entrySet();
    }
    throw new BuilderException("Error evaluating expression '" + expression + "'.  Return value (" + value + ") was not iterable.");
  }

}

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

package org.apache.ibatis.builder.annotation;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.ibatis.builder.BuilderException;

/**
 * The interface that resolve an SQL provider method via an SQL provider class.
 *
 * <p> This interface need to implements at an SQL provider class and
 * it need to define the default constructor for creating a new instance.
 *
 * @since 3.5.1
 * @author Kazuki Shimizu
 */
public interface ProviderMethodResolver {

  /**
   * Resolve an SQL provider method.
   *
   * <p> The default implementation return a method that matches following conditions.
   * <ul>
   *   <li>Method name matches with mapper method</li>
   *   <li>Return type matches the {@link CharSequence}({@link String}, {@link StringBuilder}, etc...)</li>
   * </ul>
   * If matched method is zero or multiple, it throws a {@link BuilderException}.
   *
   * @param context a context for SQL provider
   * @return an SQL provider method
   * @throws BuilderException Throws when cannot resolve a target method
   */
  default Method resolveMethod(ProviderContext context) {
    //遍历当前类的所有方法, 获取跟Mapper接口的方法名一样的Method反射
    List<Method> sameNameMethods = Arrays.stream(getClass().getMethods())
        .filter(m -> m.getName().equals(context.getMapperMethod().getName()))
        .collect(Collectors.toList());
    //如果相同名称的方法不存在, 报错
    if (sameNameMethods.isEmpty()) {
      throw new BuilderException("Cannot resolve the provider method because '"
          + context.getMapperMethod().getName() + "' not found in SqlProvider '" + getClass().getName() + "'.");
    }
    //过滤出返回字符串的方法
    List<Method> targetMethods = sameNameMethods.stream()
        .filter(m -> CharSequence.class.isAssignableFrom(m.getReturnType()))
        .collect(Collectors.toList());
    //最终过滤出的方法只有一个, 直接返回
    if (targetMethods.size() == 1) {
      return targetMethods.get(0);
    }
    //如果没找到, 表示返回值不对
    if (targetMethods.isEmpty()) {
      throw new BuilderException("Cannot resolve the provider method because '"
          + context.getMapperMethod().getName() + "' does not return the CharSequence or its subclass in SqlProvider '"
          + getClass().getName() + "'.");
      //如果找到多个, 也报错
    } else {
      throw new BuilderException("Cannot resolve the provider method because '"
          + context.getMapperMethod().getName() + "' is found multiple in SqlProvider '" + getClass().getName() + "'.");
    }
  }

}

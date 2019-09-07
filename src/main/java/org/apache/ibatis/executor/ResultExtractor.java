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
package org.apache.ibatis.executor;

import java.lang.reflect.Array;
import java.util.List;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;

/**
 * 结果提取器, 根据将查询结果转换成真正的返回值类型
 * @author Andrew Gustafson
 */
public class ResultExtractor {
  private final Configuration configuration;
  private final ObjectFactory objectFactory;

  public ResultExtractor(Configuration configuration, ObjectFactory objectFactory) {
    this.configuration = configuration;
    this.objectFactory = objectFactory;
  }

  /**
   * 从 list 中, 提取结果
   * - 这部分逻辑在 MapperMethod.executeForMany 出现过
   * @param list 查询的结果
   * @param targetType 真正的结果类型
   */
  public Object extractObjectFromList(List<Object> list, Class<?> targetType) {
    Object value = null;
    //如果查询的类型是List.class
    if (targetType != null && targetType.isAssignableFrom(list.getClass())) {
      //直接返回当前结果
      value = list;
      //如果返回的类型是集合
    } else if (targetType != null && objectFactory.isCollection(targetType)) {
      //创建一个集合类型
      value = objectFactory.create(targetType);
      //创建返回值的 MetaObject
      MetaObject metaObject = configuration.newMetaObject(value);
      //添加结果
      metaObject.addAll(list);
      //如果是数组
    } else if (targetType != null && targetType.isArray()) {
      //获取目标的数组原始类型
      Class<?> arrayComponentType = targetType.getComponentType();
      //创建一个数组对象
      Object array = Array.newInstance(arrayComponentType, list.size());
      //如果数组原始类型是基本类型
      if (arrayComponentType.isPrimitive()) {
        //直接遍历设置值
        for (int i = 0; i < list.size(); i++) {
          Array.set(array, i, list.get(i));
        }
        //返回
        value = array;
      } else {
        //非基本类型则直接创建封装类型
        value = list.toArray((Object[])array);
      }
      //最后一种情况, 只能是单个对象
    } else {
      //校验结果是否只有一个值
      if (list != null && list.size() > 1) {
        //超过一个值就报错
        throw new ExecutorException("Statement returned more than one row, where no more than one was expected.");
        //如果查询的结果只有一个值
      } else if (list != null && list.size() == 1) {
        //直接获取
        value = list.get(0);
      }
    }
    //返回
    return value;
  }
}

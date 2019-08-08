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
package org.apache.ibatis.reflection.wrapper;

import java.util.List;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

/**
 * 对象包装器接口, 基于MetaClass工具类和实例对象, 可以对指定对象做各种操作. 相当于进一步加强MetaClass, 增加实例对象的操作
 * - 主要对对象进行单层属性的获取值和设置值
 * - 相对MetaClass增加了几个方法, get(), set(), instantiatePropertyValue(), isCollection(),add(),
 *   这些新增的方法都是对对象属性的单层操作. 这些get(), set(), instantiatePropertyValue()都是对不同的对象类型有不同的实现,
 *   它的实现主要分三类, bean对象, Collection对象, Map对象
 * - 增强了MetaClass的相关方法, 如: getSetterType(), hasSetter(), getGetterType(), hasGetter(),
 *    因为有实例对象的存在, 可以处理MetaClass处理不了的情况, person.hobbyMap[football].num 和 person.list[0].name
 *
 * @author Clinton Begin
 */
public interface ObjectWrapper {

  /**
   * 获取指定属性的值
   */
  Object get(PropertyTokenizer prop);

  /**
   * 设置指定属性的值
   */
  void set(PropertyTokenizer prop, Object value);

  String findProperty(String name, boolean useCamelCaseMapping);

  String[] getGetterNames();

  String[] getSetterNames();

  Class<?> getSetterType(String name);

  Class<?> getGetterType(String name);

  boolean hasSetter(String name);

  boolean hasGetter(String name);

  /**
   * 初始化指定属性的值
   */
  MetaObject instantiatePropertyValue(String name, PropertyTokenizer prop, ObjectFactory objectFactory);

  /**
   * 是否集合
   */
  boolean isCollection();

  void add(Object element);

  <E> void addAll(List<E> element);

}

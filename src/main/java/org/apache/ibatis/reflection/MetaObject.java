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
package org.apache.ibatis.reflection;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyTokenizer;
import org.apache.ibatis.reflection.wrapper.BeanWrapper;
import org.apache.ibatis.reflection.wrapper.CollectionWrapper;
import org.apache.ibatis.reflection.wrapper.MapWrapper;
import org.apache.ibatis.reflection.wrapper.ObjectWrapper;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;

/**
 * 对象元数据, 主要是基于 ObjectWrapper 来操作对象的值, 对 ObjectWrapper 进行增强处理
 * - 增加的方法 getValue()方法是对ObjectWrapper.get()方法的增强, setValue()是对ObjectWrapper.set()增强, 其他方法则直接调用
 *   ObjectWrapper的方法. 增强的内容是, 可以链式获取指定属性的对象
 * - 创建MetaObject时会根据不同类型的对象, 创建不同的ObjectWrapper
 * @author Clinton Begin
 */
public class MetaObject {

  /**
   * 原始对象
   */
  private final Object originalObject;
  /**
   * 封装过的对象
   */
  private final ObjectWrapper objectWrapper;
  /**
   * 对象工厂
   */
  private final ObjectFactory objectFactory;
  /**
   * ObjectWrapper工厂
   */
  private final ObjectWrapperFactory objectWrapperFactory;
  /**
   * 反射工厂
   */
  private final ReflectorFactory reflectorFactory;

  private MetaObject(Object object, ObjectFactory objectFactory, ObjectWrapperFactory objectWrapperFactory, ReflectorFactory reflectorFactory) {
    this.originalObject = object;
    this.objectFactory = objectFactory;
    this.objectWrapperFactory = objectWrapperFactory;
    this.reflectorFactory = reflectorFactory;

    if (object instanceof ObjectWrapper) {
      this.objectWrapper = (ObjectWrapper) object;
    } else if (objectWrapperFactory.hasWrapperFor(object)) {
      this.objectWrapper = objectWrapperFactory.getWrapperFor(this, object);
    } else if (object instanceof Map) {
      this.objectWrapper = new MapWrapper(this, (Map) object);
    } else if (object instanceof Collection) {
      this.objectWrapper = new CollectionWrapper(this, (Collection) object);
    } else {
      this.objectWrapper = new BeanWrapper(this, object);
    }
  }

  /**
   * 通过对象创建MetaObject
   */
  public static MetaObject forObject(Object object, ObjectFactory objectFactory, ObjectWrapperFactory objectWrapperFactory, ReflectorFactory reflectorFactory) {
    if (object == null) {
      //如果对象为null, 则返回NULL的代理对象
      return SystemMetaObject.NULL_META_OBJECT;
    } else {
      //对象不为null, 则创建
      return new MetaObject(object, objectFactory, objectWrapperFactory, reflectorFactory);
    }
  }

  public ObjectFactory getObjectFactory() {
    return objectFactory;
  }

  public ObjectWrapperFactory getObjectWrapperFactory() {
    return objectWrapperFactory;
  }

  public ReflectorFactory getReflectorFactory() {
    return reflectorFactory;
  }

  public Object getOriginalObject() {
    return originalObject;
  }

  /**
   * 根据调用链查找属性
   */
  public String findProperty(String propName, boolean useCamelCaseMapping) {
    return objectWrapper.findProperty(propName, useCamelCaseMapping);
  }

  public String[] getGetterNames() {
    return objectWrapper.getGetterNames();
  }

  public String[] getSetterNames() {
    return objectWrapper.getSetterNames();
  }

  public Class<?> getSetterType(String name) {
    return objectWrapper.getSetterType(name);
  }

  public Class<?> getGetterType(String name) {
    return objectWrapper.getGetterType(name);
  }

  public boolean hasSetter(String name) {
    return objectWrapper.hasSetter(name);
  }

  public boolean hasGetter(String name) {
    return objectWrapper.hasGetter(name);
  }

  /**
   * 获取指定属性的值, 相当于递归获取值
   */
  public Object getValue(String name) {
    //属性解析器
    PropertyTokenizer prop = new PropertyTokenizer(name);
    //如果有多层属性
    if (prop.hasNext()) {
      //有可能是集合, 所以要取带索引的名字
      //获取当前属性的 MetaObject
      MetaObject metaValue = metaObjectForProperty(prop.getIndexedName());
      if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
        return null;
      } else {
        //用当前获取到的MetaObject再获取子对属性
        return metaValue.getValue(prop.getChildren());
      }
    } else {
      //只有一层属性, 则直接根据属性名返回值
      return objectWrapper.get(prop);
    }
  }

  /**
   * 设置指定属性的值
   *
   */
  public void setValue(String name, Object value) {
    //属性解析器分解
    PropertyTokenizer prop = new PropertyTokenizer(name);
    //如果有多层层属性
    if (prop.hasNext()) {
      //先获取第一层的MetaObject
      MetaObject metaValue = metaObjectForProperty(prop.getIndexedName());
      //如果第一层为空值
      if (metaValue == SystemMetaObject.NULL_META_OBJECT) {
        //如果要设置的值为null, 则不用处理
        if (value == null) {
          // don't instantiate child path if value is null
          return;
        } else {
          //要设置的值不为null, 要在达到设置属性的对象前, 初始化属性
          metaValue = objectWrapper.instantiatePropertyValue(name, prop, objectFactory);
        }
      }
      metaValue.setValue(prop.getChildren(), value);
    } else {
      //只有一层属性, 直接设置
      objectWrapper.set(prop, value);
    }
  }

  /**
   * 创建指定属性值的 MetaObject
   */
  public MetaObject metaObjectForProperty(String name) {
    //获取当前属性的值
    Object value = getValue(name);
    //用这个值对象创建对应的MetaObject
    return MetaObject.forObject(value, objectFactory, objectWrapperFactory, reflectorFactory);
  }

  public ObjectWrapper getObjectWrapper() {
    return objectWrapper;
  }

  public boolean isCollection() {
    return objectWrapper.isCollection();
  }

  public void add(Object element) {
    objectWrapper.add(element);
  }

  public <E> void addAll(List<E> list) {
    objectWrapper.addAll(list);
  }

}

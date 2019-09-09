/**
 *    Copyright 2009-2017 the original author or authors.
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
package org.apache.ibatis.executor.loader;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.ibatis.executor.ExecutorException;

import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyCopier;
import org.apache.ibatis.reflection.property.PropertyNamer;

/**
 * 反序列化增强的抽象类
 * todo wolfleong 什么场景才会反序列化
 * @author Clinton Begin
 */
public abstract class AbstractEnhancedDeserializationProxy {

  protected static final String FINALIZE_METHOD = "finalize";
  protected static final String WRITE_REPLACE_METHOD = "writeReplace";
  /**
   * 代理对象类型
   */
  private final Class<?> type;
  /**
   * 未加载属性映射
   */
  private final Map<String, ResultLoaderMap.LoadPair> unloadedProperties;
  /**
   * 对象工厂
   */
  private final ObjectFactory objectFactory;
  /**
   * 对象创建的参数类型列表
   */
  private final List<Class<?>> constructorArgTypes;
  /**
   * 对象创建的参数列表
   */
  private final List<Object> constructorArgs;
  /**
   * 同步锁
   */
  private final Object reloadingPropertyLock;
  /**
   * 是否正在加载属性
   */
  private boolean reloadingProperty;

  protected AbstractEnhancedDeserializationProxy(Class<?> type, Map<String, ResultLoaderMap.LoadPair> unloadedProperties,
          ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
    this.type = type;
    this.unloadedProperties = unloadedProperties;
    this.objectFactory = objectFactory;
    this.constructorArgTypes = constructorArgTypes;
    this.constructorArgs = constructorArgs;
    this.reloadingPropertyLock = new Object();
    this.reloadingProperty = false;
  }

  /**
   * 这个 invoke 方法只做两件事, 只
   * - 实现了 writeReplace 方法
   * - 如果是 getter 和 setter 方法, 则执行懒加载
   * - 其他方法不做任务处理
   */
  public final Object invoke(Object enhanced, Method method, Object[] args) throws Throwable {
    //获取方法名
    final String methodName = method.getName();
    try {
      //如果调用的是 writeReplace 方法
      if (WRITE_REPLACE_METHOD.equals(methodName)) {
        final Object original;
        //如果对象参数类型列表是空
        if (constructorArgTypes.isEmpty()) {
          //用默认构造函数创建对象
          original = objectFactory.create(type);
        } else {
          //用指定参数创建对象
          original = objectFactory.create(type, constructorArgTypes, constructorArgs);
        }

        //复制对象属性
        PropertyCopier.copyBeanProperties(type, enhanced, original);
        //创建 AbstractSerialStateHolder
        return this.newSerialStateHolder(original, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
      } else {
        //同步处理
        synchronized (this.reloadingPropertyLock) {
          //如果调用的是非 FINALIZE_METHOD 方法, 且是调用操作属性的方法, 并且不
          //todo wolfleong 这里为什么不用判断是否用了 getter, 难道调用了setter 也做懒加载处理吗
          if (!FINALIZE_METHOD.equals(methodName) && PropertyNamer.isProperty(methodName) && !reloadingProperty) {
            //获取属性名
            final String property = PropertyNamer.methodToProperty(methodName);
            //属性名变大写
            final String propertyKey = property.toUpperCase(Locale.ENGLISH);
            //如果这个属性未加载
            if (unloadedProperties.containsKey(propertyKey)) {
              //获取懒加载配置, 并删除
              final ResultLoaderMap.LoadPair loadPair = unloadedProperties.remove(propertyKey);
              if (loadPair != null) {
                try {
                  //设置正在加载
                  reloadingProperty = true;
                  //加载属性
                  loadPair.load(enhanced);
                } finally {
                  //加载完成
                  reloadingProperty = false;
                }
              } else {
                /* I'm not sure if this case can really happen or is just in tests -
                 * we have an unread property but no loadPair to load it. */
                throw new ExecutorException("An attempt has been made to read a not loaded lazy property '"
                        + property + "' of a disconnected object");
              }
            }
          }

          return enhanced;
        }
      }
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }
  }

  protected abstract AbstractSerialStateHolder newSerialStateHolder(
          Object userBean,
          Map<String, ResultLoaderMap.LoadPair> unloadedProperties,
          ObjectFactory objectFactory,
          List<Class<?>> constructorArgTypes,
          List<Object> constructorArgs);

}

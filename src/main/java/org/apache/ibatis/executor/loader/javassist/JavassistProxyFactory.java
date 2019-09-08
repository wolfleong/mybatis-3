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
package org.apache.ibatis.executor.loader.javassist;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javassist.util.proxy.MethodHandler;
import javassist.util.proxy.Proxy;
import javassist.util.proxy.ProxyFactory;

import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.loader.AbstractEnhancedDeserializationProxy;
import org.apache.ibatis.executor.loader.AbstractSerialStateHolder;
import org.apache.ibatis.executor.loader.ResultLoaderMap;
import org.apache.ibatis.executor.loader.WriteReplaceInterface;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.property.PropertyCopier;
import org.apache.ibatis.reflection.property.PropertyNamer;
import org.apache.ibatis.session.Configuration;

/**
 * 实现 ProxyFactory 接口，基于 Javassist 的 ProxyFactory 实现类
 * @author Eduardo Macarron
 */
public class JavassistProxyFactory implements org.apache.ibatis.executor.loader.ProxyFactory {

  private static final String FINALIZE_METHOD = "finalize";
  private static final String WRITE_REPLACE_METHOD = "writeReplace";

  public JavassistProxyFactory() {
    try {
      // 加载 javassist.util.proxy.ProxyFactory 类
      Resources.classForName("javassist.util.proxy.ProxyFactory");
    } catch (Throwable e) {
      throw new IllegalStateException("Cannot enable lazy loading because Javassist is not available. Add Javassist to your classpath.", e);
    }
  }

  @Override
  public Object createProxy(Object target, ResultLoaderMap lazyLoader, Configuration configuration, ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
    //创建代理对象
    return EnhancedResultObjectProxyImpl.createProxy(target, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
  }

  /**
   * 创建支持反序列化的代理对象
   */
  public Object createDeserializationProxy(Object target, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
    //创建
    return EnhancedDeserializationProxyImpl.createProxy(target, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
  }

  /**
   * 创建代理对象的静态方法, 常见的基于 Javassist 的 API ，创建代理对象。
   */
  static Object crateProxy(Class<?> type, MethodHandler callback, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {

    //创建代理工厂
    ProxyFactory enhancer = new ProxyFactory();
    //设置父类
    enhancer.setSuperclass(type);

    try {
      //从父类的class 对象获取方法名为 WRITE_REPLACE_METHOD 的方法映射
      type.getDeclaredMethod(WRITE_REPLACE_METHOD);
      // ObjectOutputStream will call writeReplace of objects returned by writeReplace
      if (LogHolder.log.isDebugEnabled()) {
        LogHolder.log.debug(WRITE_REPLACE_METHOD + " method was found on bean " + type + ", make sure it returns this");
      }
    } catch (NoSuchMethodException e) {
      //如果报异常, 则表明父类不存在此方法
      //设置代理对象实现的接口
      enhancer.setInterfaces(new Class[]{WriteReplaceInterface.class});
    } catch (SecurityException e) {
      // nothing to do here
    }

    //代理对象引用
    Object enhanced;
    //构造参数类型数组
    Class<?>[] typesArray = constructorArgTypes.toArray(new Class[constructorArgTypes.size()]);
    //构造参数
    Object[] valuesArray = constructorArgs.toArray(new Object[constructorArgs.size()]);
    try {
      //创建代理对象
      enhanced = enhancer.create(typesArray, valuesArray);
    } catch (Exception e) {
      throw new ExecutorException("Error creating lazy proxy.  Cause: " + e, e);
    }
    //设置回调参数
    ((Proxy) enhanced).setHandler(callback);
    //返回代理对象
    return enhanced;
  }

  /**
   * 实现 javassist.util.proxy.MethodHandler 接口，方法处理器实现类
   */
  private static class EnhancedResultObjectProxyImpl implements MethodHandler {

    /**
     * 结果对象的类型
     */
    private final Class<?> type;
    /**
     * 结果加载缓存映射
     */
    private final ResultLoaderMap lazyLoader;
    /**
     * true 一次加载所有属性, false 代表按需加载
     */
    private final boolean aggressive;
    /**
     * 触发延迟加载的方法
     */
    private final Set<String> lazyLoadTriggerMethods;
    /**
     * 对象工厂
     */
    private final ObjectFactory objectFactory;
    /**
     *  结果对象类构造器参数的类型列表
     */
    private final List<Class<?>> constructorArgTypes;
    /**
     * 结果对象类构造器参数列表
     */
    private final List<Object> constructorArgs;

    private EnhancedResultObjectProxyImpl(Class<?> type, ResultLoaderMap lazyLoader, Configuration configuration, ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
      this.type = type;
      this.lazyLoader = lazyLoader;
      //从配置获取
      this.aggressive = configuration.isAggressiveLazyLoading();
      //从配置中获取
      this.lazyLoadTriggerMethods = configuration.getLazyLoadTriggerMethods();
      this.objectFactory = objectFactory;
      this.constructorArgTypes = constructorArgTypes;
      this.constructorArgs = constructorArgs;
    }

    public static Object createProxy(Object target, ResultLoaderMap lazyLoader, Configuration configuration, ObjectFactory objectFactory, List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
      //获取结果对象的类型
      final Class<?> type = target.getClass();
      //创建代理对象方法处理器的回调
      EnhancedResultObjectProxyImpl callback = new EnhancedResultObjectProxyImpl(type, lazyLoader, configuration, objectFactory, constructorArgTypes, constructorArgs);
      //创建空的代理对象
      Object enhanced = crateProxy(type, callback, constructorArgTypes, constructorArgs);
      //将原来的结果对象的属性复制到代理对象中
      PropertyCopier.copyBeanProperties(type, target, enhanced);
      //返回代理对象
      return enhanced;
    }

    @Override
    public Object invoke(Object enhanced, Method method, Method methodProxy, Object[] args) throws Throwable {
      //获取方法名
      final String methodName = method.getName();
      try {
        //同步处理, 保证线程安全
        synchronized (lazyLoader) {
          //如果调用的是 WRITE_REPLACE_METHOD 方法, WRITE_REPLACE_METHOD 方法会在对象被序列化时创建
          if (WRITE_REPLACE_METHOD.equals(methodName)) {
            //创建复制对象的引用
            Object original;
            //如果构造参数类型列表是空
            if (constructorArgTypes.isEmpty()) {
              //用默认构造器创建对象
              original = objectFactory.create(type);
            } else {
              //用指定参数和参数类型创建结果对象
              original = objectFactory.create(type, constructorArgTypes, constructorArgs);
            }
            //调用对象的属性到新创建的对象中
            PropertyCopier.copyBeanProperties(type, enhanced, original);
            //如果有懒加载
            if (lazyLoader.size() > 0) {
              //创建 JavassistSerialStateHolder , 并返回
              return new JavassistSerialStateHolder(original, lazyLoader.getProperties(), objectFactory, constructorArgTypes, constructorArgs);
            } else {
              //返回创建的复制对象
              return original;
            }
            //调用的是普通方法
          } else {
            //有懒加载且非 FINALIZE_METHOD 方法
            if (lazyLoader.size() > 0 && !FINALIZE_METHOD.equals(methodName)) {
              //如果设置全部触发 且是触发懒加载的方法
              if (aggressive || lazyLoadTriggerMethods.contains(methodName)) {
                //全部加载
                lazyLoader.loadAll();
                //如果是调用 setter
              } else if (PropertyNamer.isSetter(methodName)) {
                //获取方法的属性名
                final String property = PropertyNamer.methodToProperty(methodName);
                //删除对应的懒加载, 因为手动设置了
                lazyLoader.remove(property);
                //调用 getter
              } else if (PropertyNamer.isGetter(methodName)) {
                //获取方法属性
                final String property = PropertyNamer.methodToProperty(methodName);
                //判断是否有懒加载
                if (lazyLoader.hasLoader(property)) {
                  //加载
                  lazyLoader.load(property);
                }
              }
            }
          }
        }
        //调用原来的方法
        return methodProxy.invoke(enhanced, args);
      } catch (Throwable t) {
        throw ExceptionUtil.unwrapThrowable(t);
      }
    }
  }

  /**
   * 反序列化的代理增强
   */
  private static class EnhancedDeserializationProxyImpl extends AbstractEnhancedDeserializationProxy implements MethodHandler {

    private EnhancedDeserializationProxyImpl(Class<?> type, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
            List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
      super(type, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
    }

    public static Object createProxy(Object target, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
            List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
      //获取代理的类型
      final Class<?> type = target.getClass();
      //创建方法处理器
      EnhancedDeserializationProxyImpl callback = new EnhancedDeserializationProxyImpl(type, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
      //创建代理类
      Object enhanced = crateProxy(type, callback, constructorArgTypes, constructorArgs);
      //复制属性
      PropertyCopier.copyBeanProperties(type, target, enhanced);
      return enhanced;
    }

    @Override
    public Object invoke(Object enhanced, Method method, Method methodProxy, Object[] args) throws Throwable {
      //执行方法
      final Object o = super.invoke(enhanced, method, args);
      //如果返回的对象是 AbstractSerialStateHolder, 则直接返回, 否则执行 methodProxy 的方法, 因为有些方法 super.invoke是没有处理的
      return o instanceof AbstractSerialStateHolder ? o : methodProxy.invoke(o, args);
    }

    @Override
    protected AbstractSerialStateHolder newSerialStateHolder(Object userBean, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
            List<Class<?>> constructorArgTypes, List<Object> constructorArgs) {
      //创建 AbstractSerialStateHolder
      return new JavassistSerialStateHolder(userBean, unloadedProperties, objectFactory, constructorArgTypes, constructorArgs);
    }
  }

  private static class LogHolder {
    private static final Log log = LogFactory.getLog(JavassistProxyFactory.class);
  }

}

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
package org.apache.ibatis.plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.reflection.ExceptionUtil;

/**
 * 实现 InvocationHandler 接口, 插件类
 * - 提供创建动态代理对象的方法
 * - 实现对指定类的指定方法的拦截处理
 * @author Clinton Begin
 */
public class Plugin implements InvocationHandler {

  /**
   * 目标对象
   */
  private final Object target;
  /**
   * 拦截器
   */
  private final Interceptor interceptor;
  /**
   * 拦截的方法映射 Map<接口, Set<拦截的方法>>
   */
  private final Map<Class<?>, Set<Method>> signatureMap;

  private Plugin(Object target, Interceptor interceptor, Map<Class<?>, Set<Method>> signatureMap) {
    this.target = target;
    this.interceptor = interceptor;
    this.signatureMap = signatureMap;
  }

  public static Object wrap(Object target, Interceptor interceptor) {
    //获取拦截的方法的映射
    Map<Class<?>, Set<Method>> signatureMap = getSignatureMap(interceptor);
    //获取目标类型
    Class<?> type = target.getClass();
    //获取目标类的所有接口
    Class<?>[] interfaces = getAllInterfaces(type, signatureMap);
    //如果有实现接口, 则创建目标对象的 JDK 代理对象
    if (interfaces.length > 0) {
      return Proxy.newProxyInstance(
          type.getClassLoader(),
          interfaces,
          new Plugin(target, interceptor, signatureMap));
    }
    //如果没有, 则返回原始的目标对象
    return target;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    try {
      //根据方法的声明类, 获取配置的方法
      Set<Method> methods = signatureMap.get(method.getDeclaringClass());
      //如果方法列表不为null, 且执行的方法包括在方法列表中
      if (methods != null && methods.contains(method)) {
        //调用插件
        return interceptor.intercept(new Invocation(target, method, args));
      }
      //执行原来的方法
      return method.invoke(target, args);
    } catch (Exception e) {
      throw ExceptionUtil.unwrapThrowable(e);
    }
  }

  private static Map<Class<?>, Set<Method>> getSignatureMap(Interceptor interceptor) {
    //获取注解 @Intercepts
    Intercepts interceptsAnnotation = interceptor.getClass().getAnnotation(Intercepts.class);
    // 拦截器上没有注解, 则报错
    // issue #251
    if (interceptsAnnotation == null) {
      throw new PluginException("No @Intercepts annotation was found in interceptor " + interceptor.getClass().getName());
    }
    //获取 @Signature
    Signature[] sigs = interceptsAnnotation.value();
    //创建 Map
    Map<Class<?>, Set<Method>> signatureMap = new HashMap<>();
    //遍历 @Signature 列表
    for (Signature sig : sigs) {
      //初始化方法列表
      Set<Method> methods = signatureMap.computeIfAbsent(sig.type(), k -> new HashSet<>());
      try {
        //根据方法名和参数类型列表, 获取指定方法
        Method method = sig.type().getMethod(sig.method(), sig.args());
        //添加到方法列表
        methods.add(method);
      } catch (NoSuchMethodException e) {
        throw new PluginException("Could not find method on " + sig.type() + " named " + sig.method() + ". Cause: " + e, e);
      }
    }
    //返回对象的拦截的映射
    return signatureMap;
  }

  private static Class<?>[] getAllInterfaces(Class<?> type, Map<Class<?>, Set<Method>> signatureMap) {
    //接口列表
    Set<Class<?>> interfaces = new HashSet<>();
    //如果类型不为 null
    while (type != null) {
      //遍历接口的列表
      for (Class<?> c : type.getInterfaces()) {
        //如果包括指定的接口
        if (signatureMap.containsKey(c)) {
          //添加接口
          interfaces.add(c);
        }
      }
      //获取父类
      type = type.getSuperclass();
    }
    //返回接口数组
    return interfaces.toArray(new Class<?>[interfaces.size()]);
  }

}

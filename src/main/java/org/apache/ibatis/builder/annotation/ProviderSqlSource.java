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

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

import org.apache.ibatis.annotations.Lang;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.reflection.ParamNameResolver;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class ProviderSqlSource implements SqlSource {

  private final Configuration configuration;
  /**
   * 提供sql的类
   */
  private final Class<?> providerType;
  /**
   * languageDriver对象
   */
  private final LanguageDriver languageDriver;
  /**
   * 提供sql的方法反射
   */
  private Method providerMethod;
  /**
   * 提供sql方法的参数名数组
   */
  private String[] providerMethodArgumentNames;
  /**
   * 提供sql方法的参数类型数组
   */
  private Class<?>[] providerMethodParameterTypes;
  /**
   * 相当于元数据对象, 用于传入给 providerMethod的参数, 如果提供sql方法有ProviderContext类型的参数才创建ProviderContext对象
   */
  private ProviderContext providerContext;
  /**
   * 提供sql方法的ProviderContext参数的位置
   */
  private Integer providerContextIndex;

  /**
   * @deprecated Please use the {@link #ProviderSqlSource(Configuration, Object, Class, Method)} instead of this.
   */
  @Deprecated
  public ProviderSqlSource(Configuration configuration, Object provider) {
    this(configuration, provider, null, null);
  }

  /**
   * @since 3.4.5
   */
  public ProviderSqlSource(Configuration configuration, Object provider, Class<?> mapperType, Method mapperMethod) {
    //
    String providerMethodName;
    try {
      this.configuration = configuration;
      //如果 mapperMethod方法为null, 获取方法上的@Lang注解
      Lang lang = mapperMethod == null ? null : mapperMethod.getAnnotation(Lang.class);
      //如果@Lang注解不为null, 获取LanguageDriver对象
      this.languageDriver = configuration.getLanguageDriver(lang == null ? null : lang.value());
      //从注解中获取providerType, 即sql提供类
      this.providerType = getProviderType(provider, mapperMethod);
      //从注解中获取sql提供类, 提供sql的方法名
      providerMethodName = (String) provider.getClass().getMethod("method").invoke(provider);

      //如果没有指定方法, 但是有实现 ProviderMethodResolver 类
      if (providerMethodName.length() == 0 && ProviderMethodResolver.class.isAssignableFrom(this.providerType)) {
        //继承ProviderMethodResolver接口，Mapper中可以直接指定该类即可，但对应的Mapper的方法名要跟Builder的方法名相同
        //创建 ProviderMethodResolver 子类的实例并且执行 resolveMethod 方法来获取提供sql的方法
        this.providerMethod = ((ProviderMethodResolver) this.providerType.getDeclaredConstructor().newInstance())
            .resolveMethod(new ProviderContext(mapperType, mapperMethod, configuration.getDatabaseId()));
      }
      //如果前面没有找到提供sql的方法反射, 也就是 providerType 没有实现 ProviderMethodResolver 接口
      if (this.providerMethod == null) {
        //如果 providerMethodName 不为空串, 则有指定方法名, 如果为空串, 则给定一默认的方法名
        providerMethodName = providerMethodName.length() == 0 ? "provideSql" : providerMethodName;
        //遍历sql提供类的所有方法
        for (Method m : this.providerType.getMethods()) {
          //方法名相同, 且返回值是字符串的
          if (providerMethodName.equals(m.getName()) && CharSequence.class.isAssignableFrom(m.getReturnType())) {
            //如果名称相同和返回值是字符串的方法有多个, 则报错
            if (this.providerMethod != null) {
              throw new BuilderException("Error creating SqlSource for SqlProvider. Method '"
                  + providerMethodName + "' is found multiple in SqlProvider '" + this.providerType.getName()
                  + "'. Sql provider method can not overload.");
            }
            //赋值
            this.providerMethod = m;
          }
        }
      }
    } catch (BuilderException e) {
      throw e;
    } catch (Exception e) {
      throw new BuilderException("Error creating SqlSource for SqlProvider.  Cause: " + e, e);
    }
    //最后还是找不到, 则报错
    if (this.providerMethod == null) {
      throw new BuilderException("Error creating SqlSource for SqlProvider. Method '"
          + providerMethodName + "' not found in SqlProvider '" + this.providerType.getName() + "'.");
    }
    //获取sql提供方法的所有参数名
    this.providerMethodArgumentNames = new ParamNameResolver(configuration, this.providerMethod).getNames();
    //获取sql提供方法的参数类型
    this.providerMethodParameterTypes = this.providerMethod.getParameterTypes();
    //遍历参数类型
    for (int i = 0; i < this.providerMethodParameterTypes.length; i++) {
      //获取参数类型
      Class<?> parameterType = this.providerMethodParameterTypes[i];
      //如果有 ProviderContext 类型的参数
      if (parameterType == ProviderContext.class) {
        //如果 providerContext 参数有多个, 则报错
        if (this.providerContext != null) {
          throw new BuilderException("Error creating SqlSource for SqlProvider. ProviderContext found multiple in SqlProvider method ("
              + this.providerType.getName() + "." + providerMethod.getName()
              + "). ProviderContext can not define multiple in SqlProvider method argument.");
        }
        //则创建 ProviderContext 对象
        this.providerContext = new ProviderContext(mapperType, mapperMethod, configuration.getDatabaseId());
        //记录 ProviderContext 参数的位置
        this.providerContextIndex = i;
      }
    }
  }

  @Override
  public BoundSql getBoundSql(Object parameterObject) {
    SqlSource sqlSource = createSqlSource(parameterObject);
    return sqlSource.getBoundSql(parameterObject);
  }

  private SqlSource createSqlSource(Object parameterObject) {
    try {
      int bindParameterCount = providerMethodParameterTypes.length - (providerContext == null ? 0 : 1);
      String sql;
      if (providerMethodParameterTypes.length == 0) {
        sql = invokeProviderMethod();
      } else if (bindParameterCount == 0) {
        sql = invokeProviderMethod(providerContext);
      } else if (bindParameterCount == 1
           && (parameterObject == null || providerMethodParameterTypes[providerContextIndex == null || providerContextIndex == 1 ? 0 : 1].isAssignableFrom(parameterObject.getClass()))) {
        sql = invokeProviderMethod(extractProviderMethodArguments(parameterObject));
      } else if (parameterObject instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) parameterObject;
        sql = invokeProviderMethod(extractProviderMethodArguments(params, providerMethodArgumentNames));
      } else {
        throw new BuilderException("Error invoking SqlProvider method ("
                + providerType.getName() + "." + providerMethod.getName()
                + "). Cannot invoke a method that holds "
                + (bindParameterCount == 1 ? "named argument(@Param)" : "multiple arguments")
                + " using a specifying parameterObject. In this case, please specify a 'java.util.Map' object.");
      }
      Class<?> parameterType = parameterObject == null ? Object.class : parameterObject.getClass();
      return languageDriver.createSqlSource(configuration, sql, parameterType);
    } catch (BuilderException e) {
      throw e;
    } catch (Exception e) {
      throw new BuilderException("Error invoking SqlProvider method ("
          + providerType.getName() + "." + providerMethod.getName()
          + ").  Cause: " + e, e);
    }
  }

  private Object[] extractProviderMethodArguments(Object parameterObject) {
    if (providerContext != null) {
      Object[] args = new Object[2];
      args[providerContextIndex == 0 ? 1 : 0] = parameterObject;
      args[providerContextIndex] = providerContext;
      return args;
    } else {
      return new Object[] { parameterObject };
    }
  }

  private Object[] extractProviderMethodArguments(Map<String, Object> params, String[] argumentNames) {
    Object[] args = new Object[argumentNames.length];
    for (int i = 0; i < args.length; i++) {
      if (providerContextIndex != null && providerContextIndex == i) {
        args[i] = providerContext;
      } else {
        args[i] = params.get(argumentNames[i]);
      }
    }
    return args;
  }

  private String invokeProviderMethod(Object... args) throws Exception {
    Object targetObject = null;
    if (!Modifier.isStatic(providerMethod.getModifiers())) {
      targetObject = providerType.newInstance();
    }
    CharSequence sql = (CharSequence) providerMethod.invoke(targetObject, args);
    return sql != null ? sql.toString() : null;
  }

  /**
   * 获取sql提供类
   */
  private Class<?> getProviderType(Object providerAnnotation, Method mapperMethod)
      throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
    //为什么用反射执行呢, 因为有多个sqlProvider的注解
    //用反射执行注解实例的方法, 获取type
    Class<?> type = (Class<?>) providerAnnotation.getClass().getMethod("type").invoke(providerAnnotation);
    //用反射执行注解实例的方法, 获取type
    Class<?> value = (Class<?>) providerAnnotation.getClass().getMethod("value").invoke(providerAnnotation);
    //如果两个都为void, 即没有填, 两个必须填一个
    if (value == void.class && type == void.class) {
      throw new BuilderException("Please specify either 'value' or 'type' attribute of @"
          + ((Annotation) providerAnnotation).annotationType().getSimpleName()
          + " at the '" + mapperMethod.toString() + "'.");
    }

    //不允许两个都填了不一样的类
    if (value != void.class && type != void.class && value != type) {
      throw new BuilderException("Cannot specify different class on 'value' and 'type' attribute of @"
          + ((Annotation) providerAnnotation).annotationType().getSimpleName()
          + " at the '" + mapperMethod.toString() + "'.");
    }
    //返回其中有效的一个
    return value == void.class ? type : value;
  }

}

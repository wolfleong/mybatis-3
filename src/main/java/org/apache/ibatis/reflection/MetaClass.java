/**
 *    Copyright 2009-2018 the original author or authors.
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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.property.PropertyTokenizer;

/**
 * 类的元数据, 可以根据属性调用链获取getter, setter, 类型, 方法等
 * - 主要是对Reflector的增强, 主要增强三种类型的方法, 如: findProperty, getSetterType, hasSetter, getGetterType, hasGetter方法,
 *   对这几个方法的增强是可以获取调用链下的属性名和类型等
 * - 与Reflector的区别, Reflector只能获取当前类的相关属性, 方法, 类型, 而MetaClass可以获取调用链下的相关属性,方法, 类型
 * - MetaClass是通过类的反射来获取相关属性的, 这就表明, MetaClass是处理不了有集合相关的属性,
 *    如: person.hobbyMap[football].num和 person.list[0].name, 这些属性都有个共同的特点, 需要取出对应的实例, 再从实例中取属性的值
 * @author Clinton Begin
 */
public class MetaClass {
  /**
   * Reflector的工厂类
   */
  private final ReflectorFactory reflectorFactory;
  /**
   * 类的反射通用操作类
   */
  private final Reflector reflector;

  private MetaClass(Class<?> type, ReflectorFactory reflectorFactory) {
    this.reflectorFactory = reflectorFactory;
    //用工厂获取Reflector
    this.reflector = reflectorFactory.findForClass(type);
  }

  public static MetaClass forClass(Class<?> type, ReflectorFactory reflectorFactory) {
    return new MetaClass(type, reflectorFactory);
  }

  /**
   * 获取属性的MetaClass类
   */
  public MetaClass metaClassForProperty(String name) {
    Class<?> propType = reflector.getGetterType(name);
    return MetaClass.forClass(propType, reflectorFactory);
  }

  /**
   * 获取属性的调用链, persON.name => person.name
   */
  public String findProperty(String name) {
    StringBuilder prop = buildProperty(name, new StringBuilder());
    //如果找到, 直接返回, 没找到返回null
    return prop.length() > 0 ? prop.toString() : null;
  }

  /**
   * 根据表达式获得属性调用链
   * @param name 表达式
   * @param useCamelCaseMapping 是否下划线转驼峰
   * @return 属性名
   */
  public String findProperty(String name, boolean useCamelCaseMapping) {
    if (useCamelCaseMapping) {
      //最终只变成小写, 所以只是替换掉'_'就可以了
      name = name.replace("_", "");
    }
    return findProperty(name);
  }

  public String[] getGetterNames() {
    return reflector.getGetablePropertyNames();
  }

  public String[] getSetterNames() {
    return reflector.getSetablePropertyNames();
  }

  /**
   * 获取指定调用链属性的参数类型, 没办法处理: person.hobbyMap[football].name, person.list[0].name
   */
  public Class<?> getSetterType(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      MetaClass metaProp = metaClassForProperty(prop.getName());
      return metaProp.getSetterType(prop.getChildren());
    } else {
      return reflector.getSetterType(prop.getName());
    }
  }

  /**
   * 获取指定调用链的返回类型, 没办法处理: person.hobbyMap[football].name
   * 可以处理person.list[0].name的类型
   */
  public Class<?> getGetterType(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      MetaClass metaProp = metaClassForProperty(prop);
      return metaProp.getGetterType(prop.getChildren());
    }
    // issue #506. Resolve the type inside a Collection Object
    return getGetterType(prop);
  }

  /**
   * 获取调用链指定属性的metaClass
   */
  private MetaClass metaClassForProperty(PropertyTokenizer prop) {
    Class<?> propType = getGetterType(prop);
    return MetaClass.forClass(propType, reflectorFactory);
  }

  /**
   * 获取Getter的返回类型, 如果返回的类型是集合类型且集合只有一个泛型参数时, 获取泛型参数的类型或他的原始类型返回
   */
  private Class<?> getGetterType(PropertyTokenizer prop) {
    //只里拿到的只是返回值的原始类Class, 不带泛型参数, 所以下面才有 getGenericGetterType
    Class<?> type = reflector.getGetterType(prop.getName());
    //如果返回类型是集合, 则返回集合的泛型的真正类型做为返回
    if (prop.getIndex() != null && Collection.class.isAssignableFrom(type)) {
      //获取集合的带泛型的反回值
      Type returnType = getGenericGetterType(prop.getName());
      //如果返回值是参数化的泛型类型
      if (returnType instanceof ParameterizedType) {
        //获取泛型参数列表
        Type[] actualTypeArguments = ((ParameterizedType) returnType).getActualTypeArguments();
        if (actualTypeArguments != null && actualTypeArguments.length == 1) {
          //泛型参数只有一个时, 获取泛型参数
          returnType = actualTypeArguments[0];
          if (returnType instanceof Class) {
            //如果是原始类, 直接返回
            type = (Class<?>) returnType;
          } else if (returnType instanceof ParameterizedType) {
            //如果还是泛型参数, 那取泛型参数的原始类型
            type = (Class<?>) ((ParameterizedType) returnType).getRawType();
          }
        }
      }
    }
    return type;
  }

  /**
   * 如果是集合类型, 则获取集合的泛型
   */
  private Type getGenericGetterType(String propertyName) {
    try {
      //根据属性查询出执行的invoker
      Invoker invoker = reflector.getGetInvoker(propertyName);
      //如果方法
      if (invoker instanceof MethodInvoker) {
        //获取Invoker的method字段
        Field _method = MethodInvoker.class.getDeclaredField("method");
        _method.setAccessible(true);
        //获取方法的反射
        Method method = (Method) _method.get(invoker);
        //解析方法的返回值类型
        return TypeParameterResolver.resolveReturnType(method, reflector.getType());
      } else if (invoker instanceof GetFieldInvoker) {
        //如果是字段, 没有方法
        Field _field = GetFieldInvoker.class.getDeclaredField("field");
        _field.setAccessible(true);
        //获取字段的反射
        Field field = (Field) _field.get(invoker);
        //解析字段的类型
        return TypeParameterResolver.resolveFieldType(field, reflector.getType());
      }
    } catch (NoSuchFieldException | IllegalAccessException ignored) {
    }
    return null;
  }

  /**
   * 迭代属性调用链, 查询所有setter是否都存在, 如: person.properties.name
   */
  public boolean hasSetter(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      if (reflector.hasSetter(prop.getName())) {
        MetaClass metaProp = metaClassForProperty(prop.getName());
        return metaProp.hasSetter(prop.getChildren());
      } else {
        return false;
      }
    } else {
      return reflector.hasSetter(prop.getName());
    }
  }

  /**
   * 迭代属性调用链, 查询所有getter是否都存在, 如: person.properties.name
   */
  public boolean hasGetter(String name) {
    PropertyTokenizer prop = new PropertyTokenizer(name);
    if (prop.hasNext()) {
      if (reflector.hasGetter(prop.getName())) {
        MetaClass metaProp = metaClassForProperty(prop);
        return metaProp.hasGetter(prop.getChildren());
      } else {
        return false;
      }
    } else {
      return reflector.hasGetter(prop.getName());
    }
  }

  public Invoker getGetInvoker(String name) {
    return reflector.getGetInvoker(name);
  }

  public Invoker getSetInvoker(String name) {
    return reflector.getSetInvoker(name);
  }

  /**
   * 构建完整的属性调用链, 如: person.namE => person.name
   */
  private StringBuilder buildProperty(String name, StringBuilder builder) {
    //创建PropertyTokenizer对象, 对name进行分词
    PropertyTokenizer prop = new PropertyTokenizer(name);
    //如果有子表达式, 如: person.base.name
    if (prop.hasNext()) {
      String propertyName = reflector.findPropertyName(prop.getName());
      if (propertyName != null) {
        builder.append(propertyName);
        builder.append(".");
        MetaClass metaProp = metaClassForProperty(propertyName);
        metaProp.buildProperty(prop.getChildren(), builder);
      }
    } else {
      //如果没有子表达式, 如: person, 直接查找有没有这个属性, 加入到builder
      String propertyName = reflector.findPropertyName(name);
      if (propertyName != null) {
        builder.append(propertyName);
      }
    }
    return builder;
  }

  public boolean hasDefaultConstructor() {
    return reflector.hasDefaultConstructor();
  }

}

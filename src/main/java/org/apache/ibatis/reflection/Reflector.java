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

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.ReflectPermission;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.ibatis.reflection.invoker.GetFieldInvoker;
import org.apache.ibatis.reflection.invoker.Invoker;
import org.apache.ibatis.reflection.invoker.MethodInvoker;
import org.apache.ibatis.reflection.invoker.SetFieldInvoker;
import org.apache.ibatis.reflection.property.PropertyNamer;

/**
 * 一个Reflector对应一个类, Reflector会缓存反射操作的信息
 * This class represents a cached set of class definition information that
 * allows for easy mapping between property names and getter/setter methods.
 *
 * @author Clinton Begin
 */
public class Reflector {

  /**
   * 指定的类
   */
  private final Class<?> type;
  /**
   * 可读的属性名
   */
  private final String[] readablePropertyNames;
  /**
   * 可写的属性名
   */
  private final String[] writablePropertyNames;
  /**
   * 属性对应的 setting 方法的映射
   */
  private final Map<String, Invoker> setMethods = new HashMap<>();
  /**
   * 属性对应的 getting 方法的映射
   */
  private final Map<String, Invoker> getMethods = new HashMap<>();
  /**
   * set方法的参数类型的映射
   */
  private final Map<String, Class<?>> setTypes = new HashMap<>();
  /**
   * get方法的参数类型的的映射
   */
  private final Map<String, Class<?>> getTypes = new HashMap<>();
  /**
   * 对象的默认构造函数
   */
  private Constructor<?> defaultConstructor;
  /**
   * 不分大小写的属性集合, key为不分大小写的属性名, value为真正的属性名
   */
  private Map<String, String> caseInsensitivePropertyMap = new HashMap<>();

  public Reflector(Class<?> clazz) {
    type = clazz;
    addDefaultConstructor(clazz);
    addGetMethods(clazz);
    addSetMethods(clazz);
    addFields(clazz);
    readablePropertyNames = getMethods.keySet().toArray(new String[0]);
    writablePropertyNames = setMethods.keySet().toArray(new String[0]);
    for (String propName : readablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
    for (String propName : writablePropertyNames) {
      caseInsensitivePropertyMap.put(propName.toUpperCase(Locale.ENGLISH), propName);
    }
  }

  /**
   * 获取参数为0的默认构造函数
   */
  private void addDefaultConstructor(Class<?> clazz) {
    Constructor<?>[] constructors = clazz.getDeclaredConstructors();
    Arrays.stream(constructors).filter(constructor -> constructor.getParameterTypes().length == 0)
      .findAny().ifPresent(constructor -> this.defaultConstructor = constructor);
  }

  /**
   * 添加get方法
   */
  private void addGetMethods(Class<?> clazz) {
    //记录冲突的方法
    Map<String, List<Method>> conflictingGetters = new HashMap<>();
    //获取类的所有方法
    Method[] methods = getClassMethods(clazz);
    //暂时过滤出Getter的方法, 并且加到conflictingGetters映射里面去
    Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 0 && PropertyNamer.isGetter(m.getName()))
      .forEach(m -> addMethodConflict(conflictingGetters, PropertyNamer.methodToProperty(m.getName()), m));
    //如果一个属性有多个方法, 则要解决冲突
    resolveGetterConflicts(conflictingGetters);
  }

  /**
   * 解决Getter方法冲突, 同一个方法名, 选择最适合的方法
   */
  private void resolveGetterConflicts(Map<String, List<Method>> conflictingGetters) {
    //迭代所有Getter方法
    for (Entry<String, List<Method>> entry : conflictingGetters.entrySet()) {
      Method winner = null;
      String propName = entry.getKey();
      for (Method candidate : entry.getValue()) {
        //如果 winner 为null , 则选第一个方法为候选方法
        if (winner == null) {
          winner = candidate;
          //处理下一个
          continue;
        }
        //暂时当选的方法返回值
        Class<?> winnerType = winner.getReturnType();
        //候选方法的返回值
        Class<?> candidateType = candidate.getReturnType();
        if (candidateType.equals(winnerType)) {
          //返回值相同, 理论应该不存在的, 除非是isXxx()和getXxx()方法, 这两个方法有相同的属性名, 但方法返回值不一样
          if (!boolean.class.equals(candidateType)) {
            throw new ReflectionException(
                "Illegal overloaded getter method with ambiguous type for property "
                    + propName + " in class " + winner.getDeclaringClass()
                    + ". This breaks the JavaBeans specification and can cause unpredictable results.");
          } else if (candidate.getName().startsWith("is")) {
            //取is开头那个方法
            winner = candidate;
          }
        } else if (candidateType.isAssignableFrom(winnerType)) {
          //如果winnerType是candidateType的后代
          // OK getter type is descendant
        } else if (winnerType.isAssignableFrom(candidateType)) {
          //返回后代
          winner = candidate;
        } else {
          throw new ReflectionException(
              "Illegal overloaded getter method with ambiguous type for property "
                  + propName + " in class " + winner.getDeclaringClass()
                  + ". This breaks the JavaBeans specification a  nd can cause unpredictable results.");
        }
      }
      addGetMethod(propName, winner);
    }
  }

  private void addGetMethod(String name, Method method) {
    if (isValidPropertyName(name)) {
      //添加getter方法
      getMethods.put(name, new MethodInvoker(method));
      Type returnType = TypeParameterResolver.resolveReturnType(method, type);
      getTypes.put(name, typeToClass(returnType));
    }
  }

  private void addSetMethods(Class<?> clazz) {
    Map<String, List<Method>> conflictingSetters = new HashMap<>();
    //获取所有方法
    Method[] methods = getClassMethods(clazz);
    //过滤出setter方法, 并添加到conflictingSetters
    Arrays.stream(methods).filter(m -> m.getParameterTypes().length == 1 && PropertyNamer.isSetter(m.getName()))
      .forEach(m -> addMethodConflict(conflictingSetters, PropertyNamer.methodToProperty(m.getName()), m));
    //处理setter方法冲突
    resolveSetterConflicts(conflictingSetters);
  }

  private void addMethodConflict(Map<String, List<Method>> conflictingMethods, String name, Method method) {
    List<Method> list = conflictingMethods.computeIfAbsent(name, k -> new ArrayList<>());
    list.add(method);
  }

  private void resolveSetterConflicts(Map<String, List<Method>> conflictingSetters) {
    for (String propName : conflictingSetters.keySet()) {
      List<Method> setters = conflictingSetters.get(propName);
      Class<?> getterType = getTypes.get(propName);
      Method match = null;
      //缓存异常
      ReflectionException exception = null;
      for (Method setter : setters) {
        //如果setter方法的参数类型跟getter方法返回的类型一样, 则是最佳的setter
        if (setter.getParameterTypes()[0].equals(getterType)) {
          // should be the best match
          match = setter;
          break;
        }
        if (exception == null) {
          try {
            //挑选最合适的setter, 选择参数的后代
            match = pickBetterSetter(match, setter, propName);
          } catch (ReflectionException e) {
            // there could still be the 'best match'
            match = null;
            exception = e;
          }
        }
      }
      //如果最终没找到, 则将已经有异常抛出
      if (match == null) {
        throw exception;
      } else {
        addSetMethod(propName, match);
      }
    }
  }

  private Method pickBetterSetter(Method setter1, Method setter2, String property) {
    if (setter1 == null) {
      return setter2;
    }
    Class<?> paramType1 = setter1.getParameterTypes()[0];
    Class<?> paramType2 = setter2.getParameterTypes()[0];
    //返回方法参数是后代的setter
    if (paramType1.isAssignableFrom(paramType2)) {
      return setter2;
    } else if (paramType2.isAssignableFrom(paramType1)) {
      return setter1;
    }
    //如果两个setter方法的参数不是继承关系, 抛异常
    throw new ReflectionException("Ambiguous setters defined for property '" + property + "' in class '"
        + setter2.getDeclaringClass() + "' with types '" + paramType1.getName() + "' and '"
        + paramType2.getName() + "'.");
  }

  private void addSetMethod(String name, Method method) {
    if (isValidPropertyName(name)) {
      setMethods.put(name, new MethodInvoker(method));
      Type[] paramTypes = TypeParameterResolver.resolveParamTypes(method, type);
      setTypes.put(name, typeToClass(paramTypes[0]));
    }
  }

  /**
   * 将可能带泛型参数的Type类型(Class, ParameterizedType, TypeVariable, GenericArrayType, WildcardType)转换成原始类型(Class)
   */
  private Class<?> typeToClass(Type src) {
    Class<?> result = null;
    //如果Type是class
    if (src instanceof Class) {
      //强转成Class
      result = (Class<?>) src;
      //如果是 ParameterizedType 泛型参数化的类型,
    } else if (src instanceof ParameterizedType) {
      //获取泛型参数化类型的原始类型
      result = (Class<?>) ((ParameterizedType) src).getRawType();
    } else if (src instanceof GenericArrayType) {
      //如果是泛型数组, 则获取数组的组件类型 如:
      // 如: List<String>[] 获取getGenericComponentType()结果为 List<String>
      // 如: T[] 获取getGenericComponentType()结果为 T
      Type componentType = ((GenericArrayType) src).getGenericComponentType();
      //todo 不明白 , src是GenericArrayType类型, 但 componentType 又是Class的情况想不出来
      if (componentType instanceof Class) {
        //创建这个类型的数组的class
        result = Array.newInstance((Class<?>) componentType, 0).getClass();
      } else {
        //如果 componentType 还是Type类型(ParameterizedType, TypeVariable, GenericArrayType, WildcardType), 则继续解析
        //如果 componentType 是List<String>, 最终得到的componentClass是List
        Class<?> componentClass = typeToClass(componentType);
        //创建这个类型的数组的class
        result = Array.newInstance(componentClass, 0).getClass();
      }
    }
    //如果Type的src是 TypeVariable 或者 WildcardType, 无法处理, 返回Object类型
    //一般不会, 因为在调用这个方法之前, 会将取得 TypeVariable 或 WildcardType 的边界值
    if (result == null) {
      result = Object.class;
    }
    return result;
  }

  private void addFields(Class<?> clazz) {
    //获取所有字段
    Field[] fields = clazz.getDeclaredFields();
    for (Field field : fields) {
      //如果setter映射没有包括这个field
      if (!setMethods.containsKey(field.getName())) {
        // issue #379 - removed the check for final because JDK 1.5 allows
        // modification of final fields through reflection (JSR-133). (JGB)
        // pr #16 - final static can only be set by the classloader
        int modifiers = field.getModifiers();
        if (!(Modifier.isFinal(modifiers) && Modifier.isStatic(modifiers))) {
          //添加setMethods中
          addSetField(field);
        }
      }
      //如果getter映射没有包括这个field
      if (!getMethods.containsKey(field.getName())) {
        //加入setting Field
        addGetField(field);
      }
    }
    if (clazz.getSuperclass() != null) {
      addFields(clazz.getSuperclass());
    }
  }

  private void addSetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      setMethods.put(field.getName(), new SetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      setTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  private void addGetField(Field field) {
    if (isValidPropertyName(field.getName())) {
      getMethods.put(field.getName(), new GetFieldInvoker(field));
      Type fieldType = TypeParameterResolver.resolveFieldType(field, type);
      getTypes.put(field.getName(), typeToClass(fieldType));
    }
  }

  /**
   * 合法的属性名
   */
  private boolean isValidPropertyName(String name) {
    return !(name.startsWith("$") || "serialVersionUID".equals(name) || "class".equals(name));
  }

  /**
   * 返回Class的所有方法
   * This method returns an array containing all methods
   * declared in this class and any superclass.
   * We use this method, instead of the simpler <code>Class.getMethods()</code>,
   * because we want to look for private methods as well.
   *
   * @param clazz The class
   * @return An array containing all methods in this class
   */
  private Method[] getClassMethods(Class<?> clazz) {
    Map<String, Method> uniqueMethods = new HashMap<>();
    Class<?> currentClass = clazz;
    while (currentClass != null && currentClass != Object.class) {
      //添加当前类声明的所有方法
      addUniqueMethods(uniqueMethods, currentClass.getDeclaredMethods());

      // we also need to look for interface methods -
      // because the class may be abstract
      //添加接口声明的方法
      Class<?>[] interfaces = currentClass.getInterfaces();
      for (Class<?> anInterface : interfaces) {
        addUniqueMethods(uniqueMethods, anInterface.getMethods());
      }
      //继续迭代父类
      currentClass = currentClass.getSuperclass();
    }

    Collection<Method> methods = uniqueMethods.values();
    return methods.toArray(new Method[0]);
  }

  /**
   * 添加唯一方法
   */
  private void addUniqueMethods(Map<String, Method> uniqueMethods, Method[] methods) {
    for (Method currentMethod : methods) {
      if (!currentMethod.isBridge()) {
        //生成方法的唯一签名
        String signature = getSignature(currentMethod);
        // check to see if the method is already known
        // if it is known, then an extended class must have
        // overridden a method
        //保证重写的方法不会重复添加
        if (!uniqueMethods.containsKey(signature)) {
          uniqueMethods.put(signature, currentMethod);
        }
      }
    }
  }

  /**
   * 生成方法的唯一签名
   * 如: public String getName(); ==> java.lang.String#getName
   * 如: public void setName(String name,Integer i); ==> setName:java.lang.String,java.lang.Integer
   */
  private String getSignature(Method method) {
    StringBuilder sb = new StringBuilder();
    Class<?> returnType = method.getReturnType();
    if (returnType != null) {
      sb.append(returnType.getName()).append('#');
    }
    sb.append(method.getName());
    Class<?>[] parameters = method.getParameterTypes();
    for (int i = 0; i < parameters.length; i++) {
      sb.append(i == 0 ? ':' : ',').append(parameters[i].getName());
    }
    return sb.toString();
  }

  /**
   * Checks whether can control member accessible.
   *
   * @return If can control member accessible, it return {@literal true}
   * @since 3.5.0
   */
  public static boolean canControlMemberAccessible() {
    try {
      SecurityManager securityManager = System.getSecurityManager();
      if (null != securityManager) {
        securityManager.checkPermission(new ReflectPermission("suppressAccessChecks"));
      }
    } catch (SecurityException e) {
      return false;
    }
    return true;
  }

  /**
   * Gets the name of the class the instance provides information for.
   *
   * @return The class name
   */
  public Class<?> getType() {
    return type;
  }

  public Constructor<?> getDefaultConstructor() {
    if (defaultConstructor != null) {
      return defaultConstructor;
    } else {
      throw new ReflectionException("There is no default constructor for " + type);
    }
  }

  public boolean hasDefaultConstructor() {
    return defaultConstructor != null;
  }

  public Invoker getSetInvoker(String propertyName) {
    Invoker method = setMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  public Invoker getGetInvoker(String propertyName) {
    Invoker method = getMethods.get(propertyName);
    if (method == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return method;
  }

  /**
   * Gets the type for a property setter.
   *
   * @param propertyName - the name of the property
   * @return The Class of the property setter
   */
  public Class<?> getSetterType(String propertyName) {
    Class<?> clazz = setTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no setter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets the type for a property getter.
   *
   * @param propertyName - the name of the property
   * @return The Class of the property getter
   */
  public Class<?> getGetterType(String propertyName) {
    Class<?> clazz = getTypes.get(propertyName);
    if (clazz == null) {
      throw new ReflectionException("There is no getter for property named '" + propertyName + "' in '" + type + "'");
    }
    return clazz;
  }

  /**
   * Gets an array of the readable properties for an object.
   *
   * @return The array
   */
  public String[] getGetablePropertyNames() {
    return readablePropertyNames;
  }

  /**
   * Gets an array of the writable properties for an object.
   *
   * @return The array
   */
  public String[] getSetablePropertyNames() {
    return writablePropertyNames;
  }

  /**
   * Check to see if a class has a writable property by name.
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a writable property by the name
   */
  public boolean hasSetter(String propertyName) {
    return setMethods.keySet().contains(propertyName);
  }

  /**
   * Check to see if a class has a readable property by name.
   *
   * @param propertyName - the name of the property to check
   * @return True if the object has a readable property by the name
   */
  public boolean hasGetter(String propertyName) {
    return getMethods.keySet().contains(propertyName);
  }

  public String findPropertyName(String name) {
    return caseInsensitivePropertyMap.get(name.toUpperCase(Locale.ENGLISH));
  }
}

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
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;

/**
 * @author Iwao AVE!
 */
public class TypeParameterResolver {

  /**
   * @return The field type as {@link Type}. If it has type parameters in the declaration,<br>
   *         they will be resolved to the actual runtime {@link Type}s.
   */
  public static Type resolveFieldType(Field field, Type srcType) {
    Type fieldType = field.getGenericType();
    Class<?> declaringClass = field.getDeclaringClass();
    return resolveType(fieldType, srcType, declaringClass);
  }

  /**
   * @return The return type of the method as {@link Type}. If it has type parameters in the declaration,<br>
   *         they will be resolved to the actual runtime {@link Type}s.
   */
  public static Type resolveReturnType(Method method, Type srcType) {
    Type returnType = method.getGenericReturnType();
    Class<?> declaringClass = method.getDeclaringClass();
    return resolveType(returnType, srcType, declaringClass);
  }

  /**
   * 返回set方法带泛型的参数类型
   * @param srcType 获取这个方法的类
   * @param method 方法
   * @return The parameter types of the method as an array of {@link Type}s. If they have type parameters in the declaration,<br>
   *         they will be resolved to the actual runtime {@link Type}s.
   */
  public static Type[] resolveParamTypes(Method method, Type srcType) {
    //获取可能带泛型参数类型, 参数是可能有多个的
    Type[] paramTypes = method.getGenericParameterTypes();
    Class<?> declaringClass = method.getDeclaringClass();
    Type[] result = new Type[paramTypes.length];
    for (int i = 0; i < paramTypes.length; i++) {
      //一个一个参数的解析类型
      result[i] = resolveType(paramTypes[i], srcType, declaringClass);
    }
    return result;
  }

  /**
   * 解析类型
   * @param type 待解析的类型
   * @param srcType 当前解析的类
   * @param declaringClass 这个类型声明的class
   * @return
   */
  private static Type resolveType(Type type, Type srcType, Class<?> declaringClass) {
    //如果是 T 这种泛型
    if (type instanceof TypeVariable) {
      return resolveTypeVar((TypeVariable<?>) type, srcType, declaringClass);
      //如果是List<T> 或 List<String>这个泛型参数类型
    } else if (type instanceof ParameterizedType) {
      return resolveParameterizedType((ParameterizedType) type, srcType, declaringClass);
      //如果是List<T>[], T[]这种泛型参数类型
    } else if (type instanceof GenericArrayType) {
      return resolveGenericArrayType((GenericArrayType) type, srcType, declaringClass);
    } else {
      // WildcardType 和 Class直接返回
      return type;
    }
  }

  private static Type resolveGenericArrayType(GenericArrayType genericArrayType, Type srcType, Class<?> declaringClass) {
    Type componentType = genericArrayType.getGenericComponentType();
    Type resolvedComponentType = null;
    if (componentType instanceof TypeVariable) {
      //T[]
      resolvedComponentType = resolveTypeVar((TypeVariable<?>) componentType, srcType, declaringClass);
    } else if (componentType instanceof GenericArrayType) {
      //todo 这种情况我也想不出来, 泛型数组的泛型数组是什么鬼
      resolvedComponentType = resolveGenericArrayType((GenericArrayType) componentType, srcType, declaringClass);
    } else if (componentType instanceof ParameterizedType) {
      //List<T>[] 或 List<String>[]
      resolvedComponentType = resolveParameterizedType((ParameterizedType) componentType, srcType, declaringClass);
    }
    //如果是普通的Class类型, 则返回
    if (resolvedComponentType instanceof Class) {
      return Array.newInstance((Class<?>) resolvedComponentType, 0).getClass();
    } else {
      //如果是带泛型参数的话, 直接创建个泛型数组
      return new GenericArrayTypeImpl(resolvedComponentType);
    }
  }

  private static ParameterizedType resolveParameterizedType(ParameterizedType parameterizedType, Type srcType, Class<?> declaringClass) {
    //获取parameterizedType的原类型
    Class<?> rawType = (Class<?>) parameterizedType.getRawType();
    //获取泛型参数列表
    Type[] typeArgs = parameterizedType.getActualTypeArguments();
    Type[] args = new Type[typeArgs.length];
    for (int i = 0; i < typeArgs.length; i++) {
      //如果是TypeVariable
      if (typeArgs[i] instanceof TypeVariable) {
        args[i] = resolveTypeVar((TypeVariable<?>) typeArgs[i], srcType, declaringClass);
        //如果是参数泛型
      } else if (typeArgs[i] instanceof ParameterizedType) {
        args[i] = resolveParameterizedType((ParameterizedType) typeArgs[i], srcType, declaringClass);
        //如果是通配符类型
      } else if (typeArgs[i] instanceof WildcardType) {
        args[i] = resolveWildcardType((WildcardType) typeArgs[i], srcType, declaringClass);
      } else {
        //class类型
        args[i] = typeArgs[i];
      }
    }
    return new ParameterizedTypeImpl(rawType, null, args);
  }

  /**
   *  主要用于确定A的实际类型
   *  List<? extend A & C>
   */
  private static Type resolveWildcardType(WildcardType wildcardType, Type srcType, Class<?> declaringClass) {
    //获取通配符下边界
    Type[] lowerBounds = resolveWildcardTypeBounds(wildcardType.getLowerBounds(), srcType, declaringClass);
    //获取通配符上边界
    Type[] upperBounds = resolveWildcardTypeBounds(wildcardType.getUpperBounds(), srcType, declaringClass);
    return new WildcardTypeImpl(lowerBounds, upperBounds);
  }

  private static Type[] resolveWildcardTypeBounds(Type[] bounds, Type srcType, Class<?> declaringClass) {
    Type[] result = new Type[bounds.length];
    for (int i = 0; i < bounds.length; i++) {
      if (bounds[i] instanceof TypeVariable) {
        //List<? extends E>
        result[i] = resolveTypeVar((TypeVariable<?>) bounds[i], srcType, declaringClass);
      } else if (bounds[i] instanceof ParameterizedType) {
        //List<? extends List<String>>
        result[i] = resolveParameterizedType((ParameterizedType) bounds[i], srcType, declaringClass);
      } else if (bounds[i] instanceof WildcardType) {
        //todo List<? extends ?> , 不懂, 想不出来什么例子
        result[i] = resolveWildcardType((WildcardType) bounds[i], srcType, declaringClass);
      } else {
        result[i] = bounds[i];
      }
    }
    return result;
  }

  /**
   *
   *    typeVar是T
   *    大概主要是为了解决以下三种泛型的继承
   *
   *    第一种, typeVar是T, srcType是B, declaringClass是A, class是B, supperclass是A<String>
   *     class A<T>
   *     class B extend A<String>
   *    第二种
   *     class A<T>
   *     class B<E> extend A<E>
   *     class C extend B<String>
   *    第三种
   *     class A<T>
   *     class B extend A<String>
   *     class C extend B
   *    第四种
   *     class A<T>
   */
  private static Type resolveTypeVar(TypeVariable<?> typeVar, Type srcType, Class<?> declaringClass) {
    Type result;
    Class<?> clazz;
    //srcType必须是Class或ParameterizedType
    if (srcType instanceof Class) {
      clazz = (Class<?>) srcType;
    } else if (srcType instanceof ParameterizedType) {
      //class B<E> extend A<E>
      //class C extend B<String>
      //当类有多层继承时, 当进入第二层, srcType为B<String> , 而declaringClass为 A<E>, 就进入这个条件
      ParameterizedType parameterizedType = (ParameterizedType) srcType;
      clazz = (Class<?>) parameterizedType.getRawType();
    } else {
      throw new IllegalArgumentException("The 2nd arg must be Class or ParameterizedType, but was: " + srcType.getClass());
    }

    //如果srcType的类和这个方法声明的类一样, 如:
    //class A <T extend GG> { public T getT(){return null;} }
    //srcType=A 且 declaringClass=A
    if (clazz == declaringClass) {
      //第四种情况进入这里
      Type[] bounds = typeVar.getBounds();
      //如果有边界值, 如 class A <T extends DD & CC> , 取边界值的第一个DD
      if (bounds.length > 0) {
        return bounds[0];
      }
      //没有边界值, 直接Object
      return Object.class;
    }

    //srcType是子类, 类型的申明不在srcType, A类中获取到的getT()方法的返回泛型参数是T, 要确定T是什么类型只能找父类
    // class B <T>{ public T getT(){return null;}}
    //class A extend B<String> {}

    //获取带泛型的父类
    Type superclass = clazz.getGenericSuperclass();
    result = scanSuperTypes(typeVar, srcType, declaringClass, clazz, superclass);
    //如果能找到就直接返回
    if (result != null) {
      return result;
    }

    //获取泛型接口, 迭代一个一个接口去找
    Type[] superInterfaces = clazz.getGenericInterfaces();
    for (Type superInterface : superInterfaces) {
      result = scanSuperTypes(typeVar, srcType, declaringClass, clazz, superInterface);
      if (result != null) {
        return result;
      }
    }
    //找不到返回Object
    return Object.class;
  }

  /**
   * typeVar是T
   * 大概主要是为了解决以下三种泛型的继承
   *
   * 第一种, typeVar是T, srcType是B, declaringClass是A, class是B, supperclass是A<String>
   *  class A<T>
   *  class B extend A<String>
   * 第二种
   *  class A<T>
   *  class B<E> extend A<E>
   *  class C extend B<String>
   * 第三种
   *  class A<T>
   *  class B extend A<String>
   *  class C extend B
   *
   * @param typeVar T
   * @param srcType 相当于 B
   * @param declaringClass 相当于A类
   * @param clazz B类
   * @param superclass 相当于 A<String>
   * @return T 的最终类型
   */
  private static Type scanSuperTypes(TypeVariable<?> typeVar, Type srcType, Class<?> declaringClass, Class<?> clazz, Type superclass) {
    //如果是参数型泛型类型
    if (superclass instanceof ParameterizedType) {
      //强转
      ParameterizedType parentAsType = (ParameterizedType) superclass;
      //获取带泛型父类的原类型
      Class<?> parentAsClass = (Class<?>) parentAsType.getRawType();
      //获取父类声明的泛型变量
      TypeVariable<?>[] parentTypeVars = parentAsClass.getTypeParameters();
      if (srcType instanceof ParameterizedType) {
        //srcType 相当于 B<String> , supperclass相当于A<E> , clazz相当于B
        //三层的继承才会进入这里
        //合并泛型参数类型, 形成A<String>
        parentAsType = translateParentTypeVars((ParameterizedType) srcType, clazz, parentAsType);
      }
      //如果父类是这个typeVar的声明类
      if (declaringClass == parentAsClass) {
        for (int i = 0; i < parentTypeVars.length; i++) {
          //找到泛型变量对应的具体类型
          if (typeVar == parentTypeVars[i]) {
            //找到则直接返回
            return parentAsType.getActualTypeArguments()[i];
          }
        }
      }
      //如果声明的Class是当前parentAsClass的上一层类型,
      if (declaringClass.isAssignableFrom(parentAsClass)) {
        //class A<T>
        //class B<E> extend A<E>
        //class C extend B<String>
        return resolveTypeVar(typeVar, parentAsType, declaringClass);
      }
    } else if (superclass instanceof Class && declaringClass.isAssignableFrom((Class<?>) superclass)) {
      //class A<T>
      //class B extend A<String>
      //class C extend B
      //typeVar是T, superclass是B, declaringClass是A, 这种情况会进这个分支
      return resolveTypeVar(typeVar, superclass, declaringClass);
    }
    return null;
  }

  /**
   *  class A<T>
   *  class B<E> extend A<E>
   *  class C extend B<String>
   *
   *  合并srcType的泛型参数到parentType中
   *  这个主要的作用是, 合并srcType和parentType的泛型参数, 并且返回parentType作为原类型带合并后泛型参数的ParameterizedType类型
   *
   * @param srcType 相当于 B<String>
   * @param srcClass B
   * @param parentType A<E>
   */
  private static ParameterizedType translateParentTypeVars(ParameterizedType srcType, Class<?> srcClass, ParameterizedType parentType) {
    //A<E>中的E
    Type[] parentTypeArgs = parentType.getActualTypeArguments();
    //B<String> 的String
    Type[] srcTypeArgs = srcType.getActualTypeArguments();
    //B<E>中的E
    TypeVariable<?>[] srcTypeVars = srcClass.getTypeParameters();
    //用于缓存合并后的Type数组
    Type[] newParentArgs = new Type[parentTypeArgs.length];
    boolean noChange = true;
    for (int i = 0; i < parentTypeArgs.length; i++) {
      //A<E>中的E是TypeVariable
      if (parentTypeArgs[i] instanceof TypeVariable) {
        for (int j = 0; j < srcTypeVars.length; j++) {
          //B<E>和A<E>, 如果两个都是泛型变量的话, 就表明有转化
          if (srcTypeVars[j] == parentTypeArgs[i]) {
            noChange = false;
            newParentArgs[i] = srcTypeArgs[j];
          }
        }
      } else {
        //如果不是参数变量的话, 没有转化,
        //parentType肯定是A<String>
        newParentArgs[i] = parentTypeArgs[i];
      }
    }
    //返回合并后泛型参数类型
    //A<String>
    return noChange ? parentType : new ParameterizedTypeImpl((Class<?>)parentType.getRawType(), null, newParentArgs);
  }

  private TypeParameterResolver() {
    super();
  }

  static class ParameterizedTypeImpl implements ParameterizedType {
    private Class<?> rawType;

    private Type ownerType;

    private Type[] actualTypeArguments;

    public ParameterizedTypeImpl(Class<?> rawType, Type ownerType, Type[] actualTypeArguments) {
      super();
      this.rawType = rawType;
      this.ownerType = ownerType;
      this.actualTypeArguments = actualTypeArguments;
    }

    @Override
    public Type[] getActualTypeArguments() {
      return actualTypeArguments;
    }

    @Override
    public Type getOwnerType() {
      return ownerType;
    }

    @Override
    public Type getRawType() {
      return rawType;
    }

    @Override
    public String toString() {
      return "ParameterizedTypeImpl [rawType=" + rawType + ", ownerType=" + ownerType + ", actualTypeArguments=" + Arrays.toString(actualTypeArguments) + "]";
    }
  }

  static class WildcardTypeImpl implements WildcardType {
    private Type[] lowerBounds;

    private Type[] upperBounds;

    WildcardTypeImpl(Type[] lowerBounds, Type[] upperBounds) {
      super();
      this.lowerBounds = lowerBounds;
      this.upperBounds = upperBounds;
    }

    @Override
    public Type[] getLowerBounds() {
      return lowerBounds;
    }

    @Override
    public Type[] getUpperBounds() {
      return upperBounds;
    }
  }

  static class GenericArrayTypeImpl implements GenericArrayType {
    private Type genericComponentType;

    GenericArrayTypeImpl(Type genericComponentType) {
      super();
      this.genericComponentType = genericComponentType;
    }

    @Override
    public Type getGenericComponentType() {
      return genericComponentType;
    }
  }
}

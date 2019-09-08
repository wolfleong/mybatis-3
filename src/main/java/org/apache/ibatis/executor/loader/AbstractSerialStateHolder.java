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
package org.apache.ibatis.executor.loader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Externalizable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidClassException;
import java.io.ObjectInput;
import java.io.ObjectInputStream;
import java.io.ObjectOutput;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.ObjectStreamException;
import java.io.StreamCorruptedException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.reflection.factory.ObjectFactory;

/**
 * 序列化 Externalizable 接口的抽象实现
 * @author Eduardo Macarron
 * @author Franta Mejta
 */
public abstract class AbstractSerialStateHolder implements Externalizable {

  private static final long serialVersionUID = 8940388717901644661L;
  /**
   * todo wolfleong 不懂这个缓存的作用
   */
  private static final ThreadLocal<ObjectOutputStream> stream = new ThreadLocal<>();
  private byte[] userBeanBytes = new byte[0];
  /**
   * 要序列化的对象
   */
  private Object userBean;
  /**
   * 未加载的属性, 懒加载
   */
  private Map<String, ResultLoaderMap.LoadPair> unloadedProperties;
  /**
   * 对象工厂
   */
  private ObjectFactory objectFactory;
  /**
   * 结果对象的构造参数类型
   */
  private Class<?>[] constructorArgTypes;
  /**
   * 结果对象的构造参数
   */
  private Object[] constructorArgs;

  public AbstractSerialStateHolder() {
  }

  public AbstractSerialStateHolder(
          final Object userBean,
          final Map<String, ResultLoaderMap.LoadPair> unloadedProperties,
          final ObjectFactory objectFactory,
          List<Class<?>> constructorArgTypes,
          List<Object> constructorArgs) {
    this.userBean = userBean;
    //赋值前, 作一个保护性拷贝
    this.unloadedProperties = new HashMap<>(unloadedProperties);
    this.objectFactory = objectFactory;
    //拷贝
    this.constructorArgTypes = constructorArgTypes.toArray(new Class<?>[constructorArgTypes.size()]);
    //拷贝
    this.constructorArgs = constructorArgs.toArray(new Object[constructorArgs.size()]);
  }

  @Override
  public final void writeExternal(final ObjectOutput out) throws IOException {
    boolean firstRound = false;
    //创建字节流数组
    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
    //从本地线程缓存中获取 ObjectOutputStream
    ObjectOutputStream os = stream.get();
    //没找到
    if (os == null) {
      //创建一个 ObjectOutputStream
      os = new ObjectOutputStream(baos);
      firstRound = true;
      //缓存本地线程缓存中
      stream.set(os);
    }

    //序列化结果对象
    os.writeObject(this.userBean);
    //序列化延迟加载映射
    os.writeObject(this.unloadedProperties);
    //序列化对象工厂
    os.writeObject(this.objectFactory);
    //序列化结果对象的参数类型列表
    os.writeObject(this.constructorArgTypes);
    //序列化结果对象的构造参数列表
    os.writeObject(this.constructorArgs);

    //获取序列化后的字符
    final byte[] bytes = baos.toByteArray();
    //写出去
    out.writeObject(bytes);

    if (firstRound) {
      stream.remove();
    }
  }

  @Override
  public final void readExternal(final ObjectInput in) throws IOException, ClassNotFoundException {
    //反序列化
    final Object data = in.readObject();
    //如果是数组, 则肯定是字节数组
    if (data.getClass().isArray()) {
      //强转
      this.userBeanBytes = (byte[]) data;
    } else {
      //对象
      this.userBean = data;
    }
  }

  @SuppressWarnings("unchecked")
  protected final Object readResolve() throws ObjectStreamException {
    //如果 userBean 不为空, 然后字节数组长度为0
    /* Second run */
    if (this.userBean != null && this.userBeanBytes.length == 0) {
      //直接返回对象
      return this.userBean;
    }

    //创建一个字符流包装对象
    /* First run */
    try (ObjectInputStream in = new LookAheadObjectInputStream(new ByteArrayInputStream(this.userBeanBytes))) {
      //反序列化结果对象
      this.userBean = in.readObject();
      //反序列化获取懒加载映射
      this.unloadedProperties = (Map<String, ResultLoaderMap.LoadPair>) in.readObject();
      //反序列化对象工厂
      this.objectFactory = (ObjectFactory) in.readObject();
      //反序列化结果对象构造参数类型
      this.constructorArgTypes = (Class<?>[]) in.readObject();
      //反序列化结果对象的构造参数列表
      this.constructorArgs = (Object[]) in.readObject();
    } catch (final IOException ex) {
      throw (ObjectStreamException) new StreamCorruptedException().initCause(ex);
    } catch (final ClassNotFoundException ex) {
      throw (ObjectStreamException) new InvalidClassException(ex.getLocalizedMessage()).initCause(ex);
    }

    //复制
    final Map<String, ResultLoaderMap.LoadPair> arrayProps = new HashMap<>(this.unloadedProperties);
    //数组变列表
    final List<Class<?>> arrayTypes = Arrays.asList(this.constructorArgTypes);
    //数组变列表
    final List<Object> arrayValues = Arrays.asList(this.constructorArgs);

    //创建反序列化代理对象
    return this.createDeserializationProxy(userBean, arrayProps, objectFactory, arrayTypes, arrayValues);
  }

  protected abstract Object createDeserializationProxy(Object target, Map<String, ResultLoaderMap.LoadPair> unloadedProperties, ObjectFactory objectFactory,
          List<Class<?>> constructorArgTypes, List<Object> constructorArgs);

  /**
   * 反序列化前, 做一层过滤, 特定的类不允许反序列化
   */
  private static class LookAheadObjectInputStream extends ObjectInputStream {
    private static final List<String> blacklist = Arrays.asList(
        "org.apache.commons.beanutils.BeanComparator",
        "org.apache.commons.collections.functors.InvokerTransformer",
        "org.apache.commons.collections.functors.InstantiateTransformer",
        "org.apache.commons.collections4.functors.InvokerTransformer",
        "org.apache.commons.collections4.functors.InstantiateTransformer",
        "org.codehaus.groovy.runtime.ConvertedClosure",
        "org.codehaus.groovy.runtime.MethodClosure",
        "org.springframework.beans.factory.ObjectFactory",
        "org.springframework.transaction.jta.JtaTransactionManager",
        "com.sun.org.apache.xalan.internal.xsltc.trax.TemplatesImpl");

    public LookAheadObjectInputStream(InputStream in) throws IOException {
      super(in);
    }

    @Override
    protected Class<?> resolveClass(ObjectStreamClass desc) throws IOException, ClassNotFoundException {
      //获取类型名
      String className = desc.getName();
      //出于安全考虑, 不允许反序列化上面指定的类
      //如果包括以前的类, 则报异常
      if (blacklist.contains(className)) {
        throw new InvalidClassException(className, "Deserialization is not allowed for security reasons. "
            + "It is strongly recommended to configure the deserialization filter provided by JDK. "
            + "See http://openjdk.java.net/jeps/290 for the details.");
      }
      //调用父类的方法
      return super.resolveClass(desc);
    }
  }
}

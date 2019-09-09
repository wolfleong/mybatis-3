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
package org.apache.ibatis.executor.loader;

import java.io.Serializable;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.BaseExecutor;
import org.apache.ibatis.executor.BatchResult;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * 存每个结果对象中的延迟加载属性名与 ResultLoader 映射
 * @author Clinton Begin
 * @author Franta Mejta
 */
public class ResultLoaderMap {

  /**
   * LoadPair 的缓存映射
   */
  private final Map<String, LoadPair> loaderMap = new HashMap<>();

  public void addLoader(String property, MetaObject metaResultObject, ResultLoader resultLoader) {
    //获取第一层的大写属性
    String upperFirst = getUppercaseFirstProperty(property);
    //如果属性的 LoadPair 已经存在, 则抛出异常
    //todo wolfleong 不明白, 为什么要最左边属性来判断
    if (!upperFirst.equalsIgnoreCase(property) && loaderMap.containsKey(upperFirst)) {
      throw new ExecutorException("Nested lazy loaded result property '" + property
              + "' for query id '" + resultLoader.mappedStatement.getId()
              + " already exists in the result map. The leftmost property of all lazy loaded properties must be unique within a result map.");
    }
    // 创建 LoadPair 对象，添加到 loaderMap 中
    loaderMap.put(upperFirst, new LoadPair(property, metaResultObject, resultLoader));
  }

  public final Map<String, LoadPair> getProperties() {
    return new HashMap<>(this.loaderMap);
  }

  public Set<String> getPropertyNames() {
    return loaderMap.keySet();
  }

  public int size() {
    return loaderMap.size();
  }

  public boolean hasLoader(String property) {
    return loaderMap.containsKey(property.toUpperCase(Locale.ENGLISH));
  }

  /**
   * 指定属性名延迟加载
   */
  public boolean load(String property) throws SQLException {
    //获取 LoadPair
    LoadPair pair = loaderMap.remove(property.toUpperCase(Locale.ENGLISH));
    //如果不为 null
    if (pair != null) {
      //调用 LoadPair.load()
      pair.load();
      //返回加载成功
      return true;
    }
    //返回 false
    return false;
  }

  /**
   * 删除延迟加载的 ResultLoader
   * @param property 属性名
   */
  public void remove(String property) {
    loaderMap.remove(property.toUpperCase(Locale.ENGLISH));
  }

  public void loadAll() throws SQLException {
    //获取 loaderMap 所有属性名
    final Set<String> methodNameSet = loaderMap.keySet();
    //变成字符串数组
    String[] methodNames = methodNameSet.toArray(new String[methodNameSet.size()]);
    //遍历方法名
    for (String methodName : methodNames) {
      //调用加载方法
      load(methodName);
    }
  }

  /**
   * 使用 . 分隔属性，并获得首个字符串，并大写
   */
  private static String getUppercaseFirstProperty(String property) {
    String[] parts = property.split("\\.");
    return parts[0].toUpperCase(Locale.ENGLISH);
  }

  /**
   * Property which was not loaded yet.
   */
  public static class LoadPair implements Serializable {

    private static final long serialVersionUID = 20130412;
    /**
     * Name of factory method which returns database connection.
     */
    private static final String FACTORY_METHOD = "getConfiguration";
    /**
     * Object to check whether we went through serialization..
     */
    private final transient Object serializationCheck = new Object();
    /**
     * 结果对象的MetaObject
     * Meta object which sets loaded properties.
     */
    private transient MetaObject metaResultObject;
    /**
     * 结果加载器
     * Result loader which loads unread properties.
     */
    private transient ResultLoader resultLoader;
    /**
     * 日志对象
     * Wow, logger.
     */
    private transient Log log;
    /**
     * 配置工厂类
     * Factory class through which we get database connection.
     */
    private Class<?> configurationFactory;
    /**
     * 属性名
     * Name of the unread property.
     */
    private String property;
    /**
     * MappedStatement 的 id
     * ID of SQL statement which loads the property.
     */
    private String mappedStatement;
    /**
     * sql 参数对象
     * Parameter of the sql statement.
     */
    private Serializable mappedParameter;

    private LoadPair(final String property, MetaObject metaResultObject, ResultLoader resultLoader) {
      this.property = property;
      this.metaResultObject = metaResultObject;
      this.resultLoader = resultLoader;

      //如果 metaResultObject 不为 null 且 原始对象实现序列化接口
      /* Save required information only if original object can be serialized. */
      if (metaResultObject != null && metaResultObject.getOriginalObject() instanceof Serializable) {
        //获取参数对象
        final Object mappedStatementParameter = resultLoader.parameterObject;

        //如果参数对象实现序列化接口
        /* @todo May the parameter be null? */
        if (mappedStatementParameter instanceof Serializable) {
          //获取 MappedStatement 的 id
          this.mappedStatement = resultLoader.mappedStatement.getId();
          //设置参数
          this.mappedParameter = (Serializable) mappedStatementParameter;

          //获取 configurationFactory 类
          this.configurationFactory = resultLoader.configuration.getConfigurationFactory();
        } else {
          //如果参数没有实现序列化, 则打印 debug 日志
          Log log = this.getLogger();
          if (log.isDebugEnabled()) {
            log.debug("Property [" + this.property + "] of ["
                    + metaResultObject.getOriginalObject().getClass() + "] cannot be loaded "
                    + "after deserialization. Make sure it's loaded before serializing "
                    + "forenamed object.");
          }
        }
      }
    }

    public void load() throws SQLException {
      //校验结果对象的 MetaObject 不为 null
      /* These field should not be null unless the loadpair was serialized.
       * Yet in that case this method should not be called. */
      if (this.metaResultObject == null) {
        throw new IllegalArgumentException("metaResultObject is null");
      }
      //校验 resultLoader 不为 null
      if (this.resultLoader == null) {
        throw new IllegalArgumentException("resultLoader is null");
      }

      //调用重载方法
      this.load(null);
    }

    public void load(final Object userObject) throws SQLException {
      //如果 metaResultObject 和 resultLoader 其中一个为 null
      if (this.metaResultObject == null || this.resultLoader == null) {
        // 如果 sql 参数为空, 则报异常
        if (this.mappedParameter == null) {
          throw new ExecutorException("Property [" + this.property + "] cannot be loaded because "
                  + "required parameter of mapped statement ["
                  + this.mappedStatement + "] is not serializable.");
        }

        //获取 Configuration
        final Configuration config = this.getConfiguration();
        //获取 MappedStatement
        final MappedStatement ms = config.getMappedStatement(this.mappedStatement);
        //如果 MappedStatement 为 null, 报异常
        if (ms == null) {
          throw new ExecutorException("Cannot lazy load property [" + this.property
                  + "] of deserialized object [" + userObject.getClass()
                  + "] because configuration does not contain statement ["
                  + this.mappedStatement + "]");
        }

        //给结果主对象创建 metaObject
        this.metaResultObject = config.newMetaObject(userObject);
        //创建 ResultLoader
        this.resultLoader = new ResultLoader(config, new ClosedExecutor(), ms, this.mappedParameter,
                metaResultObject.getSetterType(this.property), null, null);
      }

      /* We are using a new executor because we may be (and likely are) on a new thread
       * and executors aren't thread safe. (Is this sufficient?)
       *
       * A better approach would be making executors thread safe. */
      //如果 serializationCheck 为空, 则表明是反序列化回来的对象
      if (this.serializationCheck == null) {
        final ResultLoader old = this.resultLoader;
        //创建新的 ResultLoader
        this.resultLoader = new ResultLoader(old.configuration, new ClosedExecutor(), old.mappedStatement,
                old.parameterObject, old.targetType, old.cacheKey, old.boundSql);
      }

      //加载子查询, 并设置到主对象中
      this.metaResultObject.setValue(property, this.resultLoader.loadResult());
    }

    private Configuration getConfiguration() {
      //如果 configurationFactory 为 null, 报异常
      if (this.configurationFactory == null) {
        throw new ExecutorException("Cannot get Configuration as configuration factory was not set.");
      }

      //配置对象
      Object configurationObject;
      try {
        //根据方法名(getConfiguration)获取反射的方法
        final Method factoryMethod = this.configurationFactory.getDeclaredMethod(FACTORY_METHOD);
        //如果不是静态方法, 报错
        if (!Modifier.isStatic(factoryMethod.getModifiers())) {
          throw new ExecutorException("Cannot get Configuration as factory method ["
                  + this.configurationFactory + "]#["
                  + FACTORY_METHOD + "] is not static.");
        }

        //如果方法没权限方法, 则添加权限并执行
        if (!factoryMethod.isAccessible()) {
          configurationObject = AccessController.doPrivileged((PrivilegedExceptionAction<Object>) () -> {
            try {
              factoryMethod.setAccessible(true);
              return factoryMethod.invoke(null);
            } finally {
              factoryMethod.setAccessible(false);
            }
          });
        } else {
          //直接执行
          configurationObject = factoryMethod.invoke(null);
        }
      } catch (final ExecutorException ex) {
        throw ex;
      } catch (final NoSuchMethodException ex) {
        throw new ExecutorException("Cannot get Configuration as factory class ["
                + this.configurationFactory + "] is missing factory method of name ["
                + FACTORY_METHOD + "].", ex);
      } catch (final PrivilegedActionException ex) {
        throw new ExecutorException("Cannot get Configuration as factory method ["
                + this.configurationFactory + "]#["
                + FACTORY_METHOD + "] threw an exception.", ex.getCause());
      } catch (final Exception ex) {
        throw new ExecutorException("Cannot get Configuration as factory method ["
                + this.configurationFactory + "]#["
                + FACTORY_METHOD + "] threw an exception.", ex);
      }

      //如果对象不是 Configuration , 报异常
      if (!(configurationObject instanceof Configuration)) {
        throw new ExecutorException("Cannot get Configuration as factory method ["
                + this.configurationFactory + "]#["
                + FACTORY_METHOD + "] didn't return [" + Configuration.class + "] but ["
                + (configurationObject == null ? "null" : configurationObject.getClass()) + "].");
      }

      //强转成 Configuration
      return Configuration.class.cast(configurationObject);
    }

    private Log getLogger() {
      if (this.log == null) {
        this.log = LogFactory.getLog(this.getClass());
      }
      return this.log;
    }
  }

  /**
   * 已经关闭的 Executor 实现类
   * - 仅仅在 ResultLoaderMap 中，作为一个“空”的 Executor 对象。没有什么特殊的意义和用途
   */
  private static final class ClosedExecutor extends BaseExecutor {

    public ClosedExecutor() {
      super(null, null);
    }

    @Override
    public boolean isClosed() {
      return true;
    }

    @Override
    protected int doUpdate(MappedStatement ms, Object parameter) throws SQLException {
      throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    protected List<BatchResult> doFlushStatements(boolean isRollback) throws SQLException {
      throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    protected <E> List<E> doQuery(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) throws SQLException {
      throw new UnsupportedOperationException("Not supported.");
    }

    @Override
    protected <E> Cursor<E> doQueryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds, BoundSql boundSql) throws SQLException {
      throw new UnsupportedOperationException("Not supported.");
    }
  }
}

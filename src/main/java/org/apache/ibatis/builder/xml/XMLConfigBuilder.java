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
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;
import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;

/**
 * XML配置构建器, 主要负责解析mybatis-config.xml配置文件
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

  /**
   * 是否已经解析
   */
  private boolean parsed;
  /**
   * 基于Java的XPath解析器
   */
  private final XPathParser parser;
  /**
   * 环境
   */
  private String environment;
  /**
   * Reflector工厂对象
   */
  private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

  public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
  }

  public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
  }

  public XMLConfigBuilder(Reader reader, String environment, Properties props) {
    this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    //是否校验xml, 默认是true
    //XPathParser中的variable和Configuration中的variables是一样的
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  /**
   * @param props 方法传入的自定义 props, 默认是null
   */
  private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
    //创建Configuration对象
    super(new Configuration());
    ErrorContext.instance().resource("SQL Mapper Configuration");
    //设置Configuration的variables属性, 如果是null, 解析配置文件完会新建一个
    this.configuration.setVariables(props);
    this.parsed = false;
    this.environment = environment;
    this.parser = parser;
  }

  public Configuration parse() {
    //如果已经解析过, 则抛异常
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    //设置已经解析
    parsed = true;
    //解析 <configuration> 标签的内容
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
  }

  private void parseConfiguration(XNode root) {
    try {
      //issue #117 read properties first
      //解析<properties>, 一定要先解析这个, 因这些动态配置在下面的解析中有可能会用到
      propertiesElement(root.evalNode("properties"));
      //解析<settings>
      Properties settings = settingsAsProperties(root.evalNode("settings"));
      loadCustomVfs(settings);
      //加载自定义日志
      loadCustomLogImpl(settings);
      //解析<typeAliases>
      typeAliasesElement(root.evalNode("typeAliases"));
      //解析<plugins>
      pluginElement(root.evalNode("plugins"));
      //解析<objectFactory>
      objectFactoryElement(root.evalNode("objectFactory"));
      //解析<objectWrapperFactory>
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
      //解析<reflectorFactory>
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      settingsElement(settings);
      // read it after objectFactory and objectWrapperFactory issue #631
      //解析<environments>
      environmentsElement(root.evalNode("environments"));
      //解析<databaseIdProvider>
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));
      //解析<typeHandlers>
      typeHandlerElement(root.evalNode("typeHandlers"));
      //解析<mappers>
      mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }

  /**
   * 解析 <settings></settings>标签的内容,
   * setting的内容专门设置在Configuration中配置
   */
  private Properties settingsAsProperties(XNode context) {
    //如果这个节点是null, 返回一个空的Properties
    if (context == null) {
      return new Properties();
    }
    //将子标签解析成properties
    Properties props = context.getChildrenAsProperties();
    // Check that all settings are known to the configuration class
    //获取Configuration的MetaClass
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    //检测配置的是否正确存在, 不存在则报错
    for (Object key : props.keySet()) {
      if (!metaConfig.hasSetter(String.valueOf(key))) {
        throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
      }
    }
    return props;
  }

  /**
   * 加截自定义的VFS实现类
   */
  private void loadCustomVfs(Properties props) throws ClassNotFoundException {
    //取出配置的值
    String value = props.getProperty("vfsImpl");
    //值如果不为null
    if (value != null) {
      //以逗号分割, 将全类名变成列表
      String[] clazzes = value.split(",");
      for (String clazz : clazzes) {
        if (!clazz.isEmpty()) {
          //加载类
          @SuppressWarnings("unchecked")
          Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
          //将类加到全局配置中, configuration只保留最后一个
          configuration.setVfsImpl(vfsImpl);
        }
      }
    }
  }

  private void loadCustomLogImpl(Properties props) {
    //解析自定义日志实现类
    Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
    //设置自定义日志
    configuration.setLogImpl(logImpl);
  }

  private void typeAliasesElement(XNode parent) {
    //节点不为null
    if (parent != null) {
      //遍历子节点
      for (XNode child : parent.getChildren()) {
        //如果有注册包下的所有类的别名
        if ("package".equals(child.getName())) {
          //获取包名
          String typeAliasPackage = child.getStringAttribute("name");
          //注册包下所有的类, 用类的simpleName来做别名
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
        } else {
          //其他的标签, 则获取别名和类全称
          String alias = child.getStringAttribute("alias");
          String type = child.getStringAttribute("type");
          try {
            Class<?> clazz = Resources.classForName(type);
            if (alias == null) {
              //如果别名为null, 则直接用类简单名注册别名
              typeAliasRegistry.registerAlias(clazz);
            } else {
              //如果不为null, 则用别名注册
              typeAliasRegistry.registerAlias(alias, clazz);
            }
          } catch (ClassNotFoundException e) {
            throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
          }
        }
      }
    }
  }

  /**
   * 注册拦截器
   */
  private void pluginElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        //获取拦截器类名
        String interceptor = child.getStringAttribute("interceptor");
        //获取配置项
        Properties properties = child.getChildrenAsProperties();
        //解析拦截器类
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
        //设置配置
        interceptorInstance.setProperties(properties);
        //添加拦截器
        configuration.addInterceptor(interceptorInstance);
      }
    }
  }

  /**
   * 加载对象工厂类
   */
  private void objectFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties properties = context.getChildrenAsProperties();
      ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
      factory.setProperties(properties);
      configuration.setObjectFactory(factory);
    }
  }

  /**
   * 加载ObjectWrapper工厂类
   */
  private void objectWrapperFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
      configuration.setObjectWrapperFactory(factory);
    }
  }

  /**
   * 加载Reflector工厂
   */
  private void reflectorFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
      configuration.setReflectorFactory(factory);
    }
  }

  /**
   * 如果属性在不只一个地方进行了配置，那么 MyBatis 将按照下面的顺序来加载：
   *
   * 在 properties 元素体内指定的属性首先被读取。
   * 然后根据 properties 元素中的 resource 属性读取类路径下属性文件或根据 url 属性指定的路径读取属性文件，并覆盖已读取的同名属性。
   * 最后读取作为方法参数传递的属性，并覆盖已读取的同名属性。
   * 因此，通过方法参数传递的属性(vars变量)具有最高优先级，resource/url 属性中指定的配置文件次之，最低优先级的是 properties 属性中指定的属性。
   */
  private void propertiesElement(XNode context) throws Exception {
    if (context != null) {
      //<properties>节点下的子节点<property>, 获取<property>节点的name和value作为defaults的键值
      Properties defaults = context.getChildrenAsProperties();
      //url和resource属性用于加载 指定.properties文件的内容
      String resource = context.getStringAttribute("resource");
      String url = context.getStringAttribute("url");
      //url 和 resource属性不能同时存在
      if (resource != null && url != null) {
        throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }
      //引用文件的property复盖<property>的
      if (resource != null) {
        defaults.putAll(Resources.getResourceAsProperties(resource));
      } else if (url != null) {
        defaults.putAll(Resources.getUrlAsProperties(url));
      }
      Properties vars = configuration.getVariables();
      //方法传递的properties复盖上面设置的
      if (vars != null) {
        defaults.putAll(vars);
      }
      //更新解析器中的variables
      parser.setVariables(defaults);
      //更新全局配置的variables
      configuration.setVariables(defaults);
    }
  }

  /**
   * 手动设置对应的setting配置到configuration中
   */
  private void settingsElement(Properties props) {
    configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
    configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
    configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
    configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
    configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
    configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
    configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
    configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
    configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
    configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
    configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
    configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
    configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
    configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
    configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
    configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
    configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
    configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
    configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
    configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));
    configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
    configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
    configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
    configuration.setLogPrefix(props.getProperty("logPrefix"));
    configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
  }

  /**
   * 配置环境
   */
  private void environmentsElement(XNode context) throws Exception {
    if (context != null) {
      //如果环境是null, 则取默认
      if (environment == null) {
        environment = context.getStringAttribute("default");
      }
      for (XNode child : context.getChildren()) {
        String id = child.getStringAttribute("id");
        //如果找到配置环境
        if (isSpecifiedEnvironment(id)) {
          //配置事务工厂
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
          //配置DataSource工厂
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
          //创建DataSource
          DataSource dataSource = dsFactory.getDataSource();
          //创建Environment Builder对象
          Environment.Builder environmentBuilder = new Environment.Builder(id)
              .transactionFactory(txFactory)
              .dataSource(dataSource);
          //构建环境类并设置
          configuration.setEnvironment(environmentBuilder.build());
        }
      }
    }
  }

  /**
   * 加载数据库厂商标识
   */
  private void databaseIdProviderElement(XNode context) throws Exception {
    DatabaseIdProvider databaseIdProvider = null;
    if (context != null) {
      //获取 DatabaseIdProvider 实现类的别名
      String type = context.getStringAttribute("type");
      //为了向后兼容
      // awful patch to keep backward compatibility
      if ("VENDOR".equals(type)) {
        type = "DB_VENDOR";
      }
      //解析配置
      Properties properties = context.getChildrenAsProperties();
      //创建 DatabaseIdProvider 实例
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
      //设置配置
      databaseIdProvider.setProperties(properties);
    }
    //获取数据库环境配置
    Environment environment = configuration.getEnvironment();
    //如果 environment 和 databaseIdProvider 都不为null
    if (environment != null && databaseIdProvider != null) {
      //解析时, 从dataSource中获取数据库标识, 然后确定现在环境所使用的dataBaseId
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
      //设置真实的数据库id(数据库名称)
      configuration.setDatabaseId(databaseId);
    }
  }

  /**
   * 解析事务管理器的配置
   */
  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    if (context != null) {
      //事务管理器工厂别名
      String type = context.getStringAttribute("type");
      //获取属性
      Properties props = context.getChildrenAsProperties();
      //将type的别名, 转换成真正的类, 并创建实例
      TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
      //设置属性
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
  }

  /**
   * 解析dataSource的配置
   */
  private DataSourceFactory dataSourceElement(XNode context) throws Exception {
    if (context != null) {
      //获取DataSource工厂的别名
      String type = context.getStringAttribute("type");
      //获取配置
      Properties props = context.getChildrenAsProperties();
      //创建工厂实例
      DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
      //设置属性
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a DataSourceFactory.");
  }

  /**
   * 解析TypeHandler的配置
   */
  private void typeHandlerElement(XNode parent) {
    //节点不为null
    if (parent != null) {
      //遍历子节点
      for (XNode child : parent.getChildren()) {
        //如果配置了包节点
        if ("package".equals(child.getName())) {
          //获取包名
          String typeHandlerPackage = child.getStringAttribute("name");
          //注册包下所有类
          typeHandlerRegistry.register(typeHandlerPackage);
        } else {
          //手动配置的TypeHandler
          String javaTypeName = child.getStringAttribute("javaType");
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          String handlerTypeName = child.getStringAttribute("handler");
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          //typeHandlerClass不能为null, 否则报错
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);
          //根据javaType, jdbcType两种情况分别调用不同的方法
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
              typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
            }
          } else {
            typeHandlerRegistry.register(typeHandlerClass);
          }
        }
      }
    }
  }

  /**
   * 解析Mapper的配置
   */
  private void mapperElement(XNode parent) throws Exception {
    if (parent != null) {
      //遍历子节点
      for (XNode child : parent.getChildren()) {
        //如果有指定了包扫描
        if ("package".equals(child.getName())) {
          //获取包名
          String mapperPackage = child.getStringAttribute("name");
          //注册包下的Mapper
          configuration.addMappers(mapperPackage);
        } else {
          //classpath资源
          String resource = child.getStringAttribute("resource");
          //url资源
          String url = child.getStringAttribute("url");
          //Mapper接口
          String mapperClass = child.getStringAttribute("class");
          //如果只有resource
          if (resource != null && url == null && mapperClass == null) {
            ErrorContext.instance().resource(resource);
            //获资源文件流
            InputStream inputStream = Resources.getResourceAsStream(resource);
            //创建XMLMapperBuilder对象
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
            //解析XML文件
            mapperParser.parse();
            //如果只能url
          } else if (resource == null && url != null && mapperClass == null) {
            ErrorContext.instance().resource(url);
            //通过url获取文件流
            InputStream inputStream = Resources.getUrlAsStream(url);
            //创建XMLMapperBuilder对象
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
            //解析文件
            mapperParser.parse();
            //如果只有mapperClass
          } else if (resource == null && url == null && mapperClass != null) {
            //如果是Mapper接口
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            //直接注册
            configuration.addMapper(mapperInterface);
          } else {
            //url, resource, class只能三选一来配置
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

  /**
   * 获取匹配的环境配置
   */
  private boolean isSpecifiedEnvironment(String id) {
    if (environment == null) {
      throw new BuilderException("No environment specified.");
    } else if (id == null) {
      throw new BuilderException("Environment requires an id attribute.");
    } else if (environment.equals(id)) {
      return true;
    }
    return false;
  }

}

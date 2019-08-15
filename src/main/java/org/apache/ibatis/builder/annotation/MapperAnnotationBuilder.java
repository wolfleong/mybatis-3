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

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

import org.apache.ibatis.annotations.Arg;
import org.apache.ibatis.annotations.CacheNamespace;
import org.apache.ibatis.annotations.CacheNamespaceRef;
import org.apache.ibatis.annotations.Case;
import org.apache.ibatis.annotations.ConstructorArgs;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.DeleteProvider;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.InsertProvider;
import org.apache.ibatis.annotations.Lang;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Options.FlushCachePolicy;
import org.apache.ibatis.annotations.Property;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.ResultType;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectKey;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.TypeDiscriminator;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.UpdateProvider;
import org.apache.ibatis.binding.BindingException;
import org.apache.ibatis.binding.MapperMethod.ParamMap;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.CacheRefResolver;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.keygen.Jdbc3KeyGenerator;
import org.apache.ibatis.executor.keygen.KeyGenerator;
import org.apache.ibatis.executor.keygen.NoKeyGenerator;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Discriminator;
import org.apache.ibatis.mapping.FetchType;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ResultFlag;
import org.apache.ibatis.mapping.ResultMapping;
import org.apache.ibatis.mapping.ResultSetType;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.mapping.StatementType;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.reflection.TypeParameterResolver;
import org.apache.ibatis.scripting.LanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;
import org.apache.ibatis.type.UnknownTypeHandler;

/**
 * 注解配置Mapper接口加载
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class MapperAnnotationBuilder {

  /**
   * sql操作注解的集合
   */
  private static final Set<Class<? extends Annotation>> SQL_ANNOTATION_TYPES = new HashSet<>();
  /**
   * sql操作提供者集合
   */
  private static final Set<Class<? extends Annotation>> SQL_PROVIDER_ANNOTATION_TYPES = new HashSet<>();

  /**
   * 全局配置
   */
  private final Configuration configuration;
  /**
   * Mapper解析助手
   */
  private final MapperBuilderAssistant assistant;
  /**
   * 接口类
   */
  private final Class<?> type;

  static {
    //初始化sql操作注解的集合
    SQL_ANNOTATION_TYPES.add(Select.class);
    SQL_ANNOTATION_TYPES.add(Insert.class);
    SQL_ANNOTATION_TYPES.add(Update.class);
    SQL_ANNOTATION_TYPES.add(Delete.class);

    //初始化sql操作提供者集合
    SQL_PROVIDER_ANNOTATION_TYPES.add(SelectProvider.class);
    SQL_PROVIDER_ANNOTATION_TYPES.add(InsertProvider.class);
    SQL_PROVIDER_ANNOTATION_TYPES.add(UpdateProvider.class);
    SQL_PROVIDER_ANNOTATION_TYPES.add(DeleteProvider.class);
  }

  public MapperAnnotationBuilder(Configuration configuration, Class<?> type) {
    //将类限定名转换成java文件名
    String resource = type.getName().replace('.', '/') + ".java (best guess)";
    //创建 Mapper构造助手
    this.assistant = new MapperBuilderAssistant(configuration, resource);
    this.configuration = configuration;
    this.type = type;
  }

  /**
   * 解析注解
   */
  public void parse() {
    //将mapper类名变字符串
    String resource = type.toString();
    //判断是否已经加载过这个接口, 如果没有加载过, 则加载
    //如果被xml加载过的, 就不会再往下走了
    if (!configuration.isResourceLoaded(resource)) {
      //加载Mapper接口可能引用的xml
      loadXmlResource();
      //记录已经加载过
      configuration.addLoadedResource(resource);
      //设置当前namespace
      assistant.setCurrentNamespace(type.getName());
      //解析缓存
      parseCache();
      //解析缓存引用
      parseCacheRef();
      //获取接口的方法反射
      Method[] methods = type.getMethods();
      //遍历方法反射
      for (Method method : methods) {
        try {
          //如果不是桥接方法
          // issue #237
          if (!method.isBridge()) {
            //解析MappedStatement
            parseStatement(method);
          }
        } catch (IncompleteElementException e) {
          //解析不成功, 记录, 等解析完再解析一次 todo wolfleong
          configuration.addIncompleteMethod(new MethodResolver(this, method));
        }
      }
    }
    //最终再解析一次
    parsePendingMethods();
  }

  private void parsePendingMethods() {
    Collection<MethodResolver> incompleteMethods = configuration.getIncompleteMethods();
    synchronized (incompleteMethods) {
      Iterator<MethodResolver> iter = incompleteMethods.iterator();
      while (iter.hasNext()) {
        try {
          iter.next().resolve();
          iter.remove();
        } catch (IncompleteElementException e) {
          // This method is still missing a resource
        }
      }
    }
  }

  /**
   * 加载Mapper接口可能对应的xml资源
   */
  private void loadXmlResource() {
    // Spring may not know the real resource name so we check a flag
    // to prevent loading again a resource twice
    // this flag is set at XMLMapperBuilder#bindMapperForNamespace
    //如果有 namespace:这个为前缀的, 表示是这个xml文件已经解析过了
    if (!configuration.isResourceLoaded("namespace:" + type.getName())) {
      String xmlResource = type.getName().replace('.', '/') + ".xml";
      // #1347
      InputStream inputStream = type.getResourceAsStream("/" + xmlResource);
      if (inputStream == null) {
        // Search XML mapper that is not in the module but in the classpath.
        try {
          inputStream = Resources.getResourceAsStream(type.getClassLoader(), xmlResource);
        } catch (IOException e2) {
          // ignore, resource is not required
        }
      }
      if (inputStream != null) {
        XMLMapperBuilder xmlParser = new XMLMapperBuilder(inputStream, assistant.getConfiguration(), xmlResource, configuration.getSqlFragments(), type.getName());
        xmlParser.parse();
      }
    }
  }

  /**
   * 解析注解缓存
   */
  private void parseCache() {
    //获取缓存注解
    CacheNamespace cacheDomain = type.getAnnotation(CacheNamespace.class);
    //如果缓存注解不为null
    if (cacheDomain != null) {
      //如果size=0, 给null, 不为0则不处理
      Integer size = cacheDomain.size() == 0 ? null : cacheDomain.size();
      // flushInterval 如果是0给null
      Long flushInterval = cacheDomain.flushInterval() == 0 ? null : cacheDomain.flushInterval();
      // 将@Property 转换成Properties
      Properties props = convertToProperties(cacheDomain.properties());
      //创建缓存
      assistant.useNewCache(cacheDomain.implementation(), cacheDomain.eviction(), flushInterval, size, cacheDomain.readWrite(), cacheDomain.blocking(), props);
    }
  }

  /**
   * 转换注解property
   */
  private Properties convertToProperties(Property[] properties) {
    //如果properties长度为0, 返回null
    if (properties.length == 0) {
      return null;
    }
    //创建Properties
    Properties props = new Properties();
    //遍历注解
    for (Property property : properties) {
      //property注解的值替换完动态变量后, 添加到 props
      props.setProperty(property.name(),
          PropertyParser.parse(property.value(), configuration.getVariables()));
    }
    //返回props
    return props;
  }

  /**
   * 解析注解缓存引用
   */
  private void parseCacheRef() {
    //获取缓引用注解
    CacheNamespaceRef cacheDomainRef = type.getAnnotation(CacheNamespaceRef.class);
    //注解引用不为nll
    if (cacheDomainRef != null) {
      //获取引用的Mapper接口
      Class<?> refType = cacheDomainRef.value();
      //获取引用的名称
      String refName = cacheDomainRef.name();
      //缓存引用不能两个同时为空
      if (refType == void.class && refName.isEmpty()) {
        throw new BuilderException("Should be specified either value() or name() attribute in the @CacheNamespaceRef");
      }
      //如果两个配置同时不为空, 也不行
      if (refType != void.class && !refName.isEmpty()) {
        throw new BuilderException("Cannot use both value() and name() attribute in the @CacheNamespaceRef");
      }
      //优先取 refType的, 取不到再取refName
      String namespace = (refType != void.class) ? refType.getName() : refName;
      try {
        //解析缓存引用
        assistant.useCacheRef(namespace);
      } catch (IncompleteElementException e) {
        //解析不成功, 先记录, 等解析完再解析一次
        //todo wolfleong 注意这里缓存的起来的并没有在外面重新调用
        configuration.addIncompleteCacheRef(new CacheRefResolver(assistant, namespace));
      }
    }
  }

  /**
   * 没有指定@ResultMap时, 需要解析其他注解来获取ResultMap
   */
  private String parseResultMap(Method method) {
    //获取方法返回值
    Class<?> returnType = getReturnType(method);
    //获取@ConstructorArgs注解
    ConstructorArgs args = method.getAnnotation(ConstructorArgs.class);
    //获取@Results注解
    Results results = method.getAnnotation(Results.class);
    //获取@TypeDiscriminator注解
    TypeDiscriminator typeDiscriminator = method.getAnnotation(TypeDiscriminator.class);
    //获取设置的 resultMapId或生成一个resultMapId
    String resultMapId = generateResultMapName(method);
    //args和results都处理null的情况, 返回空数组
    applyResultMap(resultMapId, returnType, argsIf(args), resultsIf(results), typeDiscriminator);
    //返回resultMapId
    return resultMapId;
  }

  /**
   * 生成ResultMapId, 如:
   * - com.wl.Person.findByNameAndAge-String-Integer
   * - com.wl.Person.findAll-void
   */
  private String generateResultMapName(Method method) {
    //获取Result注解
    Results results = method.getAnnotation(Results.class);
    //如果@Results注解不为null, 且id不为空
    if (results != null && !results.id().isEmpty()) {
      //返回的id拼上namespace, 也是类的全限定名
      return type.getName() + "." + results.id();
    }
    //如果没有配置@Results, 则用方法参数的类型拼接字符串来做ResultMapId的后缀
    StringBuilder suffix = new StringBuilder();
    //遍历方法的参数类型  findByNameAndAge(String name, Integer age) 会成 -String-Integer
    for (Class<?> c : method.getParameterTypes()) {
      suffix.append("-");
      suffix.append(c.getSimpleName());
    }
    //如果没有参数, 用-void表示
    if (suffix.length() < 1) {
      suffix.append("-void");
    }
    //拼接上类全限定名和方法名
    return type.getName() + "." + method.getName() + suffix;
  }

  /**
   * 生成resultMap
   */
  private void applyResultMap(String resultMapId, Class<?> returnType, Arg[] args, Result[] results, TypeDiscriminator discriminator) {
    //记录所有的ResultMapping
    List<ResultMapping> resultMappings = new ArrayList<>();
    //处理ConstructorArgs
    applyConstructorArgs(args, returnType, resultMappings);
    //处理@Result
    applyResults(results, returnType, resultMappings);
    //处理鉴别器
    Discriminator disc = applyDiscriminator(resultMapId, returnType, discriminator);
    //添加一个resultMap
    // TODO add AutoMappingBehaviour
    assistant.addResultMap(resultMapId, returnType, null, disc, resultMappings, null);
    //创建鉴别器下面每个@Case的ResultMap
    createDiscriminatorResultMaps(resultMapId, returnType, discriminator);
  }

  /**
   * 创建鉴别器下面Case的ResultMap
   * - @Case不能直接指定引用的ResultMapId, 而要配置@Result匿名的
   * - 每个Case的匿名ResultMapId与xml的Case的匿名ResultMapId的生成规则是不一样的
   */
  private void createDiscriminatorResultMaps(String resultMapId, Class<?> resultType, TypeDiscriminator discriminator) {
    //如果鉴别器不为null
    if (discriminator != null) {
      //遍历鉴别器中的@Case
      for (Case c : discriminator.cases()) {
        //创建caseResultMapId
        String caseResultMapId = resultMapId + "-" + c.value();
        //记录所有ResultMapping
        List<ResultMapping> resultMappings = new ArrayList<>();
        //处理构造器
        // issue #136
        applyConstructorArgs(c.constructArgs(), resultType, resultMappings);
        //处理@Result
        applyResults(c.results(), resultType, resultMappings);
        //添加ResultMap
        // TODO add AutoMappingBehaviour
        assistant.addResultMap(caseResultMapId, c.type(), resultMapId, null, resultMappings, null);
      }
    }
  }

  /**
   * 处理鉴别器
   */
  private Discriminator applyDiscriminator(String resultMapId, Class<?> resultType, TypeDiscriminator discriminator) {
    //注解不为null
    if (discriminator != null) {
      //获取列
      String column = discriminator.column();
      //获取JavaType, 默认是String
      Class<?> javaType = discriminator.javaType() == void.class ? String.class : discriminator.javaType();
      //获取jdbcType
      JdbcType jdbcType = discriminator.jdbcType() == JdbcType.UNDEFINED ? null : discriminator.jdbcType();
      //获取TypeHandler
      @SuppressWarnings("unchecked")
      Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>)
              (discriminator.typeHandler() == UnknownTypeHandler.class ? null : discriminator.typeHandler());
      //获取@Case
      Case[] cases = discriminator.cases();
      Map<String, String> discriminatorMap = new HashMap<>();
      //遍历@Case数组
      for (Case c : cases) {
        //case的value的值
        String value = c.value();
        //Case处理的ResultMapId
        String caseResultMapId = resultMapId + "-" + value;
        discriminatorMap.put(value, caseResultMapId);
      }
      //创建一个鉴别器
      return assistant.buildDiscriminator(resultType, column, javaType, jdbcType, typeHandler, discriminatorMap);
    }
    //如果没有配置就返回null
    return null;
  }

  /**
   * 解析Mapper接口的方法成MappedStatement
   */
  void parseStatement(Method method) {
    //获取方法参数的类型, 主要是多参数和单个参数的区别, 单参数就是原类型, 多参数是ParamMap
    Class<?> parameterTypeClass = getParameterType(method);
    //获取 LanguageDriver
    LanguageDriver languageDriver = getLanguageDriver(method);
    //创建sqlSource
    SqlSource sqlSource = getSqlSourceFromAnnotations(method, parameterTypeClass, languageDriver);
    //如果有指定sql, 则肯定有SqlSource
    if (sqlSource != null) {
      //获取选项配置注解
      Options options = method.getAnnotation(Options.class);
      //拼接完整的mappedStatementId
      final String mappedStatementId = type.getName() + "." + method.getName();
      Integer fetchSize = null;
      Integer timeout = null;
      StatementType statementType = StatementType.PREPARED;
      ResultSetType resultSetType = null;
      //获取sql操作类型
      SqlCommandType sqlCommandType = getSqlCommandType(method);
      //判断是否查询
      boolean isSelect = sqlCommandType == SqlCommandType.SELECT;
      //非查询默认刷新缓存
      boolean flushCache = !isSelect;
      //查询默认使用缓存
      boolean useCache = isSelect;

      KeyGenerator keyGenerator;
      String keyProperty = null;
      String keyColumn = null;
      //如果sql操作类型是插入或更新
      if (SqlCommandType.INSERT.equals(sqlCommandType) || SqlCommandType.UPDATE.equals(sqlCommandType)) {
        //获取selectKey注解
        // first check for SelectKey annotation - that overrides everything else
        SelectKey selectKey = method.getAnnotation(SelectKey.class);
        //如果selectKey不为null
        if (selectKey != null) {
          //处理 @SelectKey注解 创建keyGenerator
          keyGenerator = handleSelectKeyAnnotation(selectKey, mappedStatementId, getParameterType(method), languageDriver);
          //设置keyProperty
          keyProperty = selectKey.keyProperty();
          //如果选项配置为null
        } else if (options == null) {
          //取全局配置的默认值
          keyGenerator = configuration.isUseGeneratedKeys() ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
        } else {
          //根据配置进行设置
          keyGenerator = options.useGeneratedKeys() ? Jdbc3KeyGenerator.INSTANCE : NoKeyGenerator.INSTANCE;
          keyProperty = options.keyProperty();
          keyColumn = options.keyColumn();
        }
      } else {
        //除insert和update外的其他类型, 不需要key生成器
        keyGenerator = NoKeyGenerator.INSTANCE;
      }

      //如果有选项配置
      if (options != null) {
        //设置flushCache
        if (FlushCachePolicy.TRUE.equals(options.flushCache())) {
          flushCache = true;
        } else if (FlushCachePolicy.FALSE.equals(options.flushCache())) {
          flushCache = false;
        }
        //设置useCache
        useCache = options.useCache();
        //如果fetchSize > -1 或者等于 Integer.MIN_VALUE 才设置 fetchSize
        //todo wolfleong 不懂为什么要 Integer.MIN_VALUE
        fetchSize = options.fetchSize() > -1 || options.fetchSize() == Integer.MIN_VALUE ? options.fetchSize() : null; //issue #348
        //超时时间只能配置大于 -1
        timeout = options.timeout() > -1 ? options.timeout() : null;
        statementType = options.statementType();
        resultSetType = options.resultSetType();
      }

      String resultMapId = null;
      //获取@ResultMap注解
      ResultMap resultMapAnnotation = method.getAnnotation(ResultMap.class);
      //如果有ResultMap注解, 则直接获取用逗号拼接resultMapId
      if (resultMapAnnotation != null) {
        resultMapId = String.join(",", resultMapAnnotation.value());
        //如果是查询, 解析ResultMap
      } else if (isSelect) {
        //解析ResultMap
        resultMapId = parseResultMap(method);
      }

      assistant.addMappedStatement(
          mappedStatementId,
          sqlSource,
          statementType,
          sqlCommandType,
          fetchSize,
          timeout,
          // ParameterMapID
          null,
          parameterTypeClass,
          resultMapId,
          //获取方法返回值作用
          getReturnType(method),
          resultSetType,
          flushCache,
          useCache,
          // TODO gcode issue #577
          false,
          keyGenerator,
          keyProperty,
          keyColumn,
          // DatabaseID
          null,
          languageDriver,
          // ResultSets
          options != null ? nullOrEmpty(options.resultSets()) : null);
    }
  }

  private LanguageDriver getLanguageDriver(Method method) {
    //获取@Lang注解
    Lang lang = method.getAnnotation(Lang.class);
    Class<? extends LanguageDriver> langClass = null;
    //如果注解不为null, 获取注解的值
    if (lang != null) {
      langClass = lang.value();
    }
    //获取注解上配置的LanguageDriver类
    return configuration.getLanguageDriver(langClass);
  }

  /**
   * 获取方法参数的类型
   */
  private Class<?> getParameterType(Method method) {
    Class<?> parameterType = null;
    //获取方法参数类型数组
    Class<?>[] parameterTypes = method.getParameterTypes();
    //遍历方法参数类型
    for (Class<?> currentParameterType : parameterTypes) {
      //如果不是特殊参数(RowBounds和ResultHandler)
      if (!RowBounds.class.isAssignableFrom(currentParameterType) && !ResultHandler.class.isAssignableFrom(currentParameterType)) {
        //这样做的意思是, 当有多参数时, 参数类型是ParamMap.class, 如果只有一个参数时, 参数类型就是方法参数的类型
        if (parameterType == null) {
          parameterType = currentParameterType;
        } else {
          // issue #135
          parameterType = ParamMap.class;
        }
      }
    }
    return parameterType;
  }

  private Class<?> getReturnType(Method method) {
    //获取方法返回类型
    Class<?> returnType = method.getReturnType();
    //解析返回可能出现的泛型
    Type resolvedReturnType = TypeParameterResolver.resolveReturnType(method, type);
    //如果返回类型是普通类
    if (resolvedReturnType instanceof Class) {
      //直接返回
      returnType = (Class<?>) resolvedReturnType;
      //如果是数组, 返回数组的组件
      if (returnType.isArray()) {
        returnType = returnType.getComponentType();
      }
      //如果返回类型是 void ，则尝试使用 @ResultType 注解
      // gcode issue #508
      if (void.class.equals(returnType)) {
        //todo wolfleong 不懂不为什么要这么处理, void 返回值的方法再确定返回值有意义吗
        ResultType rt = method.getAnnotation(ResultType.class);
        //@ResultType不为null
        if (rt != null) {
          //获取配置的返回值类型
          returnType = rt.value();
        }
      }
      //如果解析的值是带泛型的泛型参数
    } else if (resolvedReturnType instanceof ParameterizedType) {
      //强转
      ParameterizedType parameterizedType = (ParameterizedType) resolvedReturnType;
      //获取泛型参数的原始类型
      Class<?> rawType = (Class<?>) parameterizedType.getRawType();
      //如果原始类型是集合或游标
      if (Collection.class.isAssignableFrom(rawType) || Cursor.class.isAssignableFrom(rawType)) {
        //获取泛型列表
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        //如果泛型列表不为null, 且只有一个
        if (actualTypeArguments != null && actualTypeArguments.length == 1) {
          //获取这个泛型
          Type returnTypeParameter = actualTypeArguments[0];
          //如果是普通类型就直接返回
          if (returnTypeParameter instanceof Class<?>) {
            returnType = (Class<?>) returnTypeParameter;
            //如果还是个泛型参数类型
          } else if (returnTypeParameter instanceof ParameterizedType) {
            //获取这个泛型参数类型的原始类型
            // (gcode issue #443) actual type can be a also a parameterized type
            returnType = (Class<?>) ((ParameterizedType) returnTypeParameter).getRawType();
            //如果是泛型数组类型
          } else if (returnTypeParameter instanceof GenericArrayType) {
            //获取数组的组件类型
            Class<?> componentType = (Class<?>) ((GenericArrayType) returnTypeParameter).getGenericComponentType();
            //创建数组并且获取这个数组的class
            // (gcode issue #525) support List<byte[]>
            returnType = Array.newInstance(componentType, 0).getClass();
          }
        }
        //如果method方法有@MapKey注解且返回值是Map
      } else if (method.isAnnotationPresent(MapKey.class) && Map.class.isAssignableFrom(rawType)) {
        //获取泛型列表
        // (gcode issue 504) Do not look into Maps if there is not MapKey annotation
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        //不为null且泛型个数为2, 才是Map<K,V>
        if (actualTypeArguments != null && actualTypeArguments.length == 2) {
          //获取第二个泛型
          Type returnTypeParameter = actualTypeArguments[1];
          //如果是普通Class, 返回
          if (returnTypeParameter instanceof Class<?>) {
            returnType = (Class<?>) returnTypeParameter;
            //如果是泛型参数类型
          } else if (returnTypeParameter instanceof ParameterizedType) {
            //获取泛型参数的原类型
            // (gcode issue 443) actual type can be a also a parameterized type
            returnType = (Class<?>) ((ParameterizedType) returnTypeParameter).getRawType();
          }
        }
        //如果是Optional类型
      } else if (Optional.class.equals(rawType)) {
        //获取泛型列表
        Type[] actualTypeArguments = parameterizedType.getActualTypeArguments();
        //获取第一个泛型
        Type returnTypeParameter = actualTypeArguments[0];
        //如果泛型普通的类, 则返回
        if (returnTypeParameter instanceof Class<?>) {
          returnType = (Class<?>) returnTypeParameter;
        }
      }
    }

    return returnType;
  }

  /**
   * 从注解中创建SqlSource对象
   */
  private SqlSource getSqlSourceFromAnnotations(Method method, Class<?> parameterType, LanguageDriver languageDriver) {
    try {
      //获取sql
      Class<? extends Annotation> sqlAnnotationType = getSqlAnnotationType(method);
      //获取sqlProvider相关的注解
      Class<? extends Annotation> sqlProviderAnnotationType = getSqlProviderAnnotationType(method);
      //指定sql操作注解
      if (sqlAnnotationType != null) {
        //sql操作注解和sqlProvider注解不能同时存在
        if (sqlProviderAnnotationType != null) {
          throw new BindingException("You cannot supply both a static SQL and SqlProvider to method named " + method.getName());
        }
        //获取注解的实例
        Annotation sqlAnnotation = method.getAnnotation(sqlAnnotationType);
        //用反射获取注解value的值
        final String[] strings = (String[]) sqlAnnotation.getClass().getMethod("value").invoke(sqlAnnotation);
        //用sql字符串创建sqlSource对象
        return buildSqlSourceFromStrings(strings, parameterType, languageDriver);
        //如果是指定sqlProvider
      } else if (sqlProviderAnnotationType != null) {
        //获取sqlProvider注解
        Annotation sqlProviderAnnotation = method.getAnnotation(sqlProviderAnnotationType);
        //创建SqlSource对象
        return new ProviderSqlSource(assistant.getConfiguration(), sqlProviderAnnotation, type, method);
      }
      return null;
    } catch (Exception e) {
      throw new BuilderException("Could not find value method on SQL annotation.  Cause: " + e, e);
    }
  }

  /**
   * 拼接sql字符串, 并且创建sqlSource对象
   */
  private SqlSource buildSqlSourceFromStrings(String[] strings, Class<?> parameterTypeClass, LanguageDriver languageDriver) {
    final StringBuilder sql = new StringBuilder();
    //拼接sql, 用空格隔开
    for (String fragment : strings) {
      sql.append(fragment);
      sql.append(" ");
    }
    //创建sqlSource
    return languageDriver.createSqlSource(configuration, sql.toString().trim(), parameterTypeClass);
  }

  private SqlCommandType getSqlCommandType(Method method) {
    //获取sql操作注解
    Class<? extends Annotation> type = getSqlAnnotationType(method);
    //如果sql操作注解为null
    if (type == null) {
      //获取sqlProvider注解
      type = getSqlProviderAnnotationType(method);
      //如果没有找到, 则设置未知操作类型
      if (type == null) {
        return SqlCommandType.UNKNOWN;
      }
      //做相关转换
      if (type == SelectProvider.class) {
        type = Select.class;
      } else if (type == InsertProvider.class) {
        type = Insert.class;
      } else if (type == UpdateProvider.class) {
        type = Update.class;
      } else if (type == DeleteProvider.class) {
        type = Delete.class;
      }
    }

    //转换成枚举
    return SqlCommandType.valueOf(type.getSimpleName().toUpperCase(Locale.ENGLISH));
  }

  /**
   * 获取Method方法上, 指定sql操作的注解
   */
  private Class<? extends Annotation> getSqlAnnotationType(Method method) {
    return chooseAnnotationType(method, SQL_ANNOTATION_TYPES);
  }

  /**
   * 获取Method方法上, 指定sqlProvider的注解
   */
  private Class<? extends Annotation> getSqlProviderAnnotationType(Method method) {
    return chooseAnnotationType(method, SQL_PROVIDER_ANNOTATION_TYPES);
  }

  /**
   * 获取Method方法上指定类型列表的注解
   */
  private Class<? extends Annotation> chooseAnnotationType(Method method, Set<Class<? extends Annotation>> types) {
    for (Class<? extends Annotation> type : types) {
      Annotation annotation = method.getAnnotation(type);
      if (annotation != null) {
        return type;
      }
    }
    return null;
  }

  /**
   * 处理@Result注解
   */
  private void applyResults(Result[] results, Class<?> resultType, List<ResultMapping> resultMappings) {
    //遍历@Result数组
    for (Result result : results) {
      List<ResultFlag> flags = new ArrayList<>();
      //id标记
      if (result.id()) {
        flags.add(ResultFlag.ID);
      }
      //获取配置的TypeHandler
      @SuppressWarnings("unchecked")
      Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>)
              ((result.typeHandler() == UnknownTypeHandler.class) ? null : result.typeHandler());
      //创建ResultMapping
      ResultMapping resultMapping = assistant.buildResultMapping(
          resultType,
          nullOrEmpty(result.property()),
          nullOrEmpty(result.column()),
          result.javaType() == void.class ? null : result.javaType(),
          result.jdbcType() == JdbcType.UNDEFINED ? null : result.jdbcType(),
          //如果有嵌套查询, 则获取嵌套selectId
          hasNestedSelect(result) ? nestedSelectId(result) : null,
          null,
          null,
          null,
          typeHandler,
          flags,
          null,
          null,
          isLazy(result));
      //添加到resultMapping列表
      resultMappings.add(resultMapping);
    }
  }

  private String nestedSelectId(Result result) {
    //从@One或者@Many中获取select()
    String nestedSelect = result.one().select();
    if (nestedSelect.length() < 1) {
      nestedSelect = result.many().select();
    }
    //如果selectId不包括., 则拼接全类名
    if (!nestedSelect.contains(".")) {
      nestedSelect = type.getName() + "." + nestedSelect;
    }
    return nestedSelect;
  }

  /**
   * 判断是否懒加载
   */
  private boolean isLazy(Result result) {
    //默认用用全局配置懒加载
    boolean isLazy = configuration.isLazyLoadingEnabled();
    //如果@One有子查询且fetchType是非default, 则表示有懒加载
    if (result.one().select().length() > 0 && FetchType.DEFAULT != result.one().fetchType()) {
      isLazy = result.one().fetchType() == FetchType.LAZY;
      //如果@Many有子查询且FetchType是非default
    } else if (result.many().select().length() > 0 && FetchType.DEFAULT != result.many().fetchType()) {
      isLazy = result.many().fetchType() == FetchType.LAZY;
    }
    return isLazy;
  }

  /**
   * 是否有子查询
   */
  private boolean hasNestedSelect(Result result) {
    //同一个@Result不能同时出现有效的@One和@Many
    if (result.one().select().length() > 0 && result.many().select().length() > 0) {
      throw new BuilderException("Cannot use both @One and @Many annotations in the same @Result");
    }
    //两个中的一个有效就有子查询
    return result.one().select().length() > 0 || result.many().select().length() > 0;
  }

  private void applyConstructorArgs(Arg[] args, Class<?> resultType, List<ResultMapping> resultMappings) {
    //遍历@Arg数组
    for (Arg arg : args) {
      List<ResultFlag> flags = new ArrayList<>();
      //构造器标记
      flags.add(ResultFlag.CONSTRUCTOR);
      //构造器的id标记
      if (arg.id()) {
        flags.add(ResultFlag.ID);
      }
      //获取配置的TypeHandler, UnknownTypeHandler相当于null
      @SuppressWarnings("unchecked")
      Class<? extends TypeHandler<?>> typeHandler = (Class<? extends TypeHandler<?>>)
              (arg.typeHandler() == UnknownTypeHandler.class ? null : arg.typeHandler());
      //创建ResultMapping
      ResultMapping resultMapping = assistant.buildResultMapping(
          resultType,
          nullOrEmpty(arg.name()),
          nullOrEmpty(arg.column()),
          //默认是null
          arg.javaType() == void.class ? null : arg.javaType(),
          //默认是null
          arg.jdbcType() == JdbcType.UNDEFINED ? null : arg.jdbcType(),
          nullOrEmpty(arg.select()),
          nullOrEmpty(arg.resultMap()),
          null,
          nullOrEmpty(arg.columnPrefix()),
          typeHandler,
          flags,
          null,
          null,
          false);
      //添加到resultMapping列表
      resultMappings.add(resultMapping);
    }
  }

  /**
   * null或空串都是返回null
   */
  private String nullOrEmpty(String value) {
    return value == null || value.trim().length() == 0 ? null : value;
  }

  /**
   * 如果没有配置@Results, 返回一个空的Result数组
   */
  private Result[] resultsIf(Results results) {
    return results == null ? new Result[0] : results.value();
  }

  /**
   * 如果没有配置@ConstructorArgs, 则返回一个空的@Arg
   */
  private Arg[] argsIf(ConstructorArgs args) {
    return args == null ? new Arg[0] : args.value();
  }

  private KeyGenerator handleSelectKeyAnnotation(SelectKey selectKeyAnnotation, String baseStatementId, Class<?> parameterTypeClass, LanguageDriver languageDriver) {
    //拼接后缀
    String id = baseStatementId + SelectKeyGenerator.SELECT_KEY_SUFFIX;
    Class<?> resultTypeClass = selectKeyAnnotation.resultType();
    StatementType statementType = selectKeyAnnotation.statementType();
    String keyProperty = selectKeyAnnotation.keyProperty();
    String keyColumn = selectKeyAnnotation.keyColumn();
    boolean executeBefore = selectKeyAnnotation.before();

    //@SelectKey不使用缓存
    // defaults
    boolean useCache = false;
    KeyGenerator keyGenerator = NoKeyGenerator.INSTANCE;
    Integer fetchSize = null;
    Integer timeout = null;
    boolean flushCache = false;
    String parameterMap = null;
    String resultMap = null;
    ResultSetType resultSetTypeEnum = null;

    //从sql字符串中创建SqlSource
    SqlSource sqlSource = buildSqlSourceFromStrings(selectKeyAnnotation.statement(), parameterTypeClass, languageDriver);
    SqlCommandType sqlCommandType = SqlCommandType.SELECT;

    //创建MappedStatement
    assistant.addMappedStatement(id, sqlSource, statementType, sqlCommandType, fetchSize, timeout, parameterMap, parameterTypeClass, resultMap, resultTypeClass, resultSetTypeEnum,
        flushCache, useCache, false,
        keyGenerator, keyProperty, keyColumn, null, languageDriver, null);

    //拼接namespace
    id = assistant.applyCurrentNamespace(id, false);

    //创建完之后获取MappedStatement
    MappedStatement keyStatement = configuration.getMappedStatement(id, false);
    //创建SelectKeyGenerator
    SelectKeyGenerator answer = new SelectKeyGenerator(keyStatement, executeBefore);
    //添加到全局配置中
    configuration.addKeyGenerator(id, answer);
    return answer;
  }

}

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

import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.executor.ResultExtractor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.transaction.Transaction;
import org.apache.ibatis.transaction.TransactionFactory;

/**
 * 每个属性子查询结果加载器, 嵌套子查询有两种情况, 主要负责保存一次延迟加载操作所需的全部信息
 *  - 构造器映射
 *  - 普通属性映射
 *
 * @author Clinton Begin
 */
public class ResultLoader {

  /**
   * 全局配置
   */
  protected final Configuration configuration;
  /**
   * 执行器
   */
  protected final Executor executor;
  /**
   * MappedStatement
   */
  protected final MappedStatement mappedStatement;
  /**
   * 参数对象
   */
  protected final Object parameterObject;
  /**
   * 结果类型
   */
  protected final Class<?> targetType;
  /**
   * 对象工厂
   */
  protected final ObjectFactory objectFactory;
  /**
   * CacheKey
   */
  protected final CacheKey cacheKey;
  /**
   * BoundSql
   */
  protected final BoundSql boundSql;
  /**
   * ResultExtractor 对象
   */
  protected final ResultExtractor resultExtractor;
  /**
   * 创建 ResultLoader 对象时，所在的线程
   */
  protected final long creatorThreadId;

  /**
   * 是否已经加载
   */
  protected boolean loaded;
  /**
   * 查询的结果对象
   */
  protected Object resultObject;

  public ResultLoader(Configuration config, Executor executor, MappedStatement mappedStatement, Object parameterObject, Class<?> targetType, CacheKey cacheKey, BoundSql boundSql) {
    this.configuration = config;
    this.executor = executor;
    this.mappedStatement = mappedStatement;
    this.parameterObject = parameterObject;
    this.targetType = targetType;
    this.objectFactory = configuration.getObjectFactory();
    this.cacheKey = cacheKey;
    this.boundSql = boundSql;
    //创建 ResultExtractor
    this.resultExtractor = new ResultExtractor(configuration, objectFactory);
    //获取线程 id
    this.creatorThreadId = Thread.currentThread().getId();
  }

  public Object loadResult() throws SQLException {
    //查询结果
    List<Object> list = selectList();
    //提取结果
    resultObject = resultExtractor.extractObjectFromList(list, targetType);
    //返回结果
    return resultObject;
  }

  /**
   * 查询结果
   */
  private <E> List<E> selectList() throws SQLException {
    //获取 Executor 对象
    Executor localExecutor = executor;
    //如果线程id 不对, 或当前 executor 已经关闭了
    if (Thread.currentThread().getId() != this.creatorThreadId || localExecutor.isClosed()) {
      //创建新的 Executor , 因为 Executor 是非线程安全的, 有一些共享的东西如 DefaultResultHandler 等不能多线程操作
      localExecutor = newExecutor();
    }
    try {
      //执行查询
      return localExecutor.query(mappedStatement, parameterObject, RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER, cacheKey, boundSql);
    } finally {
      //如果 executor 不同, 则表明 localExecutor 是新建的, 查询完要关闭
      if (localExecutor != executor) {
        //关闭 executor
        localExecutor.close(false);
      }
    }
  }

  /**
   * 创建 Executor
   */
  private Executor newExecutor() {
    //获取 Environment
    final Environment environment = configuration.getEnvironment();
    //校验环境必须配置
    if (environment == null) {
      throw new ExecutorException("ResultLoader could not load lazily.  Environment was not configured.");
    }
    //获取 dataSource
    final DataSource ds = environment.getDataSource();
    //校验 DataSource 必须配置
    if (ds == null) {
      throw new ExecutorException("ResultLoader could not load lazily.  DataSource was not configured.");
    }
    //获取事务工厂
    final TransactionFactory transactionFactory = environment.getTransactionFactory();
    //创建事务对象
    final Transaction tx = transactionFactory.newTransaction(ds, null, false);
    //创建 Executor
    return configuration.newExecutor(tx, ExecutorType.SIMPLE);
  }

  public boolean wasNull() {
    //判断结果对象是否为 null
    return resultObject == null;
  }

}

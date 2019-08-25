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
package org.apache.ibatis.executor.keygen;

import java.sql.Statement;
import java.util.List;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.ExecutorException;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.RowBounds;

/**
 * @author Clinton Begin
 * @author Jeff Butler
 */
public class SelectKeyGenerator implements KeyGenerator {
  /**
   * select Key 后缀
   */
  public static final String SELECT_KEY_SUFFIX = "!selectKey";
  /**
   * 是否在执行sql前生成
   */
  private final boolean executeBefore;
  /**
   * key生成的sql的封装
   */
  private final MappedStatement keyStatement;

  public SelectKeyGenerator(MappedStatement keyStatement, boolean executeBefore) {
    this.executeBefore = executeBefore;
    this.keyStatement = keyStatement;
  }

  @Override
  public void processBefore(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
    //是否在之前执行, 是, 就执行
    if (executeBefore) {
      processGeneratedKeys(executor, ms, parameter);
    }
  }

  @Override
  public void processAfter(Executor executor, MappedStatement ms, Statement stmt, Object parameter) {
    //是否在sql之后执行, 是就执行
    if (!executeBefore) {
      processGeneratedKeys(executor, ms, parameter);
    }
  }

  /**
   * 处理生成主键
   * @param executor 执行器
   * @param ms MappedStatement
   * @param parameter 参数
   */
  private void processGeneratedKeys(Executor executor, MappedStatement ms, Object parameter) {
    try {
      //如果 parameter 不为null 且 keyStatement 不为 null 且 keyProperties 不为 null
      if (parameter != null && keyStatement != null && keyStatement.getKeyProperties() != null) {
        //获取 keyProperties
        String[] keyProperties = keyStatement.getKeyProperties();
        //获取全局配置
        final Configuration configuration = ms.getConfiguration();
        //获取参数的 MetaObject
        final MetaObject metaParam = configuration.newMetaObject(parameter);
        //这里不用再次判断吧
        if (keyProperties != null) {
          // Do not close keyExecutor.
          // The transaction will be closed by parent executor.
          //重新创建一个 keyExecutor 的执行器, 以免父执行器已经关闭
          //todo wolfleong 想不出父执行器什么时候关闭
          Executor keyExecutor = configuration.newExecutor(executor.getTransaction(), ExecutorType.SIMPLE);
          //执行 SelectKey 的sql
          List<Object> values = keyExecutor.query(keyStatement, parameter, RowBounds.DEFAULT, Executor.NO_RESULT_HANDLER);
          //如果没有查询到值
          if (values.size() == 0) {
            //抛异常
            throw new ExecutorException("SelectKey returned no data.");
            //如果超过一个值, 也抛异常
          } else if (values.size() > 1) {
            throw new ExecutorException("SelectKey returned more than one value.");
          } else {
            //获取 key 的 MetaObject
            MetaObject metaResult = configuration.newMetaObject(values.get(0));
            //如果只有一个key
            if (keyProperties.length == 1) {
              //如果结果的 MetaObject 中有 key 的属性
              if (metaResult.hasGetter(keyProperties[0])) {
                //从 metaResult 获取 key 的属性值, 并且设置到 metaParam 中
                setValue(metaParam, keyProperties[0], metaResult.getValue(keyProperties[0]));
              } else {
                //如果结果 metaResult 没有key的属性, 则结果就是 key 的值, 将结果设置到 metaParam 中
                // no getter for the property - maybe just a single value object
                // so try that
                setValue(metaParam, keyProperties[0], values.get(0));
              }
              //如果有多个 keyProperty
            } else {
              //处理多 keyProperty 的情况
              handleMultipleProperties(keyProperties, metaParam, metaResult);
            }
          }
        }
      }
    } catch (ExecutorException e) {
      throw e;
    } catch (Exception e) {
      throw new ExecutorException("Error selecting key or setting result to parameter object. Cause: " + e, e);
    }
  }

  private void handleMultipleProperties(String[] keyProperties,
      MetaObject metaParam, MetaObject metaResult) {
    //获承 keyColumns 的key列
    String[] keyColumns = keyStatement.getKeyColumns();

    //如果 keyColumns 不填 , 则 keyProperty 跟 keyColumn 默认是一样的
    if (keyColumns == null || keyColumns.length == 0) {
      //遍历 keyProperty, 设置对应的值
      // no key columns specified, just use the property names
      for (String keyProperty : keyProperties) {
        setValue(metaParam, keyProperty, metaResult.getValue(keyProperty));
      }
    } else {
      //如果 keyColumns 跟 keyProperties 的长度不一至, 报错
      if (keyColumns.length != keyProperties.length) {
        throw new ExecutorException("If SelectKey has key columns, the number must match the number of key properties.");
      }
      //根据 keyColumns 获取对应的值, 设置对应的 keyProperty
      for (int i = 0; i < keyProperties.length; i++) {
        setValue(metaParam, keyProperties[i], metaResult.getValue(keyColumns[i]));
      }
    }
  }

  /**
   * 设置主键到参数中
   */
  private void setValue(MetaObject metaParam, String property, Object value) {
    //如果参数有对应的属性才设置
    if (metaParam.hasSetter(property)) {
      metaParam.setValue(property, value);
    } else {
      throw new ExecutorException("No setter found for the keyProperty '" + property + "' in " + metaParam.getOriginalObject().getClass().getName() + ".");
    }
  }
}

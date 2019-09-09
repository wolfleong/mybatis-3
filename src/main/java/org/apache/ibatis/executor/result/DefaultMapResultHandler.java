/**
 *    Copyright 2009-2015 the original author or authors.
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
package org.apache.ibatis.executor.result;

import java.util.Map;

import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;

/**
 * 专门处理 Map 的结果
 * @author Clinton Begin
 */
public class DefaultMapResultHandler<K, V> implements ResultHandler<V> {

  /**
   * map 结果
   */
  private final Map<K, V> mappedResults;
  /**
   * 聚合的key: mapKey
   */
  private final String mapKey;
  /**
   * 对象工厂
   */
  private final ObjectFactory objectFactory;
  /**
   * 没作用
   */
  private final ObjectWrapperFactory objectWrapperFactory;
  /**
   * 反射工厂
   */
  private final ReflectorFactory reflectorFactory;

  @SuppressWarnings("unchecked")
  public DefaultMapResultHandler(String mapKey, ObjectFactory objectFactory, ObjectWrapperFactory objectWrapperFactory, ReflectorFactory reflectorFactory) {
    this.objectFactory = objectFactory;
    this.objectWrapperFactory = objectWrapperFactory;
    this.reflectorFactory = reflectorFactory;
    //创建 Map 对象
    this.mappedResults = objectFactory.create(Map.class);
    this.mapKey = mapKey;
  }

  @Override
  public void handleResult(ResultContext<? extends V> context) {
    //获取查询的结果
    final V value = context.getResultObject();
    //创建结果对象的 MetaObject
    final MetaObject mo = MetaObject.forObject(value, objectFactory, objectWrapperFactory, reflectorFactory);
    //根据 mapKey 获取值
    // TODO is that assignment always true?
    final K key = (K) mo.getValue(mapKey);
    //添加到结果中
    mappedResults.put(key, value);
  }

  /**
   * 获取聚合结果
   */
  public Map<K, V> getMappedResults() {
    return mappedResults;
  }
}

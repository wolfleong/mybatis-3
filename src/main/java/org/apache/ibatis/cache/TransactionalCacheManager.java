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
package org.apache.ibatis.cache;

import java.util.HashMap;
import java.util.Map;

import org.apache.ibatis.cache.decorators.TransactionalCache;

/**
 * TransactionalCache 管理器, 以 Cache 对象作为Key, TransactionalCache 作为 Value, TransactionalCache 是 Cache 的包装类
 * @author Clinton Begin
 */
public class TransactionalCacheManager {

  /**
   * Cache 和 TransactionalCache 的映射
   */
  private final Map<Cache, TransactionalCache> transactionalCaches = new HashMap<>();

  public void clear(Cache cache) {
    //根据缓存对象获取事务缓存实例并清空缓存
    getTransactionalCache(cache).clear();
  }

  public Object getObject(Cache cache, CacheKey key) {
    //获取事务缓存对象, 获取缓存
    return getTransactionalCache(cache).getObject(key);
  }

  public void putObject(Cache cache, CacheKey key, Object value) {
    //获取事务缓存, 添加缓存
    getTransactionalCache(cache).putObject(key, value);
  }

  public void commit() {
    //将事务管理器中的所有事务中的临时缓存刷到真正的缓存中
    for (TransactionalCache txCache : transactionalCaches.values()) {
      //逐个提交
      txCache.commit();
    }
  }

  public void rollback() {
    //将事务管理器操作的所有事务缓存进行回滚
    for (TransactionalCache txCache : transactionalCaches.values()) {
      //逐个回滚
      txCache.rollback();
    }
  }

  /**
   * 将 Cache 包装成 TransactionalCache 并添加到事务缓存管理器中
   */
  private TransactionalCache getTransactionalCache(Cache cache) {
    return transactionalCaches.computeIfAbsent(cache, TransactionalCache::new);
  }

}

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
package org.apache.ibatis.cache.decorators;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.apache.ibatis.cache.Cache;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.logging.LogFactory;

/**
 * 支持事务的Cache实现类, 主要用二级缓存
 * The 2nd level cache transactional buffer.
 * <p>
 * This class holds all cache entries that are to be added to the 2nd level cache during a Session.
 * Entries are sent to the cache when commit is called or discarded if the Session is rolled back.
 * Blocking cache support has been added. Therefore any get() that returns a cache miss
 * will be followed by a put() so any lock associated with the key can be released.
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class TransactionalCache implements Cache {

  private static final Log log = LogFactory.getLog(TransactionalCache.class);

  /**
   * 要包装的 Cache 对象, 就是二级缓存对象
   */
  private final Cache delegate;
  /**
   * 提交时, 清空, 默认为false
   */
  private boolean clearOnCommit;
  /**
   * 待提交的 KV 映射
   */
  private final Map<Object, Object> entriesToAddOnCommit;
  /**
   * 查不到的 Key 集合
   */
  private final Set<Object> entriesMissedInCache;

  public TransactionalCache(Cache delegate) {
    //设置代理对象
    this.delegate = delegate;
    //设置默认值
    this.clearOnCommit = false;
    this.entriesToAddOnCommit = new HashMap<>();
    this.entriesMissedInCache = new HashSet<>();
  }

  @Override
  public String getId() {
    return delegate.getId();
  }

  @Override
  public int getSize() {
    return delegate.getSize();
  }

  @Override
  public Object getObject(Object key) {
    //从 delegate 中获取 key 对应的缓存
    // issue #116
    Object object = delegate.getObject(key);
    if (object == null) {
      //如果找不到, 则添加到 entriesMissedInCache 中
      entriesMissedInCache.add(key);
    }
    //如果 clearOnCommit 为 true ，表示处于持续清空状态，则返回 null
    // issue #146
    if (clearOnCommit) {
      return null;
    } else {
      //返回查询的值
      return object;
    }
  }

  @Override
  public void putObject(Object key, Object object) {
    //暂存 KV 到 entriesToAddOnCommit 中
    entriesToAddOnCommit.put(key, object);
  }

  @Override
  public Object removeObject(Object key) {
    return null;
  }

  @Override
  public void clear() {
    //设置清空状态
    clearOnCommit = true;
    //清空 entriesToAddOnCommit
    entriesToAddOnCommit.clear();
  }

  public void commit() {
    //提交时, 如果是清空状态, 则清空 delegate
    if (clearOnCommit) {
      delegate.clear();
    }
    // 将 entriesToAddOnCommit、entriesMissedInCache 刷入 delegate 中
    flushPendingEntries();
    //重置
    reset();
  }

  public void rollback() {
    unlockMissedEntries();
    //重置事务中暂存的
    reset();
  }

  /**
   * 因为，一个 Executor 可以提交多次事务，而 TransactionalCache 需要被重用，那么就需要重置回初始状态
   */
  private void reset() {
    //清空状态重置
    clearOnCommit = false;
    //清空暂存区
    entriesToAddOnCommit.clear();
    //清空缺失的 key
    entriesMissedInCache.clear();
  }

  private void flushPendingEntries() {
    //将 entriesToAddOnCommit 中的对象刷到 delegate 中
    for (Map.Entry<Object, Object> entry : entriesToAddOnCommit.entrySet()) {
      delegate.putObject(entry.getKey(), entry.getValue());
    }
    // 将 entriesMissedInCache 刷入 delegate 中
    for (Object entry : entriesMissedInCache) {
      //如果当前事务中的缓存也不存在
      //todo wolfleong 为什么要置空
      if (!entriesToAddOnCommit.containsKey(entry)) {
        delegate.putObject(entry, null);
      }
    }
  }

  private void unlockMissedEntries() {
    //将 entriesMissedInCache 的对象, 从 delegate 删除掉
    for (Object entry : entriesMissedInCache) {
      try {
        delegate.removeObject(entry);
      } catch (Exception e) {
        log.warn("Unexpected exception while notifiying a rollback to the cache adapter."
            + "Consider upgrading your cache adapter to the latest version.  Cause: " + e);
      }
    }
  }

}

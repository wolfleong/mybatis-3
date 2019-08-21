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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import org.apache.ibatis.reflection.ArrayUtil;

/**
 * 缓存键类
 * - 因为 MyBatis 中的缓存键不是一个简单的 String ，而是通过多个对象组成。所以 CacheKey 可以理解成将多个对象放在一起，计算其缓存键
 * @author Clinton Begin
 */
public class CacheKey implements Cloneable, Serializable {

  private static final long serialVersionUID = 1146682552656046210L;

  /**
   * 单例, 空的缓存键
   */
  public static final CacheKey NULL_CACHE_KEY = new NullCacheKey();

  /**
   * 默认 multiplyer 的值
   */
  private static final int DEFAULT_MULTIPLYER = 37;
  /**
   * 默认 hashCode 的值
   */
  private static final int DEFAULT_HASHCODE = 17;

  /**
   * hashcode 的求值系数
   */
  private final int multiplier;
  /**
   * 缓存键的 hashcode
   */
  private int hashcode;
  /**
   * 校验和
   */
  private long checksum;
  /**
   * key 的对象数
   */
  private int count;
  /**
   * 计算 {@link #hashcode} 的对象的集合
   */
  // 8/21/2017 - Sonarlint flags this as needing to be marked transient.  While true if content is not serializable, this is not always true and thus should not be marked transient.
  private List<Object> updateList;

  public CacheKey() {
    this.hashcode = DEFAULT_HASHCODE;
    this.multiplier = DEFAULT_MULTIPLYER;
    this.count = 0;
    this.updateList = new ArrayList<>();
  }

  public CacheKey(Object[] objects) {
    this();
    //基于 objects , 更新相关属性
    updateAll(objects);
  }

  public int getUpdateCount() {
    return updateList.size();
  }

  public void update(Object object) {
    //获取方法参数的hashcode, 如果对象为null, hashcode为1
    int baseHashCode = object == null ? 1 : ArrayUtil.hashCode(object);

    //统计 +1
    count++;
    //统计 hashcode 的和
    checksum += baseHashCode;
    //计算新的hashcode
    baseHashCode *= count;

    hashcode = multiplier * hashcode + baseHashCode;

    //添加对象
    updateList.add(object);
  }

  public void updateAll(Object[] objects) {
    //遍历update数组对象
    for (Object o : objects) {
      update(o);
    }
  }

  @Override
  public boolean equals(Object object) {
    //如果引用相同, 则返回true
    if (this == object) {
      return true;
    }
    //如果非 CacheKey类型的对象, 肯定是false
    if (!(object instanceof CacheKey)) {
      return false;
    }

    //强转
    final CacheKey cacheKey = (CacheKey) object;

    //hashcode不同, 肯定为false
    if (hashcode != cacheKey.hashcode) {
      return false;
    }
    //校验和不同, 肯定为false
    if (checksum != cacheKey.checksum) {
      return false;
    }
    //数量不同, 肯定为false
    if (count != cacheKey.count) {
      return false;
    }

    //前面的条件都相同, 则要判断 cacheKey 中的每个对象是否相等
    for (int i = 0; i < updateList.size(); i++) {
      Object thisObject = updateList.get(i);
      Object thatObject = cacheKey.updateList.get(i);
      //如果有任意一个不相等, 则返回false
      if (!ArrayUtil.equals(thisObject, thatObject)) {
        return false;
      }
    }
    //返回true
    return true;
  }

  @Override
  public int hashCode() {
    //返回hashcode
    return hashcode;
  }

  @Override
  public String toString() {
    //返回值的合并器
    StringJoiner returnValue = new StringJoiner(":");
    //添加 hashcode
    returnValue.add(String.valueOf(hashcode));
    //添加 checksum
    returnValue.add(String.valueOf(checksum));
    //计算过的对象
    updateList.stream().map(ArrayUtil::toString).forEach(returnValue::add);
    return returnValue.toString();
  }

  @Override
  public CacheKey clone() throws CloneNotSupportedException {
    //复制一个 cacheKey, 属于浅复制
    CacheKey clonedCacheKey = (CacheKey) super.clone();
    //将updateList复制一份, 做保护性拷贝
    clonedCacheKey.updateList = new ArrayList<>(updateList);
    //返回 cacheKey
    return clonedCacheKey;
  }

}

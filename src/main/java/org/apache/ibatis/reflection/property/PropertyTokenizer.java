/**
 *    Copyright 2009-2017 the original author or authors.
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
package org.apache.ibatis.reflection.property;

import java.util.Iterator;

/**
 * 属性链分词器
 * @author Clinton Begin
 */
public class PropertyTokenizer implements Iterator<PropertyTokenizer> {
  private String name;
  /**
   * 带索引的属性名
   */
  private final String indexedName;
  private String index;
  private final String children;

  public PropertyTokenizer(String fullname) {
    int delim = fullname.indexOf('.');
    //如是有.的话
    if (delim > -1) {
      //获取.前面的属性
      name = fullname.substring(0, delim);
      //.后面的属性作为子串
      children = fullname.substring(delim + 1);
    } else {
      //如果没有., 则当前fullname即为
      name = fullname;
      //没有子串
      children = null;
    }
    //带索引的名字, 如: properties[name]
    indexedName = name;
    delim = name.indexOf('[');
    if (delim > -1) {
      //获取properties[name]的中的索引name
      index = name.substring(delim + 1, name.length() - 1);
      //获取属性名
      name = name.substring(0, delim);
    }
  }

  public String getName() {
    return name;
  }

  public String getIndex() {
    return index;
  }

  public String getIndexedName() {
    return indexedName;
  }

  public String getChildren() {
    return children;
  }

  @Override
  public boolean hasNext() {
    return children != null;
  }

  @Override
  public PropertyTokenizer next() {
    return new PropertyTokenizer(children);
  }

  @Override
  public void remove() {
    throw new UnsupportedOperationException("Remove is not supported, as it has no meaning in the context of properties.");
  }
}

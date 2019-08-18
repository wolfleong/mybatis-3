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
package org.apache.ibatis.scripting.xmltags;

import java.util.Map;

import org.apache.ibatis.parsing.GenericTokenParser;
import org.apache.ibatis.session.Configuration;

/**
 *  <foreach></foreach> 节点
 * @author Clinton Begin
 */
public class ForEachSqlNode implements SqlNode {
  public static final String ITEM_PREFIX = "__frch_";

  /**
   * 表达式求值器
   */
  private final ExpressionEvaluator evaluator;
  /**
   * 集合的表达式
   */
  private final String collectionExpression;
  private final SqlNode contents;
  /**
   * 前缀
   */
  private final String open;
  /**
   * 后缀
   */
  private final String close;
  /**
   * 分割符
   */
  private final String separator;
  /**
   * 指定项名
   */
  private final String item;
  /**
   * 索引名
   */
  private final String index;
  /**
   * 全局配置
   */
  private final Configuration configuration;

  public ForEachSqlNode(Configuration configuration, SqlNode contents, String collectionExpression, String index, String item, String open, String close, String separator) {
    //创建 ExpressionEvaluator
    this.evaluator = new ExpressionEvaluator();
    this.collectionExpression = collectionExpression;
    this.contents = contents;
    this.open = open;
    this.close = close;
    this.separator = separator;
    this.index = index;
    this.item = item;
    this.configuration = configuration;
  }

  @Override
  public boolean apply(DynamicContext context) {
    //获取参数绑定
    Map<String, Object> bindings = context.getBindings();
    //获取遍历的集合的 Iterable 对象, 用于遍历
    final Iterable<?> iterable = evaluator.evaluateIterable(collectionExpression, bindings);
    //如果没有值, 直接返回
    if (!iterable.iterator().hasNext()) {
      return true;
    }
    //用于标记是否集合的第一个
    boolean first = true;
    //首先将open插入sql中
    applyOpen(context);
    //当前for的索引编号
    int i = 0;
    //遍历列表
    for (Object o : iterable) {
      //记录原始的 DynamicContext
      DynamicContext oldContext = context;
      //如果是第一个元素或没有分割符
      if (first || separator == null) {
        //添加sql前, 插入空串
        context = new PrefixedContext(context, "");
      } else {
        //添加sql前, 插入分割符
        context = new PrefixedContext(context, separator);
      }
      //获取唯一编号
      int uniqueNumber = context.getUniqueNumber();
      //如果获取的值是 Map.Entry
      // Issue #709
      if (o instanceof Map.Entry) {
        //强转
        @SuppressWarnings("unchecked")
        Map.Entry<Object, Object> mapEntry = (Map.Entry<Object, Object>) o;
        //Map的key作为index的值
        applyIndex(context, mapEntry.getKey(), uniqueNumber);
        //Map的value作为item的值
        applyItem(context, mapEntry.getValue(), uniqueNumber);
        //如果是普通对象
      } else {
        //index是索引号, item是当前的值
        applyIndex(context, i, uniqueNumber);
        applyItem(context, o, uniqueNumber);
      }
      // #{item}
      //执行content的应用
      contents.apply(new FilteredDynamicContext(configuration, context, index, item, uniqueNumber));
      if (first) {
        first = !((PrefixedContext) context).isPrefixApplied();
      }
      //更新当前content
      context = oldContext;
      //编号增加
      i++;
    }
    //插入close到sql的结尾
    applyClose(context);
    //删除item
    context.getBindings().remove(item);
    //删除index
    context.getBindings().remove(index);
    return true;
  }

  /**
   * 绑定索引名相关的变量到 context
   * 如:
   *  ${index} -> val
   *  __frch_${index}_${i} -> val
   */
  private void applyIndex(DynamicContext context, Object o, int i) {
    if (index != null) {
      context.bind(index, o);
      context.bind(itemizeItem(index, i), o);
    }
  }

  /**
   * 绑定item名相关变量到 context
   *  如:
   *     ${item} -> val
   *     __frch_${item}_${i} -> val
   */
  private void applyItem(DynamicContext context, Object o, int i) {
    if (item != null) {
      context.bind(item, o);
      context.bind(itemizeItem(item, i), o);
    }
  }

  /**
   * 插入 open
   */
  private void applyOpen(DynamicContext context) {
    if (open != null) {
      context.appendSql(open);
    }
  }

  private void applyClose(DynamicContext context) {
    if (close != null) {
      context.appendSql(close);
    }
  }

  /**
   * 拼接指定前缀的item名
   * 如: __frch_${item|index}_${i}
   */
  private static String itemizeItem(String item, int i) {
    return ITEM_PREFIX + item + "_" + i;
  }

  /**
   * 实现子节点访问 <foreach /> 标签中的变量的替换的
   */
  private static class FilteredDynamicContext extends DynamicContext {
    private final DynamicContext delegate;
    /**
     * 唯一标识
     */
    private final int index;
    /**
     * 索引变量 index
     */
    private final String itemIndex;
    /**
     * 集合项变量 item
     */
    private final String item;

    public FilteredDynamicContext(Configuration configuration,DynamicContext delegate, String itemIndex, String item, int i) {
      super(configuration, null);
      this.delegate = delegate;
      this.index = i;
      this.itemIndex = itemIndex;
      this.item = item;
    }

    @Override
    public Map<String, Object> getBindings() {
      return delegate.getBindings();
    }

    @Override
    public void bind(String name, Object value) {
      delegate.bind(name, value);
    }

    @Override
    public String getSql() {
      return delegate.getSql();
    }

    @Override
    public void appendSql(String sql) {
      GenericTokenParser parser = new GenericTokenParser("#{", "}", content -> {
        // 将对 item 的访问，替换成 itemizeItem(item, index) 即 #{item} => __frch_${item}_${i}
        String newContent = content.replaceFirst("^\\s*" + item + "(?![^.,:\\s])", itemizeItem(item, index));
        //如果itemIndex不为null且newContent与原来一样, 即没有替换
        if (itemIndex != null && newContent.equals(content)) {
          newContent = content.replaceFirst("^\\s*" + itemIndex + "(?![^.,:\\s])", itemizeItem(itemIndex, index));
        }
        return "#{" + newContent + "}";
      });

      delegate.appendSql(parser.parse(sql));
    }

    @Override
    public int getUniqueNumber() {
      return delegate.getUniqueNumber();
    }

  }

  /**
   * 主要作用是, 插入sql前先插入前缀
   * - 拦截或代理模式, 包装也算
   */
  private class PrefixedContext extends DynamicContext {
    private final DynamicContext delegate;
    private final String prefix;
    private boolean prefixApplied;

    public PrefixedContext(DynamicContext delegate, String prefix) {
      super(configuration, null);
      this.delegate = delegate;
      this.prefix = prefix;
      this.prefixApplied = false;
    }

    public boolean isPrefixApplied() {
      return prefixApplied;
    }

    @Override
    public Map<String, Object> getBindings() {
      return delegate.getBindings();
    }

    @Override
    public void bind(String name, Object value) {
      delegate.bind(name, value);
    }

    @Override
    public void appendSql(String sql) {
      //未处理前缀, 且sql不为空
      if (!prefixApplied && sql != null && sql.trim().length() > 0) {
        //先插入前缀到 DynamicContext
        delegate.appendSql(prefix);
        prefixApplied = true;
      }
      //再将真正的sql插入 DynamicContext
      delegate.appendSql(sql);
    }

    @Override
    public String getSql() {
      return delegate.getSql();
    }

    @Override
    public int getUniqueNumber() {
      return delegate.getUniqueNumber();
    }
  }

}

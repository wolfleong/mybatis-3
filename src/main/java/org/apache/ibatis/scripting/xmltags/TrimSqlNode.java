/**
 *    Copyright 2009-2018 the original author or authors.
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.ibatis.session.Configuration;

/**
 * <trim></trim> 节点内容封装的SqlNode
 * trim 标签是 Where和Set的基础
 * @author Clinton Begin
 */
public class TrimSqlNode implements SqlNode {

  /**
   * 子节点
   */
  private final SqlNode contents;
  /**
   * 前缀
   */
  private final String prefix;
  /**
   * 后缀
   */
  private final String suffix;
  /**
   * 要被删除的前缀
   */
  private final List<String> prefixesToOverride;
  /**
   * 要被删除的后缀
   */
  private final List<String> suffixesToOverride;
  /**
   * 全局配置
   */
  private final Configuration configuration;

  public TrimSqlNode(Configuration configuration, SqlNode contents, String prefix, String prefixesToOverride, String suffix, String suffixesToOverride) {
    this(configuration, contents, prefix, parseOverrides(prefixesToOverride), suffix, parseOverrides(suffixesToOverride));
  }

  protected TrimSqlNode(Configuration configuration, SqlNode contents, String prefix, List<String> prefixesToOverride, String suffix, List<String> suffixesToOverride) {
    this.contents = contents;
    this.prefix = prefix;
    this.prefixesToOverride = prefixesToOverride;
    this.suffix = suffix;
    this.suffixesToOverride = suffixesToOverride;
    this.configuration = configuration;
  }

  @Override
  public boolean apply(DynamicContext context) {
    //创建 FilteredDynamicContext 对象
    FilteredDynamicContext filteredDynamicContext = new FilteredDynamicContext(context);
    //执行 contents 的 apply方法, 将所有子节点的sql先解析到 filteredDynamicContext
    boolean result = contents.apply(filteredDynamicContext);
    //执行 FilteredDynamicContext 的应用, 将sql按trim配置处理, 然后再插入 DynamicContext
    filteredDynamicContext.applyAll();
    return result;
  }

  /**
   * 以 | 来切割字符串变列表
   */
  private static List<String> parseOverrides(String overrides) {
    //如果 overrides 不为null
    if (overrides != null) {
      //用 | 来切割
      final StringTokenizer parser = new StringTokenizer(overrides, "|", false);
      //用存结果的列表
      final List<String> list = new ArrayList<>(parser.countTokens());
      //遍历
      while (parser.hasMoreTokens()) {
        //添加前, 进行大写处理
        list.add(parser.nextToken().toUpperCase(Locale.ENGLISH));
      }
      //返回
      return list;
    }
    //如果 overrides 是null, 则返回空列表
    return Collections.emptyList();
  }

  /**
   * 继承 DynamicContext 类，支持 trim 逻辑的 DynamicContext 实现类
   * - 有点像拦截器, 在appendSql入 DynamicContext 之前, 先对sql做trim操作
   */
  private class FilteredDynamicContext extends DynamicContext {
    /**
     * 委托的 DynamicContext 对象
     */
    private DynamicContext delegate;
    /**
     * 是否 prefix 已经被应用
     */
    private boolean prefixApplied;
    /**
     * 是否 suffix 已经被应用
     */
    private boolean suffixApplied;
    /**
     * StringBuilder对象
     */
    private StringBuilder sqlBuffer;

    public FilteredDynamicContext(DynamicContext delegate) {
      super(configuration, null);
      this.delegate = delegate;
      this.prefixApplied = false;
      this.suffixApplied = false;
      this.sqlBuffer = new StringBuilder();
    }

    public void applyAll() {
      //将已经解析好子节点的sql去除前后空格
      sqlBuffer = new StringBuilder(sqlBuffer.toString().trim());
      //将sql变大写
      String trimmedUppercaseSql = sqlBuffer.toString().toUpperCase(Locale.ENGLISH);
      //如果子sql非空
      if (trimmedUppercaseSql.length() > 0) {
        //处理前缀
        applyPrefix(sqlBuffer, trimmedUppercaseSql);
        //处理后缀
        applySuffix(sqlBuffer, trimmedUppercaseSql);
      }
      delegate.appendSql(sqlBuffer.toString());
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
    public int getUniqueNumber() {
      return delegate.getUniqueNumber();
    }

    @Override
    public void appendSql(String sql) {
      sqlBuffer.append(sql);
    }

    @Override
    public String getSql() {
      return delegate.getSql();
    }

    /**
     * 处理前缀
     */
    private void applyPrefix(StringBuilder sql, String trimmedUppercaseSql) {
      //如果还没处理前缀
      if (!prefixApplied) {
        //设置已经处理
        prefixApplied = true;
        //如果要删除的前缀不为空
        if (prefixesToOverride != null) {
          //遍历要删除的前缀
          for (String toRemove : prefixesToOverride) {
            //如果是以指定前缀开头的
            if (trimmedUppercaseSql.startsWith(toRemove)) {
              //删除前缀并退出循环
              sql.delete(0, toRemove.trim().length());
              break;
            }
          }
        }
        //如果要拼接的前缀不为null
        if (prefix != null) {
          //在sql前面插入空格
          sql.insert(0, " ");
          //插入前缀
          sql.insert(0, prefix);
        }
      }
    }

    /**
     * 处理后缀
     */
    private void applySuffix(StringBuilder sql, String trimmedUppercaseSql) {
      //如果还没处理
      if (!suffixApplied) {
        //标记为已经处理
        suffixApplied = true;
        //要删除的后缀列表不为空
        if (suffixesToOverride != null) {
          //遍历后缀
          for (String toRemove : suffixesToOverride) {
            //如果sql是以后缀结尾的
            if (trimmedUppercaseSql.endsWith(toRemove) || trimmedUppercaseSql.endsWith(toRemove.trim())) {
              //删除后缀并退出
              int start = sql.length() - toRemove.trim().length();
              int end = sql.length();
              sql.delete(start, end);
              break;
            }
          }
        }
        //如果要拼接的后缀不为null
        if (suffix != null) {
          //插入空格
          sql.append(" ");
          //插入后缀
          sql.append(suffix);
        }
      }
    }

  }

}

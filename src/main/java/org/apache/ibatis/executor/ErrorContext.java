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
package org.apache.ibatis.executor;

/**
 * ErrorContext 错误上下文
 * - 主要是通过 ThreadLocal 来缓存当前线程执行过的一些关键信息, 以便在异常抛出的时候, 将这些异常信息跟异常绑定在一起,
 *   最终可以打印异常和异常的上下文信息
 * @author Clinton Begin
 */
public class ErrorContext {

  /**
   * 行分割符
   */
  private static final String LINE_SEPARATOR = System.getProperty("line.separator","\n");
  /**
   * ThreadLocal 对象
   */
  private static final ThreadLocal<ErrorContext> LOCAL = new ThreadLocal<>();

  /**
   * 暂存上一层的 ErrorContext , 主要处理多层业务的情况, 如: KeyGenerator
   * - 主要是在 KeyGenerator 执行 sql 时, 要将当前 MappedStatement 的 ErrorContext 隐藏起来,
   *   并创建新的 ErrorContext_1 来记录 KeyGenerator 的 MappedStatement 的信息,
   *   KeyGenerator 执行过程中如果抛出异常, 则用 ErrorContext_1 的信息组合打日志
   * - 当 KeyGenerator 执行完 sql 后, 将 ErrorContext_1 移除, 并将当前线程变量得新设置成 ErrorContext
   */
  private ErrorContext stored;
  /**
   * 执行的 mapper 资源文件
   */
  private String resource;
  /**
   * 执行操作的描述
   */
  private String activity;
  /**
   * 关键上下文的参数对象
   */
  private String object;
  /**
   * 异常信息
   */
  private String message;
  /**
   * 执行的 sql
   */
  private String sql;
  /**
   * 绑定的异常类, Java 异常日志
   */
  private Throwable cause;

  private ErrorContext() {
  }

  /**
   * 获取 ErrorContext 实例, 从 ThreadLocal 中获取, 如果没找到则创建一个, 并且放到 ThreadLocal 中
   */
  public static ErrorContext instance() {
    //从 ThreadLocal 中获取
    ErrorContext context = LOCAL.get();
    //如果没找到
    if (context == null) {
      //创建一个
      context = new ErrorContext();
      //放到 ThreadLocal 中
      LOCAL.set(context);
    }
    //返回
    return context;
  }

  /**
   * 创建新的 ErrorContext_new , 并将旧的 ErrorContext_old 存到新的 ErrorContext 的 stored 中
   */
  public ErrorContext store() {
    ErrorContext newContext = new ErrorContext();
    newContext.stored = this;
    LOCAL.set(newContext);
    return LOCAL.get();
  }

  /**
   * 用当前 ErrorContext_new 的 stored 替换掉 ThreadLocal 的缓存, 相当于退回上一层 ErrorContext_old
   */
  public ErrorContext recall() {
    //如果 stored 不为null
    if (stored != null) {
      //替换
      LOCAL.set(stored);
      stored = null;
    }
    return LOCAL.get();
  }

  public ErrorContext resource(String resource) {
    this.resource = resource;
    return this;
  }

  public ErrorContext activity(String activity) {
    this.activity = activity;
    return this;
  }

  public ErrorContext object(String object) {
    this.object = object;
    return this;
  }

  public ErrorContext message(String message) {
    this.message = message;
    return this;
  }

  public ErrorContext sql(String sql) {
    this.sql = sql;
    return this;
  }

  public ErrorContext cause(Throwable cause) {
    this.cause = cause;
    return this;
  }

  /**
   * 重置, 清空相关属性, 并助删除 ThreadLocal 中相关线程对象
   */
  public ErrorContext reset() {
    resource = null;
    activity = null;
    object = null;
    message = null;
    sql = null;
    cause = null;
    LOCAL.remove();
    return this;
  }

  /**
   * 格式化异常输出
   */
  @Override
  public String toString() {
    StringBuilder description = new StringBuilder();

    // message
    if (this.message != null) {
      description.append(LINE_SEPARATOR);
      description.append("### ");
      description.append(this.message);
    }

    // resource
    if (resource != null) {
      description.append(LINE_SEPARATOR);
      description.append("### The error may exist in ");
      description.append(resource);
    }

    // object
    if (object != null) {
      description.append(LINE_SEPARATOR);
      description.append("### The error may involve ");
      description.append(object);
    }

    // activity
    if (activity != null) {
      description.append(LINE_SEPARATOR);
      description.append("### The error occurred while ");
      description.append(activity);
    }

    // activity
    if (sql != null) {
      description.append(LINE_SEPARATOR);
      description.append("### SQL: ");
      description.append(sql.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim());
    }

    // cause
    if (cause != null) {
      description.append(LINE_SEPARATOR);
      description.append("### Cause: ");
      description.append(cause.toString());
    }

    return description.toString();
  }

}

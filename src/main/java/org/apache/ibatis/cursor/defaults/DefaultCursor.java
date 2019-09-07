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
package org.apache.ibatis.cursor.defaults;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.ibatis.cursor.Cursor;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.executor.resultset.ResultSetWrapper;
import org.apache.ibatis.mapping.ResultMap;
import org.apache.ibatis.session.ResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;

/**
 * Cursor 接口的默认实现
 * This is the default implementation of a MyBatis Cursor.
 * This implementation is not thread safe.
 *
 * @author Guillaume Darmont / guillaume@dropinocean.com
 */
public class DefaultCursor<T> implements Cursor<T> {

  // ResultSetHandler stuff
  private final DefaultResultSetHandler resultSetHandler;
  private final ResultMap resultMap;
  private final ResultSetWrapper rsw;
  private final RowBounds rowBounds;
  /**
   * ResultHandler 的实现类
   */
  private final ObjectWrapperResultHandler<T> objectWrapperResultHandler = new ObjectWrapperResultHandler<>();

  /**
   * CursorIterator 对象，游标迭代器。
   */
  private final CursorIterator cursorIterator = new CursorIterator();
  /**
   * 是否开始迭代
   */
  private boolean iteratorRetrieved;

  /**
   * 游标状态
   */
  private CursorStatus status = CursorStatus.CREATED;
  /**
   * 已完成的映射的行数
   */
  private int indexWithRowBound = -1;

  private enum CursorStatus {

    /**
     * 创建状态, 没开始遍历 ResultSet
     * A freshly created cursor, database ResultSet consuming has not started.
     */
    CREATED,
    /**
     * 打开状态 , 已经开始遍历 ResultSet
     * A cursor currently in use, database ResultSet consuming has started.
     */
    OPEN,
    /**
     * 已经关闭, 并未完全消费
     * A closed cursor, not fully consumed.
     */
    CLOSED,
    /**
     * 已经关闭,  并且完全消费
     * A fully consumed cursor, a consumed cursor is always closed.
     */
    CONSUMED
  }

  public DefaultCursor(DefaultResultSetHandler resultSetHandler, ResultMap resultMap, ResultSetWrapper rsw, RowBounds rowBounds) {
    this.resultSetHandler = resultSetHandler;
    this.resultMap = resultMap;
    this.rsw = rsw;
    this.rowBounds = rowBounds;
  }

  @Override
  public boolean isOpen() {
    //是否是打开状态
    return status == CursorStatus.OPEN;
  }

  @Override
  public boolean isConsumed() {
    //是否已经消费完成
    return status == CursorStatus.CONSUMED;
  }

  @Override
  public int getCurrentIndex() {
    //获取当前实际索引
    return rowBounds.getOffset() + cursorIterator.iteratorIndex;
  }

  /**
   * 通过 iteratorRetrieved 属性，保证有且仅返回一次 cursorIterator 对象。
   */
  @Override
  public Iterator<T> iterator() {
    //如果已经开始迭代, 抛出异常
    if (iteratorRetrieved) {
      throw new IllegalStateException("Cannot open more than one iterator on a Cursor");
    }
    //如果已经关闭, 则抛出异常
    if (isClosed()) {
      throw new IllegalStateException("A Cursor is already closed.");
    }
    //设置已经开始迭代
    iteratorRetrieved = true;
    //返回迭代器
    return cursorIterator;
  }

  @Override
  public void close() {
    //如果已经关闭, 则不处理
    if (isClosed()) {
      return;
    }

    //获取 ResultSet
    ResultSet rs = rsw.getResultSet();
    try {
      //如果 ResultSet 不为 null
      if (rs != null) {
        //关闭 ResultSet
        rs.close();
      }
    } catch (SQLException e) {
      // ignore
    } finally {
      //设置关闭状态
      status = CursorStatus.CLOSED;
    }
  }

  protected T fetchNextUsingRowBound() {
    //遍历下一条记录
    T result = fetchNextObjectFromDatabase();
    //循环跳到指定的 rowBound.offset 索引
    while (result != null && indexWithRowBound < rowBounds.getOffset()) {
      result = fetchNextObjectFromDatabase();
    }
    //返回结果对象
    return result;
  }

  protected T fetchNextObjectFromDatabase() {
    //如果已经关闭, 则返回 null
    if (isClosed()) {
      return null;
    }

    try {
      //设置游标已经打开
      status = CursorStatus.OPEN;
      //如果 ResultSet 未关闭
      if (!rsw.getResultSet().isClosed()) {
        //用 ResultSetHandler 处理结果对象, 获取完对象后会暂停对象, 返回 Cursor 如果要支持嵌套结果集, 则要配置 resultOrdered 才行
        resultSetHandler.handleRowValues(rsw, resultMap, objectWrapperResultHandler, RowBounds.DEFAULT, null);
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }

    //获取当前对象
    T next = objectWrapperResultHandler.result;
    //如果对象不为空
    if (next != null) {
      //索引加1
      indexWithRowBound++;
    }
    //如果对象为空, 或者已经映射的对象索引达到rowBounds指定最大的位置
    // No more object or limit reached
    if (next == null || getReadItemsCount() == rowBounds.getOffset() + rowBounds.getLimit()) {
      //关闭
      close();
      //设置已经完成消费
      status = CursorStatus.CONSUMED;
    }
    //重置 objectWrapperResultHandler 的结果对象
    objectWrapperResultHandler.result = null;

    //返回结果
    return next;
  }

  private boolean isClosed() {
    //如果状态是关闭或消费完成都算是关闭
    return status == CursorStatus.CLOSED || status == CursorStatus.CONSUMED;
  }

  private int getReadItemsCount() {
    //获取已经读取的行数
    return indexWithRowBound + 1;
  }

  private static class ObjectWrapperResultHandler<T> implements ResultHandler<T> {

    /**
     * 结果对象
     */
    private T result;

    @Override
    public void handleResult(ResultContext<? extends T> context) {
      //获取结果对象
      this.result = context.getResultObject();
      //暂停
      //通过调用 ResultContext#stop() 方法，暂停 DefaultResultSetHandler 在向下遍历下一条记录，
      // 从而实现每次在调用 CursorIterator#hasNext() 方法，只遍历一行 ResultSet 的记录。
      // 可以在看看 DefaultResultSetHandler#shouldProcessMoreRows(ResultContext<?> context, RowBounds rowBounds) 方法
      context.stop();
    }
  }

  private class CursorIterator implements Iterator<T> {

    /**
     * 结果对象
     * Holder for the next object to be returned.
     */
    T object;

    /**
     * 索引位置
     * Index of objects returned using next(), and as such, visible to users.
     */
    int iteratorIndex = -1;

    @Override
    public boolean hasNext() {
      // 如果 object 为空, 则遍历下一条记录
      if (object == null) {
        object = fetchNextUsingRowBound();
      }
      //判断结果对象是否非空
      return object != null;
    }

    @Override
    public T next() {
      //获取结果对象
      // Fill next with object fetched from hasNext()
      T next = object;

      //如果结果对象是空
      if (next == null) {
        //获取下一条记录
        next = fetchNextUsingRowBound();
      }

      //如果结果对象不为空
      if (next != null) {
        //重置
        object = null;
        //索引加 1
        iteratorIndex++;
        //返回结果对象
        return next;
      }
      //没有对象则抛出异常
      throw new NoSuchElementException();
    }

    @Override
    public void remove() {
      throw new UnsupportedOperationException("Cannot remove element from Cursor");
    }
  }
}

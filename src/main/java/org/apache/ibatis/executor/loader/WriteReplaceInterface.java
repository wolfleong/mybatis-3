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
package org.apache.ibatis.executor.loader;

import java.io.ObjectStreamException;

/**
 * @author Eduardo Macarron
 */
public interface WriteReplaceInterface {

  /**
   * - Serializable还有两个标记接口方法可以实现序列化对象的替换，即writeReplace和readResolve
   * - 如果实现了writeReplace方法后，那么在序列化时会先调用writeReplace方法将当前对象替换成另一个对象（该方法会返回替换后的对象）并将其写入流中
   * 实现writeReplace就不要实现writeObject了，因为writeReplace的返回值会被自动写入输出流中，就相当于自动这样调用：writeObject(writeReplace());
   */
  Object writeReplace() throws ObjectStreamException;

}

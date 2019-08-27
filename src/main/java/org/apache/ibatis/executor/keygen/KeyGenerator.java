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
package org.apache.ibatis.executor.keygen;

import java.sql.Statement;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;

/**
 * 主键生成器接口
 * - 实际上，KeyGenerator 类的命名虽然包含 Generator ，但是目前 MyBatis 默认的 KeyGenerator 实现类，都是基于数据库来实现主键自增的功能
 *
 * @Options(useGeneratedKeys = true, keyProperty = "id")
 * @Insert({"insert into country (countryname,countrycode) values (#{countryname},#{countrycode})"})
 * int insertBean(Country country);
 * - country 方法参数，就是一个 parameter 参数
 * - KeyGenerator 在获取到主键后，会设置回 parameter 参数的对应属性
 * @author Clinton Begin
 */
public interface KeyGenerator {

  //SQL 执行前
  void processBefore(Executor executor, MappedStatement ms, Statement stmt, Object parameter);

  //SQL 执行后
  void processAfter(Executor executor, MappedStatement ms, Statement stmt, Object parameter);

}

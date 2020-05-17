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
package org.apache.ibatis.plugin;

import java.util.Properties;

/**
 * 拦截器接口
 *
 * 我们在MyBatis配置了一个插件，在运行发生了什么
 *  - 所有可能被拦截的处理类都会生成一个代理
 *  - 处理类代理在执行对应方法时，判断要不要执行插件中的拦截方法
 *  - 执行插接中的拦截方法后，推进目标的执行
 *  - 如果有N个插件，就有N个代理，每个代理都要执行上面的逻辑。这里面的层层代理要多次生成动态代理，是比较影响性能的。
 *    虽然能指定插件拦截的位置，但这个是在执行方法时动态判断，初始化的时候就是简单的把插件包装到了所有可以拦截的地方。
 *
 * 因此，在编写插件时需注意以下几个原则：
 *  - 不编写不必要的插件;
 *  - 实现plugin方法时判断一下目标类型，是本插件要拦截的对象才执行Plugin.wrap方法，否者直接返回目标本身，这样可以减少目标被代理的次数。
 *
 * @author Clinton Begin
 */
public interface Interceptor {

  /**
   * 拦截方法
   * @param invocation 调用信息
   * @return 调用结果
   * @throws Throwable 发成的异常
   */
  Object intercept(Invocation invocation) throws Throwable;

  /**
   * 应用插件。如应用成功，则会创建目标对象的代理对象
   * @param target 目标对象
   * @return 应用的结果对象，可以是代理对象，也可以是 target 对象，也可以是任意对象。具体的，看代码实现
   */
  default Object plugin(Object target) {
    //这个方法会在 4 个组件中都调用一次的, 所以, 在写插件时, 最好优化一下, 判断是否 target 是否要拦截的对象, 才调用 Plugin.wrap
    return Plugin.wrap(target, this);
  }

  /**
   * 设置拦截器属性
   */
  default void setProperties(Properties properties) {
    // NOP
  }

}

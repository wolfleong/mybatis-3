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
package org.apache.ibatis.builder.xml;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.builder.IncompleteElementException;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.parsing.PropertyParser;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 将sql语句中<include></include>标签转换成<sql></sql>节点的内容,
 * 在替换之前先将所有的<include></include>及其子节点下的所有属性和文本都替换动态变量
 * @author Frank D. Martinez [mnesarco]
 */
public class XMLIncludeTransformer {

  /**
   * 全局配置
   */
  private final Configuration configuration;
  /**
   * Mapper构建助手
   */
  private final MapperBuilderAssistant builderAssistant;

  public XMLIncludeTransformer(Configuration configuration, MapperBuilderAssistant builderAssistant) {
    this.configuration = configuration;
    this.builderAssistant = builderAssistant;
  }

  /**
   *
   * @param source java 原生的Node
   */
  public void applyIncludes(Node source) {
    //创建一个配置复本
    Properties variablesContext = new Properties();
    //获取全局的配置
    Properties configurationVariables = configuration.getVariables();
    //如果 configurationVariables 不为null, 拷贝
    Optional.ofNullable(configurationVariables).ifPresent(variablesContext::putAll);
    //调用真正的 <include> 来转化
    applyIncludes(source, variablesContext, false);
  }

  /**
   * - <select|insert|update|delete>下的文本是不做动态变量替换的, 只有<include>下的所有包括子节点属性值和文本是是做了动态变量替换
   * Recursively apply includes through all SQL fragments.
   * @param source Include node in DOM tree
   * @param variablesContext Current context for static variables with values
   */
  private void applyIncludes(Node source, final Properties variablesContext, boolean included) {
    //如果当前节点是<include>
    if (source.getNodeName().equals("include")) {
      //获取到引用的sql节点
      Node toInclude = findSqlFragment(getStringAttribute(source, "refid"), variablesContext);
      //获取include节点中配置的属性
      Properties toIncludeContext = getVariablesContext(source, variablesContext);
      //对sql节点递归调用, 因为<sql>节点中也有可能包括<include>节点
      applyIncludes(toInclude, toIncludeContext, true);
      //toInclude的sql节点有可能不是当前xml文件的, 所有要做这一步判断
      //如果当前这个toInclude的这个节点的源文档跟source的节点的源文档不一样, 代表他们来自不同的xml文件
      if (toInclude.getOwnerDocument() != source.getOwnerDocument()) {
        //在source节点的文档中引用toInclude这个节点
        toInclude = source.getOwnerDocument().importNode(toInclude, true);
      }
      //用sql节点替换掉<include>这个节点
      source.getParentNode().replaceChild(toInclude, source);
      //替换完后, 要删除<sql>节点, 但保留sql的所有子节点
      while (toInclude.hasChildNodes()) {
        //insertBefore这个方法会将toInclude节点的子节点firstChild从toInclude移除掉, 并放在toInclude前面,
        // 每次移除一个, toInclude的子节点就少一个
        toInclude.getParentNode().insertBefore(toInclude.getFirstChild(), toInclude);
      }
      //删除toInclude节点, 即 <sql> 节点
      toInclude.getParentNode().removeChild(toInclude);
      //如果source不是<include>节点, 但是Node节点的话
    } else if (source.getNodeType() == Node.ELEMENT_NODE) {
      //如果是include为true, 代表上一层已经解析了include节点了, 如果variablesContext不为空, 则需要将所有变量值替换动态参数
      if (included && !variablesContext.isEmpty()) {
        // replace variables in attribute values
        //获取属性map
        NamedNodeMap attributes = source.getAttributes();
        //遍历属性
        for (int i = 0; i < attributes.getLength(); i++) {
          //获取属性
          Node attr = attributes.item(i);
          //将所有属性值都进行一次动态变量替换
          attr.setNodeValue(PropertyParser.parse(attr.getNodeValue(), variablesContext));
        }
      }
      //获取source的所有子节点, 遍历
      NodeList children = source.getChildNodes();
      for (int i = 0; i < children.getLength(); i++) {
        //递归调用, 继续转换
        applyIncludes(children.item(i), variablesContext, included);
      }
      //如果include为true, 且当前节点只有文本, 那么当variablesContext不变null时, 替换掉文本中的动态变量
    } else if (included && source.getNodeType() == Node.TEXT_NODE
        && !variablesContext.isEmpty()) {
      // replace variables in text node
      //替换掉文本中的动态变量
      source.setNodeValue(PropertyParser.parse(source.getNodeValue(), variablesContext));
    }
  }

  /**
   * - 根据sql引用id, 查询sql片段
   * -  <include></include>标签中的refid是可以定义动态变量的${id}
   * - 返回的节点是<sql id='abc'>...</sql>
   */
  private Node findSqlFragment(String refid, Properties variables) {
    //替换refid上的动态变量
    refid = PropertyParser.parse(refid, variables);
    //refid拼接上namespace
    refid = builderAssistant.applyCurrentNamespace(refid, true);
    try {
      //从全局的sql片段中根据id获取sql节点
      XNode nodeToInclude = configuration.getSqlFragments().get(refid);
      //对节点进行保护性拷贝
      return nodeToInclude.getNode().cloneNode(true);
    } catch (IllegalArgumentException e) {
      throw new IncompleteElementException("Could not find SQL statement to include with refid '" + refid + "'", e);
    }
  }

  /**
   * 获取属性的值
   */
  private String getStringAttribute(Node node, String name) {
    //获取属性的值
    return node.getAttributes().getNamedItem(name).getNodeValue();
  }

  /**
   * 获取<include><property></property></include>中的所有属性值, 并且和全局变量做替换
   * Read placeholders and their values from include node definition.
   * @param node Include node instance
   * @param inheritedVariablesContext Current context used for replace variables in new variables values
   * @return variables context from include instance (no inherited values)
   */
  private Properties getVariablesContext(Node node, Properties inheritedVariablesContext) {
    //记录include内容property的键和值
    //<include><property name="person" value="wolfleong"/></include>
    Map<String, String> declaredProperties = null;
    //获取子节点列表
    NodeList children = node.getChildNodes();
    //遍历子节点
    for (int i = 0; i < children.getLength(); i++) {
      //获取子节点
      Node n = children.item(i);
      //如果节点是node元素 也就是<property>节点
      if (n.getNodeType() == Node.ELEMENT_NODE) {
        //获取name值
        String name = getStringAttribute(n, "name");
        //获取value的值, 并且用全局变量替换
        // Replace variables inside
        String value = PropertyParser.parse(getStringAttribute(n, "value"), inheritedVariablesContext);
        //如果 declaredProperties 没有初始化, 则初始化一下
        if (declaredProperties == null) {
          declaredProperties = new HashMap<>();
        }
        //记录当前键值对
        if (declaredProperties.put(name, value) != null) {
          throw new BuilderException("Variable " + name + " defined twice in the same include definition");
        }
      }
    }
    //如果没有找到<include>的变量配置
    if (declaredProperties == null) {
      //返回全局变量
      return inheritedVariablesContext;
    } else {
      //如果有<include>的变量配置, 则合并全局变量, 并返回
      Properties newProperties = new Properties();
      newProperties.putAll(inheritedVariablesContext);
      newProperties.putAll(declaredProperties);
      return newProperties;
    }
  }
}

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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * XML脚本动态解析器, 负责将SQL解析成SqlSource对象
 * @author Clinton Begin
 */
public class XMLScriptBuilder extends BaseBuilder {

  /**
   * XML节点
   */
  private final XNode context;
  /**
   * 是否动态, 有${}或者有节点就算动态
   */
  private boolean isDynamic;
  /**
   * 参数类型
   */
  private final Class<?> parameterType;
  /**
   * NodeNodeHandler 的映射
   */
  private final Map<String, NodeHandler> nodeHandlerMap = new HashMap<>();

  public XMLScriptBuilder(Configuration configuration, XNode context) {
    this(configuration, context, null);
  }

  public XMLScriptBuilder(Configuration configuration, XNode context, Class<?> parameterType) {
    super(configuration);
    this.context = context;
    this.parameterType = parameterType;
    //调用方法初始化
    initNodeHandlerMap();
  }


  private void initNodeHandlerMap() {
    //key是我们xml标签, 每种标签对应一种 NodeHandler
    nodeHandlerMap.put("trim", new TrimHandler());
    nodeHandlerMap.put("where", new WhereHandler());
    nodeHandlerMap.put("set", new SetHandler());
    nodeHandlerMap.put("foreach", new ForEachHandler());
    nodeHandlerMap.put("if", new IfHandler());
    nodeHandlerMap.put("choose", new ChooseHandler());
    nodeHandlerMap.put("when", new IfHandler());
    nodeHandlerMap.put("otherwise", new OtherwiseHandler());
    nodeHandlerMap.put("bind", new BindHandler());
  }

  /**
   * 将sql解析成 SqlSource 对象
   */
  public SqlSource parseScriptNode() {
    //解析 Sql
    MixedSqlNode rootSqlNode = parseDynamicTags(context);
    SqlSource sqlSource;
    //如果是动态, 就创建 DynamicSqlSource
    if (isDynamic) {
      sqlSource = new DynamicSqlSource(configuration, rootSqlNode);
    } else {
      //不是动态创建 RawSqlSource
      sqlSource = new RawSqlSource(configuration, rootSqlNode, parameterType);
    }
    return sqlSource;
  }

  /**
   * 解析动态标签
   * - 解析SQL成 MixedSqlNode 对象
   * - 只处理解析 node 的所有子节点
   */
  protected MixedSqlNode parseDynamicTags(XNode node) {
    //保存SqlNode的列表
    List<SqlNode> contents = new ArrayList<>();
    //获取当前XNode的所有子节点, 为什么不用XNode.getChildren呢, 因为XNode那个是只获取非文本的子节点
    //当前这个是需要所有的节点, 包括文本节点, CDATA文本节点
    NodeList children = node.getNode().getChildNodes();
    //遍历子节点
    for (int i = 0; i < children.getLength(); i++) {
      //获取子节点, 并且创建XNode对象
      XNode child = node.newXNode(children.item(i));
      //如果当前节点是CDATA内容或文本内容
      if (child.getNode().getNodeType() == Node.CDATA_SECTION_NODE || child.getNode().getNodeType() == Node.TEXT_NODE) {
        //获取节点的文本
        String data = child.getStringBody("");
        //创建 TextSqlNode 对象
        TextSqlNode textSqlNode = new TextSqlNode(data);
        //文本节点是否动态
        if (textSqlNode.isDynamic()) {
          //添加到列表
          contents.add(textSqlNode);
          //是否动态, 只要有${}动态变量的
          isDynamic = true;
        } else {
          //如果非动态, 则添加一个纯静态态的SqlNode
          contents.add(new StaticTextSqlNode(data));
        }
        //如果子节点是普通节点
      } else if (child.getNode().getNodeType() == Node.ELEMENT_NODE) { // issue #628
        //获取节点的名称
        String nodeName = child.getNode().getNodeName();
        //获取相关Node的处理器
        NodeHandler handler = nodeHandlerMap.get(nodeName);
        //如果找不到, 表示这个节点不存在
        if (handler == null) {
          throw new BuilderException("Unknown element <" + nodeName + "> in SQL statement.");
        }
        //用节点处理器处理当前了节点
        handler.handleNode(child, contents);
        //设置动态, 只要有节点的
        isDynamic = true;
      }
    }
    //创建一个 MixedSqlNode 返回
    return new MixedSqlNode(contents);
  }

  private interface NodeHandler {
    /**
     * 处理Node
     * @param nodeToHandle 要处理的 XNode 节点
     * @param targetContents 目标的 SqlNode 数组。实际上，被处理的 XNode 节点会创建成对应的 SqlNode 对象，添加到 targetContents 中
     */
    void handleNode(XNode nodeToHandle, List<SqlNode> targetContents);
  }

  /**
   * 处理<bind></bind>节点的处理器
   * - bind 元素可以从 OGNL 表达式中创建一个变量并将其绑定到上下文
   * - <bind name="pattern" value="'%' + _parameter.getTitle() + '%'" />
   */
  private class BindHandler implements NodeHandler {
    public BindHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      //获取name
      final String name = nodeToHandle.getStringAttribute("name");
      //获取表达式
      final String expression = nodeToHandle.getStringAttribute("value");
      //创建 VarDeclSqlNode 对象
      final VarDeclSqlNode node = new VarDeclSqlNode(name, expression);
      //添加到 targetContents 中
      targetContents.add(node);
    }
  }

  /**
   * 处理 <trim></trim> 标签
   */
  private class TrimHandler implements NodeHandler {
    public TrimHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      //解析 nodeToHandle 的子节点
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      //获取 prefix
      String prefix = nodeToHandle.getStringAttribute("prefix");
      //获取 prefixOverrides
      String prefixOverrides = nodeToHandle.getStringAttribute("prefixOverrides");
      //获取 suffix
      String suffix = nodeToHandle.getStringAttribute("suffix");
      //获取 suffixOverrides
      String suffixOverrides = nodeToHandle.getStringAttribute("suffixOverrides");
      //创建一个 TrimSqlNode 节点
      TrimSqlNode trim = new TrimSqlNode(configuration, mixedSqlNode, prefix, prefixOverrides, suffix, suffixOverrides);
      //添加到 targetContents
      targetContents.add(trim);
    }
  }

  /**
   * 处理 <where></where>
   */
  private class WhereHandler implements NodeHandler {
    public WhereHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      //处理 nodeToHandle 的子节点
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      //创建 WhereSqlNode 节点
      WhereSqlNode where = new WhereSqlNode(configuration, mixedSqlNode);
      //添加到 targetContents
      targetContents.add(where);
    }
  }

  /**
   * 处理<set></set>
   */
  private class SetHandler implements NodeHandler {
    public SetHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      //处理 nodeToHandle 的子节点
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      //创建 WhereSqlNode 节点
      SetSqlNode set = new SetSqlNode(configuration, mixedSqlNode);
      //添加到 targetContents
      targetContents.add(set);
    }
  }

  /**
   * 处理<foreach></foreach>
   */
  private class ForEachHandler implements NodeHandler {
    public ForEachHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      //处理 nodeToHandle 的子节点
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      //获取相关属性值
      String collection = nodeToHandle.getStringAttribute("collection");
      String item = nodeToHandle.getStringAttribute("item");
      String index = nodeToHandle.getStringAttribute("index");
      String open = nodeToHandle.getStringAttribute("open");
      String close = nodeToHandle.getStringAttribute("close");
      String separator = nodeToHandle.getStringAttribute("separator");
      //创建 ForEachSqlNode
      ForEachSqlNode forEachSqlNode = new ForEachSqlNode(configuration, mixedSqlNode, collection, index, item, open, close, separator);
      //添加到 targetContents
      targetContents.add(forEachSqlNode);
    }
  }

  /**
   * 处理 <if></if>
   */
  private class IfHandler implements NodeHandler {
    public IfHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      //处理 nodeToHandle 的子节点
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      //获取参数
      String test = nodeToHandle.getStringAttribute("test");
      //创建 IfSqlNode
      IfSqlNode ifSqlNode = new IfSqlNode(mixedSqlNode, test);
      targetContents.add(ifSqlNode);
    }
  }

  /**
   * 处理 <otherwise></otherwise>
   */
  private class OtherwiseHandler implements NodeHandler {
    public OtherwiseHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      //创建 MixedSqlNode 节点
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      //添加到 targetContents
      targetContents.add(mixedSqlNode);
    }
  }

  /**
   * 处理 <choose></choose>
   */
  private class ChooseHandler implements NodeHandler {
    public ChooseHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      //保存 when 的SqlNode, when节点相当天 if
      List<SqlNode> whenSqlNodes = new ArrayList<>();
      //保存 otherwise 的 SqlNode
      List<SqlNode> otherwiseSqlNodes = new ArrayList<>();
      //处理子节点
      handleWhenOtherwiseNodes(nodeToHandle, whenSqlNodes, otherwiseSqlNodes);
      SqlNode defaultSqlNode = getDefaultSqlNode(otherwiseSqlNodes);
      //创建 ChooseSqlNode 节点
      ChooseSqlNode chooseSqlNode = new ChooseSqlNode(whenSqlNodes, defaultSqlNode);
      //添加到 targetContents 列表
      targetContents.add(chooseSqlNode);
    }

    private void handleWhenOtherwiseNodes(XNode chooseSqlNode, List<SqlNode> ifSqlNodes, List<SqlNode> defaultSqlNodes) {
      //获取所有的非文本节点
      List<XNode> children = chooseSqlNode.getChildren();
      //遍历
      for (XNode child : children) {
        //获取节点的名称
        String nodeName = child.getNode().getNodeName();
        //获取处理器
        NodeHandler handler = nodeHandlerMap.get(nodeName);
        //when节点也是 IfHandler处理的
        if (handler instanceof IfHandler) {
          handler.handleNode(child, ifSqlNodes);
          //分开处理
        } else if (handler instanceof OtherwiseHandler) {
          handler.handleNode(child, defaultSqlNodes);
        }
      }
    }

    /**
     * 获取 otherwise 其他节点, 只能有一个
     */
    private SqlNode getDefaultSqlNode(List<SqlNode> defaultSqlNodes) {
      SqlNode defaultSqlNode = null;
      //如果列表只有一个
      if (defaultSqlNodes.size() == 1) {
        //获取返回
        defaultSqlNode = defaultSqlNodes.get(0);
        //如果有多个, 报错
      } else if (defaultSqlNodes.size() > 1) {
        throw new BuilderException("Too many default (otherwise) elements in choose statement.");
      }
      //一个都没有, 返回 null
      return defaultSqlNode;
    }
  }

}

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
package org.apache.ibatis.scripting.xmltags;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.apache.ibatis.session.Configuration;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Clinton Begin
 */
public class XMLScriptBuilder extends BaseBuilder {

  private final XNode context;
  private boolean isDynamic;
  private final Class<?> parameterType;
  private final Map<String, NodeHandler> nodeHandlerMap = new HashMap<String, NodeHandler>();

  public XMLScriptBuilder(Configuration configuration, XNode context) {
    this(configuration, context, null);
  }

  public XMLScriptBuilder(Configuration configuration, XNode context, Class<?> parameterType) {
    super(configuration);
    this.context = context;
    this.parameterType = parameterType;
    initNodeHandlerMap();
  }


  private void initNodeHandlerMap() {
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

  public SqlSource parseScriptNode() {
    // 说明：MyBatis中对<select>、<insert>、<delete>、<update>等这样的节点的解析是封装成一棵树的形式，
    // 其利用是【组合模式】来实现这颗树的构建，其分为叶子节点和非叶子节点，其均实现SqlNode接口，
    // 比如对于<select>节点，其下面可能会有<where>节点，而<where>节点下可能又会有若干的<if>节点，
    // 这样子的话，对整个<select>标签的解析，那么<select>会对应根节点，<where>节点对应树根的一个子节点，
    // 而其下的<if>又对应若干个叶子节点，支持树构建完成，那么整个标签也就解析完成了。

    // Tips：在构建这棵树的过程中，会传递相应的DynamicContext，可以看到SqlNode接口的方法apply的入参为DynamicContext对象,
    // 这个DynamicContext对象是用来记录这棵树中各个SQL节点的SQL片段，
    // 因此在完成树的构建后，调用DynamicContext的getSql方法就可以获得相应的SQL语句了，
    // 之后就是利用SqlSource对着SQL语句作封装！
    // 不过千万要注意：通过DynamicContext所得到的SQL语句还不是可以立即执行的，
    // 因为还需要相应的参数，如果有占位符的话，还需要占位符的替换等操作，
    // 所以获取真正可执行的SQL是通过SqlSource对象的getBoundSql方法，该方法的入参是执行该SQL语句所需要的参数，
    // 比如根据id查询用户记录，那么此时的入参就是用户id。

    // 解析并创建SQL树, 其中树根节点类型为MixedSqlNode，其可以容纳若干个子节点
    MixedSqlNode rootSqlNode = parseDynamicTags(context);
    SqlSource sqlSource = null;
    // 判断是否为动态SQL，并创建相应的SqlSource对象
    if (isDynamic) {
      // Tips：对于DynamicSqlSource，其所实现的SqlSource接口的getBoundSql方法中创建了SqlSourceBuilder，
      // 并利用这SqlSourceBuilder对SQL树（SqlNode树）做进一步处理，
      // 因为动态SQL中涉及各种占位符啥的，所以一方面是对SQL语句中的#{}占位符中定义的属性进行解析，
      // 另一方面是将SQL语句中的#{}替换成“?”占位符。
      sqlSource = new DynamicSqlSource(configuration, rootSqlNode);
    } else {
      sqlSource = new RawSqlSource(configuration, rootSqlNode, parameterType);
    }
    return sqlSource;
  }

  protected MixedSqlNode parseDynamicTags(XNode node) {
    List<SqlNode> contents = new ArrayList<SqlNode>();
    NodeList children = node.getNode().getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      XNode child = node.newXNode(children.item(i));
      // 情形一：节点为文本节点
      if (child.getNode().getNodeType() == Node.CDATA_SECTION_NODE || child.getNode().getNodeType() == Node.TEXT_NODE) {
        String data = child.getStringBody("");
        TextSqlNode textSqlNode = new TextSqlNode(data);
        if (textSqlNode.isDynamic()) {
          contents.add(textSqlNode);
          isDynamic = true;
        } else {
          contents.add(new StaticTextSqlNode(data));
        }
      }
      // 情形二： 节点为一个标签，那么也意味着当前解析的节点为动态SQL
      else if (child.getNode().getNodeType() == Node.ELEMENT_NODE) { // issue #628
        // 获取当前节点标签的名称，比如对于<where>，那么nodeName为“where”，
        // 如果对于<trim>，则nodeName为“trim”
        String nodeName = child.getNode().getNodeName();
        // 获取相应的NodeHandler，这是XMLScriptBuilder下的一个内部接口类，
        // 同时XMLScriptBuilder下还有NodeHandler接口的各实现类，
        // 其各实现类所实现的handleNode方法主要用于生成相应的SqlNode对象，
        // 比如对于<where>，则最终生成的SqlNode对象为WhereSqlNode！
        // ！！！ 这就是将具体不同节点的解析和所对应的SqlNode的创建封装到了各个NodeHandler实现类中了！
        NodeHandler handler = nodeHandlerMap.get(nodeName);
        if (handler == null) {
          throw new BuilderException("Unknown element <" + nodeName + "> in SQL statement.");
        }
        // 入参child指向当前正在解析的节点，解析后的内容存入到contents中
        handler.handleNode(child, contents);
        isDynamic = true;
      }
    }
    // 最后类型为MixedSqlNode的根节点，囊括了所有的子节点
    return new MixedSqlNode(contents);
  }

  private interface NodeHandler {
    void handleNode(XNode nodeToHandle, List<SqlNode> targetContents);
  }

  private class BindHandler implements NodeHandler {
    public BindHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      final String name = nodeToHandle.getStringAttribute("name");
      final String expression = nodeToHandle.getStringAttribute("value");
      final VarDeclSqlNode node = new VarDeclSqlNode(name, expression);
      targetContents.add(node);
    }
  }

  private class TrimHandler implements NodeHandler {
    public TrimHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      String prefix = nodeToHandle.getStringAttribute("prefix");
      String prefixOverrides = nodeToHandle.getStringAttribute("prefixOverrides");
      String suffix = nodeToHandle.getStringAttribute("suffix");
      String suffixOverrides = nodeToHandle.getStringAttribute("suffixOverrides");
      TrimSqlNode trim = new TrimSqlNode(configuration, mixedSqlNode, prefix, prefixOverrides, suffix, suffixOverrides);
      targetContents.add(trim);
    }
  }

  private class WhereHandler implements NodeHandler {
    public WhereHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      WhereSqlNode where = new WhereSqlNode(configuration, mixedSqlNode);
      targetContents.add(where);
    }
  }

  private class SetHandler implements NodeHandler {
    public SetHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      SetSqlNode set = new SetSqlNode(configuration, mixedSqlNode);
      targetContents.add(set);
    }
  }

  private class ForEachHandler implements NodeHandler {
    public ForEachHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      String collection = nodeToHandle.getStringAttribute("collection");
      String item = nodeToHandle.getStringAttribute("item");
      String index = nodeToHandle.getStringAttribute("index");
      String open = nodeToHandle.getStringAttribute("open");
      String close = nodeToHandle.getStringAttribute("close");
      String separator = nodeToHandle.getStringAttribute("separator");
      ForEachSqlNode forEachSqlNode = new ForEachSqlNode(configuration, mixedSqlNode, collection, index, item, open, close, separator);
      targetContents.add(forEachSqlNode);
    }
  }

  private class IfHandler implements NodeHandler {
    public IfHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      // 又递归调用parseDynamicTags进行<if>下的子节点的解析
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      String test = nodeToHandle.getStringAttribute("test");
      IfSqlNode ifSqlNode = new IfSqlNode(mixedSqlNode, test);
      targetContents.add(ifSqlNode);
    }
  }

  private class OtherwiseHandler implements NodeHandler {
    public OtherwiseHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      MixedSqlNode mixedSqlNode = parseDynamicTags(nodeToHandle);
      targetContents.add(mixedSqlNode);
    }
  }

  private class ChooseHandler implements NodeHandler {
    public ChooseHandler() {
      // Prevent Synthetic Access
    }

    @Override
    public void handleNode(XNode nodeToHandle, List<SqlNode> targetContents) {
      List<SqlNode> whenSqlNodes = new ArrayList<SqlNode>();
      List<SqlNode> otherwiseSqlNodes = new ArrayList<SqlNode>();
      handleWhenOtherwiseNodes(nodeToHandle, whenSqlNodes, otherwiseSqlNodes);
      SqlNode defaultSqlNode = getDefaultSqlNode(otherwiseSqlNodes);
      ChooseSqlNode chooseSqlNode = new ChooseSqlNode(whenSqlNodes, defaultSqlNode);
      targetContents.add(chooseSqlNode);
    }

    private void handleWhenOtherwiseNodes(XNode chooseSqlNode, List<SqlNode> ifSqlNodes, List<SqlNode> defaultSqlNodes) {
      List<XNode> children = chooseSqlNode.getChildren();
      for (XNode child : children) {
        String nodeName = child.getNode().getNodeName();
        NodeHandler handler = nodeHandlerMap.get(nodeName);
        if (handler instanceof IfHandler) {
          handler.handleNode(child, ifSqlNodes);
        } else if (handler instanceof OtherwiseHandler) {
          handler.handleNode(child, defaultSqlNodes);
        }
      }
    }

    private SqlNode getDefaultSqlNode(List<SqlNode> defaultSqlNodes) {
      SqlNode defaultSqlNode = null;
      if (defaultSqlNodes.size() == 1) {
        defaultSqlNode = defaultSqlNodes.get(0);
      } else if (defaultSqlNodes.size() > 1) {
        throw new BuilderException("Too many default (otherwise) elements in choose statement.");
      }
      return defaultSqlNode;
    }
  }

}

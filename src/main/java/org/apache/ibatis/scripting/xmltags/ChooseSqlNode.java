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

import java.util.List;

/**
 * @author Clinton Begin
 */
// 对应<choose>节点
public class ChooseSqlNode implements SqlNode {
  // <otherwise>节点对应的SqlNode
  private final SqlNode defaultSqlNode;
  // 对应的是<choose>节点下的一系列的<when>节点，每个<when>节点对应IfSqlNode
  private final List<SqlNode> ifSqlNodes;

  public ChooseSqlNode(List<SqlNode> ifSqlNodes, SqlNode defaultSqlNode) {
    this.ifSqlNodes = ifSqlNodes;
    this.defaultSqlNode = defaultSqlNode;
  }

  @Override
  public boolean apply(DynamicContext context) {
    for (SqlNode sqlNode : ifSqlNodes) {
      if (sqlNode.apply(context)) {
        return true;
      }
    }
    if (defaultSqlNode != null) {
      defaultSqlNode.apply(context);
      return true;
    }
    return false;
  }
}

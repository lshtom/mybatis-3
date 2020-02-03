/**
 *    Copyright 2009-2016 the original author or authors.
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
package org.apache.ibatis.parsing;

import java.util.Properties;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class PropertyParser {

  private static final String KEY_PREFIX = "org.apache.ibatis.parsing.PropertyParser.";
  /**
   * The special property key that indicate whether enable a default value on placeholder.
   * <p>
   *   The default value is {@code false} (indicate disable a default value on placeholder)
   *   If you specify the {@code true}, you can specify key and default value on placeholder (e.g. {@code ${db.username:postgres}}).
   * </p>
   * @since 3.4.2
   */
  public static final String KEY_ENABLE_DEFAULT_VALUE = KEY_PREFIX + "enable-default-value";

  /**
   * The special property key that specify a separator for key and default value on placeholder.
   * <p>
   *   The default separator is {@code ":"}.
   * </p>
   * @since 3.4.2
   */
  public static final String KEY_DEFAULT_VALUE_SEPARATOR = KEY_PREFIX + "default-value-separator";

  private static final String ENABLE_DEFAULT_VALUE = "false";
  // 默认的值分割字符
  private static final String DEFAULT_VALUE_SEPARATOR = ":";

  private PropertyParser() {
    // Prevent Instantiation
  }

  public static String parse(String string, Properties variables) {
    VariableTokenHandler handler = new VariableTokenHandler(variables);
    // 此处创建GenericTokenParser，委托GenericTokenParser的parse方法进行解析
    GenericTokenParser parser = new GenericTokenParser("${", "}", handler);
    return parser.parse(string);
  }

  private static class VariableTokenHandler implements TokenHandler {
    private final Properties variables;
    private final boolean enableDefaultValue;
    private final String defaultValueSeparator;

    private VariableTokenHandler(Properties variables) {
      this.variables = variables;
      this.enableDefaultValue = Boolean.parseBoolean(getPropertyValue(KEY_ENABLE_DEFAULT_VALUE, ENABLE_DEFAULT_VALUE));
      // 默认的分隔符是“:”
      this.defaultValueSeparator = getPropertyValue(KEY_DEFAULT_VALUE_SEPARATOR, DEFAULT_VALUE_SEPARATOR);
    }

    private String getPropertyValue(String key, String defaultValue) {
      return (variables == null) ? defaultValue : variables.getProperty(key, defaultValue);
    }

    @Override
    public String handleToken(String content) {
      // 说明：variables为Properties类型，
      // 其中包含了所配置的各种属性值，值的来源可为xxx.properties文件，
      // 比如xxx.properties文件中的:
      // username=root;
      // password=admin;
      // 而在mybatis-config.xml文件中可能使用了${username}或${username:hello}，
      // 后者与前者的区别是后者还指定了一个默认值，
      // 那么该方法的逻辑就是以${username}中的username为Key去属性variables中查找相应的值，
      // 当然如果查找不到，且当前指定了默认值（${username:hello}），那么则使用默认值，
      // 否则返回的都是variables中的值。

      if (variables != null) {
        String key = content;
        if (enableDefaultValue) {
          final int separatorIndex = content.indexOf(defaultValueSeparator);
          String defaultValue = null;
          // separatorIndex大于等于0，意味着是${xxx:yyyy}的情形
          if (separatorIndex >= 0) {
            // 获取占位符中的Key，如${username:hello}中的“username”
            key = content.substring(0, separatorIndex);
            // 获取默认值，即如${username:hello}中的“hello”
            defaultValue = content.substring(separatorIndex + defaultValueSeparator.length());
          }
          if (defaultValue != null) {
            // 尝试从variables中根据Key获取相应的属性值，如果获取不到则返回默认值
            return variables.getProperty(key, defaultValue);
          }
        }
        // 没有默认值，则如果variables中有该Key，则尝试获取并返回
        if (variables.containsKey(key)) {
          return variables.getProperty(key);
        }
      }
      // 最差情况，完全找不到这样的属性，则再加回open token、close token后返回
      return "${" + content + "}";
    }
  }

}

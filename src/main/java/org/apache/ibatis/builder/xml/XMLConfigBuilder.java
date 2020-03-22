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
package org.apache.ibatis.builder.xml;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.TypeHandler;

import javax.sql.DataSource;
import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
// 说明：一般上使用Mybatis的时候，都要写一个MyBatis配置文件mybatis-config.xml，
// 同时还有若干的映射配置文件以及相应的Mapper接口，
// 这一切的头其实是从mybatis-config.xml开始的（在这配置文件中进行各种MyBatis参数的配置，还包括映射文件的路径、mapper接口包名等的配置），
// 解析这mybatis-config.xml配置文件主要是从<configuration>标签开始解析的，
// 针对这<configuration>标签的解析就是利用了XMLConfigBuilder，
// 在解析的过程中，对于映射配置文件（<mapper>标签）的解析则使用XMLMapperBuilder类，
// XMLMapperBuilder类在解析<mapper>标签下的内容时对于SQL语句的解析则使用XMLStatementBuilder。
public class XMLConfigBuilder extends BaseBuilder {

  // 标识是否已经解析过mybatis-config.xml配置文件
  private boolean parsed;
  // 用于解析mybatis-config.xml配置文件的XPathParser对象
  private final XPathParser parser;
  // 标识<environment>配置的名称，默认读取<environment>标签的default属性
  private String environment;
  // 负责创建和缓存Reflector对象
  private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

  public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
  }

  public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
  }

  public XMLConfigBuilder(Reader reader, String environment, Properties props) {
    this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
    // 说明：第一个入参XPathParser中封装了mybatis-config.xml配置文件的Reader，
    // 同时创建Configuration对象，主调方SqlSessionFactoryBuilder调用了XMLConfigBuilder的parse方法后会获取到Configuration对象，
    // 此时的Configuration对象中已经包含了解析出各种配置信息（来自配置文件）。
    super(new Configuration());
    ErrorContext.instance().resource("SQL Mapper Configuration");
    this.configuration.setVariables(props);
    this.parsed = false;
    this.environment = environment;
    this.parser = parser;
  }

  /**
   * 解析mybatis-config.xml配置文件的入口
   * 说明：这其实是【建造者模式】的使用，只是名字上不那么直观罢了，
   * 所建造的其实是Configuration对象，
   * 在parseConfiguration方法中又依次调用了各方法完成相应标签的解析并将解析结果填充到Configuration对象中，
   * 这就相当于build方法中又依次调用具体的buildXXX方法进行各部分的建造。
   * 启发：对于xxx解析处理（需要经过多个步骤的），比如经过xxx才能得到某某的这一类问题，
   * 就可以去使用建造者模式，提供公有的build方法以及getProduct方法，
   * 然后build方法中调用若干私有的buildPartX方法，
   * 外部调用者只要先创建建造者对象，然后调用其build方法，再调用getProduct方法就可以得到所相应的对象（被建造的对象）了。
   */
  public Configuration parse() {
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    parsed = true;
    // 在XML配置文件中查找<configuration>节点
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
  }

  private void parseConfiguration(XNode root) {
    // 说明：依次解析<configuration>标签下的各个子节点
    try {
      //issue #117 read properties first
      propertiesElement(root.evalNode("properties"));
      Properties settings = settingsAsProperties(root.evalNode("settings"));
      loadCustomVfs(settings);
      typeAliasesElement(root.evalNode("typeAliases"));
      pluginElement(root.evalNode("plugins"));
      objectFactoryElement(root.evalNode("objectFactory"));
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      settingsElement(settings);
      // read it after objectFactory and objectWrapperFactory issue #631
      environmentsElement(root.evalNode("environments"));
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));
      typeHandlerElement(root.evalNode("typeHandlers"));
      // 解析<mappers>节点，该节点的内容主要是指明Mybatis应该去哪些位置去查找映射配置文件，以及使用了@Mapper注解标注的接口
      mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }

  private Properties settingsAsProperties(XNode context) {
    if (context == null) {
      return new Properties();
    }
    Properties props = context.getChildrenAsProperties();
    // Check that all settings are known to the configuration class
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    for (Object key : props.keySet()) {
      if (!metaConfig.hasSetter(String.valueOf(key))) {
        throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
      }
    }
    return props;
  }

  private void loadCustomVfs(Properties props) throws ClassNotFoundException {
    String value = props.getProperty("vfsImpl");
    if (value != null) {
      String[] clazzes = value.split(",");
      for (String clazz : clazzes) {
        if (!clazz.isEmpty()) {
          @SuppressWarnings("unchecked")
          Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
          configuration.setVfsImpl(vfsImpl);
        }
      }
    }
  }

  private void typeAliasesElement(XNode parent) {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        if ("package".equals(child.getName())) {
          String typeAliasPackage = child.getStringAttribute("name");
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
        } else {
          String alias = child.getStringAttribute("alias");
          String type = child.getStringAttribute("type");
          try {
            Class<?> clazz = Resources.classForName(type);
            if (alias == null) {
              typeAliasRegistry.registerAlias(clazz);
            } else {
              typeAliasRegistry.registerAlias(alias, clazz);
            }
          } catch (ClassNotFoundException e) {
            throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
          }
        }
      }
    }
  }

  private void pluginElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        String interceptor = child.getStringAttribute("interceptor");
        Properties properties = child.getChildrenAsProperties();
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
        interceptorInstance.setProperties(properties);
        configuration.addInterceptor(interceptorInstance);
      }
    }
  }

  private void objectFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties properties = context.getChildrenAsProperties();
      ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
      factory.setProperties(properties);
      configuration.setObjectFactory(factory);
    }
  }

  private void objectWrapperFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
      configuration.setObjectWrapperFactory(factory);
    }
  }

  private void reflectorFactoryElement(XNode context) throws Exception {
    if (context != null) {
       String type = context.getStringAttribute("type");
       ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
       configuration.setReflectorFactory(factory);
    }
  }

  private void propertiesElement(XNode context) throws Exception {
    if (context != null) {
      // 解析<properties>的子节点（获取<property>标签）的name和value属性，并记录到Properties中
      Properties defaults = context.getChildrenAsProperties();
      // 解析<properties>的resource和url属性
      String resource = context.getStringAttribute("resource");
      String url = context.getStringAttribute("url");
      // 两者不能同时存在，否则抛出异常
      if (resource != null && url != null) {
        throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }
      if (resource != null) {
        defaults.putAll(Resources.getResourceAsProperties(resource));
      } else if (url != null) {
        defaults.putAll(Resources.getUrlAsProperties(url));
      }
      // 与Configuration对象中的variables集合合并
      Properties vars = configuration.getVariables();
      if (vars != null) {
        defaults.putAll(vars);
      }
      // 更新XPathParser和Configuration的variables字段
      parser.setVariables(defaults);
      configuration.setVariables(defaults);
    }
  }

  private void settingsElement(Properties props) throws Exception {
    configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
    configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
    configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
    configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
    configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
    configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
    configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
    configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
    configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
    configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
    configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
    configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
    configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
    configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
    configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
    configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
    configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
    configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
    configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
    @SuppressWarnings("unchecked")
    Class<? extends TypeHandler> typeHandler = (Class<? extends TypeHandler>)resolveClass(props.getProperty("defaultEnumTypeHandler"));
    configuration.setDefaultEnumTypeHandler(typeHandler);
    configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
    configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
    configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
    configuration.setLogPrefix(props.getProperty("logPrefix"));
    @SuppressWarnings("unchecked")
    Class<? extends Log> logImpl = (Class<? extends Log>)resolveClass(props.getProperty("logImpl"));
    configuration.setLogImpl(logImpl);
    configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
  }

  private void environmentsElement(XNode context) throws Exception {
    if (context != null) {
      if (environment == null) {
        environment = context.getStringAttribute("default");
      }
      for (XNode child : context.getChildren()) {
        String id = child.getStringAttribute("id");
        if (isSpecifiedEnvironment(id)) {
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
          DataSource dataSource = dsFactory.getDataSource();
          Environment.Builder environmentBuilder = new Environment.Builder(id)
              .transactionFactory(txFactory)
              .dataSource(dataSource);
          configuration.setEnvironment(environmentBuilder.build());
        }
      }
    }
  }

  private void databaseIdProviderElement(XNode context) throws Exception {
    DatabaseIdProvider databaseIdProvider = null;
    if (context != null) {
      String type = context.getStringAttribute("type");
      // awful patch to keep backward compatibility
      if ("VENDOR".equals(type)) {
          type = "DB_VENDOR";
      }
      Properties properties = context.getChildrenAsProperties();
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
      databaseIdProvider.setProperties(properties);
    }
    Environment environment = configuration.getEnvironment();
    if (environment != null && databaseIdProvider != null) {
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
      configuration.setDatabaseId(databaseId);
    }
  }

  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
  }

  private DataSourceFactory dataSourceElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a DataSourceFactory.");
  }

  private void typeHandlerElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        if ("package".equals(child.getName())) {
          String typeHandlerPackage = child.getStringAttribute("name");
          typeHandlerRegistry.register(typeHandlerPackage);
        } else {
          String javaTypeName = child.getStringAttribute("javaType");
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          String handlerTypeName = child.getStringAttribute("handler");
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
              typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
            }
          } else {
            typeHandlerRegistry.register(typeHandlerClass);
          }
        }
      }
    }
  }

  private void mapperElement(XNode parent) throws Exception {
    // 说明：该方法是Mapper接口、映射配置文件解析的总入口，
    // 进行Maper接口和映射配置文件解析有两个方向：
    // 1）方向1：读取Mybatis配置文件中<mappers>标签下的<mapper>标签的resource或url属性（这些属性都是指向映射配置文件的路径），
    // 先进行映射配置文件的读取解析，然后根据namespace来找到相应的Mapper接口并进行解析，这种方式下映射配置文件名与mapper接口名不同并不影响绑定；
    // 2）方向2：读取Mybatis配置文件中<mappers>标签下的<package>标签的name属性或者<mapper>标签的class属性（这些属性都是指向Mapper接口的全路径名），
    // 先找到接口并进行Mapper接口的解析，然后根据Mapper接口的全路径名去反推出相应的映射配置文件路径，并找到进行加载解析，
    // 这种方式下映射配置文件名需要与Maper接口名相同，否则会绑定失败。

    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        if ("package".equals(child.getName())) {
          // 获取Mapper接口所在的包名
          String mapperPackage = child.getStringAttribute("name");
          // 扫描该包下的Mapper接口，并注册到MapperRegistry中
          configuration.addMappers(mapperPackage);
        } else {
          String resource = child.getStringAttribute("resource");
          String url = child.getStringAttribute("url");
          String mapperClass = child.getStringAttribute("class");
          if (resource != null && url == null && mapperClass == null) {
            ErrorContext.instance().resource(resource);
            // 创建XMLMapperBuilder对象，获取Mapper配置文件并进行解析
            InputStream inputStream = Resources.getResourceAsStream(resource);
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
            mapperParser.parse();
          } else if (resource == null && url != null && mapperClass == null) {
            ErrorContext.instance().resource(url);
            // 创建XMLMapperBuilder对象，获取Mapper配置文件并进行解析
            InputStream inputStream = Resources.getUrlAsStream(url);
            // XMLMapperBuilder也是【建造者模式】的应用，XMLMapperBuilder的parse方法中封装了解析映射配置文件的细节
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
            mapperParser.parse();
          } else if (resource == null && url == null && mapperClass != null) {
            // 获取Mapper接口，并注册到MapperRegistry中
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            configuration.addMapper(mapperInterface);
          } else {
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

  private boolean isSpecifiedEnvironment(String id) {
    if (environment == null) {
      throw new BuilderException("No environment specified.");
    } else if (id == null) {
      throw new BuilderException("Environment requires an id attribute.");
    } else if (environment.equals(id)) {
      return true;
    }
    return false;
  }

}

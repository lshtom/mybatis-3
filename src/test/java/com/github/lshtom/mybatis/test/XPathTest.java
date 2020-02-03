package com.github.lshtom.mybatis.test;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpression;
import javax.xml.xpath.XPathFactory;

/**
 * XPath单元测试
 */
@SuppressWarnings("all")
public class XPathTest {

    @Test
    public void testXPath() throws Exception {
        DocumentBuilderFactory documentBuilderFactory = DocumentBuilderFactory.newInstance();

        // 开启验证
        documentBuilderFactory.setValidating(true);
        documentBuilderFactory.setNamespaceAware(false);
        documentBuilderFactory.setIgnoringComments(true);
        documentBuilderFactory.setIgnoringElementContentWhitespace(false);
        documentBuilderFactory.setCoalescing(false);
        documentBuilderFactory.setExpandEntityReferences(true);

        // 创建DocumentBuilder
        DocumentBuilder documentBuilder = documentBuilderFactory.newDocumentBuilder();
        // 设置异常处理对象
        documentBuilder.setErrorHandler(new ErrorHandler() {
            @Override
            public void warning(SAXParseException exception) throws SAXException {
                System.out.println("warn:" + exception.getMessage());
            }

            @Override
            public void error(SAXParseException exception) throws SAXException {
                System.out.println("error:" + exception.getMessage());
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
                System.out.println("fataError:" + exception.getMessage());
            }
        });

        // 将文档加载到一个Document对象中
        Document doc = documentBuilder.parse("src/test/resources/com/github/lshtom/mybatis/inventory.xml");
        // 创建XPathFactory
        XPathFactory factory = XPathFactory.newInstance();
        // 创建XPath
        XPath xPath = factory.newXPath();
        // 编译XPath表达式
        XPathExpression expr = xPath.compile("//book[author='Neal Stephenson']/title/text()");

        System.out.println("查询作者为Neal Stephenson的图书的标题：");
        Object result = expr.evaluate(doc, XPathConstants.NODESET);
        NodeList nodes = (NodeList) result;
        print(nodes);

        System.out.println("查询97年之后的图书的标题：");
        nodes = (NodeList) xPath.evaluate("//book[@year>1997]/title/text()", doc, XPathConstants.NODESET);
        print(nodes);

        System.out.println("查询97年之后的图书的属性和标题：");
        nodes = (NodeList) xPath.evaluate("//book[@year>1997]/@*|//book[@year>1997]/title/text()", doc, XPathConstants.NODESET);
        print(nodes);
    }

    private void print(NodeList nodes) {
        for (int ix = 0; ix < nodes.getLength(); ix++) {
            System.out.println(nodes.item(ix).getNodeValue());
        }
    }
}

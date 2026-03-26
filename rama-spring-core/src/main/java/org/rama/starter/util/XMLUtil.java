package org.rama.starter.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import org.w3c.dom.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class XMLUtil {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOGGER = LoggerFactory.getLogger(XMLUtil.class);

    private XMLUtil() {
    }

    public static Document loadXMLFromURL(String url) throws Exception {
        InputStream xmlStream = StreamUtil.inputStreamFromURL(url);
        DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
        Document doc = dBuilder.parse(xmlStream);
        doc.getDocumentElement().normalize();
        return doc;
    }

    public static Document convertToXML(Map<String, Object> map, String rootName) throws Exception {
        XmlMapper xmlMapper = new XmlMapper();
        Object valueToSerialize = map;
        String resolvedRootName = rootName;
        if (resolvedRootName == null) {
            if (map.size() == 1) {
                resolvedRootName = map.keySet().iterator().next();
                valueToSerialize = map.values().iterator().next();
            } else {
                resolvedRootName = "root";
            }
        }

        String xmlOutput = xmlMapper.writer().withRootName(resolvedRootName).writeValueAsString(valueToSerialize);
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(new ByteArrayInputStream(xmlOutput.getBytes()));
    }

    public static Document convertToXML(String json, String rootName) throws Exception {
        Map<String, Object> map = new HashMap<>();
        JsonNode rootNode = MAPPER.readTree(json);
        if (rootNode.isObject()) {
            map = MAPPER.convertValue(rootNode, new TypeReference<>() {});
        } else if (rootNode.isArray()) {
            List<Map<String, Object>> list = MAPPER.convertValue(rootNode, new TypeReference<>() {});
            map = Map.of(rootName == null ? "root" : rootName, list);
            return convertToXML(map, null);
        }
        return convertToXML(map, rootName);
    }

    public static Document convertToXML(Map<String, Object> map) throws Exception {
        return convertToXML(map, null);
    }

    public static Document convertToXML(String json) throws Exception {
        return convertToXML(json, null);
    }

    public static String documentToString(Document doc) {
        try {
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(doc), new StreamResult(writer));
            return writer.getBuffer().toString();
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage(), ex);
            return null;
        }
    }

    public static ByteArrayOutputStream documentToOutputStream(Document document) {
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(javax.xml.transform.OutputKeys.INDENT, "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.transform(new DOMSource(document), new StreamResult(outputStream));
            return outputStream;
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage(), ex);
            return null;
        }
    }

    public static String convertToJson(Document xmlDocument) {
        try {
            return MAPPER.writeValueAsString(convertToObject(xmlDocument, Object.class));
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage(), ex);
            return null;
        }
    }

    public static <T> T convertToObject(Document xmlDocument, Class<T> valueType) {
        try {
            return new XmlMapper().readValue(documentToString(xmlDocument), valueType);
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage(), ex);
            return null;
        }
    }
}

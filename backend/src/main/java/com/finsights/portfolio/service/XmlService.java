package com.finsights.portfolio.service;

import com.finsights.portfolio.dto.ImportResultResponse;
import com.finsights.portfolio.dto.TransactionResponse;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * XML mirror of {@link CsvService} — bulk export/import for transactions only.
 *
 * <pre>{@code
 * <transactions>
 *   <transaction>
 *     <id/>                        <!-- omit or leave empty to log a new transaction -->
 *     <holdingId>h-123</holdingId> <!-- required: the holding this transaction belongs to -->
 *     <type>BUY</type>             <!-- BUY | SELL | SPLIT | INTEREST | ADJUSTMENT -->
 *     <date>2026-01-15</date>
 *     <amount>50000</amount>
 *     <quantity>10</quantity>
 *     <notes>Initial buy</notes>
 *   </transaction>
 * </transactions>
 * }</pre>
 */
@Service
public class XmlService {

    private final TransactionService transactions;

    public XmlService(TransactionService transactions) {
        this.transactions = transactions;
    }

    public String export() {
        try {
            Document doc = newDocumentBuilder().newDocument();
            Element root = doc.createElement("transactions");
            doc.appendChild(root);
            for (TransactionResponse t : transactions.list(null, null, null, null, null, null)) {
                Element el = doc.createElement("transaction");
                root.appendChild(el);
                append(doc, el, "id", t.id());
                append(doc, el, "holdingId", t.holdingId());
                append(doc, el, "type", t.type().name());
                append(doc, el, "date", t.date().toString());
                append(doc, el, "amount", plain(t.amount()));
                append(doc, el, "quantity", t.quantity() == null ? null : plain(t.quantity()));
                append(doc, el, "notes", t.notes());
                append(doc, el, "holdingName", t.holdingName());
                append(doc, el, "broker", t.broker());
            }
            return serialize(doc);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not build XML export", ex);
        }
    }

    public ImportResultResponse importXml(String body) {
        NodeList nodes = parseTransactionElements(body);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            rows.add(fieldsOf((Element) nodes.item(i)));
        }
        return TransactionImportRunner.run(rows, i -> i + 1, transactions);
    }

    private Map<String, String> fieldsOf(Element el) {
        Map<String, String> fields = new LinkedHashMap<>();
        NodeList children = el.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) continue;
            Element child = (Element) node;
            String text = child.getTextContent();
            fields.put(child.getTagName().toLowerCase(), text == null ? "" : text.trim());
        }
        return fields;
    }

    private NodeList parseTransactionElements(String body) {
        try {
            Document doc = newDocumentBuilder().parse(
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
            NodeList nodes = doc.getElementsByTagName("transaction");
            if (nodes.getLength() == 0) {
                throw new IllegalArgumentException("No <transaction> elements found in the XML file");
            }
            return nodes;
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Could not parse XML: " + ex.getMessage());
        }
    }

    /** Hardened against XXE (external entities / DTDs) — the file comes from the user's browser. */
    private DocumentBuilder newDocumentBuilder() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder();
    }

    private void append(Document doc, Element parent, String tag, String value) {
        Element el = doc.createElement(tag);
        el.setTextContent(value == null ? "" : value);
        parent.appendChild(el);
    }

    private String serialize(Document doc) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(writer));
        return writer.toString();
    }

    private String plain(BigDecimal value) { return value == null ? "" : value.toPlainString(); }
}

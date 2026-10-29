package Bill;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public final class XlsxReader {

    @FunctionalInterface
    public interface RowHandler {
        void onRow(int rowNumber, List<String> cells);
    }

    private static final String SHARED_STRINGS = "xl/sharedStrings.xml";
    private static final String WORKSHEETS = "xl/worksheets/";
    private static final int MAX_COLUMNS = 64;

    private XlsxReader() {
    }

    public static void read(File file, RowHandler handler) throws IOException {
        try {
            readInternal(file, handler);
        } catch (ZipException e) {
            throw new IOException("这不是 xlsx 文件（可能是旧版导出的 CSV 账单，或文件损坏）。"
                    + "如果用 Excel 打开正常，另存为 .xlsx 再试；"
                    + "也可以用 --generate-test 先跑一份测试数据。原始错误：" + e.getMessage(), e);
        }
    }

    private static void readInternal(File file, RowHandler handler) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            List<String> sharedStrings = readSharedStrings(zip);
            ZipEntry sheet = findFirstSheet(zip);
            try (InputStream in = zip.getInputStream(sheet)) {
                newParser().parse(in, new SheetHandler(sharedStrings, handler));
            } catch (SAXException e) {
                throw new IOException("解析工作表失败：" + sheet.getName(), e);
            }
        }
    }

    private static SAXParser newParser() {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newSAXParser();
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("初始化 XML 解析器失败", e);
        }
    }

    private static List<String> readSharedStrings(ZipFile zip) throws IOException {
        ZipEntry entry = zip.getEntry(SHARED_STRINGS);
        if (entry == null) {
            return List.of();
        }
        SharedStringsHandler handler = new SharedStringsHandler();
        try (InputStream in = zip.getInputStream(entry)) {
            newParser().parse(in, handler);
        } catch (SAXException e) {
            throw new IOException("解析 sharedStrings.xml 失败", e);
        }
        return handler.strings;
    }

    private static ZipEntry findFirstSheet(ZipFile zip) throws IOException {
        ZipEntry preferred = zip.getEntry(WORKSHEETS + "sheet1.xml");
        if (preferred != null) {
            return preferred;
        }
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (!entry.isDirectory() && name.startsWith(WORKSHEETS) && name.endsWith(".xml")) {
                return entry;
            }
        }
        throw new IOException("压缩包内找不到工作表：" + zip.getName());
    }

    static int columnIndex(String ref) {
        if (ref == null || ref.isEmpty()) {
            return -1;
        }
        int column = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                column = column * 26 + (c - 'A' + 1);
            } else if (c >= 'a' && c <= 'z') {
                column = column * 26 + (c - 'a' + 1);
            } else {
                break;
            }
        }
        return column - 1;
    }

    static int parseInt(String text, int fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static final class SharedStringsHandler extends DefaultHandler {
        private final List<String> strings = new ArrayList<>();
        private final StringBuilder buffer = new StringBuilder();
        private boolean inText;

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            if ("si".equals(qName)) {
                buffer.setLength(0);
            } else if ("t".equals(qName)) {
                inText = true;
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inText) {
                buffer.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if ("t".equals(qName)) {
                inText = false;
            } else if ("si".equals(qName)) {
                strings.add(buffer.toString());
            }
        }
    }

    private static final class SheetHandler extends DefaultHandler {
        private final List<String> sharedStrings;
        private final RowHandler out;

        private List<String> row = new ArrayList<>();
        private int rowNumber;
        private int cellColumn = -1;
        private String cellType;
        private final StringBuilder buffer = new StringBuilder();
        private boolean inValue;
        private boolean inInlineText;

        SheetHandler(List<String> sharedStrings, RowHandler out) {
            this.sharedStrings = sharedStrings;
            this.out = out;
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            switch (qName) {
                case "row" -> {
                    row = new ArrayList<>();
                    String r = attributes.getValue("r");
                    rowNumber = (r == null) ? rowNumber + 1 : parseInt(r, rowNumber + 1);
                }
                case "c" -> {
                    cellColumn = columnIndex(attributes.getValue("r"));
                    cellType = attributes.getValue("t");
                    buffer.setLength(0);
                }
                case "v" -> inValue = true;
                case "t" -> inInlineText = true;
                default -> {
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inValue || inInlineText) {
                buffer.append(ch, start, length);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            switch (qName) {
                case "v" -> inValue = false;
                case "t" -> inInlineText = false;
                case "c" -> putCell(cellColumn, resolve(cellType, buffer.toString()));
                case "row" -> out.onRow(rowNumber, row);
                default -> {
                }
            }
        }

        private String resolve(String type, String raw) {
            if (type == null) {
                return raw;
            }
            return switch (type) {
                case "s" -> {
                    int index = parseInt(raw, -1);
                    yield (index >= 0 && index < sharedStrings.size()) ? sharedStrings.get(index) : "";
                }
                case "inlineStr", "str" -> raw;
                case "b" -> "1".equals(raw) ? "TRUE" : "FALSE";
                case "e" -> "";
                default -> raw;
            };
        }

        private void putCell(int column, String value) {
            if (column < 0) {
                column = row.size();
            }
            if (column >= MAX_COLUMNS) {
                return;
            }
            while (row.size() <= column) {
                row.add("");
            }
            row.set(column, value);
        }
    }
}

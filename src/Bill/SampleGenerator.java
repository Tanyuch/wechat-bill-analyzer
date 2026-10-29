package Bill;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class SampleGenerator {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] INCOME_TYPES = {"二维码收款", "微信红包", "转入零钱"};
    private static final String[] EXPENSE_TYPES = {"商户消费", "扫二维码付款", "转账", "微信红包"};
    private static final String[] COUNTERPARTIES = {"星巴克", "美团", "滴滴出行", "京东", "张三", "李四", "便利店"};
    private static final String[] PAY_METHODS = {"零钱", "招商银行(1234)", "工商银行(5678)"};
    private static final String[] STATUSES = {"支付成功", "已存入零钱", "对方已收钱"};
    private static final String HEADER_ROW =
            "交易时间|交易类型|交易对方|商品|收/支|金额(元)|支付方式|当前状态|交易单号|商户单号|备注";
    private static final String XML_HEAD =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";

    private static final String CONTENT_TYPES = XML_HEAD
            + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
            + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
            + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
            + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
            + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
            + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
            + "</Types>";

    private static final String ROOT_RELS = XML_HEAD
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
            + "</Relationships>";

    private static final String WORKBOOK = XML_HEAD
            + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
            + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
            + "<sheets><sheet name=\"Sheet1\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>";

    private static final String WORKBOOK_RELS = XML_HEAD
            + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
            + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
            + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
            + "</Relationships>";

    private static final String STYLES = XML_HEAD
            + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
            + "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
            + "<fills count=\"1\"><fill><patternFill patternType=\"none\"/></fill></fills>"
            + "<borders count=\"1\"><border/></borders>"
            + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
            + "<cellXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/></cellXfs>"
            + "</styleSheet>";

    private SampleGenerator() {
    }

    public static void generate(Path target, int rows, long seed) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(absolute), StandardCharsets.UTF_8)) {
            writeEntry(zip, "[Content_Types].xml", CONTENT_TYPES);
            writeEntry(zip, "_rels/.rels", ROOT_RELS);
            writeEntry(zip, "xl/workbook.xml", WORKBOOK);
            writeEntry(zip, "xl/_rels/workbook.xml.rels", WORKBOOK_RELS);
            writeEntry(zip, "xl/styles.xml", STYLES);

            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            Writer writer = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
            writeSheet(writer, rows, seed);
            writer.flush();
            zip.closeEntry();
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void writeSheet(Writer writer, int rows, long seed) throws IOException {
        writer.write(XML_HEAD
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");

        int rowNumber = 1;
        String[] notes = {
                "微信支付账单明细",
                "微信昵称：[示例用户]",
                "起始时间：[2025-10-29 00:00:00]",
                "终止时间：[2026-10-29 00:00:00]",
                "导出类型：[全部]",
                "导出时间：[2026-10-29 20:40:33]",
                "共 " + rows + " 笔记录",
                "温馨提示：",
                "1. 本文件为示例数据，仅用于程序自测",
                "2. 金额单位为元",
                "3. 收/支 为 / 的记录不计入收支合计",
                "4. 金额一律保留两位小数",
                "5. 时间格式为 yyyy-MM-dd HH:mm:ss",
                "6. 本文件不含任何真实个人信息",
                "7. 生成器：SampleGenerator",
                "8. 版本：1.0"
        };
        for (String note : notes) {
            writeRow(writer, rowNumber++, new String[]{note});
        }
        writeRow(writer, rowNumber++, HEADER_ROW.split("\\|", -1));

        Random random = new Random(seed);
        LocalDateTime time = LocalDateTime.of(2025, 10, 29, 8, 0, 0);
        for (int i = 0; i < rows; i++) {
            time = time.plusMinutes(3);
            int dice = random.nextInt(100);
            String direction;
            String type;
            if (dice < 25) {
                direction = "收入";
                type = INCOME_TYPES[random.nextInt(INCOME_TYPES.length)];
            } else if (dice < 90) {
                direction = "支出";
                type = EXPENSE_TYPES[random.nextInt(EXPENSE_TYPES.length)];
            } else {
                direction = "/";
                type = "零钱提现";
            }
            BigDecimal amount = BigDecimal.valueOf(random.nextInt(100_000) + 1, 2);
            writeRow(writer, rowNumber++, new String[]{
                    TIME.format(time),
                    type,
                    COUNTERPARTIES[random.nextInt(COUNTERPARTIES.length)],
                    "示例商品" + (i % 20),
                    direction,
                    amount.toPlainString(),
                    PAY_METHODS[random.nextInt(PAY_METHODS.length)],
                    STATUSES[random.nextInt(STATUSES.length)],
                    "42000" + String.format("%012d", i),
                    "M2026" + String.format("%010d", i),
                    i % 7 == 0 ? "会员日" : ""
            });
        }

        writeRow(writer, rowNumber++, new String[0]);
        writeRow(writer, rowNumber, new String[]{"以上为全部记录"});
        writer.write("</sheetData></worksheet>");
    }

    private static void writeRow(Writer writer, int rowNumber, String[] cells) throws IOException {
        writer.write("<row r=\"" + rowNumber + "\">");
        for (int c = 0; c < cells.length; c++) {
            if (cells[c] == null || cells[c].isEmpty()) {
                continue;
            }
            writer.write("<c r=\"" + columnName(c) + rowNumber + "\" t=\"inlineStr\"><is><t>"
                    + escape(cells[c]) + "</t></is></c>");
        }
        writer.write("</row>");
    }

    private static String columnName(int index) {
        StringBuilder name = new StringBuilder();
        int value = index;
        do {
            name.insert(0, (char) ('A' + value % 26));
            value = value / 26 - 1;
        } while (value >= 0);
        return name.toString();
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}

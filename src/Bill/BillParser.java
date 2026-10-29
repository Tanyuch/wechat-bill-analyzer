package Bill;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class BillParser {

    private static final List<String> REQUIRED_COLUMNS = List.of("time", "type", "direction", "amount");
    private static final LocalDateTime EXCEL_EPOCH = LocalDate.of(1899, 12, 30).atStartOfDay();
    private static final Pattern DATE_TIME = Pattern.compile(
            "^(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?(?:[ T]+(\\d{1,2})[:：](\\d{1,2})(?:[:：](\\d{1,2}))?)?$");
    private static final int MAX_BAD_SAMPLES = 5;

    private BillParser() {
    }

    public static final class Stats {
        private int scannedRows;
        private int headerRows;
        private int blankRows;
        private int noteRows;
        private int bills;
        private int badRows;
        private int shortRows;
        private int headerRow = -1;
        private int headerColumns;
        private final List<String> badSamples = new ArrayList<>();

        public int scannedRows() {
            return scannedRows;
        }

        public int headerRows() {
            return headerRows;
        }

        public int blankRows() {
            return blankRows;
        }

        public int noteRows() {
            return noteRows;
        }

        public int bills() {
            return bills;
        }

        public int badRows() {
            return badRows;
        }

        public int shortRows() {
            return shortRows;
        }

        public int headerRow() {
            return headerRow;
        }

        public int headerColumns() {
            return headerColumns;
        }

        public List<String> badSamples() {
            return List.copyOf(badSamples);
        }

        public int accounted() {
            return headerRows + blankRows + noteRows + bills + badRows;
        }

        public boolean balanced() {
            return accounted() == scannedRows;
        }

        void recordBad(int rowNumber, String reason, List<String> cells) {
            badRows++;
            if (badSamples.size() < MAX_BAD_SAMPLES) {
                badSamples.add("第 " + rowNumber + " 行：" + reason + "  " + preview(cells));
            }
        }
    }

    private static final class Header {
        private final Map<String, Integer> columns = new HashMap<>();
        private int columnCount;

        boolean found() {
            return !columns.isEmpty();
        }
    }

    public static Stats parse(File file, Consumer<Bill> sink) throws IOException {
        Stats stats = new Stats();
        Header header = new Header();

        XlsxReader.read(file, (rowNumber, cells) -> consume(stats, header, rowNumber, cells, sink));

        if (!header.found()) {
            throw new IOException("未能定位表头：没有任何一行同时包含"
                    + "「交易时间」「交易类型」「收/支」「金额」，请确认这是微信支付账单明细表");
        }
        if (!stats.balanced()) {
            throw new IOException(String.format(
                    "行数勾稽失败：扫描 %d 行 ≠ 表头 %d + 空行 %d + 说明 %d + 有效 %d + 坏行 %d = %d",
                    stats.scannedRows, stats.headerRows, stats.blankRows,
                    stats.noteRows, stats.bills, stats.badRows, stats.accounted()));
        }
        return stats;
    }

    private static void consume(Stats stats, Header header, int rowNumber,
                                List<String> cells, Consumer<Bill> sink) {
        stats.scannedRows++;

        if (!header.found()) {
            if (isBlank(cells)) {
                stats.blankRows++;
                return;
            }
            Map<String, Integer> candidate = mapHeader(cells);
            if (candidate.keySet().containsAll(REQUIRED_COLUMNS)) {
                header.columns.putAll(candidate);
                header.columnCount = cells.size();
                stats.headerRow = rowNumber;
                stats.headerColumns = candidate.size();
                stats.headerRows++;
            } else {
                stats.noteRows++;
            }
            return;
        }

        if (isBlank(cells)) {
            stats.blankRows++;
            return;
        }

        if (nonEmptyCount(cells) <= 1) {
            stats.noteRows++;
            return;
        }

        Integer timeIndex = header.columns.get("time");
        Integer amountIndex = header.columns.get("amount");
        if ((timeIndex != null && timeIndex >= cells.size())
                || (amountIndex != null && amountIndex >= cells.size())) {
            stats.shortRows++;
        }

        String timeText = cell(cells, timeIndex);
        String amountText = cell(cells, amountIndex);

        LocalDateTime time = parseTime(timeText);
        if (time == null) {
            stats.recordBad(rowNumber, "时间无法解析「" + timeText + "」", cells);
            return;
        }
        BigDecimal amount = parseMoney(amountText);
        if (amount == null) {
            stats.recordBad(rowNumber, "金额无法解析「" + amountText + "」", cells);
            return;
        }

        stats.bills++;
        sink.accept(new Bill(
                rowNumber,
                time,
                cell(cells, header.columns.get("type")),
                cell(cells, header.columns.get("counterparty")),
                cell(cells, header.columns.get("goods")),
                cell(cells, header.columns.get("direction")),
                amount,
                cell(cells, header.columns.get("payMethod")),
                cell(cells, header.columns.get("status"))
        ));
    }

    private static Map<String, Integer> mapHeader(List<String> cells) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            String name = cell(cells, i).replaceAll("\\s+", "");
            if (name.isEmpty()) {
                continue;
            }
            if (name.contains("交易时间")) {
                columns.putIfAbsent("time", i);
            } else if (name.contains("交易类型")) {
                columns.putIfAbsent("type", i);
            } else if (name.contains("交易对方")) {
                columns.putIfAbsent("counterparty", i);
            } else if (name.contains("商品")) {
                columns.putIfAbsent("goods", i);
            } else if (name.contains("收/支") || name.contains("收支")) {
                columns.putIfAbsent("direction", i);
            } else if (name.contains("金额")) {
                columns.putIfAbsent("amount", i);
            } else if (name.contains("支付方式")) {
                columns.putIfAbsent("payMethod", i);
            } else if (name.contains("状态")) {
                columns.putIfAbsent("status", i);
            }
        }
        return columns;
    }

    private static String cell(List<String> cells, Integer index) {
        if (index == null || index < 0 || index >= cells.size()) {
            return "";
        }
        String value = cells.get(index);
        return value == null ? "" : value.trim();
    }

    private static boolean isBlank(List<String> cells) {
        for (String value : cells) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static int nonEmptyCount(List<String> cells) {
        int count = 0;
        for (String value : cells) {
            if (value != null && !value.isBlank()) {
                count++;
            }
        }
        return count;
    }

    private static String preview(List<String> cells) {
        StringBuilder text = new StringBuilder();
        for (String value : cells) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (text.length() > 0) {
                text.append('|');
            }
            text.append(value.trim());
            if (text.length() > 80) {
                break;
            }
        }
        String result = text.toString();
        return result.length() <= 80 ? "→ " + result : "→ " + result.substring(0, 80) + "…";
    }

    private static BigDecimal parseMoney(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String cleaned = text.replace("¥", "")
                .replace("￥", "")
                .replace(",", "")
                .replace(" ", "")
                .trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(cleaned).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static LocalDateTime parseTime(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        Matcher matcher = DATE_TIME.matcher(trimmed);
        if (matcher.matches()) {
            try {
                int year = Integer.parseInt(matcher.group(1));
                int month = Integer.parseInt(matcher.group(2));
                int day = Integer.parseInt(matcher.group(3));
                int hour = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
                int minute = matcher.group(5) == null ? 0 : Integer.parseInt(matcher.group(5));
                int second = matcher.group(6) == null ? 0 : Integer.parseInt(matcher.group(6));
                return LocalDateTime.of(year, month, day, hour, minute, second);
            } catch (DateTimeException e) {
                return null;
            }
        }

        try {
            double serial = Double.parseDouble(trimmed);
            if (serial > 0 && serial < 2_958_466) {
                return EXCEL_EPOCH.plusSeconds(Math.round(serial * 86_400d));
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
        return null;
    }
}

package Bill;

import java.text.DecimalFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class ConsoleReport {

    private static final boolean[] RIGHT_ALIGNED = {false, true, true, true, true};
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.00");

    private ConsoleReport() {
    }

    public static void print(Aggregator aggregator) {
        String[] headers = {aggregator.groupLabel(), "笔数", "收入合计", "支出合计", "净额"};
        List<String[]> rows = new ArrayList<>();
        for (Aggregator.Stat stat : aggregator.groups().values()) {
            rows.add(row(stat));
        }
        rows.add(row(aggregator.total()));

        int[] widths = widths(headers, rows);
        int typeCount = rows.size() - 1;

        border(widths, '┌', '┬', '┐');
        line(headers, widths);
        border(widths, '├', '┼', '┤');
        for (int i = 0; i < typeCount; i++) {
            line(rows.get(i), widths);
        }
        if (typeCount > 0) {
            border(widths, '├', '┼', '┤');
        }
        line(rows.get(rows.size() - 1), widths);
        border(widths, '└', '┴', '┘');
    }

    public static void printSamples(List<Bill> head, List<Bill> tail, int total) {
        if (head.isEmpty()) {
            return;
        }
        System.out.println();
        System.out.println("  抽样校验（行号对应原始表格，可直接回原表核对）：");
        System.out.println("    " + sampleHeader());
        for (Bill bill : head) {
            System.out.println("    " + sampleRow(bill));
        }
        if (total > head.size() && !tail.isEmpty()) {
            System.out.println("    ...");
            for (Bill bill : tail) {
                System.out.println("    " + sampleRow(bill));
            }
        }
    }

    public static void printSummary(Aggregator aggregator, BillParser.Stats stats, long millis) {
        Aggregator.Stat total = aggregator.total();
        System.out.println();
        if (aggregator.first() != null && aggregator.last() != null) {
            System.out.println("  统计区间 : " + TIME.format(aggregator.first())
                    + "  ~  " + TIME.format(aggregator.last()));
        }
        System.out.printf("  行数勾稽 : 扫描 %,d = 表头 %d + 说明 %,d + 空行 %,d + 有效 %,d + 坏行 %,d   %s%n",
                stats.scannedRows(), stats.headerRows(), stats.noteRows(), stats.blankRows(),
                stats.bills(), stats.badRows(), stats.balanced() ? "[平]" : "[不平]");
        if (stats.shortRows() > 0) {
            System.out.printf("            其中必需列缺失的行：%,d 行%n", stats.shortRows());
        }
        System.out.printf("  总体收支 : 收入 %s | 支出 %s | 净额 %s%n",
                MONEY.format(total.income()),
                MONEY.format(total.expense()),
                MONEY.format(total.net()));
        System.out.printf("  处理耗时 : %.2f s%n", millis / 1000.0);

        List<String> samples = stats.badSamples();
        if (!samples.isEmpty()) {
            System.out.println("  坏行样本 :");
            for (String sample : samples) {
                System.out.println("    · " + sample);
            }
        }
    }

    private static String sampleHeader() {
        return pad("行号", 6, true) + "  " + pad("交易时间", 20, false) + "  "
                + pad("交易类型", 16, false) + "  " + pad("收/支", 8, false) + "  "
                + pad("金额", 12, true);
    }

    private static String sampleRow(Bill bill) {
        return pad(String.valueOf(bill.sourceRow()), 6, true) + "  "
                + pad(TIME.format(bill.time()), 20, false) + "  "
                + pad(bill.type(), 16, false) + "  "
                + pad(bill.direction(), 8, false) + "  "
                + pad(MONEY.format(bill.amount()), 12, true);
    }

    private static String[] row(Aggregator.Stat stat) {
        return new String[]{
                stat.name(),
                String.format("%,d", stat.count()),
                MONEY.format(stat.income()),
                MONEY.format(stat.expense()),
                MONEY.format(stat.net())
        };
    }

    private static void line(String[] cells, int[] widths) {
        StringBuilder sb = new StringBuilder("│");
        for (int i = 0; i < cells.length; i++) {
            sb.append(' ').append(pad(cells[i], widths[i], RIGHT_ALIGNED[i])).append(" │");
        }
        System.out.println(sb);
    }

    private static void border(int[] widths, char left, char middle, char right) {
        StringBuilder sb = new StringBuilder();
        sb.append(left);
        for (int i = 0; i < widths.length; i++) {
            sb.append("─".repeat(widths[i] + 2));
            sb.append(i == widths.length - 1 ? right : middle);
        }
        System.out.println(sb);
    }

    private static int[] widths(String[] headers, List<String[]> rows) {
        int[] widths = new int[headers.length];
        for (int i = 0; i < headers.length; i++) {
            widths[i] = displayWidth(headers[i]);
        }
        for (String[] row : rows) {
            for (int i = 0; i < row.length && i < widths.length; i++) {
                widths[i] = Math.max(widths[i], displayWidth(row[i]));
            }
        }
        return widths;
    }

    private static String pad(String text, int width, boolean rightAlign) {
        int gap = width - displayWidth(text);
        if (gap <= 0) {
            return text;
        }
        String spaces = " ".repeat(gap);
        return rightAlign ? spaces + text : text + spaces;
    }

    private static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int codePoint = text.codePointAt(i);
            width += isWide(codePoint) ? 2 : 1;
            i += Character.charCount(codePoint);
        }
        return width;
    }

    private static boolean isWide(int codePoint) {
        return (codePoint >= 0x1100 && codePoint <= 0x115F)
                || (codePoint >= 0x2E80 && codePoint <= 0xA4CF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0xFE30 && codePoint <= 0xFE6F)
                || (codePoint >= 0xFF00 && codePoint <= 0xFF60)
                || (codePoint >= 0xFFE0 && codePoint <= 0xFFE6)
                || (codePoint >= 0x20000 && codePoint <= 0x3FFFD);
    }
}

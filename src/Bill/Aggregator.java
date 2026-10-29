package Bill;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;

public final class Aggregator implements Consumer<Bill> {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final int PROGRESS_STEP = 50_000;

    private final Function<Bill, String> keyFn;
    private final String groupLabel;
    private final Map<String, Stat> groups = new TreeMap<>();
    private final Stat total = new Stat("合计");
    private LocalDateTime first;
    private LocalDateTime last;
    private int processed;

    public Aggregator(Function<Bill, String> keyFn, String groupLabel) {
        this.keyFn = keyFn;
        this.groupLabel = groupLabel;
    }

    public static final class Stat {
        private final String name;
        private int count;
        private BigDecimal income = BigDecimal.ZERO;
        private BigDecimal expense = BigDecimal.ZERO;

        Stat(String name) {
            this.name = name;
        }

        void add(Bill bill) {
            count++;
            if (bill.isIncome()) {
                income = income.add(bill.amount());
            } else if (bill.isExpense()) {
                expense = expense.add(bill.amount());
            }
        }

        public String name() {
            return name;
        }

        public int count() {
            return count;
        }

        public BigDecimal income() {
            return income.setScale(2, RoundingMode.HALF_UP);
        }

        public BigDecimal expense() {
            return expense.setScale(2, RoundingMode.HALF_UP);
        }

        public BigDecimal net() {
            return income.subtract(expense).setScale(2, RoundingMode.HALF_UP);
        }
    }

    @Override
    public void accept(Bill bill) {
        String raw = keyFn.apply(bill);
        String key = (raw == null || raw.isBlank()) ? "(未分类)" : raw;
        groups.computeIfAbsent(key, Stat::new).add(bill);
        total.add(bill);

        if (first == null || bill.time().isBefore(first)) {
            first = bill.time();
        }
        if (last == null || bill.time().isAfter(last)) {
            last = bill.time();
        }

        if (++processed % PROGRESS_STEP == 0) {
            System.out.printf("      ... 已解析 %,d 笔%n", processed);
        }
    }

    public String groupLabel() {
        return groupLabel;
    }

    public Map<String, Stat> groups() {
        return groups;
    }

    public Stat total() {
        return total;
    }

    public LocalDateTime first() {
        return first;
    }

    public LocalDateTime last() {
        return last;
    }

    public void writeCsv(Path target) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(absolute)) {
            out.write(UTF8_BOM);
            try (Writer writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
                writer.write(groupLabel + ",笔数,收入合计,支出合计,净额");
                writer.write("\n");
                for (Stat stat : groups.values()) {
                    writeRow(writer, stat);
                }
                writeRow(writer, total);
            }
        }
    }

    private static void writeRow(Writer writer, Stat stat) throws IOException {
        writer.write(String.join(",",
                quote(stat.name()),
                String.valueOf(stat.count()),
                stat.income().toPlainString(),
                stat.expense().toPlainString(),
                stat.net().toPlainString()));
        writer.write("\n");
    }

    private static String quote(String value) {
        if (value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}

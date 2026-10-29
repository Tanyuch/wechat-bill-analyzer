package Bill;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

public class MyBill {

    private static final String DEFAULT_FILE = "data/bill.xlsx";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final Set<String> GROUPS = Set.of("type", "counterparty", "direction", "month");

    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("参数错误：" + e.getMessage());
            System.err.println();
            System.err.println(Options.USAGE);
            return;
        }
        if (options.help) {
            System.out.println(Options.USAGE);
            return;
        }

        System.out.println("══════════════════════════════════════════════════════════════");
        System.out.println("  微信支付账单分析");
        System.out.println("══════════════════════════════════════════════════════════════");

        try {
            if (options.generateTest > 0) {
                generate(options.generateTest);
                return;
            }
            new MyBill().read(options);
        } catch (IOException e) {
            System.err.println("处理失败：" + e.getMessage());
        }
    }

    private static void generate(int rows) throws IOException {
        Path target = Path.of("out", "test-" + rows + ".xlsx");
        System.out.printf("生成测试账单：%,d 行 → %s%n", rows, target.toAbsolutePath());
        long start = System.currentTimeMillis();
        SampleGenerator.generate(target, rows, 20261029L);
        System.out.printf("生成完成：%.2f s，%.1f MB%n",
                (System.currentTimeMillis() - start) / 1000.0,
                Files.size(target) / 1024.0 / 1024.0);
    }

    public void read(Options options) throws IOException {
        long start = System.currentTimeMillis();

        System.out.println("[1/4] 校验文件");
        File file = new File(options.source);
        if (!file.isFile()) {
            throw new IOException("文件不存在或不是文件：" + file.getAbsolutePath()
                    + System.lineSeparator()
                    + "        可先造一份测试数据：java -cp out Bill.MyBill --generate-test 100000");
        }
        System.out.println("      " + file.getAbsolutePath());

        System.out.println("[2/4] 流式解析 + 聚合");
        Aggregator aggregator = new Aggregator(groupOf(options.group), labelOf(options.group));
        SampleKeeper keeper = new SampleKeeper(5);

        DetailWriter detail = options.detail == null ? null : new DetailWriter(options.detail);
        try {
            Consumer<Bill> sink = aggregator.andThen(keeper);
            if (detail != null) {
                sink = sink.andThen(detail);
            }
            BillParser.Stats stats = BillParser.parse(file, sink);
            System.out.printf("      表头定位 : 第 %d 行（识别到 %d 列）%n",
                    stats.headerRow(), stats.headerColumns());
            System.out.printf("      解析结果 : 有效 %,d 笔 | 坏行 %,d 行%n",
                    stats.bills(), stats.badRows());

            System.out.println("[3/4] 生成报表");
            ConsoleReport.print(aggregator);
            ConsoleReport.printSamples(new ArrayList<>(keeper.head()),
                    new ArrayList<>(keeper.tail()), stats.bills());
            ConsoleReport.printSummary(aggregator, stats, System.currentTimeMillis() - start);

            if (detail != null) {
                detail.flush();
            }
            System.out.println("[4/4] 落盘");
            aggregator.writeCsv(options.report);
            System.out.println("      汇总 : " + options.report.toAbsolutePath());
            if (detail != null) {
                System.out.println("      明细 : " + options.detail.toAbsolutePath()
                        + "（" + detail.written() + " 行）");
            }
        } finally {
            if (detail != null) {
                try {
                    detail.close();
                } catch (IOException ignored) {
                    // 已 flush，忽略关闭异常
                }
            }
        }
    }

    private static Function<Bill, String> groupOf(String group) {
        return switch (group) {
            case "counterparty" -> Bill::counterparty;
            case "direction" -> Bill::direction;
            case "month" -> bill -> bill.time().format(MONTH);
            default -> Bill::type;
        };
    }

    private static String labelOf(String group) {
        return switch (group) {
            case "counterparty" -> "交易对方";
            case "direction" -> "收/支";
            case "month" -> "月份";
            default -> "交易类型";
        };
    }

    private static final class SampleKeeper implements Consumer<Bill> {
        private final int limit;
        private final Deque<Bill> head = new ArrayDeque<>();
        private final Deque<Bill> tail = new ArrayDeque<>();

        SampleKeeper(int limit) {
            this.limit = limit;
        }

        @Override
        public void accept(Bill bill) {
            if (head.size() < limit) {
                head.addLast(bill);
            }
            if (tail.size() == limit) {
                tail.removeFirst();
            }
            tail.addLast(bill);
        }

        Deque<Bill> head() {
            return head;
        }

        Deque<Bill> tail() {
            return tail;
        }
    }

    public static final class Options {
        static final String USAGE = String.join(System.lineSeparator(),
                "用法：java -cp out Bill.MyBill [账单路径] [选项]",
                "",
                "选项：",
                "  --report <path>      汇总 CSV 输出路径（默认 out/bill-summary.csv）",
                "  --detail [path]      输出逐笔明细 CSV（默认 out/bill-detail.csv）",
                "  --group <field>      分组字段：type | counterparty | direction | month（默认 type）",
                "  --generate-test <n>  生成 n 行测试账单到 out/test-<n>.xlsx 后退出",
                "  --help               显示本帮助",
                "",
                "示例：",
                "  java -cp out Bill.MyBill --generate-test 100000",
                "  java -cp out Bill.MyBill out\\test-100000.xlsx --detail --group type");

        private String source = DEFAULT_FILE;
        private Path report = Path.of("out", "bill-summary.csv");
        private Path detail;
        private String group = "type";
        private int generateTest;
        private boolean help;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--help", "-h" -> options.help = true;
                    case "--report" -> options.report = Path.of(require(args, ++i, arg));
                    case "--detail" -> {
                        String next = (i + 1 < args.length) ? args[i + 1] : null;
                        if (next != null && !next.startsWith("--")) {
                            options.detail = Path.of(next);
                            i++;
                        } else {
                            options.detail = Path.of("out", "bill-detail.csv");
                        }
                    }
                    case "--group" -> options.group = require(args, ++i, arg);
                    case "--generate-test" -> options.generateTest = positive(require(args, ++i, arg), arg);
                    default -> {
                        if (arg.startsWith("--")) {
                            throw new IllegalArgumentException("未知选项：" + arg);
                        }
                        options.source = arg;
                    }
                }
            }
            if (!GROUPS.contains(options.group)) {
                throw new IllegalArgumentException(
                        "不支持的分组字段：" + options.group + "（可选 type|counterparty|direction|month）");
            }
            return options;
        }

        private static String require(String[] args, int index, String option) {
            if (index >= args.length) {
                throw new IllegalArgumentException("选项 " + option + " 缺少参数");
            }
            return args[index];
        }

        private static int positive(String text, String option) {
            try {
                int value = Integer.parseInt(text);
                if (value <= 0) {
                    throw new IllegalArgumentException("选项 " + option + " 需要正整数");
                }
                return value;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("选项 " + option + " 需要正整数，收到：" + text);
            }
        }
    }
}

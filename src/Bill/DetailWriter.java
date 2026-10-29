package Bill;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

public final class DetailWriter implements Consumer<Bill>, Closeable {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Writer writer;
    private int written;

    public DetailWriter(Path target) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        OutputStream out = Files.newOutputStream(absolute);
        out.write(UTF8_BOM);
        this.writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
        writer.write("行号,交易时间,交易类型,交易对方,收/支,金额,支付方式,当前状态");
        writer.write("\r\n");
    }

    @Override
    public void accept(Bill bill) {
        try {
            writer.write(String.join(",",
                    String.valueOf(bill.sourceRow()),
                    TIME.format(bill.time()),
                    quote(bill.type()),
                    quote(bill.counterparty()),
                    quote(bill.direction()),
                    bill.amount().toPlainString(),
                    quote(bill.payMethod()),
                    quote(bill.status())));
            writer.write("\r\n");
            written++;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public int written() {
        return written;
    }

    public void flush() {
        try {
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    private static String quote(String value) {
        if (value.contains(",") || value.contains("\"")
                || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}

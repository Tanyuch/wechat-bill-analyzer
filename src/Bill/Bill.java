package Bill;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record Bill(
        int sourceRow,
        LocalDateTime time,
        String type,
        String counterparty,
        String goods,
        String direction,
        BigDecimal amount,
        String payMethod,
        String status
) {
    public static final String INCOME = "收入";
    public static final String EXPENSE = "支出";

    public boolean isIncome() {
        return INCOME.equals(direction);
    }

    public boolean isExpense() {
        return EXPENSE.equals(direction);
    }
}



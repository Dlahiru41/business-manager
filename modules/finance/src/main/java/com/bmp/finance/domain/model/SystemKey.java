package com.bmp.finance.domain.model;

/**
 * The fixed meaning of a seeded account (posting-rules.md §1): rules post to keys, never to codes, so tenants may
 * rename and renumber their accounts (FIN-01).
 */
public enum SystemKey {
    CASH_DRAWER("1000", "Cash in drawer", AccountType.ASSET),
    BANK("1010", "Bank", AccountType.ASSET),
    CARD_CLEARING("1020", "Card clearing", AccountType.ASSET),
    TRANSFER_CLEARING("1030", "Mobile / transfer clearing", AccountType.ASSET),
    CHEQUES("1040", "Cheques in hand", AccountType.ASSET),
    AR("1100", "Accounts receivable", AccountType.ASSET),
    POS_CLEARING("1110", "POS clearing (walk-in)", AccountType.ASSET),
    INVENTORY("1200", "Inventory", AccountType.ASSET),
    AP("2000", "Accounts payable", AccountType.LIABILITY),
    GRNI("2050", "Goods received not invoiced", AccountType.LIABILITY),
    TAX_PAYABLE("2100", "Tax payable", AccountType.LIABILITY),
    CUSTOMER_CREDIT("2200", "Customer credits / advances", AccountType.LIABILITY),
    EQUITY("3000", "Owner's equity", AccountType.EQUITY),
    RETAINED_EARNINGS("3100", "Retained earnings", AccountType.EQUITY),
    SALES("4000", "Sales revenue", AccountType.INCOME),
    SALES_RETURNS("4100", "Sales returns", AccountType.INCOME),
    SALES_DISCOUNTS("4200", "Sales discounts", AccountType.INCOME),
    COGS("5000", "Cost of goods sold", AccountType.EXPENSE),
    WASTAGE("5100", "Wastage and spoilage", AccountType.EXPENSE),
    COUNT_VARIANCE("5200", "Stock count variance", AccountType.EXPENSE),
    PPV("5210", "Purchase price variance", AccountType.EXPENSE),
    CASH_OVER_SHORT("5300", "Cash over / short", AccountType.EXPENSE),
    EXP_RENT("6000", "Rent", AccountType.EXPENSE),
    EXP_UTILITIES("6100", "Utilities", AccountType.EXPENSE),
    EXP_WAGES("6200", "Wages", AccountType.EXPENSE),
    EXP_TRANSPORT("6300", "Transport", AccountType.EXPENSE),
    EXP_PACKING("6400", "Packing materials", AccountType.EXPENSE),
    EXP_REPAIRS("6500", "Repairs", AccountType.EXPENSE),
    EXP_OTHER("6900", "Other expenses", AccountType.EXPENSE);

    private final String defaultCode;
    private final String defaultName;
    private final AccountType type;

    SystemKey(String defaultCode, String defaultName, AccountType type) {
        this.defaultCode = defaultCode;
        this.defaultName = defaultName;
        this.type = type;
    }

    public String defaultCode() {
        return defaultCode;
    }

    public String defaultName() {
        return defaultName;
    }

    public AccountType type() {
        return type;
    }

    /** The operating expense accounts (6000–6900), each with an expense category of its own. */
    public boolean isOperatingExpense() {
        return name().startsWith("EXP_");
    }
}

package com.amanahconnect.billing;

/** Kinds of gap-free document numbers. The prefix is the human-visible start of the number. */
public enum CounterType {
    INVOICE("INV"),
    RECEIPT("RCP"),
    MEMBER("MEM");

    private final String prefix;

    CounterType(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }
}

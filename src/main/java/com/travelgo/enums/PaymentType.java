package com.travelgo.enums;

public enum PaymentType {
    VISA_DOCUMENTATION,
    FULL_PACKAGE,
    /** Retained only so historical records remain readable; new installment payments are rejected. */
    @Deprecated PACKAGE_DEPOSIT,
    /** Retained only so historical records remain readable; new installment payments are rejected. */
    @Deprecated PACKAGE_BALANCE
}

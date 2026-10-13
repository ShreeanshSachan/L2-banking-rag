package com.example.demo.dto;

import java.math.BigDecimal;
import java.util.List;

public final class UserDataDtos {

    private UserDataDtos() {
    }

    public record AccountView(
            String accountNumber,
            String currency,
            BigDecimal balance
    ) {
    }

    public record BalanceResponse(
            List<AccountView> accounts
    ) {
    }

    public record CardView(
            Long id,
            String type,
            String last4,
            BigDecimal creditLimit,
            BigDecimal outstanding,
            String status
    ) {
    }

    public record LoanView(
            Long id,
            String productType,
            BigDecimal principal,
            BigDecimal interestRate,
            BigDecimal outstanding,
            String status
    ) {
    }
}

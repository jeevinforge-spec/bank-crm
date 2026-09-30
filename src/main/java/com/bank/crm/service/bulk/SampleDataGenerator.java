package com.bank.crm.service.bulk;

import com.bank.crm.model.AccountType;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Produces realistic, synthetic banking customers as CSV. Every run gets a unique token so
 * generated customer numbers and emails never collide with a previous run.
 * Optionally injects a percentage of deliberately bad rows to demonstrate error handling.
 */
@Component
public class SampleDataGenerator {

    private static final String[] FIRST = {"James", "Mary", "Aarav", "Priya", "Wei", "Mei", "Mohammed", "Fatima",
            "Carlos", "Sofia", "Liam", "Olivia", "Noah", "Emma", "Arjun", "Ananya", "Hiroshi", "Yuki", "Lucas",
            "Isabella", "Ethan", "Ava", "Rahul", "Divya", "Chen", "Lin", "Omar", "Aisha", "Mateo", "Valentina",
            "Oliver", "Charlotte", "Ravi", "Kavya", "Daniel", "Grace", "Samuel", "Chloe", "Vikram", "Meera"};
    private static final String[] LAST = {"Smith", "Johnson", "Patel", "Sharma", "Wang", "Li", "Khan", "Ahmed",
            "Garcia", "Rodriguez", "Brown", "Williams", "Nair", "Iyer", "Tanaka", "Sato", "Silva", "Santos",
            "Taylor", "Anderson", "Kumar", "Reddy", "Zhang", "Liu", "Hassan", "Ali", "Lopez", "Martinez",
            "Wilson", "Thomas", "Menon", "Pillai", "Nguyen", "Tran", "Clarke", "Evans", "Gupta", "Joshi"};
    private static final String[] STREETS = {"Main St", "High St", "Park Ave", "MG Road", "Church St", "Lake View Rd",
            "Station Rd", "King St", "Queen St", "Orchard Rd", "Market St", "Hill Rd", "Riverside Dr", "Elm St"};

    private record Place(String city, String state, String country, String dialCode, String postalPattern) {
    }

    private static final List<Place> PLACES = List.of(
            new Place("New York", "NY", "USA", "+1", "1####"),
            new Place("San Francisco", "CA", "USA", "+1", "94###"),
            new Place("Chicago", "IL", "USA", "+1", "606##"),
            new Place("Austin", "TX", "USA", "+1", "787##"),
            new Place("London", "England", "United Kingdom", "+44", "EC# #AB"),
            new Place("Manchester", "England", "United Kingdom", "+44", "M# #CD"),
            new Place("Mumbai", "Maharashtra", "India", "+91", "4000##"),
            new Place("Bengaluru", "Karnataka", "India", "+91", "5600##"),
            new Place("Kochi", "Kerala", "India", "+91", "6820##"),
            new Place("Chennai", "Tamil Nadu", "India", "+91", "6000##"),
            new Place("Toronto", "Ontario", "Canada", "+1", "M#A #B#"),
            new Place("Singapore", "Singapore", "Singapore", "+65", "0####"),
            new Place("Sydney", "NSW", "Australia", "+61", "2###"),
            new Place("Dubai", "Dubai", "UAE", "+971", "#####"));

    public String newRunToken() {
        // seconds since epoch in base36 (6 chars) + 1 random char: unique per run
        String t = Long.toString(System.currentTimeMillis() / 1000, 36).toUpperCase();
        return t.substring(Math.max(0, t.length() - 6))
                + Character.toUpperCase(Character.forDigit(ThreadLocalRandom.current().nextInt(36), 36));
    }

    public void write(Writer out, int rows, int invalidPercent) throws IOException {
        String token = newRunToken();
        Random rnd = new Random();
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader(CsvColumns.ALL.toArray(String[]::new)).get();
        try (CSVPrinter printer = new CSVPrinter(out, format)) {
            String previousNumber = null;
            for (int i = 1; i <= rows; i++) {
                List<Object> row = row(rnd, token, i);
                if (invalidPercent > 0 && rnd.nextInt(100) < invalidPercent) {
                    corrupt(row, rnd, previousNumber);
                }
                previousNumber = (String) row.get(0);
                printer.printRecord(row);
            }
        }
    }

    private List<Object> row(Random rnd, String token, int seq) {
        String first = pick(rnd, FIRST);
        String last = pick(rnd, LAST);
        Place place = PLACES.get(rnd.nextInt(PLACES.size()));
        AccountType type = weightedAccountType(rnd);

        int creditScore = (int) Math.max(300, Math.min(900, Math.round(690 + rnd.nextGaussian() * 80)));
        BigDecimal balance = money(Math.exp(8 + rnd.nextGaussian() * 1.4) * (type == AccountType.FIXED_DEPOSIT ? 5 : 1));
        BigDecimal income = money(Math.exp(10.8 + rnd.nextGaussian() * 0.6));
        String risk = creditScore < 580 ? "HIGH" : creditScore < 680 ? "MEDIUM" : "LOW";
        int kycRoll = rnd.nextInt(100);
        String kyc = kycRoll < 80 ? "VERIFIED" : kycRoll < 92 ? "PENDING" : kycRoll < 97 ? "EXPIRED" : "REJECTED";
        int statusRoll = rnd.nextInt(100);
        String status = statusRoll < 88 ? "ACTIVE" : statusRoll < 94 ? "DORMANT" : statusRoll < 98 ? "INACTIVE" : "CLOSED";
        LocalDate dob = LocalDate.now().minusYears(18 + rnd.nextInt(68)).minusDays(rnd.nextInt(365));

        return new java.util.ArrayList<>(List.of(
                "CN" + token + String.format("%07d", seq),
                first,
                last,
                (first + "." + last + "." + seq + "." + token + "@examplebank.test").toLowerCase(),
                place.dialCode() + " " + digits(rnd, 3) + " " + digits(rnd, 3) + " " + digits(rnd, 4),
                dob.toString(),
                (1 + rnd.nextInt(999)) + " " + pick(rnd, STREETS),
                place.city(),
                place.state(),
                postal(rnd, place.postalPattern()),
                place.country(),
                type.name(),
                balance.toPlainString(),
                income.toPlainString(),
                creditScore,
                kyc,
                risk,
                status,
                String.format("BR%03d", 1 + rnd.nextInt(40))));
    }

    /** Deliberately break one field so the loader's per-row error handling can be demonstrated. */
    private static void corrupt(List<Object> row, Random rnd, String previousNumber) {
        switch (rnd.nextInt(6)) {
            case 0 -> row.set(3, "not-an-email");                                   // invalid email
            case 1 -> row.set(2, "");                                               // missing last name
            case 2 -> row.set(14, 1200);                                            // credit score out of range
            case 3 -> row.set(5, LocalDate.now().plusYears(1).toString());          // DOB in the future
            case 4 -> row.set(11, "GOLD_PLUS");                                     // unknown account type
            default -> row.set(0, previousNumber != null ? previousNumber : "BAD"); // duplicate customer number
        }
    }

    private static AccountType weightedAccountType(Random rnd) {
        int r = rnd.nextInt(100);
        if (r < 45) return AccountType.SAVINGS;
        if (r < 65) return AccountType.SALARY;
        if (r < 82) return AccountType.CURRENT;
        if (r < 94) return AccountType.FIXED_DEPOSIT;
        return AccountType.NRI;
    }

    private static BigDecimal money(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static String postal(Random rnd, String pattern) {
        StringBuilder sb = new StringBuilder(pattern.length());
        for (char ch : pattern.toCharArray()) sb.append(ch == '#' ? (char) ('0' + rnd.nextInt(10)) : ch);
        return sb.toString();
    }

    private static String digits(Random rnd, int n) {
        StringBuilder sb = new StringBuilder(n);
        sb.append((char) ('2' + rnd.nextInt(8)));
        for (int i = 1; i < n; i++) sb.append((char) ('0' + rnd.nextInt(10)));
        return sb.toString();
    }

    private static String pick(Random rnd, String[] values) {
        return values[rnd.nextInt(values.length)];
    }
}

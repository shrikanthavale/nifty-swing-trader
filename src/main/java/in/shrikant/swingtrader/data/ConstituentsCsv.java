package in.shrikant.swingtrader.data;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parser for NSE's index constituents CSV (e.g. ind_nifty100list.csv from
 * niftyindices.com / NSE archives). Format:
 *
 *   Company Name,Industry,Symbol,Series,ISIN Code
 *   "Larsen & Toubro Ltd.",Construction,LT,EQ,INE018A01030
 *
 * Company names may be quoted and contain commas, so this is a small
 * quote-aware CSV parser rather than a String.split. Pure function — no I/O.
 */
public final class ConstituentsCsv {

    private ConstituentsCsv() {}

    /**
     * Extracts the EQ-series trading symbols from the CSV text.
     * Order-preserving, duplicates dropped.
     *
     * @throws IllegalArgumentException if the header doesn't look like an
     *         NSE constituents file (defends against NSE serving an HTML
     *         error page — blueprint §9 "stale data" thinking applied here).
     */
    public static Set<String> parseSymbols(String csvText) {
        if (csvText == null || csvText.isBlank()) {
            throw new IllegalArgumentException("Empty constituents CSV");
        }
        List<String> lines = csvText.lines().filter(l -> !l.isBlank()).toList();
        List<String> header = parseLine(lines.get(0));
        int symbolCol = indexOfIgnoreCase(header, "Symbol");
        int seriesCol = indexOfIgnoreCase(header, "Series");
        if (symbolCol < 0) {
            throw new IllegalArgumentException(
                    "Not an NSE constituents CSV — no 'Symbol' column in header: " + lines.get(0));
        }

        Set<String> symbols = new LinkedHashSet<>();
        for (int i = 1; i < lines.size(); i++) {
            List<String> fields = parseLine(lines.get(i));
            if (fields.size() <= symbolCol) continue;
            if (seriesCol >= 0 && fields.size() > seriesCol
                    && !"EQ".equalsIgnoreCase(fields.get(seriesCol).trim())) {
                continue;
            }
            String symbol = fields.get(symbolCol).trim();
            if (!symbol.isEmpty()) symbols.add(symbol);
        }
        if (symbols.isEmpty()) {
            throw new IllegalArgumentException("Constituents CSV parsed to zero symbols");
        }
        return symbols;
    }

    /** Minimal RFC-4180-ish line parser: quoted fields, "" escapes a quote. */
    static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else {
                switch (c) {
                    case '"' -> inQuotes = true;
                    case ',' -> {
                        fields.add(field.toString());
                        field.setLength(0);
                    }
                    default -> field.append(c);
                }
            }
        }
        fields.add(field.toString());
        return fields;
    }

    private static int indexOfIgnoreCase(List<String> header, String name) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).trim().equalsIgnoreCase(name)) return i;
        }
        return -1;
    }
}

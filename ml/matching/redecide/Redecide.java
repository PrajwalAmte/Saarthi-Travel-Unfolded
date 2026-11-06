package io.destinationintel.poi;

import io.destinationintel.poi.MatchEvidence.Decision;
import io.destinationintel.poi.MatchEvidence.Outcome;
import io.destinationintel.poi.MatchEvidence.Side;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Decides every exported pair with {@link MatchEvidence}, the rules the importer itself uses, and records
 * which evidence each pair had. It sits in the rules' package only to reach their package-private helpers,
 * and is compiled with the rules by run.sh, so the decisions cannot drift from the real ones.
 */
public final class Redecide {

    private Redecide() {}

    public static void main(String[] args) throws IOException {
        List<List<String>> rows = readCsv(Path.of(args[0]));
        Map<String, Integer> col = new HashMap<>();
        for (int i = 0; i < rows.get(0).size(); i++) {
            col.put(rows.get(0).get(i), i);
        }
        try (BufferedWriter out = Files.newBufferedWriter(Path.of(args[1]))) {
            out.write("record_id,poi_id,fixed_outcome,fixed_rule,phones_shared,phones_conflict,websites_shared,"
                    + "websites_conflict,chain_either,aggregated_either,brand_same\n");
            for (List<String> r : rows.subList(1, rows.size())) {
                Side a = new Side(r.get(col.get("name_a")), group(r.get(col.get("group_a"))), many(r.get(col.get("phone_a"))),
                        many(r.get(col.get("website_a"))), many(r.get(col.get("brand_a"))));
                Side b = new Side(r.get(col.get("name_b")), group(r.get(col.get("group_b"))), many(r.get(col.get("phones_b"))),
                        many(r.get(col.get("websites_b"))), many(r.get(col.get("brands_b"))));
                double distance = Double.parseDouble(r.get(col.get("distance_m")));
                Decision d = MatchEvidence.decide(a, b, distance);

                Set<String> pa = a.aggregated() ? Set.of() : MatchEvidence.phones(a.phones());
                Set<String> pb = b.aggregated() ? Set.of() : MatchEvidence.phones(b.phones());
                Set<String> wa = a.aggregated() ? Set.of() : MatchEvidence.domains(a.websites());
                Set<String> wb = b.aggregated() ? Set.of() : MatchEvidence.domains(b.websites());
                boolean phonesShared = overlap(pa, pb);
                boolean websitesShared = overlap(wa, wb);
                out.write(String.join(",", r.get(col.get("record_id")), r.get(col.get("poi_id")),
                        d.outcome() == Outcome.SAME ? "same" : "different", d.rule(),
                        flag(phonesShared), flag(!pa.isEmpty() && !pb.isEmpty() && !phonesShared),
                        flag(websitesShared), flag(!wa.isEmpty() && !wb.isEmpty() && !websitesShared),
                        flag(a.chain() || b.chain()), flag(a.aggregated() || b.aggregated()),
                        flag(overlap(lower(a.brands()), lower(b.brands())))) + "\n");
            }
        }
    }

    private static String group(String text) {
        return text.isBlank() ? null : text;
    }

    /** The values of one field that holds several, joined with " | ". */
    private static Set<String> many(String text) {
        Set<String> out = new HashSet<>();
        for (String part : text.split(" \\| ")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static Set<String> lower(Set<String> in) {
        Set<String> out = new HashSet<>();
        in.forEach(s -> out.add(s.toLowerCase(Locale.ROOT).trim()));
        return out;
    }

    private static boolean overlap(Set<String> a, Set<String> b) {
        return a.stream().anyMatch(b::contains);
    }

    private static String flag(boolean value) {
        return value ? "1" : "0";
    }

    /** RFC 4180: quoted fields may hold commas, quotes and line breaks. */
    static List<List<String>> readCsv(Path path) throws IOException {
        String text = Files.readString(path);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(field.toString());
                field.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(field.toString());
                field.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                field.append(c);
            }
        }
        if (field.length() > 0 || !row.isEmpty()) {
            row.add(field.toString());
            rows.add(row);
        }
        return rows;
    }
}

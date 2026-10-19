package io.destinationintel.poi;

import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Decides an uncertain match between a source record and an existing mapped place from evidence,
 * applying ordered rules. Each decision names the rule that made it, so every resolution can be
 * audited and its precision sampled per rule.
 *
 * <ol>
 *   <li>Different kinds of place are distinct, except a hotel and an eatery with matching names close
 *       together: in Indian usage a "hotel" is often a restaurant, and sources disagree.</li>
 *   <li>A chain brand (not a booking aggregator) more than 75 m away is another branch; chains share
 *       head-office phone numbers and websites, so these say nothing about identity.</li>
 *   <li>A shared phone number or website is the same place (aggregator call centres and booking
 *       platforms excluded).</li>
 *   <li>The same core name (generic words, spacing, punctuation and listing numbers removed) within
 *       75 m is the same place.</li>
 *   <li>Both sides list their own phone numbers, none shared, more than 75 m apart: distinct.</li>
 *   <li>The same core name within 400 m is the same place.</li>
 *   <li>A core name one or two letters apart (transliteration, typos) within 150 m, or one letter apart
 *       for longer names within 400 m, is the same place.</li>
 *   <li>One core name containing the other within 75 m is the same place.</li>
 *   <li>Within 150 m, every identifying word of the shorter name appearing in the longer one (ignoring
 *       words such as "bus stand" or "municipal park") is the same place.</li>
 *   <li>Anything else: the names differ too much for the distance, so the places are distinct.</li>
 * </ol>
 */
public final class MatchEvidence {

    public enum Outcome { SAME, DISTINCT }

    public record Decision(Outcome outcome, String rule) {}

    /** One side of a pair: names, category group, contact details and brands from all of its source records. */
    public record Side(String name, String group, Set<String> phones, Set<String> websites, Set<String> brands) {

        /** Convenience for a side known only to be, or not to be, part of a chain. */
        public Side(String name, String group, Set<String> phones, Set<String> websites, boolean chain) {
            this(name, group, phones, websites, chain ? Set.of("chain") : Set.of());
        }

        /** Listed through a booking aggregator: the phone and website are the aggregator's, not the hotel's. */
        boolean aggregated() {
            return isAggregator(name) || brands.stream().anyMatch(MatchEvidence::isAggregator);
        }

        boolean chain() {
            return !aggregated() && !brands.isEmpty();
        }
    }

    /** Words that describe the kind of business rather than identify it. */
    private static final Set<String> GENERIC = Set.of("the", "hotel", "hotels", "restaurant", "restaurants", "resort",
            "resorts", "inn", "lodge", "cafe", "bar", "family", "and", "new", "sri", "shri", "sree", "shree", "pvt", "ltd",
            "private", "limited", "of", "at", "a", "pure", "veg", "co", "stay", "stays", "homestay", "guest", "house",
            "guesthouse", "rooms", "room", "by", "oyo", "collection", "o", "spot", "on", "capital", "fabhotel", "fabhotels",
            "treebo", "townhouse", "flagship");

    /** Words naming the facility type, ignored when comparing the identifying words of two names. */
    private static final Set<String> DESCRIPTORS = Set.of("bus", "stand", "busstand", "busstop", "stop", "station",
            "railway", "rly", "junction", "jn", "terminal", "terminus", "depot", "park", "municipal", "garden", "gardens",
            "udyan", "ground", "ksrtc", "msrtc", "apsrtc", "tsrtc", "upsrtc", "hrtc", "rsrtc", "bmc", "nbstc", "museum",
            "hostel", "dormitory", "mall", "market", "shop", "store", "centre", "center", "point", "dr", "mr", "st",
            "bakery", "bakeries", "bakers", "sweets", "dhaba", "complex", "shopping", "plaza", "corner");

    /** Booking aggregators whose listings are independent hotels, not branches of a chain. */
    private static final List<String> AGGREGATORS = List.of("oyo", "fabhotel", "fabhotels", "treebo", "capital o",
            "collection o", "spot on", "townhouse", "super oyo", "flagship", "zo rooms", "vista rooms");

    static boolean isAggregator(String s) {
        if (s == null) {
            return false;
        }
        String l = s.toLowerCase(Locale.ROOT).trim();
        return AGGREGATORS.stream().anyMatch(a -> l.equals(a) || l.startsWith(a + " ") || l.startsWith(a + "-"));
    }

    /** Domains shared by many unrelated businesses, which say nothing about identity. */
    private static final Set<String> SHARED_DOMAINS = Set.of("facebook.com", "instagram.com", "google.com", "goo.gl",
            "zomato.com", "swiggy.com", "booking.com", "makemytrip.com", "goibibo.com", "tripadvisor.com", "tripadvisor.in",
            "justdial.com", "oyorooms.com", "airbnb.com", "airbnb.co.in", "agoda.com", "expedia.com", "linktr.ee", "wa.me",
            "whatsapp.com", "blogspot.com", "wordpress.com", "wixsite.com", "business.site", "sites.google.com",
            "youtube.com", "twitter.com", "x.com", "linkedin.com", "magicpin.in", "dineout.co.in", "yatra.com",
            "cleartrip.com", "trivago.in", "hotels.com", "treebo.com", "fabhotels.com");

    private MatchEvidence() {}

    public static Decision decide(Side record, Side candidate, double distanceM) {
        String a = core(record.name());
        String b = core(candidate.name());
        boolean usable = a.length() >= 3 && b.length() >= 3;
        if (!PoiCategories.compatibleGroups(record.group(), candidate.group())
                && !(hotelOrEatery(record.group(), candidate.group()) && distanceM <= 75 && usable && namesClose(a, b))) {
            return new Decision(Outcome.DISTINCT, "different_kind_of_place");
        }
        if ((record.chain() || candidate.chain()) && distanceM > 75) {
            return new Decision(Outcome.DISTINCT, "chain_branch");
        }
        Set<String> pa = record.aggregated() ? Set.of() : phones(record.phones());
        Set<String> pb = candidate.aggregated() ? Set.of() : phones(candidate.phones());
        if (overlaps(pa, pb)) {
            return new Decision(Outcome.SAME, "shared_phone");
        }
        Set<String> wa = record.aggregated() ? Set.of() : domains(record.websites());
        Set<String> wb = candidate.aggregated() ? Set.of() : domains(candidate.websites());
        if (overlaps(wa, wb)) {
            return new Decision(Outcome.SAME, "shared_website");
        }
        if (usable && distanceM <= 75 && (a.equals(b) || reordered(record.name(), b) || reordered(candidate.name(), a))) {
            return new Decision(Outcome.SAME, "same_name_same_spot");
        }
        if (!pa.isEmpty() && !pb.isEmpty() && distanceM > 75) {
            return new Decision(Outcome.DISTINCT, "different_phones");
        }
        if (usable && a.equals(b) && distanceM <= 400) {
            return new Decision(Outcome.SAME, "same_name_nearby");
        }
        if (usable && Math.min(a.length(), b.length()) >= 6) {
            int d = levenshtein(a, b, 3);
            int allowed = Math.min(a.length(), b.length()) >= 10 ? 2 : 1;
            if ((d <= allowed && distanceM <= 150) || (d <= 2 && Math.min(a.length(), b.length()) >= 7 && distanceM <= 75)
                    || (d <= 1 && Math.min(a.length(), b.length()) >= 8 && distanceM <= 400)) {
                return new Decision(Outcome.SAME, "spelling_variant");
            }
        }
        if (usable && distanceM <= 75 && Math.min(a.length(), b.length()) >= 5 && (a.contains(b) || b.contains(a))) {
            return new Decision(Outcome.SAME, "name_contains_other");
        }
        if (distanceM <= 150 && wordsContained(record.name(), candidate.name(), distanceM <= 50 ? 4 : 6)) {
            return new Decision(Outcome.SAME, "name_words_match");
        }
        return new Decision(Outcome.DISTINCT, distanceM > 150 ? "too_far_for_weak_name_match" : "names_differ");
    }

    /** Whether some order of the name's words (up to three) spells the other core: "Exp Hostel" and "HostelExp". */
    static boolean reordered(String name, String otherCore) {
        List<String> words = new ArrayList<>();
        if (name == null) {
            return false;
        }
        String s = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        for (String t : s.split("[^\\p{L}\\p{N}]+")) {
            if (!t.isEmpty() && !Set.of("the", "and", "of", "a").contains(t)) {
                words.add(t);
            }
        }
        if (words.size() < 2 || words.size() > 3) {
            return false;
        }
        List<List<Integer>> orders = words.size() == 2 ? List.of(List.of(0, 1), List.of(1, 0))
                : List.of(List.of(0, 1, 2), List.of(0, 2, 1), List.of(1, 0, 2), List.of(1, 2, 0), List.of(2, 0, 1), List.of(2, 1, 0));
        for (List<Integer> order : orders) {
            StringBuilder b = new StringBuilder();
            order.forEach(i -> b.append(words.get(i)));
            if (b.length() >= 6 && (b.toString().equals(otherCore) || core(b.toString()).equals(otherCore)
                    || b.toString().equals(otherCore.replace(" ", "")))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hotelOrEatery(String g1, String g2) {
        Set<String> pair = Set.of(g1 == null ? "" : g1, g2 == null ? "" : g2);
        return pair.contains("stay") && (pair.contains("eat") || pair.contains("drink"));
    }

    private static boolean namesClose(String a, String b) {
        return a.equals(b) || (Math.min(a.length(), b.length()) >= 5 && (a.contains(b) || b.contains(a)))
                || (Math.min(a.length(), b.length()) >= 6 && levenshtein(a, b, 2) <= 1);
    }

    /** Identifying words: no generic or facility words, single letters or long numbers. */
    static List<String> identifyingWords(String name) {
        List<String> out = new ArrayList<>();
        if (name == null) {
            return out;
        }
        String s = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        for (String t : s.split("[^\\p{L}\\p{N}]+")) {
            if (t.length() > 1 && !GENERIC.contains(t) && !DESCRIPTORS.contains(t) && !t.matches("\\d{4,}")) {
                out.add(t);
            }
        }
        return out;
    }

    /**
     * Whether every identifying word of the shorter name appears in the longer one (allowing a one-letter
     * difference in words of five letters or more), with enough identifying text to be meaningful.
     */
    static boolean wordsContained(String n1, String n2, int minIdentifyingLetters) {
        List<String> w1 = identifyingWords(n1);
        List<String> w2 = identifyingWords(n2);
        List<String> shorter = w1.size() <= w2.size() ? w1 : w2;
        List<String> longer = shorter == w1 ? w2 : w1;
        if (shorter.isEmpty() || String.join("", shorter).length() < minIdentifyingLetters) {
            return false;
        }
        for (String w : shorter) {
            boolean found = longer.stream().anyMatch(l -> l.equals(w) || (Math.min(w.length(), l.length()) >= 4 && levenshtein(w, l, 1) <= 1));
            if (!found) {
                return false;
            }
        }
        return true;
    }

    /** Lower-case letters and digits of the identifying words, without spaces: "Hotel Anna Maya" → "annamaya". */
    static String core(String name) {
        if (name == null) {
            return "";
        }
        String s = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT)
                .replace("&", " and ");
        StringBuilder out = new StringBuilder();
        for (String token : s.split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty() && !GENERIC.contains(token) && !token.matches("\\d{4,}")) {
                out.append(token);
            }
        }
        return out.toString();
    }

    /** Last ten digits of each number (Indian mobile and STD landline numbers are ten digits). */
    static Set<String> phones(Set<String> raw) {
        Set<String> out = new HashSet<>();
        for (String p : raw) {
            for (String part : p.split("[;,/]")) {
                String digits = part.replaceAll("\\D", "");
                if (digits.length() >= 10) {
                    out.add(digits.substring(digits.length() - 10));
                }
            }
        }
        return out;
    }

    /** Registrable domains of websites, ignoring platforms shared by many businesses. */
    static Set<String> domains(Set<String> raw) {
        Set<String> out = new HashSet<>();
        for (String w : raw) {
            try {
                String host = URI.create(w.trim().startsWith("http") ? w.trim() : "https://" + w.trim()).getHost();
                if (host == null) {
                    continue;
                }
                host = host.toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
                List<String> labels = new ArrayList<>(List.of(host.split("\\.")));
                int keep = labels.size() >= 3 && Set.of("co", "org", "net", "gov", "ac", "edu").contains(labels.get(labels.size() - 2))
                        ? 3 : 2;
                String domain = String.join(".", labels.subList(Math.max(0, labels.size() - keep), labels.size()));
                if (!SHARED_DOMAINS.contains(domain) && !SHARED_DOMAINS.contains(host)) {
                    out.add(domain);
                }
            } catch (IllegalArgumentException e) {
                // Not a usable address; it gives no evidence either way.
            }
        }
        return out;
    }

    private static boolean overlaps(Set<String> a, Set<String> b) {
        for (String x : a) {
            if (b.contains(x)) {
                return true;
            }
        }
        return false;
    }

    /** Edit distance, stopping early once it exceeds {@code max}. */
    static int levenshtein(String a, String b, int max) {
        if (Math.abs(a.length() - b.length()) > max) {
            return max + 1;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            int rowMin = cur[0];
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                rowMin = Math.min(rowMin, cur[j]);
            }
            if (rowMin > max) {
                return max + 1;
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}

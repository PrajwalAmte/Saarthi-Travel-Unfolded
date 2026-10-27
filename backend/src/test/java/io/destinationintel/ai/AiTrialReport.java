package io.destinationintel.ai;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.destinationintel.common.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Not a regular test: turns saved model answers for exported destinations into a before/after report, using the
 * same parsing and faithfulness checks as production. Runs only with -Dai.trial.dir=... (and -Dai.trial.out=...).
 */
class AiTrialReport {

    @Test
    void report() throws Exception {
        String dir = System.getProperty("ai.trial.dir");
        assumeTrue(dir != null && !dir.isBlank(), "trial directory not given");
        JsonMapper mapper = JsonMapper.builder().build();
        Json json = new Json(mapper);
        ListingReviewHandler handler = new ListingReviewHandler(null, null, null, null, json, null, null, null, null, null);
        OverviewWriter overview = new OverviewWriter(null, null, json);
        StringBuilder md = new StringBuilder("# AI content trial\n\n");
        for (String slug : System.getProperty("ai.trial.slugs", "new-delhi,jaipur,kochi").split(",")) {
            Path base = Path.of(dir);
            String name = Files.readString(base.resolve(slug + "-name.txt")).strip();
            Map<String, JsonNode> listings = new TreeMap<>();
            for (JsonNode l : mapper.readTree(Files.readString(base.resolve(slug + "-listings.json")))) {
                listings.put(l.path("id").asString(), l);
            }
            int reviewed = 0, flagged = 0, accepted = 0, rejected = 0, notNeeded = 0, facts = 0;
            Map<String, Integer> tagCounts = new TreeMap<>();
            List<String> summaries = new ArrayList<>(), rejections = new ArrayList<>(), flags = new ArrayList<>(), found = new ArrayList<>();
            try (var files = Files.list(base.resolve("answers"))) {
                for (Path f : files.filter(p -> p.getFileName().toString().matches(slug + "-\\d+\\.json")).sorted().toList()) {
                    JsonNode a = mapper.readTree(Files.readString(f));
                    List<String> ids = new ArrayList<>();
                    a.path("entryIds").forEach(n -> ids.add(n.asString()));
                    var results = handler.parseResults(a.path("answer").asString(), ids.size());
                    for (int k = 0; k < ids.size(); k++) {
                        var r = results.get(k + 1);
                        if (r == null) {
                            continue;
                        }
                        JsonNode l = listings.get(ids.get(k));
                        var listing = new ListingReviewHandler.Listing(UUID.fromString(ids.get(k)), l.path("section").asString(),
                                l.path("title").asString(), l.path("area").isNull() ? null : l.path("area").asString(),
                                l.path("description").isNull() ? null : l.path("description").asString(),
                                l.path("address").isNull() ? null : l.path("address").asString(), null, l.path("fact_kinds").asString(""));
                        reviewed++;
                        String label = "**" + listing.title() + "** (" + listing.section() + ")";
                        if (r.verdict().flagged(listing.hasText())) {
                            flagged++;
                            flags.add("- " + label + ": " + String.join(", ", r.verdict().issues()) + (r.verdict().fitsSection() ? "" : " · does not fit its section")
                                    + " — “" + r.verdict().reason() + "” (" + Math.round(r.verdict().confidence() * 100) + "%)");
                        }
                        var e = ListingReviewHandler.enrich(listing, r, name.replaceAll(" \\(.*", ""));
                        switch (e.summaryStatus()) {
                            case "accepted" -> {
                                accepted++;
                                summaries.add("| " + label + " | " + cell(listing.description()) + " | " + cell(e.summary()) + " |");
                            }
                            case "rejected" -> {
                                rejected++;
                                rejections.add("- " + label + ": “" + e.summary() + "” — rejected: " + e.rejection());
                            }
                            default -> notNeeded++;
                        }
                        e.tags().forEach(t -> tagCounts.merge(t.tag(), 1, Integer::sum));
                        for (var x : e.facts()) {
                            facts++;
                            found.add("- " + label + " — " + x.kind() + ": “" + x.quote() + "”");
                        }
                    }
                }
            }
            md.append("## ").append(name).append("\n\n")
                    .append(reviewed).append(" listings reviewed · ").append(flagged).append(" flagged · summaries: ")
                    .append(accepted).append(" kept, ").append(rejected).append(" rejected by the checks, ").append(notNeeded)
                    .append(" not needed (short text) · ").append(facts).append(" prices/hours found in text · tags: ")
                    .append(tagCounts).append("\n\n");

            List<OverviewWriter.SourceFact> sourceFacts = new ArrayList<>();
            JsonNode fs = mapper.readTree(Files.readString(base.resolve(slug + "-facts.json")));
            for (JsonNode f : fs) {
                sourceFacts.add(new OverviewWriter.SourceFact(UUID.fromString(f.path("id").asString()), f.path("kind").asString(),
                        f.path("section").asString(), f.path("text").asString()));
            }
            List<Map<String, Object>> ok = new ArrayList<>(), bad = new ArrayList<>();
            overview.validate(mapper.readTree(Files.readString(base.resolve("answers/" + slug + "-overview.json"))).path("answer").asString(),
                    sourceFacts, name.replaceAll(" \\(.*", ""), name.contains("(") ? name.replaceAll(".*\\((.*)\\)", "$1") : null, ok, bad);
            md.append("### In short\n\n");
            ok.forEach(o -> md.append("- **").append(o.get("topic")).append("**: ").append(o.get("text")).append(" _(cites ")
                    .append(((List<?>) o.get("factIds")).size()).append(" fact(s))_\n"));
            bad.forEach(o -> md.append("- ~~").append(o.get("text")).append("~~ — rejected: ").append(o.get("reason")).append('\n'));
            md.append("\n### Flagged for review\n\n").append(flags.isEmpty() ? "_none_\n" : String.join("\n", flags) + "\n");
            md.append("\n### Summaries kept\n\n| Listing | Original | AI summary |\n|---|---|---|\n")
                    .append(String.join("\n", summaries)).append("\n\n### Summaries rejected by the checks\n\n")
                    .append(rejections.isEmpty() ? "_none_\n" : String.join("\n", rejections) + "\n")
                    .append("\n### Prices and hours found in text\n\n").append(found.isEmpty() ? "_none_\n" : String.join("\n", found) + "\n").append("\n");
        }
        Files.writeString(Path.of(System.getProperty("ai.trial.out", dir + "/report.md")), md.toString());
    }

    private static String cell(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }
}

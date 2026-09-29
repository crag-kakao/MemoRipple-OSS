// Local LLM Phase 0 — dataset validator and field-level scorer (docs/LLM_PHASE0.md §6).
//
// Single-file Java (JDK 17 source launch, no dependencies; the host has no working python):
//   java tools/llm-eval/scorer/LlmEval.java validate <golden.jsonl>
//   java tools/llm-eval/scorer/LlmEval.java score <golden.jsonl> <results.jsonl> [<summary.json>]
//   java tools/llm-eval/scorer/LlmEval.java selftest
//
// Scoring (fixed before any model output was seen; never tuned to a model afterwards):
//   * validity is recorded at four levels per case — grammar (the generation finished under the
//     grammar at an end-of-generation token), json (the text parses), schema (the exact keys,
//     enums, ref pattern, no digits in dateToken, ref within context), semantic (every field right);
//   * a field is right when, after NFKC + whitespace folding, it equals the expected value or one
//     of the case's `accept` alternatives; missingFields compares as a set;
//   * an expected-null field that comes back non-null (and is not an accepted alternative) is a
//     hallucinated parameter; a template field that is filled although nothing was said is a
//     hallucinated field; a filled template field whose characters mostly do not occur in the
//     input is an invented value — all counted separately from plain misses;
//   * required-field precision / recall: required = expected non-null fields; TP = right,
//     FN = expected non-null but null or wrong, FP = non-null output where null was expected;
//   * ambiguous cases pass when the output is UNKNOWN with every other field null, or matches;
//     CREATE / APPEND with a filled text where the expectation was not that intent is "unsafe".
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.*;
import java.util.stream.Collectors;

public class LlmEval {
    static final List<String> INTENT_FIELDS = List.of("intent", "query", "targetRef", "targetName", "documentKind", "text", "templateId", "dateToken", "missingFields");
    static final Set<String> INTENTS = Set.of("SEARCH", "OPEN", "CREATE", "APPEND", "USE_TEMPLATE", "UNKNOWN");
    static final Set<String> KINDS = Set.of("MEMO", "OUTLINE", "JOURNAL");
    static final Set<String> DATE_TOKENS = Set.of("TODAY", "YESTERDAY", "THIS_WEEK", "LAST_WEEK");
    static final Set<String> MISSING_NAMES = Set.of("query", "targetRef", "targetName", "text", "templateId", "documentKind");
    static final Set<String> CATEGORIES = Set.of("intent", "param", "date", "template", "ambiguous", "hallucination");
    static final Map<String, List<String>> TEMPLATE_FIELDS = Map.of(
        "daily_journal", List.of("events", "feeling", "reflection"),
        "idea", List.of("title", "summary", "nextStep"),
        "character", List.of("name", "personality", "appearance"));
    static final Map<String, List<String>> TEMPLATE_REQUIRED = Map.of(
        "daily_journal", List.of("events", "feeling"),
        "idea", List.of("title", "summary"),
        "character", List.of("name"));

    public static void main(String[] args) throws Exception {
        if (args.length == 0) { usage(); return; }
        switch (args[0]) {
            case "validate" -> System.exit(validate(readJsonl(Path.of(args[1]))) ? 0 : 1);
            case "score" -> {
                List<Map<String, Object>> golden = readJsonl(Path.of(args[1]));
                List<Map<String, Object>> results = readJsonl(Path.of(args[2]));
                Map<String, Object> summary = score(golden, results, true);
                if (args.length > 3) Files.writeString(Path.of(args[3]), Json.write(summary, 0) + "\n", StandardCharsets.UTF_8);
            }
            case "parity" -> System.exit(Parity.run(readJsonl(Path.of(args[1])), readJsonl(Path.of(args[2])), readJsonl(Path.of(args[3]))) ? 0 : 1);
            case "selftest" -> selfTest();
            default -> usage();
        }
    }

    static void usage() {
        System.out.println("usage: LlmEval validate <golden.jsonl> | score <golden.jsonl> <results.jsonl> [summary.json] | parity <golden.jsonl> <a.jsonl> <b.jsonl> | selftest");
    }

    // ---------------------------------------------------------------- validate

    static boolean validate(List<Map<String, Object>> golden) {
        List<String> problems = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Map<String, Integer> counts = new TreeMap<>();
        for (Map<String, Object> rec : golden) {
            String id = str(rec.get("id"));
            if (id == null) { problems.add("record without id: " + rec); continue; }
            if (!ids.add(id)) problems.add(id + ": duplicate id");
            String cat = str(rec.get("category"));
            if (cat == null || !CATEGORIES.contains(cat)) { problems.add(id + ": bad category " + cat); continue; }
            counts.merge(cat, 1, Integer::sum);
            if (!id.startsWith(cat.equals("hallucination") ? "halluc_" : cat + "_")) problems.add(id + ": id prefix does not match category " + cat);
            if (str(rec.get("input")) == null || str(rec.get("input")).isEmpty()) problems.add(id + ": empty input");
            for (String key : rec.keySet()) if (!Set.of("id", "category", "input", "context", "expected", "notes", "accept").contains(key)) problems.add(id + ": unknown key " + key);
            Map<String, Object> expected = obj(rec.get("expected"));
            if (expected == null) { problems.add(id + ": no expected"); continue; }
            Map<String, Object> context = obj(rec.get("context"));
            Map<String, Object> accept = obj(rec.get("accept"));
            boolean templateMode = cat.equals("template") || cat.equals("hallucination");
            if (templateMode) validateTemplateExpected(id, expected, context, accept, problems);
            else validateIntentExpected(id, cat, expected, context, accept, problems);
        }
        Map<String, Integer> target = Map.of("intent", 50, "param", 40, "date", 30, "template", 30, "ambiguous", 20, "hallucination", 20);
        for (var e : target.entrySet()) if (!Objects.equals(counts.get(e.getKey()), e.getValue())) problems.add("category " + e.getKey() + ": " + counts.get(e.getKey()) + " cases, target " + e.getValue());
        System.out.println("records: " + golden.size() + " " + counts);
        problems.forEach(p -> System.out.println("PROBLEM " + p));
        System.out.println(problems.isEmpty() ? "VALID" : "INVALID (" + problems.size() + ")");
        return problems.isEmpty();
    }

    static void validateIntentExpected(String id, String cat, Map<String, Object> e, Map<String, Object> ctx, Map<String, Object> accept, List<String> problems) {
        if (!new ArrayList<>(e.keySet()).equals(INTENT_FIELDS)) problems.add(id + ": expected keys must be exactly " + INTENT_FIELDS + " in order, got " + e.keySet());
        String intent = str(e.get("intent"));
        if (intent == null || !INTENTS.contains(intent)) problems.add(id + ": bad intent " + intent);
        if (e.get("documentKind") != null && !KINDS.contains(str(e.get("documentKind")))) problems.add(id + ": bad documentKind");
        if (e.get("dateToken") != null && !DATE_TOKENS.contains(str(e.get("dateToken")))) problems.add(id + ": dateToken must be a token, got " + e.get("dateToken"));
        List<String> refs = contextRefs(ctx);
        String ref = str(e.get("targetRef"));
        if (ref != null && !refs.contains(ref)) problems.add(id + ": targetRef " + ref + " not among context results " + refs);
        if (e.get("targetRef") != null && e.get("targetName") != null) problems.add(id + ": targetRef and targetName both set");
        List<Object> missing = arr(e.get("missingFields"));
        if (missing == null) problems.add(id + ": missingFields must be an array");
        else for (Object m : missing) if (!MISSING_NAMES.contains(str(m))) problems.add(id + ": missingFields names a non-field " + m);
        if ("UNKNOWN".equals(intent)) for (String f : INTENT_FIELDS) if (!f.equals("intent") && !f.equals("missingFields") && !f.equals("documentKind") && e.get(f) != null) problems.add(id + ": UNKNOWN with a filled " + f);
        if (e.get("text") != null && !("CREATE".equals(intent) || "APPEND".equals(intent))) problems.add(id + ": text on a non-CREATE/APPEND intent");
        if ("USE_TEMPLATE".equals(intent) && e.get("templateId") != null && !TEMPLATE_FIELDS.containsKey(str(e.get("templateId")))) problems.add(id + ": unknown templateId");
        if (accept != null) for (var a : accept.entrySet()) {
            if (!INTENT_FIELDS.contains(a.getKey())) problems.add(id + ": accept names a non-field " + a.getKey());
            if (arr(a.getValue()) == null || arr(a.getValue()).isEmpty()) problems.add(id + ": accept." + a.getKey() + " must be a non-empty array");
            else if (a.getKey().equals("missingFields")) for (Object alt : arr(a.getValue())) if (arr(alt) == null) problems.add(id + ": accept.missingFields alternatives must be arrays");
            else if (a.getKey().equals("intent")) for (Object alt2 : arr(a.getValue())) if (!INTENTS.contains(str(alt2))) problems.add(id + ": accept.intent has a non-intent " + alt2);
            else if (a.getKey().equals("targetRef")) for (Object alt3 : arr(a.getValue())) if (alt3 != null && !refs.contains(str(alt3))) problems.add(id + ": accept.targetRef outside context");
        }
    }

    static void validateTemplateExpected(String id, Map<String, Object> e, Map<String, Object> ctx, Map<String, Object> accept, List<String> problems) {
        String templateId = ctx == null ? null : str(ctx.get("template"));
        if (templateId == null || !TEMPLATE_FIELDS.containsKey(templateId)) { problems.add(id + ": context.template must name a known template"); return; }
        if (!templateId.equals(str(e.get("templateId")))) problems.add(id + ": expected.templateId differs from context.template");
        if (!new ArrayList<>(e.keySet()).equals(List.of("templateId", "fields", "missingFields"))) problems.add(id + ": expected keys must be templateId, fields, missingFields");
        Map<String, Object> fields = obj(e.get("fields"));
        List<String> schema = TEMPLATE_FIELDS.get(templateId);
        if (fields == null || !new ArrayList<>(fields.keySet()).equals(schema)) problems.add(id + ": fields must be exactly " + schema + " in order");
        List<Object> missing = arr(e.get("missingFields"));
        if (missing == null) { problems.add(id + ": missingFields must be an array"); return; }
        for (Object m : missing) if (!schema.contains(str(m))) problems.add(id + ": missingFields names a non-field " + m);
        if (fields != null) {
            for (String req : TEMPLATE_REQUIRED.get(templateId))
                if (fields.get(req) == null && !missing.contains(req)) problems.add(id + ": required " + req + " is null but not in missingFields");
            for (Object m : missing) if (fields.get(str(m)) != null) problems.add(id + ": " + m + " is both filled and missing");
        }
        if (accept != null) for (var a : accept.entrySet()) {
            String k = a.getKey();
            boolean ok = k.equals("missingFields") || (k.startsWith("fields.") && schema.contains(k.substring(7)));
            if (!ok) problems.add(id + ": accept names a non-field " + k);
            if (arr(a.getValue()) == null || arr(a.getValue()).isEmpty()) problems.add(id + ": accept." + k + " must be a non-empty array");
        }
    }

    static List<String> contextRefs(Map<String, Object> ctx) {
        List<String> refs = new ArrayList<>();
        if (ctx == null || arr(ctx.get("results")) == null) return refs;
        for (Object line : arr(ctx.get("results"))) {
            String s = str(line);
            int colon = s.indexOf(':');
            refs.add(colon > 0 ? s.substring(0, colon).trim() : s.trim());
        }
        return refs;
    }

    // ---------------------------------------------------------------- score

    static final class CaseScore {
        String id, category, mode;
        boolean grammar, json, schema, semantic, parse, unsafe;
        boolean intentExact;
        Map<String, Boolean> fields = new LinkedHashMap<>();
        int tp, fp, fn, hallucinatedParams, hallucinatedFields, inventedValues, partialValues, invalidRefs, queryOverfill;
        /** Major safety failures (counted apart from field errors): invented reference, an unsupported
         *  operation turned into an executable intent, an invented diary fact, a non-token date, a
         *  target asserted without enough context. */
        int unsupportedOperation, assertedTarget, nonTokenDate;
        Boolean unknownSafe;                 // expected UNKNOWN: came back UNKNOWN (or accepted)
        /** Lenient companion of `semantic`: intent right, every expected non-null field right, no major
         *  failure — extras (an echoed query, a guessed kind, loose missingFields) are ignored here. */
        boolean executable;
        /** Every field the (expected) intent needs to execute is present in the output; n/a for UNKNOWN and template cases. */
        Boolean requiredComplete;
        List<String> notes = new ArrayList<>();
        double ttftMs, tps; String stop = "";
    }

    static Map<String, Object> score(List<Map<String, Object>> golden, List<Map<String, Object>> results, boolean print) {
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        for (Map<String, Object> r : results) byId.put(str(r.get("id")), r);
        List<CaseScore> scores = new ArrayList<>();
        for (Map<String, Object> g : golden) {
            Map<String, Object> r = byId.get(str(g.get("id")));
            if (r == null) continue;
            scores.add(scoreCase(g, r));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("goldenCases", golden.size());
        summary.put("scoredCases", scores.size());
        summary.put("total", aggregate(scores));
        Map<String, Object> perCategory = new LinkedHashMap<>();
        for (String cat : List.of("intent", "param", "date", "template", "ambiguous", "hallucination"))
            perCategory.put(cat, aggregate(scores.stream().filter(s -> s.category.equals(cat)).collect(Collectors.toList())));
        summary.put("perCategory", perCategory);
        List<Object> failures = new ArrayList<>();
        for (CaseScore s : scores) if (!s.semantic) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("id", s.id); f.put("notes", s.notes);
            failures.add(f);
        }
        summary.put("failures", failures);
        if (print) {
            System.out.println(String.format("%-14s %5s %7s %6s %6s %6s %6s %7s %6s %6s %6s %6s %6s", "category", "n", "grammar", "json", "schema", "semant", "intent", "reqCmpl", "exec", "halluc", "qOver", "major", "unkSafe"));
            for (var e : perCategory.entrySet()) printRow(e.getKey(), obj(e.getValue()));
            printRow("TOTAL", obj(summary.get("total")));
            for (CaseScore s : scores) if (!s.semantic) System.out.println("  " + s.id + ": " + String.join("; ", s.notes));
        }
        return summary;
    }

    static void printRow(String name, Map<String, Object> a) {
        System.out.println(String.format("%-14s %5s %7s %6s %6s %6s %6s %7s %6s %6s %6s %6s %6s", name, a.get("n"),
            pct(a.get("grammarValid")), pct(a.get("jsonValid")), pct(a.get("schemaValid")), pct(a.get("semanticPass")), pct(a.get("intentExact")),
            pct(a.get("requiredFieldComplete")) + "/" + a.get("requiredFieldCompleteCases"),
            pct(a.get("executableRate")), a.get("hallucinationCount"), a.get("queryOverfill"), a.get("majorFailureCount"), pct(a.get("unknownSafeRate")) + "/" + a.get("unknownCases")));
    }

    static String pct(Object v) { return v instanceof Double d ? String.format("%.0f%%", d * 100) : String.valueOf(v); }

    static Map<String, Object> aggregate(List<CaseScore> scores) {
        Map<String, Object> a = new LinkedHashMap<>();
        int n = scores.size();
        a.put("n", n);
        if (n == 0) return a;
        a.put("grammarValid", rate(scores, s -> s.grammar));
        a.put("jsonValid", rate(scores, s -> s.json));
        a.put("schemaValid", rate(scores, s -> s.schema));
        a.put("semanticPass", rate(scores, s -> s.semantic));
        a.put("parseSuccess", rate(scores, s -> s.parse));
        a.put("intentExact", rate(scores.stream().filter(s -> s.mode.equals("intent")).collect(Collectors.toList()), s -> s.intentExact));
        int tp = scores.stream().mapToInt(s -> s.tp).sum(), fp = scores.stream().mapToInt(s -> s.fp).sum(), fn = scores.stream().mapToInt(s -> s.fn).sum();
        a.put("requiredFieldPrecision", tp + fp == 0 ? 1.0 : (double) tp / (tp + fp));
        a.put("requiredFieldRecall", tp + fn == 0 ? 1.0 : (double) tp / (tp + fn));
        a.put("hallucinatedParameters", scores.stream().mapToInt(s -> s.hallucinatedParams).sum());
        a.put("hallucinatedFields", scores.stream().mapToInt(s -> s.hallucinatedFields).sum());
        a.put("inventedValues", scores.stream().mapToInt(s -> s.inventedValues).sum());
        a.put("partialValues", scores.stream().mapToInt(s -> s.partialValues).sum());
        a.put("queryOverfill", scores.stream().mapToInt(s -> s.queryOverfill).sum());
        a.put("invalidRefs", scores.stream().mapToInt(s -> s.invalidRefs).sum());
        List<CaseScore> judged = scores.stream().filter(s -> s.requiredComplete != null).collect(Collectors.toList());
        a.put("requiredFieldCompleteCases", judged.size());
        a.put("requiredFieldComplete", judged.isEmpty() ? 1.0 : rate(judged, s -> s.requiredComplete));
        a.put("hallucinationCount", scores.stream().mapToInt(s -> s.hallucinatedParams + s.hallucinatedFields + s.inventedValues).sum());
        a.put("executableRate", rate(scores, s -> s.executable));
        List<CaseScore> unk = scores.stream().filter(s -> s.unknownSafe != null).collect(Collectors.toList());
        a.put("unknownCases", unk.size());
        a.put("unknownSafeRate", unk.isEmpty() ? 1.0 : rate(unk, s -> s.unknownSafe));
        a.put("unsupportedOperationCount", scores.stream().mapToInt(s -> s.unsupportedOperation).sum());
        a.put("assertedTargetCount", scores.stream().mapToInt(s -> s.assertedTarget).sum());
        a.put("nonTokenDateCount", scores.stream().mapToInt(s -> s.nonTokenDate).sum());
        a.put("hallucinatedFactCount", scores.stream().mapToInt(s -> s.hallucinatedFields + s.inventedValues).sum());
        a.put("majorFailureCount", scores.stream().mapToInt(s -> s.invalidRefs + s.unsupportedOperation + s.assertedTarget + s.nonTokenDate + s.hallucinatedFields + s.inventedValues).sum());
        List<CaseScore> tmplCases = scores.stream().filter(s -> s.mode.equals("template")).collect(Collectors.toList());
        if (!tmplCases.isEmpty()) {
            int ttp = tmplCases.stream().mapToInt(s -> s.tp).sum(), tfp = tmplCases.stream().mapToInt(s -> s.fp).sum(), tfn = tmplCases.stream().mapToInt(s -> s.fn).sum();
            a.put("templateRequiredPrecision", ttp + tfp == 0 ? 1.0 : (double) ttp / (ttp + tfp));
            a.put("templateRequiredRecall", ttp + tfn == 0 ? 1.0 : (double) ttp / (ttp + tfn));
            a.put("templateOptionalHallucination", tmplCases.stream().mapToInt(s -> s.hallucinatedFields).sum());
        }
        a.put("unsafe", (int) scores.stream().filter(s -> s.unsafe).count());
        Map<String, Object> perField = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>();
        scores.forEach(s -> names.addAll(s.fields.keySet()));
        for (String f : names) {
            List<CaseScore> having = scores.stream().filter(s -> s.fields.containsKey(f)).collect(Collectors.toList());
            perField.put(f, rate(having, s -> s.fields.get(f)));
        }
        a.put("fieldAccuracy", perField);
        Map<String, Integer> stops = new TreeMap<>();
        scores.forEach(s -> stops.merge(s.stop, 1, Integer::sum));
        a.put("stopReasons", new LinkedHashMap<String, Object>(stops));
        a.put("ttftMsMedian", median(scores.stream().map(s -> s.ttftMs).collect(Collectors.toList())));
        a.put("tokensPerSecondMedian", median(scores.stream().map(s -> s.tps).filter(v -> v > 0).collect(Collectors.toList())));
        return a;
    }

    static double rate(List<CaseScore> scores, java.util.function.Predicate<CaseScore> p) {
        if (scores.isEmpty()) return 0.0;
        return (double) scores.stream().filter(p).count() / scores.size();
    }

    static double median(List<Double> v) {
        if (v.isEmpty()) return 0;
        List<Double> s = new ArrayList<>(v); Collections.sort(s);
        return s.size() % 2 == 1 ? s.get(s.size() / 2) : (s.get(s.size() / 2 - 1) + s.get(s.size() / 2)) / 2;
    }

    static CaseScore scoreCase(Map<String, Object> g, Map<String, Object> r) {
        CaseScore s = new CaseScore();
        s.id = str(g.get("id"));
        s.category = str(g.get("category"));
        s.mode = (s.category.equals("template") || s.category.equals("hallucination")) ? "template" : "intent";
        s.stop = Objects.toString(r.get("stop"), "");
        s.ttftMs = num(r.get("ttftMs"));
        s.tps = num(r.get("tokensPerSecond"));
        boolean ok = Boolean.TRUE.equals(r.get("ok"));
        s.grammar = ok && Boolean.TRUE.equals(r.get("grammarUsed")) && "eog".equals(s.stop);
        String text = Objects.toString(r.get("output"), "");
        Map<String, Object> out = null;
        try { out = obj(Json.parse(text)); } catch (RuntimeException ignored) { }
        s.json = out != null;
        s.parse = s.json;
        if (out == null) { s.notes.add("no JSON"); return s; }
        Map<String, Object> expected = obj(g.get("expected"));
        Map<String, Object> accept = obj(g.get("accept"));
        if (accept == null) accept = Map.of();
        if (s.mode.equals("intent")) scoreIntent(s, g, expected, accept, out);
        else scoreTemplate(s, g, expected, accept, out);
        return s;
    }

    static void scoreIntent(CaseScore s, Map<String, Object> g, Map<String, Object> e, Map<String, Object> accept, Map<String, Object> out) {
        // schema level
        boolean schema = new ArrayList<>(out.keySet()).equals(INTENT_FIELDS);
        String intent = str(out.get("intent"));
        if (intent == null || !INTENTS.contains(intent)) { schema = false; s.notes.add("bad intent enum"); }
        if (out.get("documentKind") != null && !KINDS.contains(str(out.get("documentKind")))) { schema = false; s.notes.add("bad documentKind enum"); }
        String dt = str(out.get("dateToken"));
        if (out.get("dateToken") != null && (dt == null || !DATE_TOKENS.contains(dt) || dt.matches(".*\\d.*"))) { schema = false; s.notes.add("dateToken is not a token: " + out.get("dateToken")); }
        List<String> refs = contextRefs(obj(g.get("context")));
        String ref = str(out.get("targetRef"));
        if (out.get("targetRef") != null && (ref == null || !ref.matches("result_[1-9][0-9]?"))) { schema = false; s.notes.add("bad targetRef shape"); }
        else if (ref != null && !refs.contains(ref)) { schema = false; s.notes.add("targetRef " + ref + " not among shown results " + refs); }
        if (arr(out.get("missingFields")) == null) { schema = false; s.notes.add("missingFields not an array"); }
        for (String f : List.of("query", "targetName", "text", "templateId")) if (out.get(f) != null && !(out.get(f) instanceof String)) { schema = false; s.notes.add(f + " not a string"); }
        s.schema = schema;

        // semantic level
        boolean all = true;
        String eIntent = str(e.get("intent"));
        s.intentExact = matches(e.get("intent"), out.get("intent"), accept.get("intent"), false);
        boolean unknownEscape = s.category.equals("ambiguous") && "UNKNOWN".equals(intent);
        for (String f : INTENT_FIELDS) {
            boolean isSet = f.equals("missingFields");
            Object ev = e.get(f), ov = out.get(f);
            boolean match = matches(ev, ov, accept.get(f), isSet);
            if (!match && ov == null && (f.equals("targetRef") || f.equals("targetName"))) {
                // The target may be given either way: an accepted alternative on the counterpart
                // field (targetName for targetRef, and vice versa) permits this one to be null.
                String other = f.equals("targetRef") ? "targetName" : "targetRef";
                if (accept.get(other) != null && !matches(e.get(other), out.get(other), null, false) && matches(e.get(other), out.get(other), accept.get(other), false)) match = true;
            }
            if (unknownEscape && !f.equals("intent")) match = isSet ? true : ov == null || match;
            s.fields.put(f, match);
            if (!match) { all = false; s.notes.add(f + ": expected " + Json.write(ev, -1) + " got " + Json.write(ov, -1)); }
            if (f.equals("intent") || isSet) continue;
            if (ev != null) { if (match) s.tp++; else s.fn++; }
            else if (ov != null && !match) { s.fp++; s.hallucinatedParams++; }
        }
        // required-field completeness, judged against the expected intent (the model's own intent if it
        // matched an accepted alternative): APPEND needs a target and text; OPEN a target; CREATE a
        // documentKind; USE_TEMPLATE a templateId; SEARCH at least one of query / documentKind / dateToken.
        String judgedIntent = s.intentExact ? intent : eIntent;
        // a targetRef counts only when it names a shown result: a fabricated ref is not a target
        boolean hasTarget = (ref != null && refs.contains(ref)) || (out.get("targetName") != null && !str(out.get("targetName")).isBlank());
        boolean hasText = out.get("text") != null && !str(out.get("text")).isBlank();
        switch (judgedIntent == null ? "" : judgedIntent) {
            case "APPEND" -> s.requiredComplete = hasTarget && hasText;
            case "OPEN" -> s.requiredComplete = hasTarget;
            case "CREATE" -> s.requiredComplete = out.get("documentKind") != null;
            case "USE_TEMPLATE" -> s.requiredComplete = out.get("templateId") != null;
            case "SEARCH" -> s.requiredComplete = out.get("query") != null || out.get("documentKind") != null || out.get("dateToken") != null;
            default -> s.requiredComplete = null;
        }
        if (Boolean.FALSE.equals(s.requiredComplete)) s.notes.add("required fields incomplete for " + judgedIntent);
        if (out.get("query") != null && e.get("query") == null && !matches(e.get("query"), out.get("query"), accept.get("query"), false)) s.queryOverfill++;
        if (ref != null && !refs.contains(ref)) s.invalidRefs++;
        boolean fillsText = ("CREATE".equals(intent) || "APPEND".equals(intent)) && out.get("text") != null && !str(out.get("text")).isBlank();
        boolean textExpected = e.get("text") != null || (accept.get("text") != null && !arr(accept.get("text")).stream().allMatch(Objects::isNull));
        if (fillsText && !(intent.equals(eIntent) || matches(e.get("intent"), intent, accept.get("intent"), false)) || (fillsText && !textExpected && !intent.equals(eIntent))) { s.unsafe = true; s.notes.add("unsafe: " + intent + " with invented text"); }
        if (s.category.equals("ambiguous") && fillsText && !textExpected) { s.unsafe = true; if (!s.notes.contains("unsafe: ambiguous filled text")) s.notes.add("unsafe: ambiguous filled text"); }
        if (!s.intentExact && !unknownEscape) all = false;
        s.semantic = schema && all && !s.unsafe;
        // major safety failures
        if ("UNKNOWN".equals(eIntent)) {
            s.unknownSafe = "UNKNOWN".equals(intent) || matches(e.get("intent"), intent, accept.get("intent"), false);
            if (!s.unknownSafe && ("CREATE".equals(intent) || "APPEND".equals(intent) || "OPEN".equals(intent) || "USE_TEMPLATE".equals(intent))) { s.unsupportedOperation++; s.notes.add("major: unsupported request turned into " + intent); }
        }
        if (dt != null && dt.matches(".*\\d.*")) { s.nonTokenDate++; s.notes.add("major: non-token date"); }
        boolean expectsNoTarget = e.get("targetRef") == null && e.get("targetName") == null && accept.get("targetRef") == null && accept.get("targetName") == null;
        if (expectsNoTarget && (out.get("targetRef") != null || (out.get("targetName") != null && !str(out.get("targetName")).isBlank())) && (s.category.equals("ambiguous") || refs.isEmpty() && s.invalidRefs > 0 || s.invalidRefs > 0)) { s.assertedTarget++; s.notes.add("major: target asserted without context"); }
        boolean requiredRight = true;
        for (String f : INTENT_FIELDS) if (!f.equals("intent") && !f.equals("missingFields") && e.get(f) != null && !Boolean.TRUE.equals(s.fields.get(f))) requiredRight = false;
        s.executable = schema && (s.intentExact || unknownEscape) && requiredRight && !s.unsafe && s.invalidRefs == 0 && s.unsupportedOperation == 0 && s.assertedTarget == 0 && s.nonTokenDate == 0;
    }

    static void scoreTemplate(CaseScore s, Map<String, Object> g, Map<String, Object> e, Map<String, Object> accept, Map<String, Object> out) {
        String templateId = str(e.get("templateId"));
        List<String> schemaFields = TEMPLATE_FIELDS.get(templateId);
        boolean schema = new ArrayList<>(out.keySet()).equals(List.of("templateId", "fields", "missingFields"))
            && templateId.equals(str(out.get("templateId")))
            && obj(out.get("fields")) != null && new ArrayList<>(obj(out.get("fields")).keySet()).equals(schemaFields)
            && arr(out.get("missingFields")) != null;
        if (!schema) s.notes.add("template schema mismatch");
        s.schema = schema;
        s.intentExact = true;
        boolean all = schema;
        Map<String, Object> ef = obj(e.get("fields")), of = obj(out.get("fields"));
        if (of == null) of = Map.of();
        String input = norm(str(g.get("input")));
        for (String f : schemaFields) {
            Object ev = ef.get(f), ov = of.get(f);
            String evs = str(ev), ovs = str(ov);
            boolean match;
            List<Object> alts = arr(accept.get("fields." + f));
            if (ov == null) match = ev == null || (alts != null && alts.contains(null));
            else if (ev == null) match = alts != null && alts.stream().anyMatch(a -> a != null && norm(ovs).contains(norm(str(a))));
            else match = norm(ovs).contains(norm(evs)) || (alts != null && alts.stream().anyMatch(a -> a != null && norm(ovs).contains(norm(str(a)))));
            if (ov != null && ev == null && !match) { s.hallucinatedFields++; s.fp++; s.notes.add(f + ": filled although nothing was said: " + ovs); }
            if (ov != null && overlap(norm(ovs), input) < 0.6) { s.inventedValues++; if (match) match = false; s.notes.add(f + ": invented value: " + ovs); }
            if (ev != null) { if (match) s.tp++; else s.fn++; }
            s.fields.put(f, match);
            if (!match) {
                all = false;
                if (ov == null && ev != null) s.notes.add(f + ": missing, expected " + evs);
                else if (ev != null && ov != null && (s.notes.isEmpty() || !s.notes.get(s.notes.size() - 1).startsWith(f + ":"))) {
                    // A faithful sub-phrase of the reference (e.g. 疲れた for 手続きが多くて疲れた) is not an
                    // invention: still a miss under the strict rule, but counted apart as "partial".
                    String ovsCore = norm(ovs).replaceAll("[。．.、,!！?？\\s]+$", "");
                    boolean partial = ovsCore.length() >= 2 && norm(evs).contains(ovsCore);
                    if (partial) s.partialValues++;
                    s.notes.add(f + (partial ? ": partial (sub-phrase of reference) " : ": expected ") + evs + " got " + ovs);
                }
            }
        }
        boolean missingMatch = matches(e.get("missingFields"), out.get("missingFields"), accept.get("missingFields"), true);
        s.fields.put("missingFields", missingMatch);
        if (!missingMatch) { all = false; s.notes.add("missingFields: expected " + Json.write(e.get("missingFields"), -1) + " got " + Json.write(out.get("missingFields"), -1)); }
        // consistency: every required null field must be declared missing (model-side sanity, not the expected set)
        List<Object> outMissing = arr(out.get("missingFields"));
        if (outMissing != null) for (String req : TEMPLATE_REQUIRED.get(templateId))
            if (of.get(req) == null && !outMissing.contains(req)) { all = false; s.notes.add("required " + req + " null but not declared missing"); }
        s.semantic = all && s.hallucinatedFields == 0 && s.inventedValues == 0;
        boolean requiredRight = true;
        for (String req : TEMPLATE_REQUIRED.get(templateId)) if (ef.get(req) != null && !Boolean.TRUE.equals(s.fields.get(req))) requiredRight = false;
        s.executable = schema && requiredRight && s.hallucinatedFields == 0 && s.inventedValues == 0;
    }

    /** Fraction of the value's non-space characters that occur in the input (character-set containment). */
    static double overlap(String value, String input) {
        String v = value.replaceAll("[\\s\\p{Punct}、。「」・]", "");
        if (v.isEmpty()) return 1.0;
        int hit = 0;
        for (int i = 0; i < v.length(); i++) if (input.indexOf(v.charAt(i)) >= 0) hit++;
        return (double) hit / v.length();
    }

    static boolean matches(Object expected, Object actual, Object alternatives, boolean asSet) {
        if (valuesEqual(expected, actual, asSet)) return true;
        List<Object> alts = arr(alternatives);
        if (alts == null) return false;
        for (Object alt : alts) if (valuesEqual(alt, actual, asSet)) return true;
        return false;
    }

    static boolean valuesEqual(Object a, Object b, boolean asSet) {
        if (asSet) {
            List<Object> la = arr(a), lb = arr(b);
            if (la == null || lb == null) return false;
            return la.stream().map(x -> norm(str(x))).collect(Collectors.toCollection(TreeSet::new))
                .equals(lb.stream().map(x -> norm(str(x))).collect(Collectors.toCollection(TreeSet::new)));
        }
        if (a == null || b == null) return a == null && b == null;
        return norm(str(a)).equals(norm(str(b)));
    }

    static String norm(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC);
        return n.replaceAll("\\s+", " ").trim();
    }

    // ---------------------------------------------------------------- self test

    static void selfTest() {
        List<Map<String, Object>> golden = new ArrayList<>();
        golden.add(obj(Json.parse("{\"id\":\"intent_001\",\"category\":\"intent\",\"input\":\"昨日の日記を探して\",\"expected\":{\"intent\":\"SEARCH\",\"query\":null,\"targetRef\":null,\"targetName\":null,\"documentKind\":\"JOURNAL\",\"text\":null,\"templateId\":null,\"dateToken\":\"YESTERDAY\",\"missingFields\":[]}}")));
        golden.add(obj(Json.parse("{\"id\":\"param_001\",\"category\":\"param\",\"input\":\"2番目に「x」と追記\",\"context\":{\"results\":[\"result_1: a\",\"result_2: b\"]},\"expected\":{\"intent\":\"APPEND\",\"query\":null,\"targetRef\":\"result_2\",\"targetName\":null,\"documentKind\":null,\"text\":\"x\",\"templateId\":null,\"dateToken\":null,\"missingFields\":[]},\"accept\":{\"targetName\":[\"b\"]}}")));
        golden.add(obj(Json.parse("{\"id\":\"ambiguous_001\",\"category\":\"ambiguous\",\"input\":\"それやって\",\"expected\":{\"intent\":\"UNKNOWN\",\"query\":null,\"targetRef\":null,\"targetName\":null,\"documentKind\":null,\"text\":null,\"templateId\":null,\"dateToken\":null,\"missingFields\":[]}}")));
        golden.add(obj(Json.parse("{\"id\":\"halluc_001\",\"category\":\"hallucination\",\"input\":\"疲れた。\",\"context\":{\"template\":\"daily_journal\"},\"expected\":{\"templateId\":\"daily_journal\",\"fields\":{\"events\":null,\"feeling\":\"疲れた\",\"reflection\":null},\"missingFields\":[\"events\"]},\"accept\":{\"missingFields\":[[\"events\",\"reflection\"]]}}")));
        golden.add(obj(Json.parse("{\"id\":\"template_001\",\"category\":\"template\",\"input\":\"朝ランニングした。楽しかった。\",\"context\":{\"template\":\"daily_journal\"},\"expected\":{\"templateId\":\"daily_journal\",\"fields\":{\"events\":\"朝ランニングした\",\"feeling\":\"楽しかった\",\"reflection\":null},\"missingFields\":[\"reflection\"]},\"accept\":{\"missingFields\":[[]]}}")));

        List<Map<String, Object>> results = new ArrayList<>();
        // 1 exact (NFKC-folded full-width spaces / ascii ok)
        results.add(result("intent_001", "{\"intent\":\"SEARCH\",\"query\":null,\"targetRef\":null,\"targetName\":null,\"documentKind\":\"JOURNAL\",\"text\":null,\"templateId\":null,\"dateToken\":\"YESTERDAY\",\"missingFields\":[]}", "eog"));
        // 2 accepted alternative (targetName instead of ref) + hallucinated dateToken
        results.add(result("param_001", "{\"intent\":\"APPEND\",\"query\":null,\"targetRef\":null,\"targetName\":\"b\",\"documentKind\":null,\"text\":\"ｘ\",\"templateId\":null,\"dateToken\":\"TODAY\",\"missingFields\":[]}", "eog"));
        // 3 ambiguous answered UNKNOWN with everything null → pass
        results.add(result("ambiguous_001", "{\"intent\":\"UNKNOWN\",\"query\":null,\"targetRef\":null,\"targetName\":null,\"documentKind\":null,\"text\":null,\"templateId\":null,\"dateToken\":null,\"missingFields\":[]}", "eog"));
        // 4 hallucinated events + invented text
        results.add(result("halluc_001", "{\"templateId\":\"daily_journal\",\"fields\":{\"events\":\"仕事が忙しかった\",\"feeling\":\"疲れた\",\"reflection\":null},\"missingFields\":[]}", "eog"));
        // 5 truncated (max tokens) → no JSON
        results.add(result("template_001", "{\"templateId\":\"daily_journal\",\"fields\":{\"events\":\"朝ランニング", "max"));

        Map<String, Object> summary = score(golden, results, true);
        Map<String, Object> total = obj(summary.get("total"));
        check(total.get("n").equals(5), "n");
        check(((Double) total.get("jsonValid")) == 0.8, "jsonValid 4/5");
        check(((Double) total.get("grammarValid")) == 0.8, "grammarValid 4/5");
        check(((Double) total.get("semanticPass")) == 0.4, "semantic 2/5 (intent_001, ambiguous_001)");
        check(total.get("hallucinatedParameters").equals(1), "one hallucinated parameter (dateToken)");
        check(total.get("hallucinatedFields").equals(1), "one hallucinated field (events)");
        check(total.get("inventedValues").equals(1), "one invented value");
        check(total.get("unsafe").equals(0), "no unsafe");
        // the accepted targetName alternative also excuses the null targetRef; only the dateToken is wrong
        List<Map<String, Object>> pair = List.of(result("param_001", "{\"intent\":\"APPEND\",\"query\":null,\"targetRef\":null,\"targetName\":\"b\",\"documentKind\":null,\"text\":\"x\",\"templateId\":null,\"dateToken\":null,\"missingFields\":[]}", "eog"));
        check(((Double) obj(score(golden, pair, false).get("total")).get("semanticPass")) == 1.0, "targetName alternative excuses the null targetRef");
        Map<String, Object> ambiguous = obj(obj(summary.get("perCategory")).get("ambiguous"));
        check(((Double) ambiguous.get("semanticPass")) == 1.0, "ambiguous UNKNOWN escape");
        // targetRef outside the shown list is a schema failure
        List<Map<String, Object>> bad = List.of(result("param_001", "{\"intent\":\"APPEND\",\"query\":null,\"targetRef\":\"result_4\",\"targetName\":null,\"documentKind\":null,\"text\":\"x\",\"templateId\":null,\"dateToken\":null,\"missingFields\":[]}", "eog"));
        Map<String, Object> s2 = score(golden, bad, false);
        check(((Double) obj(s2.get("total")).get("schemaValid")) == 0.0, "ref outside context fails schema");
        // unsafe: ambiguous answered CREATE with text
        List<Map<String, Object>> unsafe = List.of(result("ambiguous_001", "{\"intent\":\"CREATE\",\"query\":null,\"targetRef\":null,\"targetName\":null,\"documentKind\":\"MEMO\",\"text\":\"それ\",\"templateId\":null,\"dateToken\":null,\"missingFields\":[]}", "eog"));
        check(obj(score(golden, unsafe, false).get("total")).get("unsafe").equals(1), "unsafe counted");
        // validator on the self-test golden (counts differ from the target, so only the structural problems matter)
        // required vs optional: UNKNOWN with every field null is safe and complete (n/a); APPEND with neither target is incomplete
        Map<String, Object> t1 = obj(score(golden, List.of(result("ambiguous_001", "{\"intent\":\"UNKNOWN\",\"query\":null,\"targetRef\":null,\"targetName\":null,\"documentKind\":null,\"text\":null,\"templateId\":null,\"dateToken\":null,\"missingFields\":[]}", "eog")), false).get("total"));
        check(((Double) t1.get("unknownSafeRate")) == 1.0 && t1.get("requiredFieldCompleteCases").equals(0) && ((Double) t1.get("executableRate")) == 1.0, "UNKNOWN with nulls: safe, not judged for completeness, executable");
        Map<String, Object> t2 = obj(score(golden, List.of(result("param_001", "{\"intent\":\"APPEND\",\"query\":null,\"targetRef\":null,\"targetName\":null,\"documentKind\":null,\"text\":\"x\",\"templateId\":null,\"dateToken\":null,\"missingFields\":[\"targetRef\"]}", "eog")), false).get("total"));
        check(((Double) t2.get("requiredFieldComplete")) == 0.0 && ((Double) t2.get("executableRate")) == 0.0 && t2.get("majorFailureCount").equals(0), "APPEND without any target: incomplete, not executable, no major failure");
        // an echoed query on a right SEARCH: strict fails, executable passes, over-fill counted
        Map<String, Object> t3 = obj(score(golden, List.of(result("intent_001", "{\"intent\":\"SEARCH\",\"query\":\"昨日の日記\",\"targetRef\":null,\"targetName\":null,\"documentKind\":\"JOURNAL\",\"text\":null,\"templateId\":null,\"dateToken\":\"YESTERDAY\",\"missingFields\":[]}", "eog")), false).get("total"));
        check(((Double) t3.get("semanticPass")) == 0.0 && ((Double) t3.get("executableRate")) == 1.0 && t3.get("queryOverfill").equals(1), "echoed query: strict miss, executable, over-fill 1");
        // a destructive request turned into CREATE is a major failure
        Map<String, Object> t4 = obj(score(golden, List.of(result("ambiguous_001", "{\"intent\":\"CREATE\",\"query\":null,\"targetRef\":null,\"targetName\":null,\"documentKind\":\"MEMO\",\"text\":null,\"templateId\":null,\"dateToken\":null,\"missingFields\":[]}", "eog")), false).get("total"));
        check(t4.get("unsupportedOperationCount").equals(1) && ((Double) t4.get("unknownSafeRate")) == 0.0, "unsupported request → CREATE counted as a major failure");
        // parity: whitespace / key order differences are the same proposal; a changed field is not
        List<Map<String, Object>> pa = List.of(result("intent_001", "{\"intent\":\"SEARCH\",\"query\":null,\"targetRef\":null,\"targetName\":null,\"documentKind\":\"JOURNAL\",\"text\":null,\"templateId\":null,\"dateToken\":\"YESTERDAY\",\"missingFields\":[]}", "eog"));
        List<Map<String, Object>> pb = List.of(result("intent_001", "{ \"dateToken\": \"YESTERDAY\",\n \"intent\": \"SEARCH\", \"query\": null, \"targetRef\": null, \"targetName\": null, \"documentKind\": \"JOURNAL\", \"text\": null, \"templateId\": null, \"missingFields\": [ ] }", "eog"));
        List<Map<String, Object>> pc = List.of(result("intent_001", "{\"intent\":\"SEARCH\",\"query\":null,\"targetRef\":null,\"targetName\":null,\"documentKind\":\"MEMO\",\"text\":null,\"templateId\":null,\"dateToken\":\"YESTERDAY\",\"missingFields\":[]}", "eog"));
        check(Parity.run(golden, pa, pb), "parity: whitespace and key order are the same proposal");
        check(!Parity.run(golden, pa, pc), "parity: a changed documentKind is a difference");
        check(validateStructureOnly(golden), "self-test golden is structurally valid");
        System.out.println("SELFTEST OK");
    }

    static boolean validateStructureOnly(List<Map<String, Object>> golden) {
        java.io.PrintStream out = System.out;
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        System.setOut(new java.io.PrintStream(buf, true, StandardCharsets.UTF_8));
        validate(golden);
        System.setOut(out);
        String text = buf.toString(StandardCharsets.UTF_8);
        return Arrays.stream(text.split("\n")).filter(l -> l.startsWith("PROBLEM")).allMatch(l -> l.contains("cases, target"));
    }

    static Map<String, Object> result(String id, String output, String stop) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("id", id); r.put("ok", true); r.put("grammarUsed", true); r.put("stop", stop); r.put("output", output);
        r.put("ttftMs", 100.0); r.put("tokensPerSecond", 10.0);
        return r;
    }

    static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError("selftest failed: " + what);
        System.out.println("  ok: " + what);
    }

    // ---------------------------------------------------------------- helpers

    static List<Map<String, Object>> readJsonl(Path p) throws Exception {
        List<Map<String, Object>> out = new ArrayList<>();
        int lineNo = 0;
        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
            lineNo++;
            if (line.isBlank()) continue;
            try { out.add(obj(Json.parse(line))); }
            catch (RuntimeException ex) { throw new RuntimeException(p + ":" + lineNo + ": " + ex.getMessage()); }
        }
        return out;
    }

    @SuppressWarnings("unchecked") static Map<String, Object> obj(Object o) { return o instanceof Map ? (Map<String, Object>) o : null; }
    @SuppressWarnings("unchecked") static List<Object> arr(Object o) { return o instanceof List ? (List<Object>) o : null; }
    static String str(Object o) { return o instanceof String ? (String) o : null; }
    static double num(Object o) { return o instanceof Number ? ((Number) o).doubleValue() : 0; }

    /** Minimal JSON: objects keep insertion order (LinkedHashMap), numbers are Double, null is null. */
    static final class Json {
        final String s; int i;
        Json(String s) { this.s = s; }
        static Object parse(String s) {
            Json j = new Json(s);
            j.ws();
            Object v = j.value();
            j.ws();
            if (j.i != s.length()) throw new RuntimeException("trailing characters at " + j.i);
            return v;
        }
        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        Object value() {
            if (i >= s.length()) throw new RuntimeException("unexpected end");
            char c = s.charAt(i);
            if (c == '{') return object();
            if (c == '[') return array();
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (start == i) throw new RuntimeException("unexpected '" + c + "' at " + i);
            return Double.parseDouble(s.substring(start, i));
        }
        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++; ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws();
                String k = string();
                ws(); expect(':'); ws();
                m.put(k, value());
                ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect('}'); return m;
            }
        }
        List<Object> array() {
            List<Object> l = new ArrayList<>();
            i++; ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                ws(); l.add(value()); ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect(']'); return l;
            }
        }
        String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw new RuntimeException("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c == '\\') {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r');
                        case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                        case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                        default -> b.append(e);
                    }
                } else b.append(c);
            }
        }
        void expect(char c) { if (i >= s.length() || s.charAt(i) != c) throw new RuntimeException("expected '" + c + "' at " + i); i++; }

        static String write(Object v, int indent) {
            StringBuilder b = new StringBuilder();
            write(b, v, indent, 0);
            return b.toString();
        }
        static void write(StringBuilder b, Object v, int indent, int depth) {
            if (v == null) b.append("null");
            else if (v instanceof String str) { b.append('"'); for (char c : str.toCharArray()) { switch (c) { case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\"); case '\n' -> b.append("\\n"); case '\t' -> b.append("\\t"); case '\r' -> b.append("\\r"); default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); } } } b.append('"'); }
            else if (v instanceof Double d) { if (d == Math.rint(d) && !d.isInfinite()) b.append(d.longValue()); else b.append(d); }
            else if (v instanceof Number || v instanceof Boolean) b.append(v);
            else if (v instanceof Map<?, ?> m) {
                b.append('{'); boolean first = true;
                for (var e : m.entrySet()) { if (!first) b.append(','); first = false; nl(b, indent, depth + 1); write(b, e.getKey(), -1, 0); b.append(':'); if (indent >= 0) b.append(' '); write(b, e.getValue(), indent, depth + 1); }
                if (!first) nl(b, indent, depth); b.append('}');
            } else if (v instanceof List<?> l) {
                b.append('['); boolean first = true;
                for (Object o : l) { if (!first) b.append(','); first = false; nl(b, indent, depth + 1); write(b, o, indent, depth + 1); }
                if (!first) nl(b, indent, depth); b.append(']');
            } else b.append('"').append(v).append('"');
        }
        static void nl(StringBuilder b, int indent, int depth) { if (indent < 0) return; b.append('\n'); for (int k = 0; k < depth * 2; k++) b.append(' '); }
    }
}

/** Mac ↔ S20 parity: the same cases run by two executors, compared as parsed proposals (docs/LLM_PHASE0.md §7.2). */
class Parity {
    /** true when every common case has the same proposal (field values after normalization; whitespace / key order ignored). */
    static boolean run(java.util.List<java.util.Map<String, Object>> golden, java.util.List<java.util.Map<String, Object>> a, java.util.List<java.util.Map<String, Object>> b) {
        java.util.Map<String, java.util.Map<String, Object>> ba = new java.util.LinkedHashMap<>(), bb = new java.util.LinkedHashMap<>();
        for (var r : a) ba.put(LlmEval.str(r.get("id")), r);
        for (var r : b) bb.put(LlmEval.str(r.get("id")), r);
        int common = 0, same = 0, byteSame = 0, diff = 0, unparsable = 0;
        java.util.List<String> differing = new java.util.ArrayList<>();
        for (var g : golden) {
            String id = LlmEval.str(g.get("id"));
            var ra = ba.get(id); var rb = bb.get(id);
            if (ra == null || rb == null) continue;
            common++;
            String ta = java.util.Objects.toString(ra.get("output"), ""), tb = java.util.Objects.toString(rb.get("output"), "");
            if (ta.equals(tb)) byteSame++;
            Object pa = null, pb = null;
            try { pa = LlmEval.Json.parse(ta); } catch (RuntimeException ignored) { }
            try { pb = LlmEval.Json.parse(tb); } catch (RuntimeException ignored) { }
            if (pa == null || pb == null) { unparsable++; if ((pa == null) != (pb == null)) { diff++; differing.add(id + ": one side unparsable"); } else same++; continue; }
            String d = describeDiff(pa, pb, "");
            if (d.isEmpty()) same++; else { diff++; differing.add(id + ": " + d); }
        }
        System.out.println("parity: common=" + common + " sameProposal=" + same + " byteIdentical=" + byteSame + " differing=" + diff + " unparsable=" + unparsable);
        differing.forEach(x -> System.out.println("  " + x));
        var sa = LlmEval.score(golden, a, false); var sb = LlmEval.score(golden, b, false);
        var ta = LlmEval.obj(sa.get("total")); var tb = LlmEval.obj(sb.get("total"));
        for (String k : java.util.List.of("intentExact", "requiredFieldComplete", "semanticPass", "executableRate", "hallucinationCount", "queryOverfill", "majorFailureCount"))
            System.out.println(String.format("  %-22s a=%s b=%s", k, fmt(ta.get(k)), fmt(tb.get(k))));
        return diff == 0;
    }
    static String fmt(Object v) { return v instanceof Double d ? String.format("%.2f", d) : String.valueOf(v); }
    @SuppressWarnings("unchecked")
    static String describeDiff(Object a, Object b, String path) {
        if (a instanceof java.util.Map && b instanceof java.util.Map) {
            var ma = (java.util.Map<String, Object>) a; var mb = (java.util.Map<String, Object>) b;
            java.util.Set<String> keys = new java.util.LinkedHashSet<>(ma.keySet()); keys.addAll(mb.keySet());
            StringBuilder sb = new StringBuilder();
            for (String k : keys) { String d = describeDiff(ma.get(k), mb.get(k), path.isEmpty() ? k : path + "." + k); if (!d.isEmpty()) { if (sb.length() > 0) sb.append("; "); sb.append(d); } }
            return sb.toString();
        }
        if (a instanceof java.util.List && b instanceof java.util.List) {
            var la = ((java.util.List<Object>) a).stream().map(x -> LlmEval.norm(LlmEval.str(x))).sorted().toList();
            var lb = ((java.util.List<Object>) b).stream().map(x -> LlmEval.norm(LlmEval.str(x))).sorted().toList();
            return la.equals(lb) ? "" : path + " " + la + " vs " + lb;
        }
        if (a == null && b == null) return "";
        if (a == null || b == null) return path + " " + LlmEval.Json.write(a, -1) + " vs " + LlmEval.Json.write(b, -1);
        return LlmEval.norm(String.valueOf(a)).equals(LlmEval.norm(String.valueOf(b))) ? "" : path + " " + LlmEval.Json.write(a, -1) + " vs " + LlmEval.Json.write(b, -1);
    }
}

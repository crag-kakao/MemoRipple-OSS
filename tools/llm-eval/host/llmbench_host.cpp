// Mac-side accuracy executor (docs/LLM_PHASE0.md §7.2). Mirrors BenchRunner.kt + llmbench.cpp:
// the same prompt rendering (Jinja through llama-common, enable_thinking=false), the same
// grammar → greedy chain, the same per-case result record. Accuracy only: timings are recorded
// but are Mac timings, never mixed with the S20 rows.
#include "llama.h"
#include "chat.h"
#include "nlohmann/json.hpp"

#include <chrono>
#include <cstdio>
#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

using json = nlohmann::ordered_json;

static std::string read_file(const std::string & p) {
    std::ifstream f(p);
    if (!f) { fprintf(stderr, "cannot read %s\n", p.c_str()); exit(2); }
    std::stringstream ss; ss << f.rdbuf(); return ss.str();
}
static std::string trim(std::string s) {
    while (!s.empty() && isspace((unsigned char) s.back())) s.pop_back();
    size_t i = 0; while (i < s.size() && isspace((unsigned char) s[i])) i++;
    return s.substr(i);
}
static std::string replace_all(std::string s, const std::string & a, const std::string & b) {
    size_t p = 0; while ((p = s.find(a, p)) != std::string::npos) { s.replace(p, a.size(), b); p += b.size(); } return s;
}
static double ms_since(std::chrono::steady_clock::time_point t0) {
    return std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - t0).count() / 1000.0;
}

struct Tmpl { std::string id, name; std::vector<std::tuple<std::string, std::string, bool>> fields; };

int main(int argc, char ** argv) {
    std::map<std::string, std::string> a;
    for (int i = 1; i + 1 < argc; i += 2) a[argv[i]] = argv[i + 1];
    auto arg = [&](const std::string & k, const std::string & d) { return a.count(k) ? a[k] : d; };
    std::string model_path = arg("--model", ""), inputs = arg("--inputs", "tools/llm-eval"), label = arg("--label", "host");
    std::string cases_file = arg("--cases", inputs + "/dataset/golden.jsonl"), ids_file = arg("--ids", ""), out_path = arg("--out", "");
    std::string prompt_version = arg("--prompt-version", "v2");
    int n_ctx = atoi(arg("--n-ctx", "4096").c_str()), n_batch = atoi(arg("--n-batch", "512").c_str());
    int n_threads = atoi(arg("--threads", "4").c_str()), max_tokens = atoi(arg("--max-tokens", "256").c_str()), limit = atoi(arg("--limit", "0").c_str());
    if (model_path.empty() || out_path.empty()) { fprintf(stderr, "usage: llmbench_host --model x.gguf --out results.jsonl [--cases f.jsonl] [--ids ids.txt] [--inputs tools/llm-eval] [--prompt-version v2] [--limit n] [--threads 4]\n"); return 2; }

    // cases
    std::vector<json> cases;
    { std::ifstream f(cases_file); std::string line; while (std::getline(f, line)) if (!trim(line).empty()) cases.push_back(json::parse(line)); }
    if (!ids_file.empty()) {
        std::vector<std::string> ids; std::ifstream f(ids_file); std::string line;
        while (std::getline(f, line)) { line = trim(line); if (!line.empty() && line[0] != '#') ids.push_back(line); }
        std::vector<json> sel; for (auto & id : ids) for (auto & c : cases) if (c["id"] == id) sel.push_back(c);
        cases = sel;
    }
    if (limit > 0 && (int) cases.size() > limit) cases.resize(limit);

    std::string intent_system = trim(read_file(inputs + "/prompts/" + prompt_version + "/intent_system.txt"));
    std::string template_system = trim(read_file(inputs + "/prompts/" + prompt_version + "/template_system.txt"));
    std::string intent_grammar = read_file(inputs + "/grammar/intent_proposal.gbnf");
    std::string template_grammar = read_file(inputs + "/grammar/template_fields.gbnf.template");
    std::map<std::string, Tmpl> templates;
    for (const char * t : {"daily_journal", "idea", "character"}) {
        json j = json::parse(read_file(inputs + "/templates/" + t + ".json"));
        Tmpl tm; tm.id = j["id"]; tm.name = j.value("name", tm.id);
        for (auto & f : j["fields"]) tm.fields.emplace_back(f["key"], f.value("label", std::string(f["key"])), f.value("required", false));
        templates[tm.id] = tm;
    }

    llama_backend_init();
    llama_model_params mp = llama_model_default_params(); mp.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(model_path.c_str(), mp);
    if (!model) { fprintf(stderr, "load failed\n"); return 1; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = n_ctx; cp.n_batch = n_batch; cp.n_ubatch = n_batch; cp.n_threads = n_threads; cp.n_threads_batch = n_threads; cp.no_perf = true;
    llama_context * ctx = llama_init_from_model(model, cp);
    const llama_vocab * vocab = llama_model_get_vocab(model);
    common_chat_templates_ptr tmpls = common_chat_templates_init(model, "");
    const char * bos_c = llama_vocab_get_text(vocab, llama_vocab_bos(vocab));
    std::string bos_text = bos_c ? bos_c : "";
    fprintf(stderr, "loaded %s; cases=%zu; prompts=%s; system info: %s\n", model_path.c_str(), cases.size(), prompt_version.c_str(), llama_print_system_info());

    std::ofstream out(out_path);
    int n = 0;
    for (auto & c : cases) {
        std::string id = c["id"], category = c["category"], input = c["input"];
        bool template_mode = category == "template" || category == "hallucination";
        std::string system, user, grammar;
        if (template_mode) {
            std::string tid = c.contains("context") && c["context"].contains("template") ? std::string(c["context"]["template"]) : "daily_journal";
            Tmpl & t = templates[tid];
            std::string field_list, fields_rule; bool first = true;
            for (auto & [k, l, req] : t.fields) {
                field_list += "- " + k + ": " + l + " / " + (req ? "必須" : "任意") + "\n";
                if (!first) fields_rule += " \",\" ws "; first = false;
                fields_rule += "\"\\\"" + k + "\\\":\" ws strornull";
            }
            if (!field_list.empty()) field_list.pop_back();
            system = replace_all(replace_all(replace_all(template_system, "{{TEMPLATE_NAME}}", t.name), "{{TEMPLATE_ID}}", t.id), "{{FIELD_LIST}}", field_list);
            user = "入力: " + input;
            grammar = replace_all(replace_all(template_grammar, "{{TEMPLATE_ID}}", t.id), "{{FIELDS}}", fields_rule);
        } else {
            system = intent_system;
            user = "表示中の候補:\n";
            bool any = false;
            if (c.contains("context") && c["context"].contains("results")) for (auto & r : c["context"]["results"]) { user += std::string(r) + "\n"; any = true; }
            if (!any) user += "(なし)\n";
            user += "入力: " + input;
            grammar = intent_grammar;
        }
        common_chat_templates_inputs in;
        common_chat_msg sm; sm.role = "system"; sm.content = system; in.messages.push_back(sm);
        common_chat_msg um; um.role = "user"; um.content = user; in.messages.push_back(um);
        in.add_generation_prompt = true; in.use_jinja = true; in.enable_thinking = false;
        std::string prompt = common_chat_templates_apply(tmpls.get(), in).prompt;
        bool has_bos = !bos_text.empty() && prompt.rfind(bos_text, 0) == 0;

        llama_memory_clear(llama_get_memory(ctx), true);
        int nt = -llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), nullptr, 0, !has_bos, true);
        std::vector<llama_token> toks(nt);
        llama_tokenize(vocab, prompt.c_str(), (int) prompt.size(), toks.data(), nt, !has_bos, true);

        llama_sampler_chain_params sp = llama_sampler_chain_default_params(); sp.no_perf = true;
        llama_sampler * chain = llama_sampler_chain_init(sp);
        llama_sampler * g = llama_sampler_init_grammar(vocab, grammar.c_str(), "root");
        if (!g) { fprintf(stderr, "%s: grammar failed\n", id.c_str()); return 1; }
        llama_sampler_chain_add(chain, g);
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());

        auto t0 = std::chrono::steady_clock::now();
        std::string error, stop = "error", text; int gen = 0; double ttft = 0, gen_ms = 0;
        for (size_t off = 0; off < toks.size(); off += n_batch) {
            int k = (int) std::min((size_t) n_batch, toks.size() - off);
            if (llama_decode(ctx, llama_batch_get_one(toks.data() + off, k)) != 0) { error = "prompt decode failed"; break; }
        }
        double prompt_ms = ms_since(t0);
        if (error.empty()) try {
            auto t_first = std::chrono::steady_clock::time_point{};
            int n_past = (int) toks.size();
            while (true) {
                llama_token tok = llama_sampler_sample(chain, ctx, -1);
                if (gen == 0) { ttft = ms_since(t0); t_first = std::chrono::steady_clock::now(); }
                if (llama_vocab_is_eog(vocab, tok)) { stop = "eog"; break; }
                char buf[256]; int m = llama_token_to_piece(vocab, tok, buf, sizeof buf, 0, true);
                if (m > 0) text.append(buf, m);
                gen++;
                if (gen >= max_tokens) { stop = "max"; break; }
                if (n_past + 1 > n_ctx) { stop = "ctx"; break; }
                if (llama_decode(ctx, llama_batch_get_one(&tok, 1)) != 0) { error = "decode failed"; break; }
                n_past++;
            }
            if (gen > 0) gen_ms = ms_since(t_first);
        } catch (const std::exception & ex) { error = std::string("exception: ") + ex.what(); }
        llama_sampler_free(chain);

        bool json_valid = false; try { auto p = json::parse(text); json_valid = p.is_object(); } catch (...) {}
        json rec;
        rec["id"] = id; rec["category"] = category; rec["mode"] = template_mode ? "template" : "intent";
        rec["label"] = label; rec["modelFile"] = model_path.substr(model_path.find_last_of('/') + 1); rec["promptVersion"] = prompt_version; rec["executor"] = "mac";
        rec["ok"] = error.empty(); rec["stop"] = stop; rec["grammarUsed"] = true; rec["output"] = text; rec["jsonValid"] = json_valid;
        rec["promptTokens"] = (int) toks.size(); rec["genTokens"] = gen; rec["promptMs"] = prompt_ms; rec["ttftMs"] = ttft; rec["genMs"] = gen_ms;
        rec["tokensPerSecond"] = (gen_ms > 0 && gen > 1) ? (gen - 1) * 1000.0 / gen_ms : 0.0;
        if (!error.empty()) rec["error"] = error;
        out << rec.dump() << "\n"; out.flush();
        n++;
        fprintf(stderr, "%s %s %dtok ttft=%.0fms %s\n", id.c_str(), stop.c_str(), gen, ttft, text.substr(0, 60).c_str());
    }
    llama_free(ctx); llama_model_free(model); llama_backend_free();
    fprintf(stderr, "done: %d cases → %s\n", n, out_path.c_str());
    return 0;
}

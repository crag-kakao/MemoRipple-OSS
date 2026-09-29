// MemoRipple's local-LLM bridge: load / render / generate / stop / unload over llama.cpp.
// A product-side rewrite of what the Phase 0 bench proved (llmbench/src/main/cpp/llmbench.cpp):
// no metrics, no workloads, no UI — one model at a time, one greedy grammar-constrained
// generation at a time, an explicit stop flag, and the model's own chat template rendered
// through llama-common with thinking off unless the profile says otherwise.
#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <string>
#include <vector>

#include "llama.h"
#include "chat.h"

#define TAG "memoripple_llm"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    common_chat_templates_ptr tmpls;
    std::string bos_text;
    int n_ctx = 0;
    int n_batch = 0;
    std::atomic<bool> stop_requested{false};
    // Phase 4B (docs/GENERATION_EFFICIENCY.md): the tokens whose KV the context currently holds, in order —
    // the prompt and the decoded generated tokens of the last request. A new request reuses only the
    // token-for-token common prefix (never a string offset); everything after it is rewound and re-evaluated.
    std::vector<llama_token> cache;
};

// The number of leading tokens two sequences share.
size_t common_prefix(const std::vector<llama_token> &a, const std::vector<llama_token> &b) {
    size_t n = 0, lim = std::min(a.size(), b.size());
    while (n < lim && a[n] == b[n]) n++;
    return n;
}

using clk = std::chrono::steady_clock;
double ms_since(clk::time_point t0) { return std::chrono::duration_cast<std::chrono::microseconds>(clk::now() - t0).count() / 1000.0; }

std::string utf8(JNIEnv *env, jstring s) {
    if (!s) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    env->ReleaseStringUTFChars(s, c);
    return out;
}

std::string sanitize(const std::string &s) {
    std::string out; size_t i = 0;
    while (i < s.size()) {
        unsigned char c = s[i];
        size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 0;
        bool ok = len > 0 && i + len <= s.size();
        for (size_t k = 1; ok && k < len; k++) ok = ((unsigned char) s[i + k] & 0xC0) == 0x80;
        if (ok) { out.append(s, i, len); i += len; } else { out += "\xEF\xBF\xBD"; i += 1; }
    }
    return out;
}
jstring jstr(JNIEnv *env, const std::string &s) { return env->NewStringUTF(sanitize(s).c_str()); }

void escape(std::string &out, const std::string &s) {
    for (unsigned char c : s) switch (c) {
        case '"': out += "\\\""; break; case '\\': out += "\\\\"; break; case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break; case '\t': out += "\\t"; break;
        default: if (c < 0x20) { char b[8]; snprintf(b, sizeof b, "\\u%04x", c); out += b; } else out += (char) c;
    }
}

Session *session(jlong h) { return reinterpret_cast<Session *>(h); }

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special) {
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, add_special, true);
    std::vector<llama_token> t(n > 0 ? n : 0);
    if (n > 0) { int got = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), t.data(), n, add_special, true); t.resize(got > 0 ? got : 0); }
    return t;
}

std::string piece(const llama_vocab *vocab, llama_token tok) {
    char buf[256];
    int n = llama_token_to_piece(vocab, tok, buf, sizeof buf, 0, true);
    if (n < 0) { std::string big((size_t) -n, '\0'); n = llama_token_to_piece(vocab, tok, big.data(), (int32_t) big.size(), 0, true); big.resize(n > 0 ? n : 0); return big; }
    return std::string(buf, (size_t) n);
}

} // namespace

extern "C" {

#define FN(name) Java_io_github_cragcoffee_memoripple_data_ai_llamacpp_LlamaCppEngine_##name

JNIEXPORT void JNICALL FN(nativeBackendInit)(JNIEnv *, jobject) { llama_backend_init(); }

JNIEXPORT jlong JNICALL FN(nativeLoad)(JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_batch, jint n_threads) {
    std::string path = utf8(env, jpath);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { LOGE("model load failed: %s", path.c_str()); return 0; }
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) n_ctx; cp.n_batch = (uint32_t) n_batch; cp.n_ubatch = (uint32_t) n_batch;
    cp.n_threads = n_threads; cp.n_threads_batch = n_threads; cp.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { LOGE("context init failed"); llama_model_free(model); return 0; }
    auto *s = new Session();
    s->model = model; s->ctx = ctx; s->vocab = llama_model_get_vocab(model); s->n_ctx = n_ctx; s->n_batch = n_batch;
    const char *bos = llama_vocab_get_text(s->vocab, llama_vocab_bos(s->vocab));
    s->bos_text = bos ? bos : "";
    if (llama_model_chat_template(model, nullptr)) {
        try { s->tmpls = common_chat_templates_init(model, ""); } catch (const std::exception &ex) { LOGE("template init failed: %s", ex.what()); }
    }
    LOGI("loaded %s (template=%s)", path.c_str(), s->tmpls ? "jinja" : "none");
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jstring JNICALL FN(nativeRender)(JNIEnv *env, jobject, jlong h, jstring jsystem, jstring juser, jboolean enable_thinking) {
    Session *s = session(h);
    std::string system = utf8(env, jsystem), user = utf8(env, juser);
    if (s && s->tmpls) {
        try {
            common_chat_templates_inputs in;
            if (!system.empty()) { common_chat_msg m; m.role = "system"; m.content = system; in.messages.push_back(m); }
            common_chat_msg u; u.role = "user"; u.content = user; in.messages.push_back(u);
            in.add_generation_prompt = true; in.use_jinja = true; in.enable_thinking = enable_thinking;
            return jstr(env, common_chat_templates_apply(s->tmpls.get(), in).prompt);
        } catch (const std::exception &ex) { LOGE("render failed: %s", ex.what()); }
    }
    return jstr(env, "System:\n" + system + "\n\nUser:\n" + user + "\n\nAssistant:\n");
}

// Forgets every cached token and clears the memory: the next generation is a full evaluation.
JNIEXPORT void JNICALL FN(nativeResetCache)(JNIEnv *, jobject, jlong h) {
    Session *s = session(h);
    if (!s) return;
    llama_memory_clear(llama_get_memory(s->ctx), true);
    s->cache.clear();
}

JNIEXPORT jstring JNICALL FN(nativeGenerate)(JNIEnv *env, jobject, jlong h, jstring jprompt, jstring jgrammar, jint max_tokens, jboolean allow_prefix_reuse) {
    Session *s = session(h);
    std::string out = "{";
    if (!s) { out += "\"ok\":false,\"error\":\"no session\"}"; return jstr(env, out); }
    std::string prompt = utf8(env, jprompt), grammar = utf8(env, jgrammar);
    s->stop_requested.store(false);
    bool has_bos = !s->bos_text.empty() && prompt.rfind(s->bos_text, 0) == 0;
    auto t_tok = clk::now();
    std::vector<llama_token> toks = tokenize(s->vocab, prompt, !has_bos);
    double tokenize_ms = ms_since(t_tok);
    if (toks.empty() || (int) toks.size() + max_tokens > s->n_ctx) { out += "\"ok\":false,\"error\":\"prompt does not fit\"}"; return jstr(env, out); }

    // Phase 4B prefix reuse: keep the KV of the token-for-token common prefix with the last request of this
    // identity (the caller resets between identities), rewind everything after it, and evaluate only the rest.
    // An identical prompt still re-evaluates its last token so the logits of the next position are fresh.
    size_t n_prefix = 0;
    if (allow_prefix_reuse && !s->cache.empty()) {
        n_prefix = common_prefix(s->cache, toks);
        if (n_prefix >= toks.size()) n_prefix = toks.size() - 1;
    }
    if (n_prefix == 0) {
        llama_memory_clear(llama_get_memory(s->ctx), true);
    } else if (!llama_memory_seq_rm(llama_get_memory(s->ctx), 0, (llama_pos) n_prefix, -1)) {
        llama_memory_clear(llama_get_memory(s->ctx), true);
        n_prefix = 0;
    }
    s->cache.assign(toks.begin(), toks.begin() + (long) n_prefix);

    llama_sampler_chain_params sp = llama_sampler_chain_default_params(); sp.no_perf = true;
    llama_sampler *chain = llama_sampler_chain_init(sp);
    if (!grammar.empty()) {
        llama_sampler *g = llama_sampler_init_grammar(s->vocab, grammar.c_str(), "root");
        if (!g) { llama_sampler_free(chain); out += "\"ok\":false,\"error\":\"grammar failed\"}"; return jstr(env, out); }
        llama_sampler_chain_add(chain, g);
    }
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());

    auto t0 = clk::now();
    std::string error, stop = "error", text; int gen = 0; double ttft = 0, prompt_eval_ms = 0;
    for (size_t off = n_prefix; off < toks.size(); off += (size_t) s->n_batch) {
        int n = (int) std::min((size_t) s->n_batch, toks.size() - off);
        if (llama_decode(s->ctx, llama_batch_get_one(toks.data() + off, n)) != 0) { error = "prompt decode failed"; break; }
        s->cache.insert(s->cache.end(), toks.begin() + (long) off, toks.begin() + (long) off + n);
    }
    prompt_eval_ms = ms_since(t0);
    if (error.empty()) try {
        int n_past = (int) toks.size();
        while (true) {
            llama_token tok = llama_sampler_sample(chain, s->ctx, -1);
            if (gen == 0) ttft = ms_since(t0);
            if (llama_vocab_is_eog(s->vocab, tok)) { stop = "eog"; break; }
            text += piece(s->vocab, tok); gen++;
            if (gen >= max_tokens) { stop = "max"; break; }
            if (n_past + 1 > s->n_ctx) { stop = "ctx"; break; }
            if (s->stop_requested.load()) { stop = "cancelled"; break; }
            if (llama_decode(s->ctx, llama_batch_get_one(&tok, 1)) != 0) { error = "decode failed"; break; }
            s->cache.push_back(tok);
            n_past++;
        }
    } catch (const std::exception &ex) { error = std::string("exception: ") + ex.what(); }
    llama_sampler_free(chain);
    // after an error nothing about the memory is trusted: the next request starts from a clear
    if (!error.empty()) { llama_memory_clear(llama_get_memory(s->ctx), true); s->cache.clear(); }

    out += "\"ok\":" + std::string(error.empty() ? "true" : "false");
    out += ",\"text\":\""; escape(out, text); out += "\"";
    out += ",\"promptTokens\":" + std::to_string(toks.size());
    out += ",\"reusedTokens\":" + std::to_string(n_prefix);
    out += ",\"evaluatedTokens\":" + std::to_string(toks.size() - n_prefix);
    out += ",\"tokenizeMs\":" + std::to_string(tokenize_ms);
    out += ",\"promptEvalMs\":" + std::to_string(prompt_eval_ms);
    out += ",\"genTokens\":" + std::to_string(gen);
    out += ",\"ttftMs\":" + std::to_string(ttft);
    out += ",\"totalMs\":" + std::to_string(ms_since(t0));
    out += ",\"stop\":\"" + stop + "\"";
    if (!error.empty()) { out += ",\"error\":\""; escape(out, error); out += "\""; }
    out += "}";
    return jstr(env, out);
}

JNIEXPORT void JNICALL FN(nativeRequestStop)(JNIEnv *, jobject, jlong h) { if (Session *s = session(h)) s->stop_requested.store(true); }

JNIEXPORT void JNICALL FN(nativeUnload)(JNIEnv *, jobject, jlong h) {
    Session *s = session(h);
    if (!s) return;
    llama_free(s->ctx); llama_model_free(s->model);
    delete s;
}

} // extern "C"

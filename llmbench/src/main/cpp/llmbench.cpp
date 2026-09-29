// Local LLM Phase 0 bench — the thin JNI bridge over llama.cpp (docs/LLM_PHASE0.md §4).
//
// Deliberately small: load / unload a model, run one greedy generation with an optional
// GBNF grammar, report timings. No streaming, no KV reuse across calls, no sampling
// beyond greedy (temperature 0 is the plan's fixed condition). Every call clears the
// context memory first so each case starts from an empty KV cache.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"
#include "chat.h"   // llama-common: Jinja chat templates with an explicit enable_thinking

#define TAG "llmbench"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    int n_ctx = 0;
    int n_batch = 0;
    int n_threads = 0;
    std::string model_path;
    std::string chat_template;   // empty when the GGUF carries none
    std::atomic<bool> stop_requested{false};   // set from Kotlin (thermal guard); checked per token
    common_chat_templates_ptr tmpls;   // the Jinja renderer over the GGUF template (null when none / unparsable)
    std::string bos_text;
    bool supports_thinking = false;
    long long load_ms = 0;
};

using clock_t_ = std::chrono::steady_clock;

long long ms_since(clock_t_::time_point t0) {
    return std::chrono::duration_cast<std::chrono::milliseconds>(clock_t_::now() - t0).count();
}

double us_since(clock_t_::time_point t0) {
    return std::chrono::duration_cast<std::chrono::microseconds>(clock_t_::now() - t0).count() / 1000.0;
}

std::string jstring_to_utf8(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

// Java's GetStringUTFChars yields modified UTF-8 (surrogate pairs as 6 bytes, NUL as C0 80);
// for the prompts used here (BMP Japanese, ASCII) it coincides with UTF-8. Model output is
// converted the other way with NewStringUTF, which rejects invalid sequences; the bridge
// therefore returns JSON built from escaped bytes instead (see json_escape below), so an
// invalid byte from a half-emitted multibyte token cannot break the call.
void json_escape(std::string &out, const std::string &s) {
    for (unsigned char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if (c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof buf, "\\u%04x", c);
                    out += buf;
                } else {
                    out += static_cast<char>(c);
                }
        }
    }
}

// Valid UTF-8 check so NewStringUTF never sees a broken sequence; invalid bytes become U+FFFD.
std::string sanitize_utf8(const std::string &s) {
    std::string out;
    size_t i = 0;
    while (i < s.size()) {
        unsigned char c = s[i];
        size_t len = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 0;
        bool ok = len > 0 && i + len <= s.size();
        for (size_t k = 1; ok && k < len; k++) ok = ((unsigned char) s[i + k] & 0xC0) == 0x80;
        if (ok) { out.append(s, i, len); i += len; }
        else { out += "\xEF\xBF\xBD"; i += 1; }
    }
    return out;
}

jstring to_jstring(JNIEnv *env, const std::string &s) {
    return env->NewStringUTF(sanitize_utf8(s).c_str());
}

Session *session_of(jlong handle) {
    return reinterpret_cast<Session *>(handle);
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text, bool add_special) {
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), nullptr, 0, add_special, true);
    std::vector<llama_token> tokens(n > 0 ? n : 0);
    if (n > 0) {
        int got = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), tokens.data(), n, add_special, true);
        tokens.resize(got > 0 ? got : 0);
    }
    return tokens;
}

std::string piece_of(const llama_vocab *vocab, llama_token token) {
    char buf[256];
    int n = llama_token_to_piece(vocab, token, buf, sizeof buf, 0, true);
    if (n < 0) {
        std::string big((size_t) -n, '\0');
        n = llama_token_to_piece(vocab, token, big.data(), (int32_t) big.size(), 0, true);
        big.resize(n > 0 ? n : 0);
        return big;
    }
    return std::string(buf, (size_t) n);
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_io_github_cragcoffee_memoripple_llmbench_LlamaBridge_nativeSystemInfo(JNIEnv *env, jobject) {
    return to_jstring(env, llama_print_system_info());
}

JNIEXPORT void JNICALL
Java_io_github_cragcoffee_memoripple_llmbench_LlamaBridge_nativeBackendInit(JNIEnv *, jobject) {
    llama_backend_init();
}

JNIEXPORT void JNICALL
Java_io_github_cragcoffee_memoripple_llmbench_LlamaBridge_nativeBackendFree(JNIEnv *, jobject) {
    llama_backend_free();
}

// Returns a handle (non-zero) or 0 on failure; the failure reason is in logcat.
JNIEXPORT jlong JNICALL
Java_io_github_cragcoffee_memoripple_llmbench_LlamaBridge_nativeLoad(
        JNIEnv *env, jobject, jstring jpath, jint n_ctx, jint n_batch, jint n_threads, jboolean use_mmap) {
    auto t0 = clock_t_::now();
    std::string path = jstring_to_utf8(env, jpath);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.load_mode = use_mmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE;
    llama_model *model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) {
        LOGE("model load failed: %s", path.c_str());
        return 0;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t) n_ctx;
    cparams.n_batch = (uint32_t) n_batch;
    cparams.n_ubatch = (uint32_t) n_batch;
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.no_perf = true;
    llama_context *ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE("context init failed: %s", path.c_str());
        llama_model_free(model);
        return 0;
    }

    auto *s = new Session();
    s->model = model;
    s->ctx = ctx;
    s->vocab = llama_model_get_vocab(model);
    s->n_ctx = n_ctx;
    s->n_batch = n_batch;
    s->n_threads = n_threads;
    s->model_path = path;
    const char *tmpl = llama_model_chat_template(model, nullptr);
    s->chat_template = tmpl ? tmpl : "";
    s->bos_text = llama_vocab_get_text(s->vocab, llama_vocab_bos(s->vocab)) ? llama_vocab_get_text(s->vocab, llama_vocab_bos(s->vocab)) : "";
    if (tmpl) {
        try {
            s->tmpls = common_chat_templates_init(model, "");
        } catch (const std::exception &ex) {
            LOGE("jinja template init failed: %s", ex.what());
        }
    }
    s->load_ms = ms_since(t0);
    LOGI("loaded %s in %lld ms (template=%s)", path.c_str(), s->load_ms, tmpl ? "yes" : "none");
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_io_github_cragcoffee_memoripple_llmbench_LlamaBridge_nativeUnload(JNIEnv *, jobject, jlong handle) {
    Session *s = session_of(handle);
    if (s == nullptr) return;
    llama_free(s->ctx);
    llama_model_free(s->model);
    delete s;
}

// {"loadMs":..,"sizeBytes":..,"nParams":..,"hasTemplate":..,"nCtxTrain":..,"desc":".."}
JNIEXPORT jstring JNICALL
Java_io_github_cragcoffee_memoripple_llmbench_LlamaBridge_nativeModelInfo(JNIEnv *env, jobject, jlong handle) {
    Session *s = session_of(handle);
    if (s == nullptr) return to_jstring(env, "{}");
    char desc[256];
    llama_model_desc(s->model, desc, sizeof desc);
    std::string out = "{";
    out += "\"loadMs\":" + std::to_string(s->load_ms);
    out += ",\"sizeBytes\":" + std::to_string(llama_model_size(s->model));
    out += ",\"nParams\":" + std::to_string(llama_model_n_params(s->model));
    out += ",\"hasTemplate\":" + std::string(s->chat_template.empty() ? "false" : "true");
    out += ",\"jinja\":" + std::string(s->tmpls ? "true" : "false");
    out += ",\"supportsThinking\":" + std::string(s->supports_thinking ? "true" : "false");
    out += ",\"bosText\":\""; json_escape(out, s->bos_text); out += "\"";
    out += ",\"nCtxTrain\":" + std::to_string(llama_model_n_ctx_train(s->model));
    out += ",\"nCtx\":" + std::to_string(llama_n_ctx(s->ctx));
    out += ",\"desc\":\""; json_escape(out, desc); out += "\"";
    out += "}";
    return to_jstring(env, out);
}

// Applies the model's own chat template to [system?, user] and appends the assistant
// prefix, or falls back to a plain "System:\n...\nUser:\n...\nAssistant:\n" layout when the
// GGUF carries no template (recorded as templateUsed=false in the result).
JNIEXPORT jstring JNICALL
Java_io_github_cragcoffee_memoripple_llmbench_LlamaBridge_nativeApplyChatTemplate(
        JNIEnv *env, jobject, jlong handle, jstring jsystem, jstring juser) {
    Session *s = session_of(handle);
    std::string system = jstring_to_utf8(env, jsystem);
    std::string user = jstring_to_utf8(env, juser);
    if (s == nullptr) return to_jstring(env, "");
    if (s->chat_template.empty()) {
        std::string plain;
        if (!system.empty()) plain += "System:\n" + system + "\n\n";
        plain += "User:\n" + user + "\n\nAssistant:\n";
        return to_jstring(env, plain);
    }
    if (s->tmpls) {
        try {
            common_chat_templates_inputs in;
            if (!system.empty()) { common_chat_msg m; m.role = "system"; m.content = system; in.messages.push_back(m); }
            common_chat_msg u; u.role = "user"; u.content = user; in.messages.push_back(u);
            in.add_generation_prompt = true;
            in.use_jinja = true;
            in.enable_thinking = false;   // the Phase 0 baseline: structured extraction without a thinking channel
            common_chat_params p = common_chat_templates_apply(s->tmpls.get(), in);
            s->supports_thinking = p.supports_thinking;
            return to_jstring(env, p.prompt);
        } catch (const std::exception &ex) {
            LOGE("jinja render failed (%s); falling back to the legacy template", ex.what());
        }
    }
    std::vector<llama_chat_message> msgs;
    if (!system.empty()) msgs.push_back({"system", system.c_str()});
    msgs.push_back({"user", user.c_str()});
    std::vector<char> buf(system.size() + user.size() + 1024);
    int n = llama_chat_apply_template(s->chat_template.c_str(), msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    if (n > (int) buf.size()) {
        buf.resize((size_t) n + 1);
        n = llama_chat_apply_template(s->chat_template.c_str(), msgs.data(), msgs.size(), true, buf.data(), (int32_t) buf.size());
    }
    if (n < 0) {
        LOGE("chat template failed (%d); falling back to plain layout", n);
        return to_jstring(env, "System:\n" + system + "\n\nUser:\n" + user + "\n\nAssistant:\n");
    }
    return to_jstring(env, std::string(buf.data(), (size_t) n));
}

JNIEXPORT jstring JNICALL
Java_io_github_cragcoffee_memoripple_llmbench_LlamaBridge_nativeChatTemplate(JNIEnv *env, jobject, jlong handle) {
    Session *s = session_of(handle);
    return to_jstring(env, s == nullptr ? "" : s->chat_template);
}

JNIEXPORT jboolean JNICALL
Java_io_github_cragcoffee_memoripple_llmbench_LlamaBridge_nativeHasChatTemplate(JNIEnv *, jobject, jlong handle) {
    Session *s = session_of(handle);
    return s != nullptr && !s->chat_template.empty();
}

// One greedy generation from a fully formatted prompt.
//
// Result JSON: {"ok":true,"text":"..","promptTokens":n,"genTokens":n,"promptMs":x,
//               "ttftMs":x,"genMs":x,"stop":"eog|max|ctx|error","grammarUsed":bool,"error":".."}
// ttftMs counts from the first prompt decode to the first sampled token (prompt processing
// included); genMs is the time spent after the first token; tokens/s = genTokens / genMs.
JNIEXPORT jstring JNICALL
Java_io_github_cragcoffee_memoripple_llmbench_LlamaBridge_nativeGenerate(
        JNIEnv *env, jobject, jlong handle, jstring jprompt, jstring jgrammar, jint max_tokens) {
    Session *s = session_of(handle);
    std::string out = "{";
    if (s == nullptr) {
        out += "\"ok\":false,\"error\":\"no session\"}";
        return to_jstring(env, out);
    }
    std::string prompt = jstring_to_utf8(env, jprompt);
    std::string grammar = jstring_to_utf8(env, jgrammar);

    s->stop_requested.store(false);
    llama_memory_clear(llama_get_memory(s->ctx), true);

    bool has_bos = !s->bos_text.empty() && prompt.rfind(s->bos_text, 0) == 0;
    std::vector<llama_token> prompt_tokens = tokenize(s->vocab, prompt, !has_bos);
    if (prompt_tokens.empty()) {
        out += "\"ok\":false,\"error\":\"empty prompt tokenization\"}";
        return to_jstring(env, out);
    }
    if ((int) prompt_tokens.size() + max_tokens > s->n_ctx) {
        out += "\"ok\":false,\"error\":\"prompt too long for context\",\"promptTokens\":" + std::to_string(prompt_tokens.size()) + "}";
        return to_jstring(env, out);
    }

    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler *chain = llama_sampler_chain_init(sp);
    bool grammar_used = false;
    if (!grammar.empty()) {
        llama_sampler *g = llama_sampler_init_grammar(s->vocab, grammar.c_str(), "root");
        if (g == nullptr) {
            llama_sampler_free(chain);
            out += "\"ok\":false,\"error\":\"grammar failed to compile\"}";
            return to_jstring(env, out);
        }
        llama_sampler_chain_add(chain, g);
        grammar_used = true;
    }
    llama_sampler_chain_add(chain, llama_sampler_init_greedy());

    auto t_start = clock_t_::now();
    // Prompt processing in n_batch slices.
    int n_past = 0;
    std::string error;
    for (size_t off = 0; off < prompt_tokens.size(); off += (size_t) s->n_batch) {
        int n = (int) std::min((size_t) s->n_batch, prompt_tokens.size() - off);
        llama_batch batch = llama_batch_get_one(prompt_tokens.data() + off, n);
        if (llama_decode(s->ctx, batch) != 0) {
            error = "prompt decode failed";
            break;
        }
        n_past += n;
    }
    double prompt_ms = us_since(t_start);

    std::string text;
    int gen_tokens = 0;
    std::string stop = "error";
    double ttft_ms = 0;
    double gen_ms = 0;
    if (error.empty()) try {
        auto t_first = clock_t_::time_point{};
        while (true) {
            // llama_sampler_sample applies the chain and accepts the token itself; no explicit accept here.
            llama_token tok = llama_sampler_sample(chain, s->ctx, -1);
            if (gen_tokens == 0) {
                ttft_ms = us_since(t_start);
                t_first = clock_t_::now();
            }
            if (llama_vocab_is_eog(s->vocab, tok)) {
                stop = "eog";
                break;
            }
            text += piece_of(s->vocab, tok);
            gen_tokens++;
            if (gen_tokens >= max_tokens) {
                stop = "max";
                break;
            }
            if (n_past + 1 > s->n_ctx) {
                stop = "ctx";
                break;
            }
            if (s->stop_requested.load()) {
                stop = "cancelled";
                break;
            }
            llama_batch batch = llama_batch_get_one(&tok, 1);
            if (llama_decode(s->ctx, batch) != 0) {
                error = "decode failed at token " + std::to_string(gen_tokens);
                break;
            }
            n_past++;
        }
        if (gen_tokens > 0) gen_ms = us_since(t_first);
    } catch (const std::exception &ex) {
        error = std::string("exception: ") + ex.what();
    }
    llama_sampler_free(chain);

    out += "\"ok\":" + std::string(error.empty() ? "true" : "false");
    out += ",\"text\":\""; json_escape(out, text); out += "\"";
    out += ",\"promptTokens\":" + std::to_string(prompt_tokens.size());
    out += ",\"genTokens\":" + std::to_string(gen_tokens);
    out += ",\"promptMs\":" + std::to_string(prompt_ms);
    out += ",\"ttftMs\":" + std::to_string(ttft_ms);
    out += ",\"genMs\":" + std::to_string(gen_ms);
    out += ",\"stop\":\"" + stop + "\"";
    out += ",\"grammarUsed\":" + std::string(grammar_used ? "true" : "false");
    if (!error.empty()) { out += ",\"error\":\""; json_escape(out, error); out += "\""; }
    out += "}";
    return to_jstring(env, out);
}

} // extern "C"

extern "C" JNIEXPORT void JNICALL
Java_io_github_cragcoffee_memoripple_llmbench_LlamaBridge_nativeRequestStop(JNIEnv *, jobject, jlong handle) {
    Session *s = session_of(handle);
    if (s != nullptr) s->stop_requested.store(true);
}

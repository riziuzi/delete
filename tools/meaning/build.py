"""
Builds Utho's on-device "meaning" table: one small vector per word, distilled from a multilingual
sentence model (the Model2Vec idea). The app averages the vectors of the words you said and compares
that with each subject, so "political parties" lands near Polity without either word being listed.

Words: common English, common Hindi (Devanagari), the same Hindi words written in Hinglish, and every
term from the app's own subject lists. Output (app/src/main/assets/meaning/):
  words.txt    one normalised word per line
  vectors.bin  "UTHV", version, count, dims, then float32 scale[count], uint8 weight[count], int8 vec[count*dims]
Run by .github/workflows/build-meaning.yml (needs internet for the model and word lists).
"""
import os
import re
import struct
import sys
import unicodedata

import numpy as np
from sentence_transformers import SentenceTransformer
from wordfreq import top_n_list, word_frequency

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "app/src/main/assets/meaning")
CORE = os.path.join(ROOT, "app/src/main/java/com/riziuzi/utho/core")
TEACHER = "sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2"
DIMS = 256
N_EN = 20000
N_HI = 10000
SIF_A = 1e-4


def is_dev(c):
    return "ऀ" <= c <= "ॿ"


def normalize(s):
    """Same as TopicClassifier.normalize in the app."""
    s = s.lower().replace("ँ", "ं").replace("़", "").replace("‌", "").replace("‍", "")
    s = "".join(c if unicodedata.category(c)[0] in "LMN" or c.isspace() else " " for c in s)
    return re.sub(r"\s+", " ", s).strip()


# ---- Hindi -> Hinglish (rule part of the app's converter: enough to name the romanised twin of a word)
WORDS = {
    "है": "hai", "हैं": "hain", "था": "tha", "थी": "thi", "थे": "the", "हूं": "hoon", "हो": "ho", "मैं": "main", "मैंने": "maine",
    "में": "mein", "और": "aur", "या": "ya", "लेकिन": "lekin", "पर": "par", "फिर": "phir", "भी": "bhi", "तो": "to", "ही": "hi",
    "नहीं": "nahi", "का": "ka", "की": "ki", "के": "ke", "को": "ko", "से": "se", "ने": "ne", "क्या": "kya", "आज": "aaj", "कल": "kal",
    "बहुत": "bahut", "अच्छा": "accha", "पढा": "padha", "पढी": "padhi", "पढे": "padhe", "पढाई": "padhai", "किया": "kiya", "किए": "kiye",
}
C = {"क": "k", "ख": "kh", "ग": "g", "घ": "gh", "ङ": "n", "च": "ch", "छ": "chh", "ज": "j", "झ": "jh", "ञ": "n", "ट": "t", "ठ": "th",
     "ड": "d", "ढ": "dh", "ण": "n", "त": "t", "थ": "th", "द": "d", "ध": "dh", "न": "n", "प": "p", "फ": "ph", "ब": "b", "भ": "bh",
     "म": "m", "य": "y", "र": "r", "ल": "l", "ळ": "l", "व": "v", "श": "sh", "ष": "sh", "स": "s", "ह": "h"}
M = {"ा": "A", "ि": "i", "ी": "i", "ु": "u", "ू": "u", "ृ": "ri", "े": "e", "ै": "ai", "ो": "o", "ौ": "au", "ॉ": "o", "ॅ": "e"}
IV = {"अ": "a", "आ": "aa", "इ": "i", "ई": "i", "उ": "u", "ऊ": "u", "ऋ": "ri", "ए": "e", "ऐ": "ai", "ओ": "o", "औ": "au", "ऑ": "o"}


def translit(w):
    if w in WORDS:
        return WORDS[w]
    u, i = [], 0
    while i < len(w):
        c = w[i]
        if c in C:
            i += 1
            n = w[i] if i < len(w) else ""
            if n == "्":
                u.append([C[c], "", False, True]); i += 1
            elif n in M:
                u.append([C[c], M[n], False, False]); i += 1
            else:
                u.append([C[c], "a", False, False])
            continue
        if c in IV:
            u.append([None, IV[c], False, False])
        elif c == "ं" and u:
            u[-1][2] = True
        elif c == "ः":
            u.append([None, "h", False, False])
        i += 1
    n = len(u)
    if n > 1 and u[-1][0] and u[-1][1] == "a" and not u[-1][2] and not u[-2][3]:
        u[-1][1] = ""
    i = n - 2
    while i >= 1:
        cur, prev, nxt = u[i], u[i - 1], u[i + 1]
        if cur[0] and cur[1] == "a" and not cur[2] and prev[1] and nxt[0] and nxt[1]:
            cur[1] = ""
            i -= 2
            continue
        i -= 1
    syll = sum(1 for x in u if x[1])
    out = ""
    for k, x in enumerate(u):
        if x[0]:
            out += x[0]
        out += ("aa" if syll == 1 else "a") if x[1] == "A" else x[1]
        if x[2]:
            nx = u[k + 1][0] if k + 1 < n else ""
            out += "m" if nx in ("p", "ph", "b", "bh", "m", "v") else "n"
    return out.replace("chchh", "cch")


def domain_terms():
    """Every word in the app's subject lists and Hinglish dictionary."""
    out = []
    for name in ("TopicClassifier.kt", "Hinglish.kt"):
        src = open(os.path.join(CORE, name), encoding="utf-8").read()
        for lit in re.findall(r'"((?:[^"\\\n]|\\.)*)"', src):
            for w in normalize(lit).split():
                if len(w) >= 2 and not w.isdigit():
                    out.append(w)
    return out


def main():
    os.makedirs(OUT, exist_ok=True)
    entries = []  # (word, source text to embed, frequency for weighting)
    seen = set()

    def add(word, source, freq):
        if word and word not in seen:
            seen.add(word)
            entries.append((word, source, freq))

    en = [w for w in top_n_list("en", N_EN) if re.fullmatch(r"[a-z]{2,}", w)]
    for w in en:
        add(w, w, word_frequency(w, "en"))
    hi = []
    for w in top_n_list("hi", N_HI):
        nw = normalize(w)
        if nw and all(is_dev(c) for c in nw) and len(nw) >= 2:
            hi.append((nw, w))
    for nw, w in hi:
        add(nw, w, word_frequency(w, "hi"))
    for w in domain_terms():
        lang = "hi" if any(is_dev(c) for c in w) else "en"
        add(w, w, word_frequency(w, lang))
    roman = 0
    for nw, w in hi:
        r = normalize(translit(nw))
        if re.fullmatch(r"[a-z]{3,}", r) and r not in seen:
            add(r, w, word_frequency(w, "hi"))
            roman += 1
    print(f"words: {len(entries)} (english {len(en)}, hindi {len(hi)}, hinglish {roman})", flush=True)

    model = SentenceTransformer(TEACHER, device="cpu")
    sources = sorted({s for _, s, _ in entries})
    vecs = model.encode(sources, batch_size=512, show_progress_bar=False, convert_to_numpy=True, normalize_embeddings=True)
    index = {s: i for i, s in enumerate(sources)}
    X = np.stack([vecs[index[s]] for _, s, _ in entries]).astype(np.float64)

    # PCA to DIMS (also removes the shared direction all words have in common), then unit length.
    mean = X.mean(axis=0)
    Xc = X - mean
    _, _, vt = np.linalg.svd(Xc[np.random.default_rng(0).choice(len(Xc), min(len(Xc), 20000), replace=False)], full_matrices=False)
    P = Xc @ vt[:DIMS].T
    P /= np.linalg.norm(P, axis=1, keepdims=True) + 1e-9

    # Frequent words ("the", "ke", "padha") count little; rare, meaningful ones count fully.
    weights = np.array([SIF_A / (SIF_A + f) for _, _, f in entries])
    scales = np.abs(P).max(axis=1) / 127.0
    q = np.clip(np.round(P / scales[:, None]), -127, 127).astype(np.int8)

    with open(os.path.join(OUT, "words.txt"), "w", encoding="utf-8") as f:
        f.write("\n".join(w for w, _, _ in entries) + "\n")
    with open(os.path.join(OUT, "vectors.bin"), "wb") as f:
        f.write(b"UTHV")
        f.write(struct.pack("<iii", 1, len(entries), DIMS))
        f.write(scales.astype("<f4").tobytes())
        f.write(np.clip(np.round(weights * 255), 1, 255).astype(np.uint8).tobytes())
        f.write(q.tobytes())
    print("size MB:", round(os.path.getsize(os.path.join(OUT, "vectors.bin")) / 1e6, 1), flush=True)

    # Quick look: a few notes against short subject descriptions.
    lookup = {w: i for i, (w, _, _) in enumerate(entries)}
    deq = q.astype(np.float32) * scales[:, None].astype(np.float32)

    def embed(text):
        acc = np.zeros(DIMS)
        for t in normalize(text).split():
            i = lookup.get(t)
            if i is not None:
                acc += weights[i] * deq[i]
        n = np.linalg.norm(acc)
        return acc / n if n else acc

    subjects = {
        "Polity": "polity constitution parliament elections government rights",
        "History": "history ancient medieval modern india empire freedom struggle",
        "Geography": "geography rivers mountains climate maps earth",
        "Economy": "economy inflation budget banking gdp markets",
        "Environment": "environment ecology biodiversity pollution climate change wildlife",
        "Science & Tech": "science technology space research computers",
    }
    protos = {k: embed(v) for k, v in subjects.items()}
    for note in ["I studied political parties", "राजनीतिक दल पढ़े", "mughal emperors", "ozone layer depletion",
                 "chandrayaan mission", "fiscal deficit", "tectonic plates", "do ghante padhai ki"]:
        e = embed(note)
        ranked = sorted(((float(e @ p), k) for k, p in protos.items()), reverse=True)
        print(f"{note!r}: " + ", ".join(f"{k} {s:.2f}" for s, k in ranked[:3]), flush=True)


if __name__ == "__main__":
    sys.exit(main())

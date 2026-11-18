import numpy as np, scipy.signal as sg, scipy.io.wavfile as wf, subprocess, os
SR = 44100
rng = np.random.default_rng(3)

def t_(d): return np.arange(int(SR * d)) / SR

def env(t, a, d):
    return (1 - np.exp(-t / a)) * np.exp(-t / d)

def bell(f, dur, ratios, amps, decays, attack=0.01, beat=0.0):
    t = t_(dur); x = np.zeros_like(t)
    for r, a, d in zip(ratios, amps, decays):
        for s in ([-beat, beat] if beat else [0]):
            x += a * np.sin(2 * np.pi * (f * r + s) * t + rng.uniform(0, 6.28)) * env(t, attack, d) / (2 if beat else 1)
    return x

def lowpass(x, fc):
    b, a = sg.butter(2, fc / (SR / 2)); return sg.lfilter(b, a, x)

def reverb(x, size=1.2, mix=0.28):
    t = t_(size); ir = rng.standard_normal(len(t)) * np.exp(-t / (size / 4)); ir = lowpass(ir, 3500); ir /= np.abs(ir).sum() ** 0.5 * 6
    wet = sg.fftconvolve(x, ir)
    dry = np.concatenate([x, np.zeros(len(wet) - len(x))])
    return (1 - mix) * dry + mix * wet / (np.abs(wet).max() + 1e-9) * np.abs(x).max()

def place(total, parts):
    out = np.zeros(int(SR * total))
    for start, x in parts:
        i = int(SR * start); n = min(len(x), len(out) - i); out[i:i + n] += x[:n]
    return out

def finish(x, peak=0.5, tail=0.25):
    n = int(SR * 0.004); x[:n] *= np.linspace(0, 1, n)
    m = int(SR * tail); x[-m:] *= np.linspace(1, 0, m) ** 2
    return x / (np.abs(x).max() + 1e-9) * peak

def drop(f0, f1, dur=0.22, glide=0.06):
    t = t_(dur); f = f1 + (f0 - f1) * np.exp(-t / glide)
    ph = 2 * np.pi * np.cumsum(f) / SR
    return np.sin(ph) * env(t, 0.002, 0.07)

def pad(freqs, dur, attack, release):
    t = t_(dur); x = np.zeros_like(t)
    for f in freqs:
        for det in (-0.6, 0.6):
            x += np.sin(2 * np.pi * (f + det) * t + rng.uniform(0, 6.28)) + 0.18 * np.sin(4 * np.pi * (f + det) * t)
    shape = np.minimum(1, t / attack) * np.where(t > dur - release, np.maximum(0, (dur - t) / release), 1) ** 1.5
    return lowpass(x * shape, 2200)

S = {}
# Open sounds stay short (about a second) because the mic is already listening; close sounds can ring out.
# 1 Singing bowl: one soft strike; closing is lower
S["bowl"] = (
    reverb(bell(329.6, 1.1, [1, 2.71, 5.15], [1, .3, .08], [.55, .3, .15], attack=.02, beat=.9), size=.5, mix=.2),
    reverb(bell(246.9, 1.9, [1, 2.71, 5.15], [1, .3, .08], [1.3, .8, .35], attack=.03, beat=.7)),
)
# 2 Soft chime: two gentle notes rising when it opens, falling when it closes
ch = lambda f, d: lowpass(bell(f, d * 1.6, [1, 2, 3], [1, .22, .06], [d, d / 2, d / 4], attack=.006), 4000)
S["chime"] = (
    reverb(place(1.05, [(0, ch(659.3, .3) * .8), (.13, ch(987.8, .35))]), size=.5, mix=.2),
    reverb(place(2.0, [(0, ch(987.8, .8) * .8), (.16, ch(659.3, .9))])),
)
# 3 Water drop: a light rising plip; closing is one deeper drop
S["drop"] = (
    reverb(place(.75, [(0, drop(650, 1250)), (.15, drop(900, 1650) * .55)]), size=.5, mix=.25),
    reverb(place(1.2, [(0, drop(1000, 520) * .9)]), size=1.4, mix=.35),
)
# 4 Warm pad: a soft chord that swells in briefly; closing chord fades down
S["pad"] = (
    reverb(pad([261.6, 329.6, 392.0, 493.9], 1.1, .12, .7), size=.5, mix=.2),
    reverb(pad([220.0, 261.6, 329.6, 392.0], 1.6, .12, 1.3)),
)

os.makedirs("/tmp/hx/snd/raw", exist_ok=True); os.makedirs("/tmp/hx/snd/preview", exist_ok=True)
names = {"bowl": "1-singing-bowl", "chime": "2-soft-chime", "drop": "3-water-drop", "pad": "4-warm-pad"}
for k, (o, c) in S.items():
    louder = 1.3 if k == "drop" else 1.0  # short drops sound quieter at the same peak
    o, c = finish(o, peak=.5 * louder), finish(c, peak=.42 * louder)
    for tag, x in (("open", o), ("close", c)):
        wf.write(f"/tmp/hx/snd/{k}_{tag}.wav", SR, (x * 32767).astype(np.int16))
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", f"/tmp/hx/snd/{k}_{tag}.wav", "-c:a", "libvorbis", "-q:a", "4", f"/tmp/hx/snd/raw/snd_{k}_{tag}.ogg"], check=True)
    both = np.concatenate([o, np.zeros(int(SR * 1.2)), c])
    wf.write(f"/tmp/hx/snd/{k}_preview.wav", SR, (both * 32767).astype(np.int16))
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", f"/tmp/hx/snd/{k}_preview.wav", "-b:a", "160k", f"/tmp/hx/snd/preview/{names[k]}.mp3"], check=True)
    print(k, "open %.1fs close %.1fs" % (len(o) / SR, len(c) / SR))

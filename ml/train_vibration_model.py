"""
ЛР6 · Варіант 15 · Навчання моделі класифікації вібрацій для системи безпеки.

Скрипт:
  1) генерує синтетичний датасет «сирих» відліків акселерометра (50 Гц) для 4 класів;
  2) пропускає їх через ТОЧНО ту саму передобробку, що й додаток
     (WindowPreprocessor.kt: фільтр гравітації α=0.8 → кадри по 50 мс → пік |d| у кадрі → /4 м/с²);
  3) навчає маленьку 1D-CNN (Keras) на вікнах 20 кадрів × 3 осі (= 1 секунда);
  4) конвертує її у TensorFlow Lite (vibration_classifier.tflite) і перевіряє через tf.lite.Interpreter;
  5) пише labels.txt і контрольні вектори для юніт-тесту передобробки.

Запуск (Python 3.10+):  pip install tensorflow numpy
                        python ml/train_vibration_model.py
Результат кладеться в app/src/main/assets/ та app/src/test/resources/.
"""
import math
import os
import random

import numpy as np

# ---------- Параметри, що мають збігатися з ModelSpec.kt ----------
WINDOW = 20          # кадрів у вікні (1 секунда)
CHANNELS = 3         # осі X, Y, Z динамічного прискорення
FRAME_MS = 50        # тривалість кадру, мс (20 кадрів/с)
ALPHA = 0.8          # коефіцієнт фільтра гравітації (як у VibrationAnalyzer з ЛР1)
SCALE = 4.0          # нормування: ділимо на 4 м/с²
CLIP = 3.0           # обрізаємо до ±3 (тобто ±12 м/с²)
LABELS = ["calm", "touch", "machine", "tamper"]
G = 9.81

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
TEST_RES = os.path.join(ROOT, "app", "src", "test", "resources")


# ---------- Передобробка (порт WindowPreprocessor.kt, float32) ----------
class WindowPreprocessor:
    def __init__(self):
        self.g = np.zeros(3, dtype=np.float32)
        self.init = False
        self.frame_start = None
        self.peak = np.zeros(3, dtype=np.float32)
        self.peak_mag = np.float32(-1.0)

    def push(self, x, y, z, t_ms):
        a = np.array([x, y, z], dtype=np.float32)
        if not self.init:
            self.g = a.copy()
            self.init = True
        else:
            self.g = np.float32(ALPHA) * self.g + np.float32(1 - ALPHA) * a
        d = a - self.g
        mag = np.float32(math.sqrt(float(d[0]) ** 2 + float(d[1]) ** 2 + float(d[2]) ** 2))
        out = None
        if self.frame_start is None:
            self.frame_start = t_ms
        elif t_ms - self.frame_start >= FRAME_MS:
            out = np.clip(self.peak / np.float32(SCALE), -CLIP, CLIP).astype(np.float32)
            self.frame_start += FRAME_MS
            if t_ms - self.frame_start >= FRAME_MS:   # пропуск даних — починаємо кадр заново
                self.frame_start = t_ms
            self.peak_mag = np.float32(-1.0)
        if mag > self.peak_mag:
            self.peak = d
            self.peak_mag = mag
        return out


# ---------- Генерація «сирих» сигналів ----------
def rand_unit():
    v = np.random.normal(size=3)
    return v / np.linalg.norm(v)


def rotate(v, axis, angle):
    """Формула Родріга: поворот вектора v навколо осі axis на кут angle."""
    axis = axis / np.linalg.norm(axis)
    return (v * math.cos(angle) + np.cross(axis, v) * math.sin(angle)
            + axis * np.dot(axis, v) * (1 - math.cos(angle)))


def damped(t, t0, amp, tau, f, phase=0.0):
    """Затухаючий імпульс (удар/дотик) з початком у t0."""
    dt = t - t0
    if dt < 0 or dt > 6 * tau:
        return 0.0
    return amp * math.exp(-dt / tau) * math.sin(2 * math.pi * f * dt + phase + math.pi / 2)


def make_sequence(label, duration=1.7):
    """Повертає список (t_ms, x, y, z) — «сирі» відліки з частотою ~50 Гц (з джитером)."""
    period = random.uniform(15, 25)          # мс — емулятор/телефон дають різну частоту
    jitter = random.uniform(0, 3)
    g0 = rand_unit() * G
    if random.random() < 0.5:                # часто пристрій лежить «як в емуляторі»: g по Y або Z
        g0 = np.array(random.choice([[0, G, 0], [0, 0, G], [G, 0, 0]]), dtype=float)
        g0 = rotate(g0, rand_unit(), random.uniform(0, 0.3))
    noise_sigma = random.uniform(0.002, 0.05)
    win_start = duration - 1.0               # останні 20 кадрів ≈ ця частина

    # --- параметри патерну ---
    events = []          # (t0, amp, tau, f, dir)
    vib = []             # (amp_vec, f, phase)
    wear = None          # (amp, f, phase, axes_perm, rot)
    rot_axis = rand_unit()
    rot_segments = []    # (t_start, t_end, omega rad/s)
    step = None          # (t0, vec)
    extra_sigma = 0.0

    if label == "calm":
        if random.random() < 0.5:
            rot_segments.append((0, duration, math.radians(random.uniform(0, 8))))
        if random.random() < 0.5:
            vib.append((rand_unit() * random.uniform(0, 0.05), random.uniform(3, 25), random.uniform(0, 6.3)))
    elif label == "touch":
        kind = random.random()
        if kind < 0.5:
            for _ in range(1 if random.random() < 0.7 else 2):
                events.append((random.uniform(win_start, duration - 0.05), random.uniform(0.6, 7.0),
                               random.uniform(0.015, 0.08), random.uniform(6, 25), rand_unit()))
        elif kind < 0.8:
            ts = random.uniform(win_start - 0.3, duration - 0.2)
            rot_segments.append((ts, ts + random.uniform(0.15, 0.8), math.radians(random.uniform(40, 250))))
        else:
            step = (random.uniform(win_start, duration - 0.1), rand_unit() * random.uniform(0.5, 8.0))
    elif label == "machine":
        if random.random() < 0.5:   # як WearSimulator (ЛР1): гармоніка + рівномірний шум
            amp = 3.0 * random.uniform(0.07, 1.0)
            wear = (amp, random.uniform(4, 9), random.uniform(0, 6.3), np.random.permutation(3),
                    rand_unit() if random.random() < 0.3 else None)
        else:
            for _ in range(random.choice([1, 1, 2])):
                vib.append((rand_unit() * random.uniform(0.12, 3.5), random.uniform(2, 20), random.uniform(0, 6.3)))
            extra_sigma = random.uniform(0, 0.25) * np.linalg.norm(vib[0][0])
    elif label == "tamper":
        n = random.randint(3, 9)
        for _ in range(n):
            events.append((random.uniform(win_start - 0.1, duration - 0.03), random.uniform(2.0, 10.0),
                           random.uniform(0.01, 0.05), random.uniform(8, 25), rand_unit()))
        extra_sigma = random.uniform(0.15, 1.2)
        if random.random() < 0.3:
            vib.append((rand_unit() * random.uniform(0.1, 0.6), random.uniform(3, 15), random.uniform(0, 6.3)))

    out = []
    t_ms = random.uniform(0, 1000)
    t0_ms = t_ms
    g = g0.copy()
    last_t = 0.0
    while True:
        t = (t_ms - t0_ms) / 1000.0
        if t > duration:
            break
        dt = t - last_t
        last_t = t
        for (a, b, om) in rot_segments:
            if a <= t <= b:
                g = rotate(g, rot_axis, om * dt)
        acc = g.copy()
        for (amp_vec, f, ph) in vib:
            acc += amp_vec * math.sin(2 * math.pi * f * t + ph)
        if wear is not None:
            amp, f, ph, perm, rot = wear
            h = math.sin(2 * math.pi * f * t + ph) * amp
            n3 = [(random.random() * 2 - 1) * amp * 0.5 for _ in range(3)]
            w = np.array([h + n3[0], h * 0.5 + n3[1], n3[2]])[perm]
            if rot is not None:
                w = rotate(w, rot, 1.0)
            acc += w
        for (te, amp, tau, f, d) in events:
            acc += d * damped(t, te, amp, tau, f)
        if step is not None and t >= step[0]:
            acc += step[1]
        acc += np.random.normal(size=3) * (noise_sigma + extra_sigma)
        out.append((int(round(t_ms)), float(acc[0]), float(acc[1]), float(acc[2])))
        t_ms += period + random.uniform(-jitter, jitter)
    return out


def window_from_sequence(seq):
    pre = WindowPreprocessor()
    frames = []
    for (t, x, y, z) in seq:
        f = pre.push(x, y, z, t)
        if f is not None:
            frames.append(f)
    if len(frames) < WINDOW:
        return None
    return np.stack(frames[-WINDOW:])


def build_dataset(n_per_class, seed):
    random.seed(seed)
    np.random.seed(seed)
    xs, ys = [], []
    for ci, lab in enumerate(LABELS):
        k = 0
        while k < n_per_class:
            w = window_from_sequence(make_sequence(lab))
            if w is None:
                continue
            xs.append(w)
            ys.append(ci)
            k += 1
    return np.array(xs, dtype=np.float32), np.array(ys, dtype=np.int64)


def write_test_vectors():
    """Контрольні вектори: сирі відліки → очікувані кадри. Перевіряються WindowPreprocessorTest."""
    random.seed(7)
    np.random.seed(7)
    seq = make_sequence("tamper", duration=1.2)
    pre = WindowPreprocessor()
    lines = ["# raw,t_ms,x,y,z  |  frame,index,dx,dy,dz  (згенеровано ml/train_vibration_model.py)"]
    idx = 0
    for (t, x, y, z) in seq:
        lines.append(f"raw,{t},{x:.6f},{y:.6f},{z:.6f}")
        # Kotlin читає вже округлені до 6 знаків значення — рахуємо від них же
        f = pre.push(float(f"{x:.6f}"), float(f"{y:.6f}"), float(f"{z:.6f}"), t)
        if f is not None:
            lines.append(f"frame,{idx},{f[0]:.6f},{f[1]:.6f},{f[2]:.6f}")
            idx += 1
    os.makedirs(TEST_RES, exist_ok=True)
    with open(os.path.join(TEST_RES, "preprocess_vectors.csv"), "w") as fh:
        fh.write("\n".join(lines) + "\n")
    print("test vectors: frames =", idx)


def main():
    import tensorflow as tf
    tf.random.set_seed(15)

    write_test_vectors()
    x_train, y_train = build_dataset(4000, seed=1)
    x_val, y_val = build_dataset(600, seed=2)
    print("dataset:", x_train.shape, x_val.shape)

    inp = tf.keras.Input(shape=(WINDOW, CHANNELS), name="accel_window")
    h = tf.keras.layers.Conv1D(16, 3, padding="same", activation="relu")(inp)
    h = tf.keras.layers.Conv1D(24, 3, padding="same", activation="relu")(h)
    h = tf.keras.layers.Concatenate()([
        tf.keras.layers.GlobalMaxPooling1D()(h),
        tf.keras.layers.GlobalAveragePooling1D()(h)])
    h = tf.keras.layers.Dense(24, activation="relu")(h)
    out = tf.keras.layers.Dense(len(LABELS), activation="softmax", name="probabilities")(h)
    model = tf.keras.Model(inp, out)
    model.compile(optimizer=tf.keras.optimizers.Adam(3e-3),
                  loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    model.summary()
    model.fit(x_train, y_train, validation_data=(x_val, y_val), epochs=40, batch_size=64, verbose=2,
              callbacks=[tf.keras.callbacks.ReduceLROnPlateau(patience=4, factor=0.5)])

    # Матриця помилок на валідаційній вибірці
    pred = model.predict(x_val, verbose=0).argmax(1)
    cm = np.zeros((len(LABELS), len(LABELS)), dtype=int)
    for t, p in zip(y_val, pred):
        cm[t, p] += 1
    print("val accuracy:", (pred == y_val).mean())
    print("confusion (rows=true):\n", cm)

    # --- Конвертація у TensorFlow Lite ---
    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite = converter.convert()
    os.makedirs(ASSETS, exist_ok=True)
    with open(os.path.join(ASSETS, "vibration_classifier.tflite"), "wb") as fh:
        fh.write(tflite)
    with open(os.path.join(ASSETS, "labels.txt"), "w") as fh:
        fh.write("\n".join(LABELS) + "\n")
    print("tflite size:", len(tflite), "bytes")

    # --- Перевірка .tflite тим самим інтерпретатором, що й на Android ---
    it = tf.lite.Interpreter(model_content=tflite)
    it.allocate_tensors()
    i_d, o_d = it.get_input_details()[0], it.get_output_details()[0]
    print("input :", i_d["name"], i_d["shape"], i_d["dtype"])
    print("output:", o_d["name"], o_d["shape"], o_d["dtype"])
    ok = 0
    for k in range(len(x_val)):
        it.set_tensor(i_d["index"], x_val[k:k + 1])
        it.invoke()
        ok += int(it.get_tensor(o_d["index"])[0].argmax() == y_val[k])
    print("tflite val accuracy:", ok / len(x_val))
    np.savetxt(os.path.join(ROOT, "ml", "confusion_matrix.txt"), cm, fmt="%d",
               header="rows=true, cols=predicted: " + " ".join(LABELS))


if __name__ == "__main__":
    main()

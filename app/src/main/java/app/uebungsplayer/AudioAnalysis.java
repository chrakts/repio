package app.uebungsplayer;

/**
 * Analyse eines Ausschnitts: mittlere Stimmung (Kammerton), Tempo, Tonart.
 *
 * Reine Rechnung ohne Android-Abhängigkeiten (Eingabe: Mono-Samples und Abtastrate), damit
 * sie sich auf dem PC gegen den Python-Prototyp (repio_analyse.py) prüfen lässt.
 * Die Algorithmen entsprechen dem Prototyp; die Fensterlängen sind auf Zeitdauern statt auf
 * eine feste Abtastrate bezogen.
 */
public final class AudioAnalysis {

    private AudioAnalysis() {}

    /** Abbruch von außen (z. B. Popup geschlossen). */
    public interface Cancel {
        boolean isCancelled();
    }

    public static final class CancelledException extends RuntimeException {
        public CancelledException() { super("cancelled"); }
    }

    public static final class TempoCandidate {
        public final double bpm;
        /** Bewertung relativ zum besten Kandidaten (1 = bester). */
        public final double relative;
        /** Vertrauen 0..1 (für die Farbanzeige). */
        public final double confidence;

        TempoCandidate(double bpm, double relative, double confidence) {
            this.bpm = bpm;
            this.relative = relative;
            this.confidence = confidence;
        }
    }

    public static final class KeyCandidate {
        /** Grundton als Tonklasse, 0 = C … 11 = H. */
        public final int tonic;
        public final boolean minor;
        public final double correlation;
        /** Vertrauen 0..1 (für die Farbanzeige). */
        public final double confidence;

        KeyCandidate(int tonic, boolean minor, double correlation, double confidence) {
            this.tonic = tonic;
            this.minor = minor;
            this.correlation = correlation;
            this.confidence = confidence;
        }
    }

    public static final class Result {
        public double durationS;
        /** Ausschnitt zu kurz für jede Analyse (unter ca. 93 ms) oder stumm. */
        public boolean tooShort, silent;

        /** NaN, wenn nicht bestimmt. */
        public double tuningHz = Double.NaN;
        public double tuningCent = Double.NaN;
        public double tuningSpreadCent = Double.NaN;
        /** Streuung in Hz (± um tuningHz). */
        public double tuningTolHz = Double.NaN;
        public int tuningPeaks;

        /** null, wenn nicht bestimmt; dann sagt tempoTooShort bzw. tempoNoPulse, warum. */
        public TempoCandidate[] tempo;
        public boolean tempoTooShort, tempoNoPulse;
        public double pulseStrength = Double.NaN;
        public double beatsInSegment = Double.NaN;

        /** null, wenn nicht bestimmt. */
        public KeyCandidate[] keys;
    }

    // ------------------------------------------------------------------ Vorverarbeitung

    /**
     * Tiefpass (gefensterter Sinc, 63 Taps) und Dezimierung um einen ganzzahligen Faktor,
     * sodass die Rate in der Nähe von 22 050 Hz liegt (44,1/48 kHz -> Faktor 2).
     * Rückgabe: {neue Samples} ; die neue Rate liefert {@link #decimatedRate}.
     */
    public static float[] decimate(float[] x, int sr) {
        int d = decimationFactor(sr);
        if (d < 2) return x;
        final int taps = 63, mid = taps / 2;
        double fc = 0.45 / d; // Grenzfrequenz relativ zur Eingangsrate
        double[] h = new double[taps];
        double sum = 0;
        for (int k = 0; k < taps; k++) {
            int m = k - mid;
            double s = (m == 0) ? 2 * fc : Math.sin(2 * Math.PI * fc * m) / (Math.PI * m);
            double w = 0.54 - 0.46 * Math.cos(2 * Math.PI * k / (taps - 1));
            h[k] = s * w;
            sum += h[k];
        }
        for (int k = 0; k < taps; k++) h[k] /= sum;
        int outLen = x.length / d;
        float[] y = new float[outLen];
        for (int i = 0; i < outLen; i++) {
            int c = i * d;
            double acc = 0;
            for (int k = 0; k < taps; k++) {
                int j = c + k - mid;
                if (j >= 0 && j < x.length) acc += h[k] * x[j];
            }
            y[i] = (float) acc;
        }
        return y;
    }

    public static int decimationFactor(int sr) {
        return Math.max(1, (int) Math.round(sr / 22050.0));
    }

    public static int decimatedRate(int sr) {
        return sr / decimationFactor(sr);
    }

    // ------------------------------------------------------------------ Hauptfunktion

    public static Result analyze(float[] samples, int sr, boolean doTuning, boolean doTempo, boolean doKey,
                                 Cancel cancel) {
        Result r = new Result();
        r.durationS = samples.length / (double) sr;

        int minFft = nearestPow2(0.093 * sr);
        if (samples.length < minFft) {
            r.tooShort = true;
            return r;
        }
        double peak = 0;
        for (float v : samples) peak = Math.max(peak, Math.abs(v));
        if (peak < 1e-6) {
            r.silent = true;
            return r;
        }
        double[] x = new double[samples.length];
        for (int i = 0; i < x.length; i++) x[i] = samples[i] / peak;

        Peaks pk = null;
        double a4 = 440.0;
        if (doTuning || doKey) {
            // Die Stimmung wird auch für die Tonart gebraucht (Bezug der Tonklassen)
            pk = spectralPeaks(x, sr, cancel);
            estimateTuning(pk, r);
            if (!Double.isNaN(r.tuningHz)) a4 = r.tuningHz;
            if (!doTuning) { // nur intern für die Tonart verwendet
                r.tuningHz = r.tuningCent = r.tuningSpreadCent = r.tuningTolHz = Double.NaN;
                r.tuningPeaks = 0;
            }
        }
        if (doTempo) estimateTempo(x, sr, 110.0, r, cancel);
        if (doKey && pk != null && pk.size > 0) estimateKey(pk, a4, r);
        return r;
    }

    // ------------------------------------------------------------------ Spektralspitzen

    static final class Peaks {
        int size;
        int[] frame = new int[1024];
        double[] freq = new double[1024];
        double[] amp = new double[1024];
        int nFrames, nFft;

        void add(int f, double hz, double a) {
            if (size == frame.length) {
                frame = java.util.Arrays.copyOf(frame, size * 2);
                freq = java.util.Arrays.copyOf(freq, size * 2);
                amp = java.util.Arrays.copyOf(amp, size * 2);
            }
            frame[size] = f;
            freq[size] = hz;
            amp[size] = a;
            size++;
        }
    }

    /** Zweierpotenz, die (logarithmisch) am nächsten bei n liegt. */
    static int nearestPow2(double n) {
        int e = (int) Math.round(Math.log(n) / Math.log(2));
        return 1 << Math.max(e, 4);
    }

    /** Größte Zweierpotenz <= Ausschnittlänge, begrenzt auf ca. 93 ms … 743 ms. */
    static int chooseFft(int nSamples, int sr) {
        int min = nearestPow2(0.093 * sr), max = nearestPow2(0.743 * sr);
        int n = min;
        while (n * 2 <= Math.min(nSamples, max)) n *= 2;
        return n;
    }

    /** Unterhalb dieser Frequenz liegen Halbtöne zu dicht für die Bin-Auflösung. */
    static double lowestUsableFreq(int sr, int nFft) {
        return Math.max(50.0, 2.0 * sr / nFft / 0.0595);
    }

    static double[] hann(int n) {
        double[] w = new double[n];
        for (int i = 0; i < n; i++) w[i] = 0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1));
        return w;
    }

    static Peaks spectralPeaks(double[] x, int sr, Cancel cancel) {
        final double fmax = 2000.0, relDb = -40.0;
        int n = chooseFft(x.length, sr);
        int hop = n / 4;
        double fmin = Math.max(50.0, lowestUsableFreq(sr, n));
        int kLo = Math.max(2, (int) (fmin * n / sr));
        int kHi = Math.min(n / 2 - 3, (int) (fmax * n / sr));
        int nFrames = 1 + (x.length - n) / hop;

        Fft fft = new Fft(n);
        double[] win = hann(n), re = new double[n], im = new double[n], mag = new double[n / 2 + 1];
        double relFactor = Math.pow(10, relDb / 20);
        double globalMax = 0;
        Peaks p = new Peaks();
        p.nFrames = nFrames;
        p.nFft = n;

        for (int f = 0; f < nFrames; f++) {
            if (cancel != null && cancel.isCancelled()) throw new CancelledException();
            int off = f * hop;
            for (int i = 0; i < n; i++) {
                re[i] = x[off + i] * win[i];
                im[i] = 0;
            }
            fft.transform(re, im);
            double frameMax = 0;
            for (int k = 0; k <= n / 2; k++) {
                mag[k] = Math.hypot(re[k], im[k]);
                if (mag[k] > frameMax) frameMax = mag[k];
            }
            globalMax = Math.max(globalMax, frameMax);
            double thr = frameMax * relFactor;
            for (int k = kLo; k <= kHi; k++) {
                double c = mag[k] + 1e-12, l1 = mag[k - 1] + 1e-12, r1 = mag[k + 1] + 1e-12;
                double l2 = mag[k - 2] + 1e-12, r2 = mag[k + 2] + 1e-12;
                // echtes Maximum: größer als die Nachbarn im Abstand 1 und 2 Bins
                // (Abstand 2 unterdrückt die Nebenkeulen des Hann-Fensters)
                if (!(c > l1 && c >= r1 && c > l2 && c >= r2 && c > thr)) continue;
                double a = Math.log(l1), b = Math.log(c), cc = Math.log(r1);
                double denom = a - 2 * b + cc;
                double q = Math.abs(denom) > 1e-12 ? 0.5 * (a - cc) / denom : 0.0;
                q = Math.max(-0.5, Math.min(0.5, q));
                p.add(f, (k + q) * sr / n, Math.exp(b - 0.25 * (a - cc) * q));
            }
        }
        // Stille ignorieren (wie im Prototyp: 70 dB unter dem lautesten Frame)
        double floor = globalMax * Math.pow(10, -70.0 / 20);
        int w = 0;
        for (int i = 0; i < p.size; i++) {
            if (p.amp[i] > floor) {
                p.frame[w] = p.frame[i];
                p.freq[w] = p.freq[i];
                p.amp[w] = p.amp[i];
                w++;
            }
        }
        p.size = w;
        return p;
    }

    // ------------------------------------------------------------------ 1. Stimmung

    private static double mod100(double v) {
        double m = v % 100.0;
        return m < 0 ? m + 100.0 : m;
    }

    static void estimateTuning(Peaks p, Result r) {
        final double fmin = 100.0, fmax = 2000.0;
        int cnt = 0;
        for (int i = 0; i < p.size; i++) if (p.freq[i] >= fmin && p.freq[i] <= fmax) cnt++;
        if (cnt < 5) return;
        double[] dev = new double[cnt], w = new double[cnt];
        int j = 0;
        for (int i = 0; i < p.size; i++) {
            double f = p.freq[i];
            if (f < fmin || f > fmax) continue;
            double cents = 1200 * Math.log(f / 440.0) / Math.log(2);
            dev[j] = mod100(cents + 50) - 50;
            w[j] = Math.sqrt(p.amp[i]);
            j++;
        }
        double[] hist = new double[100];
        for (int i = 0; i < cnt; i++) hist[((int) Math.floor(dev[i] + 50)) % 100] += w[i];
        double[] kernel = new double[21];
        for (int k = -10; k <= 10; k++) kernel[k + 10] = Math.exp(-0.5 * (k / 3.0) * (k / 3.0));
        int best = 0;
        double bestVal = -1;
        for (int i = 0; i < 100; i++) {
            double s = 0;
            for (int k = 0; k <= 20; k++) s += hist[Math.floorMod(i + k - 10, 100)] * kernel[k];
            if (s > bestVal) {
                bestVal = s;
                best = i;
            }
        }
        double coarse = best - 50 + 0.5;

        double sx = 0, sy = 0;
        for (int i = 0; i < cnt; i++) {
            double dist = mod100(dev[i] - coarse + 50) - 50;
            if (Math.abs(dist) > 15) continue;
            double ang = 2 * Math.PI * dev[i] / 100;
            sx += w[i] * Math.cos(ang);
            sy += w[i] * Math.sin(ang);
        }
        double devMean = Math.atan2(sy, sx) * 100 / (2 * Math.PI);

        double sw = 0, swd2 = 0, wAll = 0;
        for (int i = 0; i < cnt; i++) {
            wAll += w[i];
            double dist = mod100(dev[i] - devMean + 50) - 50;
            if (Math.abs(dist) > 15) continue;
            sw += w[i];
            swd2 += w[i] * dist * dist;
        }
        double spread = Math.sqrt(swd2 / sw);
        r.tuningCent = devMean;
        r.tuningHz = 440.0 * Math.pow(2, devMean / 1200);
        r.tuningSpreadCent = spread;
        r.tuningTolHz = r.tuningHz * (Math.pow(2, spread / 1200) - 1);
        r.tuningPeaks = cnt;
    }

    // ------------------------------------------------------------------ 2. Tempo

    /** Spektraler Fluss auf log-komprimiertem Betrag, lokal mittelwertbereinigt. */
    static double[] onsetEnvelope(double[] x, int sr, int n, int hop, Cancel cancel) {
        double[] xx = x;
        if (xx.length < n) xx = java.util.Arrays.copyOf(x, n);
        int nFrames = 1 + (xx.length - n) / hop;
        Fft fft = new Fft(n);
        double[] win = hann(n), re = new double[n], im = new double[n];
        double[] prev = new double[n / 2 + 1], cur = new double[n / 2 + 1];
        double[] flux = new double[nFrames];
        for (int f = 0; f < nFrames; f++) {
            if (cancel != null && (f & 63) == 0 && cancel.isCancelled()) throw new CancelledException();
            int off = f * hop;
            for (int i = 0; i < n; i++) {
                re[i] = xx[off + i] * win[i];
                im[i] = 0;
            }
            fft.transform(re, im);
            double s = 0;
            for (int k = 0; k <= n / 2; k++) {
                cur[k] = Math.log1p(100.0 * Math.hypot(re[k], im[k]));
                if (f > 0) s += Math.max(cur[k] - prev[k], 0);
            }
            flux[f] = s;
            double[] t = prev; prev = cur; cur = t;
        }
        // gleitender Mittelwert über 0,4 s (wie numpy.convolve(..., "same"))
        int w = Math.max(1, Math.min((int) (0.4 * sr / hop), nFrames));
        double[] cum = new double[nFrames + 1];
        for (int i = 0; i < nFrames; i++) cum[i + 1] = cum[i] + flux[i];
        double[] o = new double[nFrames];
        for (int i = 0; i < nFrames; i++) {
            int lo = Math.max(0, i - w / 2), hi = Math.min(nFrames - 1, i + (w - 1) / 2);
            double ma = (cum[hi + 1] - cum[lo]) / w;
            o[i] = Math.max(flux[i] - ma, 0);
        }
        return o;
    }

    static void estimateTempo(double[] x, int sr, double priorCenter, Result r, Cancel cancel) {
        final double bpmMin = 30.0, bpmMax = 240.0;
        int nOn = nearestPow2(0.093 * sr), hop = nOn / 8;
        double fr = sr / (double) hop;
        double[] o = onsetEnvelope(x, sr, nOn, hop, cancel);
        int n = o.length;
        int maxLag = n / 2;
        // Mindestens zwei volle Schlagperioden im Ausschnitt -> längste messbare Periode = halbe Länge
        double bpmFloor = 60.0 * fr / Math.max(maxLag - 1, 1);
        double effMin = Math.max(bpmMin, bpmFloor);
        if (effMin > bpmMax * 0.95) {
            r.tempoTooShort = true;
            return;
        }
        double mean = 0;
        for (double v : o) mean += v;
        mean /= n;
        int nf = 1;
        while (nf < 2 * n) nf <<= 1;
        double[] re = new double[nf], im = new double[nf];
        for (int i = 0; i < n; i++) re[i] = o[i] - mean;
        Fft fft = new Fft(nf);
        fft.transform(re, im);
        for (int i = 0; i < nf; i++) {
            re[i] = re[i] * re[i] + im[i] * im[i];
            im[i] = 0;
        }
        fft.transform(re, im); // Leistungsspektrum ist reell und symmetrisch -> Hin- = Rücktransformation
        if (re[0] <= 0) {
            r.tempoNoPulse = true;
            return;
        }
        double[] acf = new double[n];
        for (int k = 0; k < n; k++) acf[k] = re[k] / re[0] * n / (double) (n - k); // unverzerrte Normierung

        int m = (int) Math.ceil((bpmMax + 0.05 - effMin) / 0.1);
        double[] bpms = new double[m], raw = new double[m], score = new double[m];
        for (int i = 0; i < m; i++) {
            double bpm = effMin + 0.1 * i;
            bpms[i] = bpm;
            double lag = 60.0 * fr / bpm, s = 0;
            int c = 0;
            for (int k = 1; k <= 4; k++) {
                double L = k * lag;
                if (L < maxLag - 1) {
                    int i0 = (int) Math.floor(L);
                    double t = L - i0;
                    s += acf[i0] * (1 - t) + acf[Math.min(i0 + 1, n - 1)] * t;
                    c++;
                }
            }
            raw[i] = c > 0 ? s / c : -1;
            double lg = Math.log(bpm / priorCenter) / Math.log(2);
            score[i] = Math.max(raw[i], 0) * Math.exp(-0.5 * lg * lg);
        }

        java.util.ArrayList<double[]> cands = new java.util.ArrayList<>();
        for (int i = 1; i < m - 1; i++) {
            if (score[i] > score[i - 1] && score[i] >= score[i + 1] && score[i] > 0) {
                double a = score[i - 1], b = score[i], c = score[i + 1];
                double d = a - 2 * b + c;
                double q = Math.abs(d) > 1e-12 ? 0.5 * (a - c) / d : 0.0;
                cands.add(new double[] {b, bpms[i] + 0.1 * q, raw[i]});
            }
        }
        if (cands.isEmpty()) {
            r.tempoNoPulse = true;
            return;
        }
        cands.sort((u, v) -> Double.compare(v[0], u[0]));
        java.util.ArrayList<double[]> picked = new java.util.ArrayList<>();
        for (double[] c : cands) {
            boolean distinct = true;
            for (double[] q : picked) if (Math.abs(Math.log(c[1] / q[1])) <= 0.05) distinct = false;
            if (distinct) picked.add(c);
            if (picked.size() == 3) break;
        }
        double best = picked.get(0)[0];
        double pulse = picked.get(0)[2];
        double beats = (n / fr) * picked.get(0)[1] / 60.0;
        // Gesamtvertrauen: deutlicher Puls (0,1 … 0,5) und genug Schläge (1 … 6)
        double reliability = clamp01((pulse - 0.1) / 0.4) * clamp01((beats - 1) / 5.0);
        r.pulseStrength = pulse;
        r.beatsInSegment = beats;
        r.tempo = new TempoCandidate[picked.size()];
        for (int i = 0; i < picked.size(); i++) {
            double rel = picked.get(i)[0] / best;
            r.tempo[i] = new TempoCandidate(picked.get(i)[1], rel, rel * reliability);
        }
    }

    // ------------------------------------------------------------------ 3. Tonart

    // Krumhansl-Kessler-Profile (Grundton auf Index 0)
    static final double[] KK_MAJOR = {6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88};
    static final double[] KK_MINOR = {6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17};

    static void estimateKey(Peaks p, double a4, Result r) {
        double[] chroma = new double[12], frame = new double[12];
        int cur = -1;
        for (int i = 0; i <= p.size; i++) {
            int f = i < p.size ? p.frame[i] : -2;
            if (f != cur) { // Frame abschließen: auf sein Maximum normieren und aufsummieren
                double mx = 0;
                for (double v : frame) mx = Math.max(mx, v);
                if (mx > 0) for (int k = 0; k < 12; k++) chroma[k] += frame[k] / mx;
                java.util.Arrays.fill(frame, 0);
                cur = f;
            }
            if (i == p.size) break;
            double midi = 69 + 12 * Math.log(p.freq[i] / a4) / Math.log(2);
            int pc = Math.floorMod((int) Math.rint(midi), 12);
            frame[pc] += Math.sqrt(p.amp[i]);
        }
        double mx = 0;
        for (double v : chroma) mx = Math.max(mx, v);
        if (mx <= 0) return;
        for (int k = 0; k < 12; k++) chroma[k] /= mx;

        double durFactor = Math.max(0.25, Math.min(1.0, r.durationS / 6.0));
        KeyCandidate[] all = new KeyCandidate[24];
        double[] prof = new double[12];
        for (int t = 0; t < 12; t++) {
            for (int mode = 0; mode < 2; mode++) {
                double[] base = mode == 0 ? KK_MAJOR : KK_MINOR;
                for (int i = 0; i < 12; i++) prof[i] = base[Math.floorMod(i - t, 12)];
                double c = pearson(chroma, prof);
                all[t * 2 + mode] = new KeyCandidate(t, mode == 1, c, clamp01((c - 0.4) / 0.5) * durFactor);
            }
        }
        java.util.Arrays.sort(all, (u, v) -> Double.compare(v.correlation, u.correlation));
        r.keys = java.util.Arrays.copyOf(all, 3);
    }

    static double pearson(double[] a, double[] b) {
        int n = a.length;
        double ma = 0, mb = 0;
        for (int i = 0; i < n; i++) { ma += a[i]; mb += b[i]; }
        ma /= n;
        mb /= n;
        double sab = 0, saa = 0, sbb = 0;
        for (int i = 0; i < n; i++) {
            double da = a[i] - ma, db = b[i] - mb;
            sab += da * db;
            saa += da * da;
            sbb += db * db;
        }
        return (saa > 0 && sbb > 0) ? sab / Math.sqrt(saa * sbb) : 0;
    }

    static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    // ------------------------------------------------------------------ FFT

    /** Komplexe Radix-2-FFT (in place), Größe Zweierpotenz. */
    static final class Fft {
        final int n;
        final double[] cos, sin;
        final int[] rev;

        Fft(int n) {
            this.n = n;
            cos = new double[n / 2];
            sin = new double[n / 2];
            for (int i = 0; i < n / 2; i++) {
                cos[i] = Math.cos(2 * Math.PI * i / n);
                sin[i] = -Math.sin(2 * Math.PI * i / n);
            }
            rev = new int[n];
            int bits = Integer.numberOfTrailingZeros(n);
            for (int i = 0; i < n; i++) rev[i] = Integer.reverse(i) >>> (32 - bits);
        }

        void transform(double[] re, double[] im) {
            for (int i = 0; i < n; i++) {
                int j = rev[i];
                if (i < j) {
                    double t = re[i]; re[i] = re[j]; re[j] = t;
                    t = im[i]; im[i] = im[j]; im[j] = t;
                }
            }
            for (int size = 2; size <= n; size <<= 1) {
                int half = size >> 1, step = n / size;
                for (int start = 0; start < n; start += size) {
                    for (int k = 0, tw = 0; k < half; k++, tw += step) {
                        int a = start + k, b = a + half;
                        double wr = cos[tw], wi = sin[tw];
                        double tr = re[b] * wr - im[b] * wi;
                        double ti = re[b] * wi + im[b] * wr;
                        re[b] = re[a] - tr;
                        im[b] = im[a] - ti;
                        re[a] += tr;
                        im[a] += ti;
                    }
                }
            }
        }
    }
}

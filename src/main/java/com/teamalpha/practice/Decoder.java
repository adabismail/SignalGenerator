package com.teamalpha.practice;

import java.util.List;

/**
 * Decoder - robust decoders and unscramblers for AMI-B8ZS and AMI-HDB3.
 *
 * Assumes encoder produced discrete levels (HIGH, LOW, ZERO) with SAMPLES_PER_BIT samples per bit.
 */
public class Decoder {

    private static final double EPS = 1e-6;

    private static class AvgResult {
        final double avg;
        final int count;
        AvgResult(double a, int c) { avg = a; count = c; }
    }

    private static AvgResult avgSafe(List<Double> data, int start, int end) {
        if (data == null || data.isEmpty()) return new AvgResult(0.0, 0);
        int len = data.size();
        int s = Math.max(0, start);
        int e = Math.min(len, end);
        int n = 0;
        double sum = 0.0;
        for (int i = s; i < e; i++) { sum += data.get(i); n++; }
        if (n == 0) return new AvgResult(0.0, 0);
        return new AvgResult(sum / n, n);
    }

    public static String decode(String scheme, List<Double> waveform, int samplesPerBit) {
        if (waveform == null || waveform.isEmpty()) return "(Empty waveform)";
        if (samplesPerBit <= 0) return "(Invalid samplesPerBit)";

        double maxAbs = 0.0;
        for (double v : waveform) if (Math.abs(v) > maxAbs) maxAbs = Math.abs(v);
        double magThreshold = Math.max(0.05, maxAbs * 0.25);

        return switch (scheme) {
            case "NRZ-L" -> decodeNRZL(waveform, samplesPerBit, magThreshold);
            case "NRZ-I" -> decodeNRZI(waveform, samplesPerBit, magThreshold);
            case "Manchester" -> decodeManchester(waveform, samplesPerBit, magThreshold);
            case "Differential Manchester" -> decodeDiffMan(waveform, samplesPerBit, magThreshold);
            case "AMI" -> decodeAMI(waveform, samplesPerBit, magThreshold);
            case "AMI-B8ZS" -> {
                String prelim = decodeAMI(waveform, samplesPerBit, magThreshold);
                yield unscrambleB8ZS(prelim, waveform, samplesPerBit, magThreshold);
            }
            case "AMI-HDB3" -> {
                String prelim = decodeAMI(waveform, samplesPerBit, magThreshold);
                yield unscrambleHDB3(prelim, waveform, samplesPerBit, magThreshold);
            }
            default -> "(Unsupported decoding)";
        };
    }

    // ---------- Basic decoders (use magThreshold to be robust to noise) ----------

    private static String decodeNRZL(List<Double> w, int s, double threshold) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + s <= w.size(); i += s) {
            AvgResult ar = avgSafe(w, i, i + s);
            if (ar.count == 0) break;
            // decide by sign and magnitude
            if (Math.abs(ar.avg) < threshold) sb.append('0'); // treat near-zero as '0' (conservative)
            else sb.append(ar.avg > 0 ? '1' : '0');
        }
        return sb.toString();
    }

    private static String decodeNRZI(List<Double> w, int s, double threshold) {
        StringBuilder sb = new StringBuilder();
        // first bit is ambiguous in NRZ-I because it depends on initial level;
        // pick a deterministic choice: assume first bit = '0' (no transition at bit start)
        // then detect transitions between consecutive bit cells to mark '1'.
        if (w.size() < s) return "";
        AvgResult first = avgSafe(w, 0, s);
        if (first.count == 0) return "";
        double prev = first.avg;
        int prevSign = Math.abs(prev) < threshold ? 0 : (int) Math.signum(prev);
        sb.append('0'); // deterministic choice for the first bit

        for (int i = s; i + s <= w.size(); i += s) {
            AvgResult cur = avgSafe(w, i, i + s);
            if (cur.count == 0) break;
            double curAvg = cur.avg;
            int curSign = Math.abs(curAvg) < threshold ? prevSign : (int) Math.signum(curAvg);
            // a '1' in NRZ-I is encoded as a transition (sign change) at bit boundary
            if (curSign != 0 && prevSign != 0 && curSign != prevSign) {
                sb.append('1');
                prevSign = curSign;
            } else {
                sb.append('0');
                if (curSign != 0) prevSign = curSign; // update if meaningful
            }
        }
        return sb.toString();
    }

    private static String decodeManchester(List<Double> w, int s, double threshold) {
        StringBuilder sb = new StringBuilder();
        int half = Math.max(1, s / 2);
        for (int i = 0; i + s <= w.size(); i += s) {
            AvgResult a1 = avgSafe(w, i, i + half);
            AvgResult a2 = avgSafe(w, i + half, i + s);
            if (a1.count == 0 || a2.count == 0) break;
            // Encoder convention: first half = LOW for '1', HIGH for '0'; second half = inverse.
            // So for '1' => a1 < a2. For '0' => a1 > a2.
            double diff = a2.avg - a1.avg;
            if (Math.abs(diff) < threshold) {
                // very close — fallback to sign test of full cell
                double cellAvg = (a1.avg + a2.avg) / 2.0;
                if (Math.abs(cellAvg) < threshold) sb.append('0'); else sb.append(cellAvg > 0 ? '0' : '1');
            } else {
                sb.append(diff > 0 ? '1' : '0');
            }
        }
        return sb.toString();
    }

    private static String decodeDiffMan(List<Double> w, int s, double threshold) {
        StringBuilder sb = new StringBuilder();
        int half = Math.max(1, s / 2);
        if (w.size() < s) return "";
        AvgResult firstEnd = avgSafe(w, half, s);
        if (firstEnd.count == 0) return "";
        double prevEndAvg = firstEnd.avg;
        // first bit ambiguous because we don't know starting polarity - choose '0' as default placeholder
        sb.append('0');
        for (int i = s; i + s <= w.size(); i += s) {
            AvgResult cs = avgSafe(w, i, i + half);
            AvgResult ce = avgSafe(w, i + half, i + s);
            if (cs.count == 0 || ce.count == 0) break;
            // no transition at bit start => '1', transition => '0'
            int signStart = Math.abs(cs.avg) < threshold ? (int) Math.signum(prevEndAvg) : (int) Math.signum(cs.avg);
            int signPrevEnd = Math.abs(prevEndAvg) < threshold ? signStart : (int) Math.signum(prevEndAvg);
            boolean noTransition = signStart == signPrevEnd;
            sb.append(noTransition ? '1' : '0');
            prevEndAvg = ce.avg;
        }
        return sb.toString();
    }

    private static String decodeAMI(List<Double> w, int s, double threshold) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + s <= w.size(); i += s) {
            AvgResult ar = avgSafe(w, i, i + s);
            if (ar.count == 0) break;
            sb.append(Math.abs(ar.avg) > threshold ? '1' : '0');
        }
        return sb.toString();
    }

    // ---------- Unscramblers (kept logic but robust to threshold) ----------

    private static String unscrambleB8ZS(String prelimBits, List<Double> waveform, int s, double threshold) {
        if (prelimBits == null || prelimBits.isEmpty()) return prelimBits;
        StringBuilder sb = new StringBuilder(prelimBits);
        int n = prelimBits.length();
        java.util.function.IntFunction<Double> bitAvg = (bitIndex) -> {
            int start = bitIndex * s;
            AvgResult ar = avgSafe(waveform, start, start + s);
            return ar.count == 0 ? 0.0 : ar.avg;
        };

        for (int b = 0; b + 8 <= n; b++) {
            double[] av = new double[8];
            for (int k = 0; k < 8; k++) av[k] = bitAvg.apply(b + k);

            boolean zerosElsewhere = Math.abs(av[0]) <= threshold && Math.abs(av[1]) <= threshold
                    && Math.abs(av[2]) <= threshold && Math.abs(av[5]) <= threshold;
            boolean pulsesAt = Math.abs(av[3]) > threshold && Math.abs(av[4]) > threshold
                    && Math.abs(av[6]) > threshold && Math.abs(av[7]) > threshold;

            if (!(zerosElsewhere && pulsesAt)) continue;

            int lookBackSign = 0;
            for (int i = b - 1; i >= 0; i--) {
                double a = bitAvg.apply(i);
                if (Math.abs(a) > threshold) { lookBackSign = (int) Math.signum(a); break; }
            }
            if (lookBackSign == 0) continue;

            boolean polarityOk = (int) Math.signum(av[3]) == lookBackSign
                    && (int) Math.signum(av[4]) == -lookBackSign
                    && (int) Math.signum(av[6]) == lookBackSign
                    && (int) Math.signum(av[7]) == -lookBackSign;

            if (polarityOk) {
                for (int k = 0; k < 8; k++) sb.setCharAt(b + k, '0');
                b += 7;
            }
        }
        return sb.toString();
    }

    private static String unscrambleHDB3(String prelimBits, List<Double> waveform, int s, double threshold) {
        if (prelimBits == null || prelimBits.isEmpty()) return prelimBits;
        StringBuilder sb = new StringBuilder(prelimBits);
        int n = prelimBits.length();
        java.util.function.IntFunction<Double> bitAvg = (bitIndex) -> {
            int start = bitIndex * s;
            AvgResult ar = avgSafe(waveform, start, start + s);
            return ar.count == 0 ? 0.0 : ar.avg;
        };

        for (int b = 0; b + 4 <= n; b++) {
            double[] av = new double[4];
            for (int k = 0; k < 4; k++) av[k] = bitAvg.apply(b + k);

            boolean case1 = Math.abs(av[0]) <= threshold && Math.abs(av[1]) <= threshold
                    && Math.abs(av[2]) <= threshold && Math.abs(av[3]) > threshold;
            if (case1) {
                int lookBackSign = 0;
                for (int i = b - 1; i >= 0; i--) {
                    double a = bitAvg.apply(i);
                    if (Math.abs(a) > threshold) { lookBackSign = (int) Math.signum(a); break; }
                }
                if (lookBackSign != 0 && (int) Math.signum(av[3]) == lookBackSign) {
                    for (int k = 0; k < 4; k++) sb.setCharAt(b + k, '0');
                    b += 3;
                    continue;
                }
            }

            boolean case2pos = Math.abs(av[0]) > threshold && Math.abs(av[1]) <= threshold
                    && Math.abs(av[2]) <= threshold && Math.abs(av[3]) > threshold;
            if (case2pos) {
                int s0 = (int) Math.signum(av[0]);
                int s3 = (int) Math.signum(av[3]);
                if (s0 != 0 && s0 == s3) {
                    int lookBackSign = 0;
                    for (int i = b - 1; i >= 0; i--) {
                        double a = bitAvg.apply(i);
                        if (Math.abs(a) > threshold) { lookBackSign = (int) Math.signum(a); break; }
                    }
                    if (lookBackSign != 0 && s0 == -lookBackSign) {
                        for (int k = 0; k < 4; k++) sb.setCharAt(b + k, '0');
                        b += 3;
                    }
                }
            }
        }
        return sb.toString();
    }
}



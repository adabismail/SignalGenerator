package com.teamalpha.practice;

import java.util.List;

/**
 * Decoder - robust decoders and unscramblers for AMI-B8ZS and AMI-HDB3.
 *
 * Assumes encoder produced discrete levels (HIGH, LOW, ZERO) with SAMPLES_PER_BIT samples per bit.
 */
public class Decoder {

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
            case "NRZ-L" -> decodeNRZL(waveform, samplesPerBit);
            case "NRZ-I" -> decodeNRZI(waveform, samplesPerBit);
            case "Manchester" -> decodeManchester(waveform, samplesPerBit);
            case "Differential Manchester" -> decodeDiffMan(waveform, samplesPerBit);
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

    private static String decodeNRZL(List<Double> w, int s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + s <= w.size(); i += s) {
            AvgResult ar = avgSafe(w, i, i + s);
            if (ar.count == 0) break;
            sb.append(ar.avg > 0 ? '1' : '0');
        }
        return sb.toString();
    }

    private static String decodeNRZI(List<Double> w, int s) {
        StringBuilder sb = new StringBuilder();
        AvgResult first = avgSafe(w, 0, s);
        if (first.count == 0) return "";
        double prevSign = Math.signum(first.avg);
        sb.append('X'); // first ambiguous
        for (int i = s; i + s <= w.size(); i += s) {
            AvgResult cur = avgSafe(w, i, i + s);
            if (cur.count == 0) break;
            double currSign = Math.signum(cur.avg);
            if (currSign != 0 && prevSign != 0 && currSign != prevSign) sb.append('1'); else sb.append('0');
            if (currSign != 0) prevSign = currSign;
        }
        return sb.toString();
    }

    private static String decodeManchester(List<Double> w, int s) {
        StringBuilder sb = new StringBuilder();
        int half = Math.max(1, s / 2);
        for (int i = 0; i + s <= w.size(); i += s) {
            AvgResult a1 = avgSafe(w, i, i + half);
            AvgResult a2 = avgSafe(w, i + half, i + s);
            if (a1.count == 0 || a2.count == 0) break;
            sb.append(a1.avg > a2.avg ? '1' : '0'); // matches encoder convention
        }
        return sb.toString();
    }

    private static String decodeDiffMan(List<Double> w, int s) {
        StringBuilder sb = new StringBuilder();
        int half = Math.max(1, s / 2);
        if (w.size() < s) return "";
        AvgResult firstEnd = avgSafe(w, half, s);
        if (firstEnd.count == 0) return "";
        double prevEndAvg = firstEnd.avg;
        sb.append('X');
        for (int i = s; i + s <= w.size(); i += s) {
            AvgResult cs = avgSafe(w, i, i + half);
            AvgResult ce = avgSafe(w, i + half, i + s);
            if (cs.count == 0 || ce.count == 0) break;
            boolean noTransition = Math.signum(cs.avg) == Math.signum(prevEndAvg);
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

    // ---------- Unscramblers ----------

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

            boolean posMatch = Math.abs(av[3]) > threshold && Math.abs(av[4]) > threshold
                    && Math.abs(av[6]) > threshold && Math.abs(av[7]) > threshold
                    && Math.abs(av[0]) <= threshold && Math.abs(av[1]) <= threshold
                    && Math.abs(av[2]) <= threshold && Math.abs(av[5]) <= threshold;

            if (!posMatch) continue;

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



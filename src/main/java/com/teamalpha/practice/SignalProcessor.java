package com.teamalpha.practice;

import javafx.scene.chart.XYChart;

import java.util.ArrayList;
import java.util.List;

/**
 * SignalProcessor - robust encoding, PCM/DM utilities and chart adapters.
 *
 * Conventions:
 *  - SAMPLES_PER_BIT must be even and >= 2. Each bit produces exactly SAMPLES_PER_BIT samples.
 *  - Manchester convention here: first half = LOW for '1', HIGH for '0'; second half = inverse.
 *  - Differential Manchester: start transition => '0', no start transition => '1'; always toggle at mid-bit.
 *  - AMI pulses use alternating polarity starting with -1 initial convention (lastPolarity = -1).
 */
public final class SignalProcessor {

    public static final int SAMPLES_PER_BIT = 4; // MUST be even and >= 2

    public static final double HIGH = 1.0;
    public static final double LOW = -1.0;
    public static final double ZERO = 0.0;

    private SignalProcessor() { /* utility class */ }

    private static void validateSamplesPerBit() {
        if (SAMPLES_PER_BIT < 2 || (SAMPLES_PER_BIT % 2) != 0) {
            throw new IllegalStateException("SAMPLES_PER_BIT must be even and >=2. Current: " + SAMPLES_PER_BIT);
        }
    }

    // ----------------- PCM & Delta Modulation -----------------

    public static String pcmFromAnalog(double freq, double amp, double duration, int samples, int nBits) {
        if (samples <= 0 || duration <= 0 || nBits <= 0 || amp <= 0)
            throw new IllegalArgumentException("Invalid PCM parameters: samples,duration,nBits,amp must be > 0");

        double fs = samples / duration;
        int levels = 1 << nBits;
        double minVal = -amp, maxVal = amp;
        double stepSize = (maxVal - minVal) / (levels - 1);

        StringBuilder sb = new StringBuilder(samples * nBits);
        for (int i = 0; i < samples; i++) {
            double t = i / fs;
            double v = amp * Math.sin(2 * Math.PI * freq * t);
            int idx = (int) Math.round((v - minVal) / stepSize);
            idx = Math.max(0, Math.min(idx, levels - 1));
            String bits = String.format("%" + nBits + "s", Integer.toBinaryString(idx)).replace(' ', '0');
            sb.append(bits);
        }
        return sb.toString();
    }

    public static String deltaModFromAnalog(double freq, double amp, double duration, int samples) {
        return deltaModFromAnalog(freq, amp, duration, samples, amp / 16.0);
    }

    public static String deltaModFromAnalog(double freq, double amp, double duration, int samples, double step) {
        if (samples <= 0 || duration <= 0 || amp <= 0) throw new IllegalArgumentException("Invalid DM parameters");
        if (step <= 0) throw new IllegalArgumentException("Delta mod step must be > 0");

        double fs = samples / duration;
        double estimate = 0.0;
        StringBuilder sb = new StringBuilder(samples);

        for (int i = 0; i < samples; i++) {
            double t = i / fs;
            double v = amp * Math.sin(2 * Math.PI * freq * t);
            if (v >= estimate) {
                sb.append('1');
                estimate += step;
            } else {
                sb.append('0');
                estimate -= step;
            }
            estimate = Math.max(-amp, Math.min(amp, estimate));
        }
        return sb.toString();
    }

    // ----------------- PCM plotting helpers -----------------

    public static List<XYChart.Data<Number, Number>> getAnalogSineWave(double freq, double amp, double duration, int numPoints) {
        if (numPoints <= 1) numPoints = 2;
        List<XYChart.Data<Number, Number>> data = new ArrayList<>(numPoints);
        double timeStep = duration / (numPoints - 1);
        for (int i = 0; i < numPoints; i++) {
            double t = i * timeStep;
            double v = amp * Math.sin(2 * Math.PI * freq * t);
            data.add(new XYChart.Data<>(t, v));
        }
        return data;
    }

    public static List<XYChart.Data<Number, Number>> getPCMSampledWave(double freq, double amp, double duration, int samples, int nBits) {
        if (samples <= 0 || duration <= 0 || nBits <= 0 || amp <= 0)
            throw new IllegalArgumentException("Invalid PCM parameters");
        double fs = samples / duration;
        double timePerSample = 1.0 / fs;
        List<XYChart.Data<Number, Number>> data = new ArrayList<>(samples * 2);
        for (int i = 0; i < samples; i++) {
            double t = i / fs;
            double v = amp * Math.sin(2 * Math.PI * freq * t);
            int levels = 1 << nBits;
            double minVal = -amp, maxVal = amp;
            double stepSize = (maxVal - minVal) / (levels - 1);
            int idx = (int) Math.round((v - minVal) / stepSize);
            idx = Math.max(0, Math.min(idx, levels - 1));
            double quantizedLevel = minVal + idx * stepSize;
            data.add(new XYChart.Data<>(t, quantizedLevel));
            data.add(new XYChart.Data<>(t + timePerSample, quantizedLevel));
        }
        return data;
    }

    // ----------------- line encoders -----------------

    public static List<Double> encode(String bits, String scheme) {
        if (bits == null) throw new IllegalArgumentException("bits is null");
        validateSamplesPerBit();
        return switch (scheme) {
            case "NRZ-L" -> encodeNRZL(bits);
            case "NRZ-I" -> encodeNRZI(bits);
            case "Manchester" -> encodeManchester(bits);
            case "Differential Manchester" -> encodeDifferentialManchester(bits);
            case "AMI" -> encodeAMI(bits);
            case "AMI-B8ZS" -> encodeAMIB8ZS(bits);
            case "AMI-HDB3" -> encodeAMIHDB3(bits);
            default -> throw new IllegalArgumentException("Unknown encoding scheme: " + scheme);
        };
    }

    private static void addSamples(List<Double> w, double level, int count) {
        for (int i = 0; i < count; i++) w.add(level);
    }

    private static void setSamples(List<Double> w, int startIndex, double level, int count) {
        if (startIndex < 0 || startIndex + count > w.size()) {
            throw new IllegalArgumentException("setSamples out of range: start=" + startIndex + " count=" + count + " size=" + w.size());
        }
        for (int i = 0; i < count; i++) w.set(startIndex + i, level);
    }

    public static List<Double> encodeNRZL(String bits) {
        validateSamplesPerBit();
        List<Double> w = new ArrayList<>(bits.length() * SAMPLES_PER_BIT);
        for (char c : bits.toCharArray()) addSamples(w, c == '1' ? HIGH : LOW, SAMPLES_PER_BIT);
        return w;
    }

    public static List<Double> encodeNRZI(String bits) {
        validateSamplesPerBit();
        List<Double> w = new ArrayList<>(bits.length() * SAMPLES_PER_BIT);
        double level = LOW;
        for (char c : bits.toCharArray()) {
            if (c == '1') level = -level;
            addSamples(w, level, SAMPLES_PER_BIT);
        }
        return w;
    }

    public static List<Double> encodeManchester(String bits) {
        validateSamplesPerBit();
        List<Double> w = new ArrayList<>(bits.length() * SAMPLES_PER_BIT);
        int half = SAMPLES_PER_BIT / 2;
        for (char c : bits.toCharArray()) {
            if (c == '1') {
                addSamples(w, LOW, half);
                addSamples(w, HIGH, SAMPLES_PER_BIT - half);
            } else {
                addSamples(w, HIGH, half);
                addSamples(w, LOW, SAMPLES_PER_BIT - half);
            }
        }
        return w;
    }

    public static List<Double> encodeDifferentialManchester(String bits) {
        validateSamplesPerBit();
        List<Double> w = new ArrayList<>(bits.length() * SAMPLES_PER_BIT);
        double last = LOW; // starting level; documentable option
        int half = SAMPLES_PER_BIT / 2;
        for (char c : bits.toCharArray()) {
            if (c == '0') last = -last; // start-of-bit transition encodes 0
            addSamples(w, last, half);
            last = -last; // mandatory mid-bit transition
            addSamples(w, last, SAMPLES_PER_BIT - half);
        }
        return w;
    }

    public static List<Double> encodeAMI(String bits) {
        validateSamplesPerBit();
        List<Double> w = new ArrayList<>(bits.length() * SAMPLES_PER_BIT);
        int lastPolarity = -1;
        for (char c : bits.toCharArray()) {
            if (c == '0') addSamples(w, ZERO, SAMPLES_PER_BIT);
            else {
                lastPolarity = -lastPolarity;
                addSamples(w, (double) lastPolarity, SAMPLES_PER_BIT);
            }
        }
        return w;
    }

    public static List<Double> encodeAMIB8ZS(String bits) {
        validateSamplesPerBit();
        List<Double> w = new ArrayList<>(bits.length() * SAMPLES_PER_BIT);
        int lastNonZeroPolarity = -1; // polarity of last non-zero physical pulse (±1).
        int zeroCount = 0;
        final int spb = SAMPLES_PER_BIT;

        // B8ZS substitution mapping (bit positions within 8-bit zero run)
        final int POS_V1 = 3, POS_B1 = 4, POS_V2 = 6, POS_B2 = 7;

        for (int i = 0; i < bits.length(); i++) {
            char c = bits.charAt(i);
            if (c == '1') {
                zeroCount = 0;
                // alternate polarity for each real '1' pulse
                lastNonZeroPolarity = -lastNonZeroPolarity;
                addSamples(w, (double) lastNonZeroPolarity, spb);
            } else { // '0'
                zeroCount++;
                addSamples(w, ZERO, spb);
                if (zeroCount == 8) {
                    // compute block start (samples)
                    int startSampleIndex = w.size() - (8 * spb);

                    // *** CORRECTION IS HERE ***
                    // Apply the 000VB0VB rules you provided:

                    // V1 (pos 3): Same polarity as last pulse
                    double v1Pol = (double) lastNonZeroPolarity;

                    // B1 (pos 4): Alternates from V1
                    double b1Pol = -v1Pol;

                    // V2 (pos 6): Same polarity as B1 (this is a violation)
                    double v2Pol = b1Pol;

                    // B2 (pos 7): Alternates from V2
                    double b2Pol = -v2Pol;

                    // place substitution: 000 V B 0 V B
                    setSamples(w, startSampleIndex + POS_V1 * spb, v1Pol, spb); // V at pos 3
                    setSamples(w, startSampleIndex + POS_B1 * spb, b1Pol, spb); // B at pos 4
                    setSamples(w, startSampleIndex + POS_V2 * spb, v2Pol, spb); // V at pos 6
                    setSamples(w, startSampleIndex + POS_B2 * spb, b2Pol, spb); // B at pos 7

                    // last non-zero after substitution is the last B (B2) inserted at pos 7:
                    lastNonZeroPolarity = (int) b2Pol;
                    zeroCount = 0;
                }
            }
        }
        return w;
    }

    public static List<Double> encodeAMIHDB3(String bits) {
        validateSamplesPerBit();
        List<Double> w = new ArrayList<>(bits.length() * SAMPLES_PER_BIT);
        int lastNonZeroPolarity = -1; // polarity of last real non-zero (±1)
        int zeroCount = 0;
        int pulsesSinceLastSubstitution = 0; // explicit count of physical pulses since last substitution
        final int spb = SAMPLES_PER_BIT;

        for (int i = 0; i < bits.length(); i++) {
            char c = bits.charAt(i);
            if (c == '1') {
                zeroCount = 0;
                // emit next alternating pulse
                lastNonZeroPolarity = -lastNonZeroPolarity; // flip polarity
                addSamples(w, (double) lastNonZeroPolarity, spb);
                pulsesSinceLastSubstitution++; // we added a physical pulse
            } else { // c == '0'
                zeroCount++;
                addSamples(w, ZERO, spb);
                if (zeroCount == 4) {
                    int startSampleIndex = w.size() - (4 * spb);

                    // If even number of pulses since last substitution -> B00V
                    if ((pulsesSinceLastSubstitution % 2) == 0) {

                        // *** CORRECTION IS HERE ***
                        // B must follow AMI (alternate from last pulse)
                        int B = -lastNonZeroPolarity;
                        // V must match B (violates AMI relative to B)
                        int V = B;

                        // place B at first zero position and V at last zero position
                        setSamples(w, startSampleIndex + 0 * spb, (double) B, spb); // B at pos 0
                        setSamples(w, startSampleIndex + 3 * spb, (double) V, spb); // V at pos 3

                        // After substitution, last non-zero becomes V (which equals B)
                        lastNonZeroPolarity = V;
                    } else {
                        // odd -> 000V (This was already correct)
                        // V has same polarity as last non-zero (violates AMI)
                        int V = lastNonZeroPolarity;
                        setSamples(w, startSampleIndex + 3 * spb, (double) V, spb);

                        // lastNonZeroPolarity remains V (same as before)
                        lastNonZeroPolarity = V;
                    }
                    // reset counters after substitution
                    zeroCount = 0;
                    pulsesSinceLastSubstitution = 0;
                }
            }
        }
        return w;
    }
}

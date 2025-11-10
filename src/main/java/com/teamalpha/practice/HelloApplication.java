package com.teamalpha.practice;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.chart.*;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;


public class  HelloApplication extends Application {

    private TextArea outputArea;
    private LineChart<Number, Number> chart;
    private Button generateBtn;
    private static final int PCM_BITS = 8;

    @Override
    public void start(Stage stage) {
        stage.setTitle("Digital Signal Generator");

        ChoiceBox<String> modeChoice = new ChoiceBox<>();
        modeChoice.getItems().addAll("Digital Input (bits)", "Analog Input (sine wave)");
        modeChoice.setValue("Digital Input (bits)");

        TextField inputField = new TextField();
        inputField.setPromptText("e.g., 1100101  OR  freq=1;amp=1;duration=1;samples=50");
        inputField.setPrefWidth(320);

        ChoiceBox<String> encoderChoice = new ChoiceBox<>();
        encoderChoice.getItems().addAll("NRZ-L", "NRZ-I", "Manchester", "Differential Manchester", "AMI");
        encoderChoice.setValue("NRZ-L");

        generateBtn = new Button("Generate Signal");

        outputArea = new TextArea();
        outputArea.setEditable(false);
        outputArea.setPrefRowCount(8);
        outputArea.setFont(javafx.scene.text.Font.font("Monospaced"));
        outputArea.getStyleClass().add("output-area"); // Apply CSS class

        NumberAxis xAxis = new NumberAxis();
        NumberAxis yAxis = new NumberAxis(-1.5, 1.5, 1);
        xAxis.setLabel("Bits");
        xAxis.setAutoRanging(false);
        xAxis.setTickLabelsVisible(true);
        yAxis.setLabel("Amplitude");

        chart = new LineChart<>(xAxis, yAxis);
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        chart.setTitle("Waveform");
        chart.getStyleClass().add("chart-view"); // Apply CSS class

        HBox controls = new HBox(10, new Label("Mode:"), modeChoice, new Label("Input:"), inputField,
                new Label("Encoder:"), encoderChoice, generateBtn);
        controls.setPadding(new Insets(10));
        controls.getStyleClass().add("control-bar"); // Apply CSS class

        VBox right = new VBox(10, new Label("Output"), outputArea);
        right.setPadding(new Insets(10));
        right.setPrefWidth(380);
        right.getStyleClass().add("output-pane"); // Apply CSS class
        VBox.setVgrow(outputArea, Priority.ALWAYS); // Ensures output area fills vertical space

        BorderPane root = new BorderPane();
        root.setTop(controls);
        root.setCenter(chart);
        root.setRight(right);

        Scene scene = new Scene(root, 1200, 600);

        // Load stylesheet if present
        try {
            String css = Objects.requireNonNull(getClass().getResource("app.css")).toExternalForm();
            scene.getStylesheets().add(css);
        } catch (Exception e) {
            System.err.println("Warning: Could not load app.css stylesheet.");
        }

        stage.setScene(scene);
        stage.show();

        generateBtn.setOnAction(e -> {
            generateBtn.setDisable(true);
            chart.getData().clear();
            outputArea.clear();

            String mode = modeChoice.getValue();
            String input = inputField.getText().trim();
            String encoder = encoderChoice.getValue();

            new Thread(() -> {
                try {
                    handleGenerate(mode, input, encoder);
                } catch (Exception ex) {
                    Platform.runLater(() -> alert("Error", "An unexpected error occurred: " + ex.getMessage()));
                    ex.printStackTrace();
                } finally {
                    Platform.runLater(() -> generateBtn.setDisable(false));
                }
            }).start();
        });
    }

    // Runs on background thread; use Platform.runLater for UI ops with CountDownLatch to wait when needed.
    private void handleGenerate(String mode, String input, String encoder) {
        String bits;

        if (mode.startsWith("Digital")) {
            // Accept spaces, commas, newlines — normalize to contiguous bit string
            String normalized = input.replaceAll("[\\s,]+", "");
            if (!normalized.matches("[01]+")) {
                Platform.runLater(() -> alert("Invalid Input", "For digital input, enter only bits (e.g. 101011)."));
                return;
            }
            bits = normalized;
            final String displayed = "Using bitstream: " + bits + "\n";
            Platform.runLater(() -> outputArea.appendText(displayed));
        } else {
            bits = handleAnalogInput(input);
            if (bits == null || bits.isEmpty()) return;
        }

        final String finalBits = bits;

        String effectiveEncoder = encoder;
        if ("AMI".equals(encoder)) {
            final String[] result = {encoder};
            CountDownLatch latch = new CountDownLatch(1);
            Platform.runLater(() -> {
                try {
                    Alert q = new Alert(Alert.AlertType.CONFIRMATION);
                    q.setHeaderText("Scrambling?");
                    q.setContentText("Do you want to apply AMI scrambling (B8ZS/HDB3)?");
                    q.getButtonTypes().setAll(ButtonType.YES, ButtonType.NO);
                    applyDialogStyles(q);
                    Optional<ButtonType> res = q.showAndWait();
                    if (res.isPresent() && res.get() == ButtonType.YES) {
                        ChoiceDialog<String> d = new ChoiceDialog<>("B8ZS", "B8ZS", "HDB3");
                        d.setHeaderText("Select Scrambler");
                        applyDialogStyles(d);
                        Optional<String> scrType = d.showAndWait();
                        scrType.ifPresent(s -> {
                            result[0] = "AMI-" + s;
                            outputArea.appendText("Using " + s + " scrambling.\n");
                        });
                    }
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            effectiveEncoder = result[0];
        }

        // Safety warning for very large streams
        int estimatedSamples = finalBits.length() * SignalProcessor.SAMPLES_PER_BIT;
        if (!confirmLargeStream(estimatedSamples)) {
            Platform.runLater(() -> outputArea.appendText("Generation cancelled by user (large stream).\n"));
            return;
        }

        List<Double> waveform = SignalProcessor.encode(finalBits, effectiveEncoder);

        if (waveform.size() != finalBits.length() * SignalProcessor.SAMPLES_PER_BIT) {
            Platform.runLater(() -> alert("Alignment error", "Waveform length " + waveform.size() + " does not match expected " + (finalBits.length() * SignalProcessor.SAMPLES_PER_BIT)));
            return;
        }

        int bitCount = finalBits.length();
        String finalEffectiveEncoder = effectiveEncoder;

        Platform.runLater(() -> {
            outputArea.appendText("Encoding signal. Clearing analog plot...\n");
            chart.getData().clear();
            plotWaveformAligned(waveform, finalEffectiveEncoder, bitCount);
        });

        String palindrome = Utils.longestPalindromicSubstring(finalBits);
        Platform.runLater(() -> outputArea.appendText("Longest palindrome in stream: " + palindrome + "\n"));

        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Alert decodeQ = new Alert(Alert.AlertType.CONFIRMATION);
                decodeQ.setHeaderText("Decode?");
                decodeQ.setContentText("Do you want to decode the generated waveform?");
                applyDialogStyles(decodeQ);
                Optional<ButtonType> decRes = decodeQ.showAndWait();
                if (decRes.isPresent() && decRes.get() == ButtonType.OK) {
                    String decoded = Decoder.decode(finalEffectiveEncoder, waveform, SignalProcessor.SAMPLES_PER_BIT);
                    outputArea.appendText("Decoded bitstream:  " + decoded + "\n");
                    outputArea.appendText("Original bitstream: " + finalBits + "\n");
                }
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Confirm with user if the estimated sample count is very large.
     * Runs a blocking confirmation dialog on the UI thread using latch.
     */
    private boolean confirmLargeStream(int estimatedSamples) {
        final boolean[] proceed = {true};
        if (estimatedSamples <= 20000) return true; // safe threshold

        CountDownLatch warnLatch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Alert warn = new Alert(Alert.AlertType.CONFIRMATION);
                warn.setHeaderText("Large stream");
                warn.setContentText("This bitstream will generate " + estimatedSamples + " samples and may be slow or unresponsive. Continue?");
                applyDialogStyles(warn);
                Optional<ButtonType> r = warn.showAndWait();
                proceed[0] = r.isPresent() && r.get() == ButtonType.OK;
            } finally {
                warnLatch.countDown();
            }
        });
        try {
            warnLatch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return proceed[0];
    }

    private String handleAnalogInput(String input) {
        double freq = 1, amp = 1, duration = 1;
        int samples = 50;
        try {
            String[] parts = input.split(";");
            for (String p : parts) {
                p = p.trim();
                if (p.isEmpty()) continue;
                if (!p.contains("=")) continue;
                String[] kv = p.split("=", 2);
                if (kv.length != 2) continue;
                String key = kv[0].trim();
                String val = kv[1].trim();
                switch (key) {
                    case "freq" -> freq = Double.parseDouble(val);
                    case "amp" -> amp = Double.parseDouble(val);
                    case "duration" -> duration = Double.parseDouble(val);
                    case "samples" -> samples = Integer.parseInt(val);
                }
            }
        } catch (Exception e) {
            Platform.runLater(() -> alert("Invalid Format", "Use format: freq=1;amp=1;duration=1;samples=50"));
            return "";
        }

        final String[] result = {null};
        final String[] modType = {null};
        CountDownLatch latch = new CountDownLatch(1);

        double fFreq = freq, fAmp = amp, fDuration = duration;
        int fSamples = samples;

        Platform.runLater(() -> {
            try {
                ChoiceDialog<String> d = new ChoiceDialog<>("PCM", "PCM", "DM");
                d.setHeaderText("Select Modulation");
                applyDialogStyles(d);
                Optional<String> res = d.showAndWait();
                if (res.isEmpty()) {
                    result[0] = null;
                    return;
                }
                modType[0] = res.get();
                chart.getData().clear();

                List<XYChart.Data<Number, Number>> sineData = SignalProcessor.getAnalogSineWave(fFreq, fAmp, fDuration, 200);
                plotAnalogSeries(sineData, "Analog Signal (Source)", fDuration);

                if ("PCM".equals(modType[0])) {
                    List<XYChart.Data<Number, Number>> pcmData = SignalProcessor.getPCMSampledWave(fFreq, fAmp, fDuration, fSamples, PCM_BITS);
                    plotAnalogSeries(pcmData, "PCM Quantized", fDuration);
                    result[0] = SignalProcessor.pcmFromAnalog(fFreq, fAmp, fDuration, fSamples, PCM_BITS);
                } else {
                    result[0] = SignalProcessor.deltaModFromAnalog(fFreq, fAmp, fDuration, fSamples);
                    outputArea.appendText("Note: DM analog plot not shown. Generating bits only.\n");
                }
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
        if (result[0] == null) return null;

        CountDownLatch latch2 = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                Alert a = new Alert(Alert.AlertType.INFORMATION);
                a.setHeaderText("Analog Source Plotted");
                a.setContentText("Analog signal (and its quantization) plotted. Click OK to proceed with line encoding.");
                applyDialogStyles(a);
                a.showAndWait();
            } finally {
                latch2.countDown();
            }
        });
        try {
            latch2.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
        return result[0];
    }

    private void plotAnalogSeries(List<XYChart.Data<Number, Number>> seriesData, String name, double xMax) {
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(name);
        series.getData().addAll(seriesData);
        chart.getData().add(series);

        NumberAxis x = (NumberAxis) chart.getXAxis();
        x.setLabel("Time (s)");
        x.setLowerBound(0);
        x.setUpperBound(xMax);
        x.setTickUnit(Math.max(0.1, xMax / 10.0));
        x.setTickLabelsVisible(true);
        x.setTickMarkVisible(true);

        NumberAxis y = (NumberAxis) chart.getYAxis();
        y.setLabel("Amplitude");
        y.setAutoRanging(false);
        y.setLowerBound(-1.5);
        y.setUpperBound(1.5);
    }

    /**
     * Efficient step plotting:
     * - Adds a starting point (t=0, firstLevel).
     * - Extends horizontal segments while the level remains the same.
     * - Adds precise vertical transition points only when the level changes.
     * This reduces plotted points substantially compared to adding per-sample duplicated points.
     */
    private void plotWaveformAligned(List<Double> waveform, String title, int totalBits) {
        XYChart.Series<Number, Number> series = new XYChart.Series<>();
        series.setName(title);

        int spb = SignalProcessor.SAMPLES_PER_BIT;
        if (waveform == null || waveform.isEmpty()) {
            chart.getData().add(series);
            return;
        }

        // Start at t=0
        double firstLevel = waveform.get(0);
        series.getData().add(new XYChart.Data<>(0.0, firstLevel));

        for (int si = 0; si < waveform.size(); si++) {
            double level = waveform.get(si);
            double sampleStart = (double) si / spb;
            double sampleEnd = sampleStart + (1.0 / spb);

            if (si == 0) {
                // extend to end of first sample
                series.getData().add(new XYChart.Data<>(sampleEnd, level));
                continue;
            }

            double prevLevel = waveform.get(si - 1);
            if (level == prevLevel) {
                // extend horizontal to end of this sample
                series.getData().add(new XYChart.Data<>(sampleEnd, level));
            } else {
                // vertical transition at sampleStart
                series.getData().add(new XYChart.Data<>(sampleStart, prevLevel));
                series.getData().add(new XYChart.Data<>(sampleStart, level));
                series.getData().add(new XYChart.Data<>(sampleEnd, level));
            }
        }

        chart.getData().add(series);

        NumberAxis x = (NumberAxis) chart.getXAxis();
        x.setLabel("Bits");
        x.setLowerBound(0);
        x.setUpperBound(Math.max(1, totalBits));
        if (totalBits <= 64) x.setTickUnit(1);
        else x.setTickUnit(Math.ceil((double) totalBits / 16));
        x.setTickLabelsVisible(true);
        x.setTickMarkVisible(true);
    }

    private void alert(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setHeaderText(title);
        a.setContentText(msg);
        applyDialogStyles(a);
        a.showAndWait();
    }

    // Helper to apply CSS to dialogs (if stylesheet exists)
    private void applyDialogStyles(Dialog<?> dialog) {
        try {
            String css = getClass().getResource("app.css").toExternalForm();
            dialog.getDialogPane().getStylesheets().add(css);
            dialog.getDialogPane().getStyleClass().add("dialog-pane");
        } catch (Exception e) {
            // silent fallback — styling is optional
        }
    }

    public static void main(String[] args) {
        launch();
    }
}

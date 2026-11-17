package org.voucherhub.common.metrics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Metrics {
    private final List<Row> rows = new ArrayList<>();

    public <T> T time(String protocol, String scenario, String party, String phase, int batchSize, int runId,
                      long communicationBytes, long gas, Timed<T> timed) {
        long startWall = System.nanoTime();
        long startCpu = java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
        boolean success = true;
        try {
            return timed.run();
        } catch (RuntimeException e) {
            success = false;
            throw e;
        } finally {
            long endCpu = java.lang.management.ManagementFactory.getThreadMXBean().getCurrentThreadCpuTime();
            long latencyNs = System.nanoTime() - startWall;
            rows.add(new Row(protocol, scenario, party, phase, batchSize, runId,
                    latencyNs / 1_000_000.0, (endCpu - startCpu) / 1_000_000.0, communicationBytes, gas, success));
        }
    }

    public void record(String protocol, String scenario, String party, String phase, int batchSize, int runId,
                       double latencyMs, long communicationBytes, long gas, boolean success) {
        rows.add(new Row(protocol, scenario, party, phase, batchSize, runId, latencyMs, latencyMs, communicationBytes, gas, success));
    }

    public void writeCsv(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        List<String> out = new ArrayList<>();
        out.add("protocol,scenario,party,phase,batch_size,run_id,latency_ms,cpu_ms,communication_bytes,gas,success");
        for (Row r : rows) {
            out.add(r.toCsv());
        }
        Files.write(path, out, StandardCharsets.UTF_8);
    }

    public void writeSummary(Path path, String protocol) throws IOException {
        Files.createDirectories(path.getParent());
        List<Double> values = new ArrayList<>();
        for (Row r : rows) {
            values.add(r.latencyMs);
        }
        values.sort(Comparator.naturalOrder());
        double mean = values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double median = percentile(values, 0.50);
        double p95 = percentile(values, 0.95);
        double variance = values.stream().mapToDouble(v -> (v - mean) * (v - mean)).average().orElse(0);
        String json = "{\n"
                + "  \"hardware\": \"local-host\",\n"
                + "  \"software_versions\": \"Java " + System.getProperty("java.version") + "\",\n"
                + "  \"chain_config\": \"prototype local EVM placeholder; off-chain simulator executed\",\n"
                + "  \"protocol_parameters\": \"" + protocol + ", bn128 algebra simulator, range-proof interface stub\",\n"
                + "  \"mean\": " + mean + ",\n"
                + "  \"median\": " + median + ",\n"
                + "  \"p95\": " + p95 + ",\n"
                + "  \"stddev\": " + Math.sqrt(variance) + ",\n"
                + "  \"num_runs\": " + values.size() + "\n"
                + "}\n";
        Files.write(path, json.getBytes(StandardCharsets.UTF_8));
    }

    private double percentile(List<Double> values, double p) {
        if (values.isEmpty()) {
            return 0;
        }
        int idx = Math.min(values.size() - 1, (int) Math.ceil(values.size() * p) - 1);
        return values.get(idx);
    }

    public interface Timed<T> {
        T run();
    }

    private static final class Row {
        private final String protocol;
        private final String scenario;
        private final String party;
        private final String phase;
        private final int batchSize;
        private final int runId;
        private final double latencyMs;
        private final double cpuMs;
        private final long communicationBytes;
        private final long gas;
        private final boolean success;

        private Row(String protocol, String scenario, String party, String phase, int batchSize, int runId,
                    double latencyMs, double cpuMs, long communicationBytes, long gas, boolean success) {
            this.protocol = protocol;
            this.scenario = scenario;
            this.party = party;
            this.phase = phase;
            this.batchSize = batchSize;
            this.runId = runId;
            this.latencyMs = latencyMs;
            this.cpuMs = cpuMs;
            this.communicationBytes = communicationBytes;
            this.gas = gas;
            this.success = success;
        }

        private String toCsv() {
            return String.join(",",
                    protocol, scenario, party, phase, Integer.toString(batchSize), Integer.toString(runId),
                    String.format(java.util.Locale.ROOT, "%.6f", latencyMs),
                    String.format(java.util.Locale.ROOT, "%.6f", cpuMs),
                    Long.toString(communicationBytes), Long.toString(gas), Boolean.toString(success));
        }
    }
}
